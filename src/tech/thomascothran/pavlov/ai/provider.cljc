(ns tech.thomascothran.pavlov.ai.provider
  (:require [clojure.string :as str]))

(def max-error-message-length
  "Longest provider error text copied into an anomaly message."
  2000)

(defn error-message
  "The anomaly message for a provider failure: TEXT, the provider's or CLI's own
  error text, trimmed and truncated, or FALLBACK when TEXT is not a nonblank
  string or OPTIONS set :omit-provider-error-message?. Provider text can echo
  request input; the option keeps it out of anomalies, events and logs."
  [options text fallback]
  (if (and (not (:omit-provider-error-message? options))
           (string? text)
           (not (str/blank? text)))
    (let [text (str/trim text)]
      (if (> (count text) max-error-message-length)
        (str (subs text 0 max-error-message-length) "…")
        text))
    fallback))

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

(defn effort-name
  "The wire form of a shared :effort option: a keyword's name, else EFFORT.
  Values are not validated; the provider or CLI decides what it accepts."
  [effort]
  (if (keyword? effort) (name effort) effort))

(defn request-body
  "Merge EXTRA-BODY, the caller's :extra-body passthrough, under OWNED, the
  fields an adapter builds itself. Top-level EXTRA-BODY keys may be keywords or
  strings and are keywordized so they cannot duplicate OWNED keys; OWNED keys
  always win. Nested values are sent as given."
  [extra-body owned]
  (merge (into {}
               (map (fn [[k v]] [(if (string? k) (keyword k) k) v]))
               extra-body)
         owned))

(defn valid-extra-body?
  "True when EXTRA-BODY is absent or a map."
  [extra-body]
  (or (nil? extra-body) (map? extra-body)))

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
