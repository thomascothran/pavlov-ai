(ns tech.thomascothran.pavlov.ai.io-integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [tech.thomascothran.pavlov.ai :as ai]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bprogram :as program]
            [tech.thomascothran.pavlov.bprogram.proto :as proto]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bp]
            [tech.thomascothran.pavlov.io :as io]))

(deftest structured-outcomes-reach-a-waiting-bthread-through-io
  (doseq [[label post! expected-type]
          [[:success (fn [_ _]
                       {:status 200
                        :body "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"value\\\":42}\"},\"finish_reason\":\"stop\"}]}"})
            :model/succeeded]
           [:failure (fn [_ _] (throw (java.net.ConnectException. "Unavailable")))
            :model/failed]]]
    (testing (name label)
      (let [events (atom [])
            request {:type :model/call
                     :call-id [:integration label]
                     :input "Return a value."
                     :schema [:map [:value :int]]
                     :success-event-type :model/succeeded
                     :failure-event-type :model/failed}
            subscriber (io/make-subscriber!
                        {:model/call
                         (ai/make-handler
                          {:provider :openai-compatible
                           :provider-options {:post! post!
                                              :url "https://model.example/chat/completions"
                                              :model "fixture-model"}})})
            running (bp/make-program!
                       [[:caller (b/scenario
                                  [{:request #{request}}
                                   {:wait-on #{:model/succeeded :model/failed}}
                                   {:request #{{:type :done :terminal true}}}])]]
                       {:subscribers {:model subscriber
                                      :record (fn [event _] (swap! events conj event))}})
            terminal (try (deref (proto/stopped running) 3000 ::timeout)
                          (finally (program/kill! running)))
            outcomes (filter #(#{:model/succeeded :model/failed} (:type %)) @events)
            outcome (first outcomes)]
        (is (= :done (:type terminal)))
        (is (= 1 (count outcomes)))
        (is (= expected-type (:type outcome)))
        (is (= (:call-id request) (:call-id outcome)))
        (if (= label :success)
          (is (= {:value 42} (:data outcome)))
          (is (= :cognitect.anomalies/unavailable
                 (get-in outcome [:anomaly :cognitect.anomalies/category]))))))))
