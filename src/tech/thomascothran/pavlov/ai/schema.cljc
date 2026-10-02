(ns tech.thomascothran.pavlov.ai.schema)

(defmulti ->json-schema type)

(defmulti validate
  (fn [schema _value]
    (type schema)))
(defmulti explain
  (fn [schema _value]
    (type schema)))
(defmulti decode
  (fn [schema _value]
    (type schema)))
(defmulti encode
  (fn [schema _value]
    (type schema)))

(defn decode-result
  "Decode DATA using RESPONSE-SCHEMA and validate the decoded value.
  Return the value or a schema-violation anomaly with an explanation."
  [response-schema data]
  (let [value (decode response-schema data)]
    (if (validate response-schema value)
      value
      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
       :cognitect.anomalies/message "Model output does not conform to the response schema"
       :kind :schema-violation
       :explanation (explain response-schema value)})))
