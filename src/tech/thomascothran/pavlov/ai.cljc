(ns tech.thomascothran.pavlov.ai)

(defn call!
  "Return data decoded and validated against :schema, or a Cognitect anomaly.
  Provider options include injected transport. Contract stub; not implemented."
  [_request]
  nil)

(defn make-handler
  "Create a Pavlov IO handler. Configuration supplies provider options; each
  event supplies :schema, :input, :call-id, :success-event-type and
  :failure-event-type. Complete with correlated :data or :anomaly.
  Contract stub; not implemented."
  [_config]
  (fn [_context] nil))
