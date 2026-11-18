(ns pavlov-ai.pi-cli
  "The pavlov-ai.llm urgency bthread, answered through the local pi CLI using
  whatever model provider pi is logged in to, instead of OpenRouter. The
  bthread is unchanged; only the provider configuration differs."
  (:refer-clojure :exclude [run!])
  (:require [clojure.pprint :refer [pprint]]
            [pavlov-ai.llm :as llm]
            [tech.thomascothran.pavlov.ai :as ai]
            [tech.thomascothran.pavlov.ai.provider.process :as process]
            [tech.thomascothran.pavlov.bprogram :as program]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bp]
            [tech.thomascothran.pavlov.bprogram.proto :as proto]
            [tech.thomascothran.pavlov.io :as io]))

(defn run!
  "Print each selected event and return the terminal :data or :anomaly event.
  OPTIONS overrides provider options (:model-provider, :model, :effort, :dir,
  :tools, :cache-key, ...). Wait at most :timeout-ms (default 120000) for both
  the CLI and the program."
  ([] (run! {}))
  ([options]
   (let [timeout-ms (get options :timeout-ms 120000)
         _ (println "Asking a model (via pi) to assess task urgency...")
         running
         (bp/make-program!
          {::llm/urgency (llm/make-bthread llm/tasks)}
          {:subscribers
           {::trace (fn [event _] (pprint event))
            ::pi (io/make-subscriber!
                  {::llm/assess
                   (ai/make-handler
                    {:provider :pi-cli
                     :provider-options
                     (merge {:run! process/run-process
                             :model-provider "openai-codex"
                             :model "gpt-6-luna"
                             :effort "low"
                             :timeout-ms timeout-ms}
                            options)})})}})]
     (try
       (let [outcome (deref (proto/stopped running) timeout-ms ::timeout)]
         (if (= ::timeout outcome)
           (throw (ex-info "Timed out waiting for pi" {}))
           outcome))
       (finally (program/kill! running))))))

(comment
  (run!)
  (run! {:model-provider "openrouter" :model "openai/gpt-5-mini" :effort "medium"}))
