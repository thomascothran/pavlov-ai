(ns tech.thomascothran.pavlov.ai
  (:require [tech.thomascothran.pavlov.ai.provider :as provider]
            [tech.thomascothran.pavlov.ai.schema :as schema]
            [tech.thomascothran.pavlov.ai.schema.malli]
            #?(:clj [clojure.data.json :as json])
            #?(:clj [tech.thomascothran.pavlov.ai.provider.openai-compatible])))

(defn- anomaly
  [kind message]
  {:cognitect.anomalies/category :cognitect.anomalies/incorrect
   :cognitect.anomalies/message message
   :kind kind})

(defn- read-json
  [text]
  #?(:clj (json/read-str text :key-fn keyword)
     :cljs (js->clj (js/JSON.parse text) :keywordize-keys true)))

(defn- decode-result
  [response-schema text]
  (let [parsed (try
                 {:value (read-json text)}
                 (catch #?(:clj Exception :cljs :default) _
                   (anomaly :invalid-json "Model output is not valid JSON")))]
    (if (provider/anomaly? parsed)
      parsed
      (let [value (schema/decode response-schema (:value parsed))]
        (if (schema/validate response-schema value)
          value
          (assoc (anomaly :schema-violation "Model output does not conform to the response schema")
                 :explanation (schema/explain response-schema value)))))))

(defn call!
  "Perform one synchronous model call. REQUEST supplies :input (a string),
  :schema (a Malli vector schema), :provider and :provider-options.

  Return JSON-decoded, schema-decoded and locally validated data, or a
  Cognitect anomaly for expected provider/output failures. OpenAI-compatible
  options require :post! (Hato-shaped transport), :url and :model; :api-key
  and :headers are optional. No agent loop or tool calls are used.

  Schema/configuration failures are anomalies; unexpected programming defects
  in adapters or transport are allowed to propagate. Model output must not use
  the reserved top-level :cognitect.anomalies/category key."
  [{response-schema :schema :keys [provider input provider-options]}]
  (let [converted (try
                    {:schema (schema/->json-schema response-schema)}
                    (catch #?(:clj Exception :cljs :default) _
                      (anomaly :invalid-schema "Response schema cannot be converted to JSON Schema")))]
    (if (provider/anomaly? converted)
      converted
      (let [result (provider/structured-output!
                    provider (assoc provider-options
                                    :input input :json-schema (:schema converted)))]
        (if (provider/anomaly? result)
          result
          (decode-result response-schema (:json result)))))))

(defn make-handler
  "Create a handler for Pavlov IO's {:event ... :on-complete! ...} contract.
  CONFIG supplies :provider and :provider-options. Each request event supplies
  :schema, :input, :call-id, :success-event-type and :failure-event-type.

  Calls on-complete! once with {:event outcome}, containing :call-id and either
  :data or :anomaly. Dispatch blocking calls outside the bprogram step (the
  JVM IO subscriber does this by default). Callback exceptions propagate."
  [config]
  (fn [{:keys [event on-complete!]}]
    (let [result (call! (merge (select-keys config [:provider :provider-options])
                              (select-keys event [:schema :input])))
          failed? (provider/anomaly? result)]
      (on-complete!
       {:event {:type (get event (if failed? :failure-event-type :success-event-type))
                :call-id (:call-id event)
                (if failed? :anomaly :data) result}}))))
