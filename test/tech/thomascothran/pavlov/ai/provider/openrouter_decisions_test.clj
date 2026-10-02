(ns tech.thomascothran.pavlov.ai.provider.openrouter-decisions-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [tech.thomascothran.pavlov.ai :as ai]))

(def questions
  {:team {:type :choice :instructions "Which team?"
          :criteria {:billing "Charges" :technical "Bugs"}}
   :urgent {:type :noul :instructions "Is it urgent?"}
   :severity {:type :score :instructions "How severe?" :criteria ["Low" "High"]}})
(def answers
  {:team {:type "choice" :choice "billing" :probabilities {:billing 0.9 :technical 0.1} :confidence 0.8}
   :urgent {:type "noul" :noul 0.7}
   :severity {:type "score" :score 0.6 :legend {:0 "Low" :1 "High"}
              :probabilities {:0 0.4 :1 0.6} :confidence 0.2}})
(def probability [:double {:min 0 :max 1}])
(def answer-schema
  [:map {:closed true}
   [:team [:map [:type [:= "choice"]] [:choice [:enum :billing :technical]]
           [:probabilities [:map-of :keyword probability]] [:confidence probability]]]
   [:urgent [:map [:type [:= "noul"]] [:noul probability]]]
   [:severity [:map [:type [:= "score"]] [:score :double]
               [:legend [:map-of :keyword :string]]
               [:probabilities [:map-of :keyword probability]] [:confidence probability]]]])
(defn response [value] {:status 200 :body (json/write-str {:answers value :usage {:input_tokens 10}})})
(defn request [post!]
  {:provider :openrouter-decisions :schema answer-schema
   :input {:state {:ticket "Double charged"} :questions questions}
   :provider-options {:post! post! :api-key "fixture-secret" :model "typesafe/jev-1.13"}})

(deftest sends-native-decisions-request-and-validates-all-three-answer-types
  (let [calls (atom [])
        result (ai/call! (request (fn [url opts]
                                    (swap! calls conj [url opts])
                                    (response answers))))
        [url opts] (first @calls)]
    (is (= (assoc-in answers [:team :choice] :billing) result))
    (is (= 1 (count @calls)))
    (is (= "https://openrouter.ai/api/alpha/decisions" url))
    (is (= "Bearer fixture-secret" (get-in opts [:headers "Authorization"])))
    (is (= false (:throw-exceptions opts)))
    (is (= (json/read-str (json/write-str {:model "typesafe/jev-1.13"
                                           :state {:ticket "Double charged"} :questions questions}))
           (when (:body opts) (json/read-str (:body opts)))))))

(deftest invalid-answers-are-schema-anomalies
  (doseq [a [(assoc-in answers [:team :choice] "unknown")
             (assoc-in answers [:urgent :noul] 2.0)
             (dissoc answers :severity)]]
    (let [result (ai/call! (request (fn [_ _] (response a))))]
      (is (= :schema-violation (:kind result)))
      (is (some? (:explanation result))))))

(deftest unsupported-input-fails-without-http
  (doseq [input ["A chat prompt" {:state "Ticket" :questions {}}
                 {:questions questions} {:state nil :questions questions}
                 {:state "Ticket" :questions {:team {:type :unknown :instructions "Question"}}}]]
    (let [calls (atom 0)
          result (ai/call! (assoc (request (fn [_ _] (swap! calls inc))) :input input))]
      (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category result)))
      (is (zero? @calls)))))

(deftest api-failures-return-anomalies
  (doseq [[post! category]
          [[(fn [_ _] {:status 429 :body "fixture-secret"}) :cognitect.anomalies/busy]
           [(fn [_ _] (throw (java.net.ConnectException. "fixture-secret"))) :cognitect.anomalies/unavailable]
           [(fn [_ _] (throw (java.net.SocketTimeoutException. "fixture-secret"))) :cognitect.anomalies/interrupted]
           [(fn [_ _] {:status 200 :body "not JSON"}) :cognitect.anomalies/fault]
           [(fn [_ _] {:status 200 :body "{}"}) :cognitect.anomalies/fault]
           [(fn [_ _] {:status 200 :body "{\"answers\":[]}"}) :cognitect.anomalies/fault]]]
    (let [result (ai/call! (request post!))]
      (is (= category (:cognitect.anomalies/category result)))
      (is (not (.contains (pr-str result) "fixture-secret"))))))

(deftest handler-accepts-a-decision-event-with-structured-input
  (let [outcomes (atom []) req (request (fn [_ _] (response answers)))]
    ((ai/make-handler (select-keys req [:provider :provider-options]))
     {:event {:type :decision/requested :input (:input req) :schema answer-schema
              :call-id :decision-1 :success-event-type :decision/completed
              :failure-event-type :decision/failed}
      :on-complete! #(swap! outcomes conj %)})
    (is (= [{:event {:type :decision/completed :call-id :decision-1
                     :data (assoc-in answers [:team :choice] :billing)}}]
           @outcomes))))

(deftest endpoint-and-headers-can-be-configured
  (let [calls (atom [])
        req (-> (request (fn [url opts] (swap! calls conj [url opts]) (response answers)))
                (assoc-in [:provider-options :url] "https://proxy.example/decisions")
                (assoc-in [:provider-options :headers] {"X-Title" "Pavlov"}))]
    (ai/call! req)
    (is (= "https://proxy.example/decisions" (ffirst @calls)))
    (is (= "Pavlov" (get-in (second (first @calls)) [:headers "X-Title"])))))

(deftest handler-returns-a-correlated-failure-and-cannot-override-provider-config
  (let [outcomes (atom []) calls (atom [])
        req (request (fn [url _] (swap! calls conj url) {:status 503 :body "Unavailable"}))]
    ((ai/make-handler (select-keys req [:provider :provider-options]))
     {:event {:type :decision/requested :input (:input req) :schema answer-schema
              :provider :openai-compatible :provider-options {:url "https://untrusted.example"}
              :call-id :decision-2 :success-event-type :decision/completed
              :failure-event-type :decision/failed}
      :on-complete! #(swap! outcomes conj %)})
    (is (= ["https://openrouter.ai/api/alpha/decisions"] @calls))
    (is (= 1 (count @outcomes)))
    (is (= :decision/failed (get-in @outcomes [0 :event :type])))
    (is (= :decision-2 (get-in @outcomes [0 :event :call-id])))
    (is (= :cognitect.anomalies/unavailable
           (get-in @outcomes [0 :event :anomaly :cognitect.anomalies/category])))))
