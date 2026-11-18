(ns tech.thomascothran.pavlov.ai.provider.typesafe-decisions-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [tech.thomascothran.pavlov.ai :as ai]))

(def questions
  {:team {:type :choice :instructions "Which team?"
          :criteria {:billing "Charges" :technical "Bugs"}}
   :urgent {:type :noul :instructions "Is it urgent?"
            :criteria {true "Needs action today" false "Can wait"}}
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
(defn response [value]
  {:status 200 :body (json/write-str {:model "jev-1.13" :answers value
                                      :usage {:input_tokens 10 :output_tokens 3}})})
(defn request [post!]
  {:provider :typesafe-decisions :schema answer-schema
   :input {:state {:ticket "Double charged"} :questions questions}
   :provider-options {:post! post! :api-key "fixture-secret" :model "jev-latest"}})

(deftest sends-native-typesafe-request-and-validates-all-three-answer-types
  (let [calls (atom [])
        result (ai/call! (request (fn [url opts]
                                    (swap! calls conj [url opts])
                                    (response answers))))
        [url opts] (first @calls)]
    (is (= (assoc-in answers [:team :choice] :billing) result))
    (is (= 1 (count @calls)))
    (is (= "https://api.typesafe.ai/v1/systemone" url))
    (is (= "Bearer fixture-secret" (get-in opts [:headers "Authorization"])))
    (is (= "application/json" (get-in opts [:headers "Content-Type"])))
    (is (= false (:throw-exceptions opts)))
    (is (= {"model" "jev-latest"
            "state" {"ticket" "Double charged"}
            "questions" {"team" {"type" "choice" "instructions" "Which team?"
                                 "criteria" {"billing" "Charges" "technical" "Bugs"}}
                         "urgent" {"type" "noul" "instructions" "Is it urgent?"
                                   "criteria" {"true" "Needs action today" "false" "Can wait"}}
                         "severity" {"type" "score" "instructions" "How severe?"
                                     "criteria" ["Low" "High"]}}}
           (when (:body opts) (json/read-str (:body opts)))))))

(deftest native-question-limits-are-accepted
  (doseq [qs [{:q {:type :score :instructions "Level?" :criteria ["Only level"]}}
              {:q {:type :noul :criteria {true "Yes" false "No"}}}
              {:q {:type "choice" :criteria {"only" "The only option"}}}
              {:q {:type :choice :criteria (into {} (map (fn [i] [(keyword (str "o" i)) "x"]))
                                                 (range 255))}}]]
    (let [calls (atom 0)]
      (ai/call! (assoc (request (fn [_ _] (swap! calls inc) (response answers)))
                       :input {:state "Ticket" :questions qs}))
      (is (= 1 @calls) (pr-str (keys (:q qs)))))))

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
                 {:state "Ticket" :questions {:team {:type :unknown :instructions "Question"}}}
                 {:state "Ticket" :questions {:q {:type :score :criteria []}}}
                 {:state "Ticket" :questions {:q {:type :score :criteria (vec (repeat 11 "x"))}}}
                 {:state "Ticket" :questions {:q {:type :choice :criteria {}}}}
                 {:state "Ticket" :questions {:q {:type :choice
                                                  :criteria (into {} (map (fn [i] [(str "o" i) "x"]))
                                                                  (range 256))}}}
                 {:state "Ticket" :questions {:q {:type :noul :criteria {:maybe "Unsure"}}}}
                 {:state "Ticket" :questions {:q {:type :noul :instructions ""}}}
                 {:state "Ticket" :questions {:q {:type :noul}}}]]
    (let [calls (atom 0)
          result (ai/call! (assoc (request (fn [_ _] (swap! calls inc))) :input input))]
      (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category result)) (pr-str input))
      (is (zero? @calls)))))

