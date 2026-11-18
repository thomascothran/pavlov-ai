(ns pavlov-ai.decision
  "A bthread asks Jev whether a task list needs attention today."
  (:refer-clojure :exclude [run!])
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [hato.client :as http]
            [llm :as openrouter]
            [tech.thomascothran.pavlov.ai :as ai]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bprogram :as program]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bp]
            [tech.thomascothran.pavlov.bprogram.proto :as proto]
            [tech.thomascothran.pavlov.io :as io]))

(def tasks
  [{:task "Pay the electricity bill" :due "today"}
   {:task "Buy a birthday present" :due "next week"}
   {:task "Organize the bookshelf" :due "whenever"}])

(def typesafe-api-key
  (delay (-> (slurp "/home/default/.secrets/pavlov-ai-jev-token")
             str/trim)))

(def providers
  "Provider defaults selectable with run!'s :provider option."
  {:openrouter-decisions {:model "typesafe/jev-1.13"
                          :api-key openrouter/openrouter-api-key}
   :typesafe-decisions {:model "jev-latest"
                        :api-key typesafe-api-key}})

(defn make-bthread [task-list]
  (b/scenario
   [{:request #{{:type ::assess
                 :call-id ::urgency
                 :input {:state {:tasks task-list}
                         :questions
                         {:urgent {:type :noul
                                   :instructions "Does any task need attention today?"}}}
                 :schema [:map
                          [:urgent [:map
                                    [:type [:= "noul"]]
                                    [:noul [:double {:min 0 :max 1}]]]]]
                 :success-event-type ::assessed
                 :failure-event-type ::failed}}}
    {:wait-on #{::assessed ::failed}}
    (fn [{:keys [event]}]
      {:bid {:request #{(assoc event :type ::done :terminal true)}}})]))

(defn run!
  "Print each selected event and return the terminal :data or :anomaly event.
  OPTIONS selects :provider (:openrouter-decisions by default, or
  :typesafe-decisions) and overrides transport, model or credentials. Wait at
  most :timeout-ms (default 120000), then throw a timeout exception and stop
  the program."
  ([] (run! {}))
  ([options]
   (let [provider (get options :provider :openrouter-decisions)
         defaults (or (get providers provider)
                      (throw (ex-info "Unknown decision provider" {:provider provider})))
         _ (println "Asking Jev via" provider "whether any task needs attention today...")
         running
         (bp/make-program!
          {::urgency (make-bthread tasks)}
          {:subscribers
           {::trace (fn [event _]
                      (pprint event))
            ::decision (io/make-subscriber!
                        {::assess
                         (ai/make-handler
                          {:provider provider
                           :provider-options
                           (merge {:post! http/post
                                   :model (:model defaults)
                                   :api-key (if (contains? options :api-key)
                                              (:api-key options)
                                              @(:api-key defaults))}
                                  (dissoc options :timeout-ms :provider))})})}})]
     (try
       (let [outcome (deref (proto/stopped running)
                            (get options :timeout-ms 120000) ::timeout)]
         (if (= ::timeout outcome)
           (throw (ex-info "Timed out waiting for Jev" {}))
           outcome))
       (finally (program/kill! running))))))

(comment
  (require 'pavlov-ai.decision :reload)
  (run!)
  (run! {:provider :typesafe-decisions}))
