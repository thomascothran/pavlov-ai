(ns pavlov-ai.claude-cli
  "The pavlov-ai.llm urgency bthread, answered by the local Claude CLI using the
  logged-in Claude subscription instead of OpenRouter. The bthread is unchanged;
  only the provider configuration differs."
  (:refer-clojure :exclude [run!])
  (:require [clojure.pprint :refer [pprint]]
            [pavlov-ai.llm :as llm]
            [tech.thomascothran.pavlov.ai :as ai]
            [tech.thomascothran.pavlov.ai.provider.claude-cli :as claude-cli]
            [tech.thomascothran.pavlov.bprogram :as program]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bp]
            [tech.thomascothran.pavlov.bprogram.proto :as proto]
            [tech.thomascothran.pavlov.io :as io]))

(defn run!
  "Print each selected event and return the terminal :data or :anomaly event.
  OPTIONS overrides provider options (:model, :effort, :dir, :tools, ...).
  Wait at most :timeout-ms (default 120000) for both the CLI and the program."
  ([] (run! {}))
  ([options]
   (let [timeout-ms (get options :timeout-ms 120000)
         _ (println "Asking Claude (via the CLI) to assess task urgency...")
         running
         (bp/make-program!
          {::llm/urgency (llm/make-bthread llm/tasks)}
          {:subscribers
           {::trace (fn [event _] (pprint event))
            ::claude (io/make-subscriber!
                      {::llm/assess
                       (ai/make-handler
                        {:provider :claude-cli
                         :provider-options
                         (merge {:run! claude-cli/run-process
                                 :model "haiku"
                                 :effort "low"
                                 :timeout-ms timeout-ms}
                                options)})})}})]
     (try
       (let [outcome (deref (proto/stopped running) timeout-ms ::timeout)]
         (if (= ::timeout outcome)
           (throw (ex-info "Timed out waiting for Claude" {}))
           outcome))
       (finally (program/kill! running))))))

(comment
  (run!)
  (run! {:model "sonnet" :effort "medium"}))
