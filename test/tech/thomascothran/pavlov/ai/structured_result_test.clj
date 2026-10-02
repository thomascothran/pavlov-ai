(ns tech.thomascothran.pavlov.ai.structured-result-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.ai :as ai]
            [tech.thomascothran.pavlov.ai.schema :as schema]
            [tech.thomascothran.pavlov.ai.schema.malli]))

(def result-schema
  [:map {:closed true}
   [:sentiment [:enum :positive :neutral :negative]]
   [:confidence [:double {:min 0 :max 1}]]])

(defn completion
  [content]
  {:status 200
   :body (json/write-str
          {:choices [{:message {:role "assistant" :content content}
                      :finish_reason "stop"}]})})

(defn request
  [post!]
  {:provider :openai-compatible
   :schema result-schema
   :input "Classify this review: Excellent."
   :provider-options {:post! post!
                      :url "https://model.example/chat/completions"
                      :api-key "fixture-secret"
                      :model "fixture-model"}})

(defn anomaly?
  [value]
  (and (map? value)
       (keyword? (:cognitect.anomalies/category value))
       (string? (:cognitect.anomalies/message value))))

(deftest returns-decoded-domain-data
  (is (= {:sentiment :positive :confidence 0.9}
         (ai/call! (request (fn [_ _]
                              (completion "{\"sentiment\":\"positive\",\"confidence\":0.9}")))))))

(deftest supports-arbitrary-schema-data-without-action-or-message-envelope
  (let [s [:vector :int]]
    (is (= [3 1 2]
           (ai/call! (assoc (request (fn [_ _] (completion "[3,1,2]")))
                            :schema s :input "Return three integers."))))))

(deftest requests-json-schema-output-without-tool-call-protocol
  (let [calls (atom [])]
    (ai/call! (request (fn [url options]
                         (swap! calls conj [url options])
                         (completion "{\"sentiment\":\"positive\",\"confidence\":0.9}"))))
    (is (= 1 (count @calls)))
    (let [[url options] (first @calls)
          body (when (:body options)
                 (json/read-str (:body options) :key-fn keyword))]
      (is (= "https://model.example/chat/completions" url))
      (is (= "fixture-model" (:model body)))
      (is (= "json_schema" (get-in body [:response_format :type])))
      ;; Compare through JSON because JSON object keys are strings on the wire.
      (is (= (json/read-str (json/write-str (schema/->json-schema result-schema)))
             (when-let [s (get-in body [:response_format :json_schema :schema])]
               (json/read-str (json/write-str s)))))
      (is (seq (:messages body)))
      (is (not (contains? body :tools)))
      (is (not (contains? body :tool_choice))))))

(deftest validates-original-malli-constraints-after-decoding
  (doseq [content ["{\"sentiment\":\"unknown\",\"confidence\":0.9}"
                   "{\"sentiment\":\"positive\",\"confidence\":2.0}"
                   "{\"sentiment\":\"positive\"}"
                   "{\"sentiment\":\"positive\",\"confidence\":0.9,\"extra\":true}"]]
    (testing content
      (let [result (ai/call! (request (fn [_ _] (completion content))))]
        (is (anomaly? result))
        (is (some? (:explanation result)))))))

(deftest enforces-malli-predicates-not-expressible-as-json-schema
  (let [s [:and :int [:fn {:json-schema {}} even?]]]
    (is (= 4 (ai/call! (assoc (request (fn [_ _] (completion "4"))) :schema s))))
    (is (anomaly? (ai/call! (assoc (request (fn [_ _] (completion "3"))) :schema s))))))

(deftest expected-failures-are-anomalies-rather-than-thrown-exceptions
  (doseq [[label post!]
          [[:connection (fn [_ _] (throw (java.net.ConnectException. "Connection refused")))]
           [:timeout (fn [_ _] (throw (java.net.SocketTimeoutException. "Timed out")))]
           [:http-error (fn [_ _] {:status 503 :body "Service unavailable"})]
           [:malformed-http-json (fn [_ _] {:status 200 :body "not JSON"})]
           [:malformed-output-json (fn [_ _] (completion "not JSON"))]
           [:missing-output (fn [_ _] {:status 200 :body "{\"choices\":[]}"})]
           [:refusal (fn [_ _] {:status 200 :body (json/write-str
                                                   {:choices [{:message {:role "assistant"
                                                                         :refusal "Cannot comply"}
                                                               :finish_reason "stop"}]})})]
           [:incomplete (fn [_ _] {:status 200 :body (json/write-str
                                                      {:choices [{:message {:role "assistant"
                                                                            :content "{\"sentiment\":\"positive\",\"confidence\":0.9}"}
                                                                  :finish_reason "length"}]})})]]]
    (testing (name label)
      (let [result (ai/call! (request post!))]
        (is (anomaly? result))
        (is (not (.contains (pr-str result) "fixture-secret")))))))

