(ns pavlov-ai.llm
  "A bthread asks an LLM how urgently a task list needs attention."
  (:refer-clojure :exclude [run!])
  (:require [clojure.pprint :refer [pprint]]
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

(defn make-bthread [task-list]
  (b/scenario
   [{:request #{{:type ::assess
                 :call-id ::urgency
                 :input (str "Assess the overall urgency of this task list. "
                             "Use high if anything needs attention today, otherwise "
                             "medium or low. Give a short reason. Tasks: "
                             (pr-str task-list))
                 :schema [:map
                          [:urgency [:enum :low :medium :high]]
                          [:reason :string]]
                 :success-event-type ::assessed
                 :failure-event-type ::failed}}}
    {:wait-on #{::assessed ::failed}}
    (fn [{:keys [event]}]
      {:bid {:request #{(assoc event :type ::done :terminal true)}}})]))

(defn run!
  "Print each selected event and return the terminal :data or :anomaly event.
  OPTIONS overrides transport, model or credentials. Wait at most :timeout-ms
  (default 120000), then throw a timeout exception and stop the program."
  ([] (run! {}))
  ([options]
   (let [_ (println "Asking the LLM to assess task urgency...")
         running
         (bp/make-program!
          {::urgency (make-bthread tasks)}
          {:subscribers
           {::trace (fn [event _]
                      (pprint event))
            ::llm (io/make-subscriber!
                   {::assess
                    (ai/make-handler
                     {:provider :openai-compatible
                      :provider-options
                      (merge {:post! http/post
                              :url "https://openrouter.ai/api/v1/chat/completions"
                              :model "openai/gpt-4o-mini"
                              :api-key (if (contains? options :api-key)
                                         (:api-key options)
                                         @openrouter/openrouter-api-key)}
                             (dissoc options :timeout-ms))})})}})]
     (try
       (let [outcome (deref (proto/stopped running)
                            (get options :timeout-ms 120000) ::timeout)]
         (if (= ::timeout outcome)
           (throw (ex-info "Timed out waiting for the LLM" {}))
           outcome))
       (finally (program/kill! running))))))

(comment
  (run!))
