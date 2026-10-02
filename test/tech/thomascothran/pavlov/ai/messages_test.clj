(ns tech.thomascothran.pavlov.ai.messages-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [tech.thomascothran.pavlov.ai :as ai]))

(def history
  [{:role :system :content "Return the requested data."}
   {:role "developer" :content "Use the supplied schema."}
   {:role :user :content "Previous question"}
   {:role :assistant :content "Previous answer"}
   {:role :user :content "Current question"}])

(defn fixture [calls]
  {:provider :openai-compatible
   :schema [:map [:value :int]]
   :provider-options
   {:url "https://model.example/chat/completions"
    :model "fixture-model"
    :post! (fn [_ options]
             (swap! calls conj (json/read-str (:body options) :key-fn keyword))
             {:status 200
              :body (json/write-str
                     {:choices [{:message {:role "assistant" :content "{\"value\":42}"}
                                 :finish_reason "stop"}]})})}})

(deftest sends-explicit-history-in-order-and-validates-the-result
  (let [calls (atom [])]
    (is (= {:value 42} (ai/call! (assoc (fixture calls) :messages history))))
    (is (= 1 (count @calls)))
    (is (= (mapv #(update % :role (fn [role] (if (keyword? role) (name role) role))) history)
           (:messages (first @calls))))))

(deftest input-is-shorthand-for-one-user-message
  (let [calls (atom [])]
    (is (= {:value 42} (ai/call! (assoc (fixture calls) :input "Question"))))
    (is (= [{:role "user" :content "Question"}] (:messages (first @calls))))))

(deftest invalid-or-ambiguous-message-input-fails-before-http
  (doseq [input [{:input "Question" :messages history}
                 {:input nil :messages history}
                 {:messages []}
                 {:messages nil}
                 {:messages [{:role :tool :content "No tool protocol"}]}
                 {:messages [{:role :user :content 42}]}
                 {}]]
    (let [calls (atom [])
          result (ai/call! (merge (fixture calls) input))]
      (is (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category result)))
      (is (empty? @calls)))))

(deftest io-handler-forwards-history-from-the-request-event
  (let [calls (atom [])
        outcomes (atom [])
        req (fixture calls)]
    ((ai/make-handler (select-keys req [:provider :provider-options]))
     {:event {:type :model/call :schema (:schema req) :messages history
              :call-id :history-call :success-event-type :done :failure-event-type :failed}
      :on-complete! #(swap! outcomes conj %)})
    (is (= [{:event {:type :done :call-id :history-call :data {:value 42}}}] @outcomes))
    (is (= 5 (count (:messages (first @calls)))))))
