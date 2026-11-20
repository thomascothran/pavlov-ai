(ns tech.thomascothran.pavlov.ai.provider.typesafe-decisions
  "TypeSafe's native Jev decisions API (POST /v1/systemone); no chat or
  tool-call protocol.

  provider/call! requires :model and :input {:state ... :questions ...}.
  :post! (Hato-shaped transport, default hato.client/post), :url, :api-key and
  :headers are optional.
  Opt-in TypeSafe headers such as TypeSafe-Danger-Zone-Long-Context may be
  supplied through :headers. The adapter returns decoded answers; :schema is
  used by the caller for local decoding and validation, not sent to TypeSafe.

  Optional request settings:
  - :timeout-ms  per-request timeout, passed to :post! as Hato's :timeout.
  - :extra-body  map merged into the JSON request body; the adapter's own
                 fields (model, state, questions) win.
  - :omit-provider-error-message?  when true, anomalies get a generic message
                 instead of TypeSafe's error detail. Default false.
  :effort is not sent: this API has no reasoning-effort field.

  Failed calls carry TypeSafe's error detail ({\"detail\": ...}, including
  validation errors as \"location: message\") as the anomaly message, truncated.
  Non-JSON error bodies are not copied.

  Deliberately independent of the OpenRouter decisions adapter so that the two
  APIs may diverge."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hato.client :as http]
            [tech.thomascothran.pavlov.ai.provider :as provider]))

(def default-url "https://api.typesafe.ai/v1/systemone")

(defn- failure [category kind message]
  {:cognitect.anomalies/category category
   :cognitect.anomalies/message message
   :kind kind})

(defn- text? [x] (and (string? x) (not (str/blank? x))))
(defn- identifier? [x] (or (keyword? x) (text? x)))
(defn- noul-criteria-key? [k] (contains? #{true false :true :false "true" "false"} k))

(defn- valid-question? [{:keys [type instructions criteria]}]
  (and (or (nil? instructions) (text? instructions))
       (case type
         (:choice "choice") (and (map? criteria) (<= 1 (count criteria) 255)
                                  (every? identifier? (keys criteria))
                                  (every? text? (vals criteria)))
         (:score "score") (and (vector? criteria) (<= 1 (count criteria) 10)
                                (every? text? criteria))
         ;; TypeSafe rejects a noul question with neither criteria nor
         ;; instructions (HTTP 400).
         (:noul "noul") (if (nil? criteria)
                           (some? instructions)
                           (and (map? criteria) (seq criteria)
                                (every? noul-criteria-key? (keys criteria))
                                (every? text? (vals criteria))))
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

(defn- validation-error-text
  [{:keys [loc msg]}]
  (when (string? msg)
    (if (sequential? loc)
      (str (str/join "." loc) ": " msg)
      msg)))

(defn- error-text
  "TypeSafe's error detail from a decoded BODY, if any. Validation errors
  ([{\"loc\": [...], \"msg\": ...}]) become \"location: message\" entries;
  API errors ({\"error_type\": ..., \"message\": ...}) give their message."
  [body]
  (when (map? body)
    (let [{:keys [detail error]} body]
      (cond
        (string? detail) detail
        (map? detail) (:message detail)
        (sequential? detail) (some->> (keep #(when (map? %) (validation-error-text %)) detail)
                                      seq
                                      (str/join "; "))
        (string? error) error
        (map? error) (:message error)
        :else nil))))

(defn- result [options {:keys [status body]}]
  (cond
    (not (integer? status))
    (failure :cognitect.anomalies/fault :malformed-http-response
             "TypeSafe response is missing an integer HTTP status")
    (not (<= 200 status 299))
    (assoc (failure (status-category status) :provider-http-error
                    (provider/error-message
                     options (error-text (decode-body body))
                     "TypeSafe API returned an unsuccessful HTTP status"))
           :status status)
    :else
    (let [decoded (decode-body body)]
      (cond
        (= ::invalid decoded)
        (failure :cognitect.anomalies/fault :invalid-json
                 "TypeSafe response is not valid JSON")
        (and (map? decoded) (or (:error decoded) (:detail decoded)))
        (failure :cognitect.anomalies/fault :provider-error
                 (provider/error-message options (error-text decoded)
                                         "TypeSafe API returned an error"))
        (not (and (map? decoded) (map? (:answers decoded)) (seq (:answers decoded))))
        (failure :cognitect.anomalies/fault :malformed-response
                 "TypeSafe response is missing an answers object")
        ;; Only decoded answers cross this boundary; usage and the resolved
        ;; model stay here.
        :else {:data (:answers decoded)}))))

(defmethod provider/call! :typesafe-decisions
  [_provider {:keys [post! url model input messages api-key headers timeout-ms extra-body]
              :or {post! http/post} :as options}]
  (if-not (and (ifn? post!) (text? model) (or (nil? url) (text? url))
               (nil? messages) (valid-input? input)
               (provider/valid-extra-body? extra-body)
               (or (nil? timeout-ms) (pos-int? timeout-ms)))
    (failure :cognitect.anomalies/incorrect :invalid-options
             (str "TypeSafe decisions calls require :model and :input with state and typed questions, "
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
        (failure :cognitect.anomalies/interrupted :timeout "TypeSafe request timed out"))
      (catch java.net.http.HttpTimeoutException _
        (failure :cognitect.anomalies/interrupted :timeout "TypeSafe request timed out"))
      (catch java.io.IOException e
        (failure :cognitect.anomalies/unavailable :connection-failure
                 (provider/error-message options (ex-message e)
                                         "TypeSafe transport failed"))))))
