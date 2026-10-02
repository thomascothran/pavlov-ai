(ns tech.thomascothran.pavlov.ai.provider)

(defn anomaly?
  "Return true when X is a Cognitect anomaly."
  [x]
  (and (map? x)
       (contains? x :cognitect.anomalies/category)))

(defn bail-on-anomaly
  "Wrap F so an anomaly passes through without invoking F."
  [f]
  (fn [x]
    (if (anomaly? x)
      x
      (f x))))

(defmulti chat-completion!
  "Perform a non-streaming chat completion using PROVIDER.

  PROVIDER must be a keyword or nil. Provider implementations define the
  remaining options and return the decoded HTTP response without translating
  it into Pavlov's internal response representation."
  (fn [provider _options]
    {:pre [(or (keyword? provider)
               (nil? provider))]}
    provider))

(defmulti normalize-chat-completion
  "Translate a provider's decoded HTTP RESPONSE into a provider-neutral result.

  The result has `:outcome` set to either `:success` or `:failure`. This
  function is pure: runtime correlation and event submission remain the
  responsibility of the caller."
  (fn [provider _response]
    {:pre [(or (keyword? provider)
               (nil? provider))]}
    provider))

(defmulti call!
  "Perform one model call using PROVIDER with :input or :messages and
  :schema (the original response schema). Providers own any schema conversion
  required by their APIs and translate supported input formats; unsupported
  formats return anomalies.
  Return {:data decoded-value} or a Cognitect anomaly. Implementations own
  response parsing, provider wire format and transport; no tool-call protocol
  is involved. The :data value may be nil or false."
  (fn [provider _options] provider))

(defmethod call! :default
  [_provider _options]
  {:cognitect.anomalies/category :cognitect.anomalies/unsupported
   :cognitect.anomalies/message "Provider does not support structured output"
   :kind :unsupported-provider})
