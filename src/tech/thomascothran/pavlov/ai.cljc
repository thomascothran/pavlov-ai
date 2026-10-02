(ns tech.thomascothran.pavlov.ai
  (:require [tech.thomascothran.pavlov.ai.provider :as provider]
            [tech.thomascothran.pavlov.ai.schema :as schema]
            [tech.thomascothran.pavlov.ai.schema.malli]
            #?(:clj [tech.thomascothran.pavlov.ai.provider.openai-compatible])
            #?(:clj [tech.thomascothran.pavlov.ai.provider.openrouter-decisions])))

(defn call!
  "Perform one synchronous model call.

  REQUEST supplies :provider, :provider-options, :schema, and provider-specific
  :input and/or :messages. The provider owns input formats, option requirements,
  and response parsing; the schema implementation owns decoding and validation.

  Return schema-decoded, locally validated data or a Cognitect anomaly for
  expected failures. Unexpected programming exceptions propagate. No conversation
  state, retries, or agent loop are maintained. The top-level
  :cognitect.anomalies/category key is reserved for anomalies."
  [{response-schema :schema :keys [provider provider-options] :as request}]
  (let [result (provider/call!
                provider (merge (dissoc provider-options :input :messages)
                                (select-keys request [:input :messages])
                                {:schema response-schema}))]
    (if (provider/anomaly? result)
      result
      (schema/decode-result response-schema (:data result)))))

(defn make-handler
  "Create a handler for Pavlov IO's {:event ... :on-complete! ...} contract.
  CONFIG supplies :provider and :provider-options. Each request event supplies
  :schema, provider-specific :input and/or :messages, :call-id, :success-event-type and
  :failure-event-type. History is caller-owned; the handler retains no history.

  Calls on-complete! once with {:event outcome}, containing :call-id and either
  :data or :anomaly. Dispatch blocking calls outside the bprogram step (the
  JVM IO subscriber does this by default). Callback exceptions propagate."
  [config]
  (fn [{:keys [event on-complete!]}]
    (let [result (call! (merge (select-keys config [:provider :provider-options])
                               (select-keys event [:schema :input :messages])))
          failed? (provider/anomaly? result)]
      (on-complete!
       {:event {:type (get event (if failed? :failure-event-type :success-event-type))
                :call-id (:call-id event)
                (if failed? :anomaly :data) result}}))))
