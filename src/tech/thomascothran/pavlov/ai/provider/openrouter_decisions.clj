(ns tech.thomascothran.pavlov.ai.provider.openrouter-decisions
  "OpenRouter's native Decisions API; no chat or tool-call protocol.

  provider/call! requires :model and :input {:state ... :questions ...}.
  :post! (Hato-shaped transport, default hato.client/post), :url, :api-key and
  :headers are optional.
  The adapter returns decoded answers; :schema is used by the caller for local
  decoding and validation, not sent to the Decisions API.

  Optional request settings:
  - :timeout-ms  per-request timeout, passed to :post! as Hato's :timeout.
  - :extra-body  map merged into the JSON request body; the adapter's own
                 fields (model, state, questions) win.
  - :omit-provider-error-message?  when true, anomalies get a generic message
                 instead of OpenRouter's error message. Default false.
  :effort is not sent: this API has no reasoning-effort field.

  Failed calls carry OpenRouter's error message ({\"error\": {\"message\": ...}})
  as the anomaly message, truncated. Non-JSON error bodies are not copied."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hato.client :as http]
            [tech.thomascothran.pavlov.ai.provider :as provider]))

(def default-url "https://openrouter.ai/api/alpha/decisions")

(defn- failure [category kind message]
  {:cognitect.anomalies/category category
   :cognitect.anomalies/message message
   :kind kind})

(defn- text? [x] (and (string? x) (not (str/blank? x))))
(defn- identifier? [x] (or (keyword? x) (text? x)))
(defn- valid-question? [{:keys [type instructions criteria]}]
  (and (text? instructions)
       (case type
         (:choice "choice") (and (map? criteria) (seq criteria)
                                  (every? identifier? (keys criteria))
                                  (every? text? (vals criteria)))
         (:score "score") (and (vector? criteria) (<= 2 (count criteria) 10)
                                (every? text? criteria))
         (:noul "noul") true
         false)))

(defn- valid-input? [{:keys [state questions] :as input}]
  (and (map? input)
       (not-any? #(contains? input %) [:messages :tools :tool_choice])
       (or (string? state) (map? state) (vector? state))
       (map? questions) (seq questions)
       (every? identifier? (keys questions))
       (every? #(and (map? %) (valid-question? %)) (vals questions))))

(defn- status-category [status]
  (cond
    (#{401 403} status) :cognitect.anomalies/forbidden
    (= 404 status) :cognitect.anomalies/not-found
    (= 408 status) :cognitect.anomalies/interrupted
    (= 409 status) :cognitect.anomalies/conflict
    (= 429 status) :cognitect.anomalies/busy
    (<= 500 status 599) :cognitect.anomalies/unavailable
    (<= 400 status 499) :cognitect.anomalies/incorrect
    :else :cognitect.anomalies/fault))

(defn- decode-body
  "BODY decoded as JSON, or ::invalid when it is not JSON."
  [body]
  (try (if (string? body) (json/read-str body :key-fn keyword) body)
       (catch Exception _ ::invalid)))

(defn- error-text
  "OpenRouter's error message from a decoded BODY, if any."
  [body]
  (let [error (when (map? body) (:error body))]
    (cond
      (map? error) (:message error)
      (string? error) error
      :else nil)))

(defn- result [options {:keys [status body]}]
  (cond
    (not (integer? status))
    (failure :cognitect.anomalies/fault :malformed-http-response
             "Decisions response is missing an integer HTTP status")
    (not (<= 200 status 299))
    (assoc (failure (status-category status) :provider-http-error
                    (provider/error-message
                     options (error-text (decode-body body))
                     "Decisions API returned an unsuccessful HTTP status"))
           :status status)
    :else
    (let [decoded (decode-body body)]
      (cond
        (= ::invalid decoded)
        (failure :cognitect.anomalies/fault :invalid-json
                 "Decisions response is not valid JSON")
        (and (map? decoded) (:error decoded))
        (failure :cognitect.anomalies/fault :provider-error
                 (provider/error-message options (error-text decoded)
                                         "Decisions API returned an error"))
        (not (and (map? decoded) (map? (:answers decoded)) (seq (:answers decoded))))
        (failure :cognitect.anomalies/fault :malformed-response
                 "Decisions response is missing an answers object")
        ;; Only decoded answers cross this boundary; usage and provider
        ;; metadata stay here.
        :else {:data (:answers decoded)}))))

(defmethod provider/call! :openrouter-decisions
  [_provider {:keys [post! url model input messages api-key headers timeout-ms extra-body]
              :or {post! http/post} :as options}]
  (if-not (and (ifn? post!) (text? model) (or (nil? url) (text? url))
               (nil? messages) (valid-input? input)
               (provider/valid-extra-body? extra-body)
               (or (nil? timeout-ms) (pos-int? timeout-ms)))
    (failure :cognitect.anomalies/incorrect :invalid-options
             (str "Decisions calls require :model and :input with state and typed questions, "
                  "a map :extra-body and a positive :timeout-ms when given"))
    (try
      (result
       options
       (post! (or url default-url)
              (cond-> {:headers (merge {"Content-Type" "application/json"}
                                       (when api-key {"Authorization" (str "Bearer " api-key)})
                                       headers)
                       :body (json/write-str
                              (provider/request-body
                               extra-body
                               {:model model :state (:state input) :questions (:questions input)}))
                       :throw-exceptions false}
                timeout-ms (assoc :timeout timeout-ms))))
      (catch java.net.SocketTimeoutException _
        (failure :cognitect.anomalies/interrupted :timeout "Decisions request timed out"))
      (catch java.net.http.HttpTimeoutException _
        (failure :cognitect.anomalies/interrupted :timeout "Decisions request timed out"))
      (catch java.io.IOException e
        (failure :cognitect.anomalies/unavailable :connection-failure
                 (provider/error-message options (ex-message e)
                                         "Decisions transport failed"))))))