(deftest chat-messages-are-rejected-without-http
  (let [calls (atom 0)
        result (ai/call! (assoc (request (fn [_ _] (swap! calls inc)))
                                :messages [{:role :user :content "Hi"}]))]
    (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category result)))
    (is (zero? @calls))))

(deftest api-failures-return-anomalies-without-leaking-details
  (doseq [[post! category]
          [[(fn [_ _] {:status 422 :body "{\"detail\":[{\"loc\":[\"body\"],\"msg\":\"fixture-secret\"}]}"})
            :cognitect.anomalies/incorrect]
           [(fn [_ _] {:status 400 :body "{\"detail\":\"fixture-secret\"}"}) :cognitect.anomalies/incorrect]
           [(fn [_ _] {:status 401 :body "fixture-secret"}) :cognitect.anomalies/forbidden]
           [(fn [_ _] {:status 429 :body "fixture-secret"}) :cognitect.anomalies/busy]
           [(fn [_ _] (throw (java.net.ConnectException. "fixture-secret"))) :cognitect.anomalies/unavailable]
           [(fn [_ _] (throw (java.net.SocketTimeoutException. "fixture-secret"))) :cognitect.anomalies/interrupted]
           [(fn [_ _] {:status 200 :body "not JSON"}) :cognitect.anomalies/fault]
           [(fn [_ _] {:status 200 :body "{}"}) :cognitect.anomalies/fault]
           [(fn [_ _] {:status 200 :body "{\"detail\":\"fixture-secret\"}"}) :cognitect.anomalies/fault]
           [(fn [_ _] {:status 200 :body "{\"answers\":[]}"}) :cognitect.anomalies/fault]]]
    (let [result (ai/call! (request post!))]
      (is (= category (:cognitect.anomalies/category result)))
      (is (not (.contains (pr-str result) "fixture-secret"))))))

(deftest endpoint-and-headers-can-be-configured
  (let [calls (atom [])
        req (-> (request (fn [url opts] (swap! calls conj [url opts]) (response answers)))
                (assoc-in [:provider-options :url] "https://proxy.example/v1/systemone")
                (assoc-in [:provider-options :headers]
                          {"TypeSafe-Danger-Zone-Long-Context" "true"}))]
    (ai/call! req)
    (is (= "https://proxy.example/v1/systemone" (ffirst @calls)))
    (is (= "true" (get-in (second (first @calls))
                          [:headers "TypeSafe-Danger-Zone-Long-Context"])))))

(deftest handler-returns-correlated-outcomes-and-cannot-override-provider-config
  (let [outcomes (atom []) calls (atom [])
        handler (ai/make-handler
                 (select-keys (request (fn [url _]
                                         (swap! calls conj url)
                                         (if (= 1 (count @calls))
                                           (response answers)
                                           {:status 503 :body "Unavailable"})))
                              [:provider :provider-options]))
        event (fn [call-id]
                {:type :decision/requested
                 :input {:state {:ticket "Double charged"} :questions questions}
                 :schema answer-schema
                 :provider :openrouter-decisions
                 :provider-options {:url "https://untrusted.example"}
                 :call-id call-id :success-event-type :decision/completed
                 :failure-event-type :decision/failed})]
    (handler {:event (event :decision-1) :on-complete! #(swap! outcomes conj %)})
    (handler {:event (event :decision-2) :on-complete! #(swap! outcomes conj %)})
    (is (= ["https://api.typesafe.ai/v1/systemone" "https://api.typesafe.ai/v1/systemone"] @calls))
    (is (= {:event {:type :decision/completed :call-id :decision-1
                    :data (assoc-in answers [:team :choice] :billing)}}
           (first @outcomes)))
    (is (= :decision/failed (get-in @outcomes [1 :event :type])))
    (is (= :decision-2 (get-in @outcomes [1 :event :call-id])))
    (is (= :cognitect.anomalies/unavailable
           (get-in @outcomes [1 :event :anomaly :cognitect.anomalies/category])))))