(deftest unsupported-provider-is-an-anomaly-without-network-work
  (let [calls (atom 0)]
    (is (anomaly? (ai/call! (assoc (request (fn [_ _] (swap! calls inc)))
                                   :provider ::unsupported))))
    (is (zero? @calls))))

(defn run-handler
  [post!]
  (let [outcomes (atom [])
        req (request post!)
        handler (ai/make-handler (select-keys req [:provider :provider-options]))]
    (handler {:event {:type :review/classify
                      :schema result-schema
                      :input (:input req)
                      :call-id [:review 42]
                      :success-event-type :review/classified
                      :failure-event-type :review/classification-failed}
              :on-complete! #(swap! outcomes conj %)})
    @outcomes))

(deftest io-handler-completes-once-with-correlated-validated-data
  (is (= [{:event {:type :review/classified
                   :call-id [:review 42]
                   :data {:sentiment :positive :confidence 0.9}}}]
         (run-handler (fn [_ _]
                        (completion "{\"sentiment\":\"positive\",\"confidence\":0.9}"))))))

(deftest io-handler-completes-once-with-correlated-failure
  (doseq [post! [(fn [_ _] (throw (java.net.ConnectException. "Connection refused")))
                 (fn [_ _] (completion "{\"sentiment\":\"unknown\",\"confidence\":0.9}"))]]
    (let [outcomes (run-handler post!)
          event (:event (first outcomes))]
      (is (= 1 (count outcomes)))
      (is (= :review/classification-failed (:type event)))
      (is (= [:review 42] (:call-id event)))
      (is (anomaly? (:anomaly event)))
      (is (not (contains? event :data))))))

(deftest json-values-are-not-confused-with-missing-results
  (doseq [[s content expected] [[[:maybe :string] "null" nil]
                                [[:boolean] "false" false]
                                [[:map [:labels [:set :keyword]]]
                                 "{\"labels\":[\"urgent\",\"boss\"]}"
                                 {:labels #{:urgent :boss}}]]]
    (is (= expected
           (ai/call! (assoc (request (fn [_ _] (completion content))) :schema s))))))

(deftest invalid-schema-fails-before-network-work
  (let [calls (atom 0)
        result (ai/call! (assoc (request (fn [_ _] (swap! calls inc)))
                                :schema [:not-a-malli-schema]))]
    (is (anomaly? result))
    (is (= :invalid-schema (:kind result)))
    (is (zero? @calls))))

(deftest http-status-survives-a-non-json-error-body
  (let [result (ai/call! (request (fn [_ _] {:status 503 :body "fixture-secret"})))]
    (is (= :cognitect.anomalies/unavailable (:cognitect.anomalies/category result)))
    (is (= 503 (:status result)))
    (is (not (.contains (pr-str result) "fixture-secret")))))

(deftest provider-error-text-is-not-exposed-in-anomalies
  (let [result (ai/call! (request (fn [_ _]
                                    {:status 200
                                     :body "{\"error\":{\"message\":\"fixture-secret\"}}"})))]
    (is (anomaly? result))
    (is (not (.contains (pr-str result) "fixture-secret")))))

(deftest programming-defects-are-not-reported-as-provider-failures
  (is (thrown? IllegalStateException
               (ai/call! (request (fn [_ _] (throw (IllegalStateException. "Transport bug"))))))))

(deftest completion-callback-errors-do-not-trigger-a-second-outcome
  (let [calls (atom 0)
        req (request (fn [_ _]
                       (completion "{\"sentiment\":\"positive\",\"confidence\":0.9}")))
        handler (ai/make-handler (select-keys req [:provider :provider-options]))]
    (is (thrown? IllegalStateException
                 (handler {:event (assoc (select-keys req [:schema :input])
                                         :call-id :callback-test
                                         :success-event-type :done
                                         :failure-event-type :failed)
                           :on-complete! (fn [_]
                                           (swap! calls inc)
                                           (throw (IllegalStateException. "Callback bug")))})))
    (is (= 1 @calls))))
