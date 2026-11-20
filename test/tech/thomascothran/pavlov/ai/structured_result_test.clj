(ns tech.thomascothran.pavlov.ai.structured-result-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [hato.client :as http]
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

(defn- handle-with-event-options
  "Run a handler configured with CONFIG-OPTIONS over (request post!) on one event
  carrying EVENT-OPTIONS. Return [outcome [url post-options-with-decoded-body]]."
  [config-options event-options]
  (let [calls (atom [])
        outcomes (atom [])
        post! (fn [url opts]
                (swap! calls conj [url (update opts :body json/read-str)])
                (completion "{\"sentiment\":\"positive\",\"confidence\":0.9}"))
        req (update (request post!) :provider-options merge config-options)
        handler (ai/make-handler (select-keys req [:provider :provider-options]))]
    (handler {:event (cond-> {:type :review/classify
                              :schema result-schema
                              :input (:input req)
                              :call-id [:review 43]
                              :success-event-type :review/classified
                              :failure-event-type :review/classification-failed}
                       event-options (assoc :provider-options event-options))
              :on-complete! #(swap! outcomes conj %)})
    [(first @outcomes) (first @calls)]))

(deftest event-provider-options-override-call-settings
  (let [[outcome [_ {:keys [body timeout]}]]
        (handle-with-event-options
         {:effort :low :timeout-ms 1000 :extra-body {:service_tier "flex"}}
         {:model "event-model" :effort :high :timeout-ms 5000
          :extra-body {:temperature 0}})]
    (is (= :review/classified (get-in outcome [:event :type])))
    (is (= "event-model" (get body "model")))
    (is (= "high" (get body "reasoning_effort")))
    (is (= 5000 timeout))
    (testing ":extra-body is replaced, not deep-merged"
      (is (= 0 (get body "temperature")))
      (is (not (contains? body "service_tier")))))
  (testing "configured settings apply when the event has none"
    (let [[_ [_ {:keys [body timeout]}]] (handle-with-event-options {:effort :low :timeout-ms 1000} nil)]
      (is (= "fixture-model" (get body "model")))
      (is (= "low" (get body "reasoning_effort")))
      (is (= 1000 timeout))))
  (testing "non-map event options are ignored"
    (let [[outcome [_ {:keys [body]}]] (handle-with-event-options {} [:model "x"])]
      (is (= :review/classified (get-in outcome [:event :type])))
      (is (= "fixture-model" (get body "model"))))))

(deftest event-provider-options-cannot-override-destination-credentials-or-transport
  (let [event-post-calls (atom 0)
        [outcome [url {:keys [headers body]}]]
        (handle-with-event-options
         {:headers {"X-Title" "Pavlov"}}
         {:url "https://untrusted.example/steal"
          :api-key "event-key"
          :headers {"X-Title" "evil"}
          :post! (fn [_ _] (swap! event-post-calls inc))
          :run! (fn [_] (swap! event-post-calls inc))
          :executable "/tmp/evil"
          :model "event-model"})]
    (is (= :review/classified (get-in outcome [:event :type])))
    (is (= "https://model.example/chat/completions" url))
    (is (= "Bearer fixture-secret" (get headers "Authorization")))
    (is (= "Pavlov" (get headers "X-Title")))
    (is (zero? @event-post-calls))
    (testing "other settings in the same event still apply"
      (is (= "event-model" (get body "model"))))))

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
  (let [result (ai/call! (request (fn [_ _] {:status 503 :body "<html>fixture-page</html>"})))]
    (is (= :cognitect.anomalies/unavailable (:cognitect.anomalies/category result)))
    (is (= 503 (:status result)))
    (is (= "Provider returned HTTP status 503" (:cognitect.anomalies/message result)))
    (testing "a non-JSON body is not copied"
      (is (not (.contains (pr-str result) "fixture-page"))))))

(deftest provider-error-details-are-reported-in-anomalies
  (testing "an HTTP error carries the provider's message, code, type and param"
    (let [result (ai/call! (request (fn [_ _]
                                      {:status 400
                                       :body (json/write-str
                                              {:error {:message "Unsupported value: 'ultra'"
                                                       :code "unsupported_value"
                                                       :type "invalid_request_error"
                                                       :param "reasoning_effort"}})})))]
      (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category result)))
      (is (= "Unsupported value: 'ultra'" (:cognitect.anomalies/message result)))
      (is (= 400 (:status result)))
      (is (= {:kind :provider-http-error
              :message "Unsupported value: 'ultra'"
              :status 400
              :provider-code "unsupported_value"
              :provider-type "invalid_request_error"
              :provider-param "reasoning_effort"}
             (:error result)))))
  (testing "an error in a 200 response carries the provider's message"
    (let [result (ai/call! (request (fn [_ _]
                                      {:status 200
                                       :body "{\"error\":{\"message\":\"Upstream failed\",\"code\":502}}"})))]
      (is (anomaly? result))
      (is (= "Upstream failed" (:cognitect.anomalies/message result)))
      (is (= 502 (get-in result [:error :provider-code])))))
  (testing "a string error is used as the message"
    (let [result (ai/call! (request (fn [_ _] {:status 401 :body "{\"error\":\"No auth\"}"})))]
      (is (= :cognitect.anomalies/forbidden (:cognitect.anomalies/category result)))
      (is (= "No auth" (:cognitect.anomalies/message result))))))

(deftest provider-error-message-can-be-omitted
  (let [body (json/write-str {:error {:message "echoed fixture-input"
                                      :code "unsupported_value"
                                      :param "reasoning_effort"}})
        call (fn [status options]
               (ai/call! (update (request (fn [_ _] {:status status :body body}))
                                 :provider-options merge options)))]
    (testing "HTTP errors keep code and param but not the message"
      (let [result (call 400 {:omit-provider-error-message? true})]
        (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category result)))
        (is (= "Provider returned an error (HTTP status 400)"
               (:cognitect.anomalies/message result)))
        (is (= "unsupported_value" (get-in result [:error :provider-code])))
        (is (= "reasoning_effort" (get-in result [:error :provider-param])))
        (is (not (.contains (pr-str result) "fixture-input")))))
    (testing "errors in a 200 response too"
      (is (not (.contains (pr-str (call 200 {:omit-provider-error-message? true}))
                          "fixture-input"))))
    (testing "off by default"
      (is (= "echoed fixture-input" (:cognitect.anomalies/message (call 400 {})))))))

(deftest credentials-are-never-copied-into-anomalies
  (let [result (ai/call! (-> (request (fn [_ _] {:status 401 :body "{\"error\":{\"message\":\"Bad key\"}}"}))
                             (assoc-in [:provider-options :api-key] "fixture-secret")))]
    (is (= "Bad key" (:cognitect.anomalies/message result)))
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

(deftest posts-with-hato-when-no-post!-is-supplied
  (let [calls (atom [])]
    (with-redefs [http/post (fn [url _opts]
                              (swap! calls conj url)
                              (completion "{\"sentiment\":\"positive\",\"confidence\":0.9}"))]
      (is (= {:sentiment :positive :confidence 0.9}
             (ai/call! (update (request nil) :provider-options dissoc :post!)))))
    (is (= ["https://model.example/chat/completions"] @calls))))
