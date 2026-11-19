(ns tech.thomascothran.pavlov.ai.provider.typesafe-decisions
  "TypeSafe's native Jev decisions API (POST /v1/systemone); no chat or
  tool-call protocol.

  provider/call! requires :model and :input {:state ... :questions ...}.
  :post! (Hato-shaped transport, default hato.client/post), :url, :api-key and
  :headers are optional.
  Opt-in TypeSafe headers such as TypeSafe-Danger-Zone-Long-Context may be
  supplied through :headers. The adapter returns decoded answers; :schema is
  used by the caller for local decoding and validation, not sent to TypeSafe.

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

(defn- result [{:keys [status body]}]
  (cond
    (not (integer? status))
    (failure :cognitect.anomalies/fault :malformed-http-response
             "TypeSafe response is missing an integer HTTP status")
    (not (<= 200 status 299))
    ;; Error bodies ({"detail": ...}) are not copied: they may echo input.
    (assoc (failure (status-category status) :provider-http-error
                    "TypeSafe API returned an unsuccessful HTTP status") :status status)
    :else
    (let [parsed (try {:body (if (string? body) (json/read-str body :key-fn keyword) body)}
                      (catch Exception _
                        (failure :cognitect.anomalies/fault :invalid-json
                                 "TypeSafe response is not valid JSON")))
          decoded (:body parsed)]
      (cond
        (provider/anomaly? parsed) parsed
        (and (map? decoded) (or (:error decoded) (:detail decoded)))
        (failure :cognitect.anomalies/fault :provider-error "TypeSafe API returned an error")
        (not (and (map? decoded) (map? (:answers decoded)) (seq (:answers decoded))))
        (failure :cognitect.anomalies/fault :malformed-response
                 "TypeSafe response is missing an answers object")
        ;; Only decoded answers cross this boundary; usage and the resolved
        ;; model stay here.
        :else {:data (:answers decoded)}))))

(defmethod provider/call! :typesafe-decisions
  [_provider {:keys [post! url model input messages api-key headers]
              :or {post! http/post}}]
  (if-not (and (ifn? post!) (text? model) (or (nil? url) (text? url))
               (nil? messages) (valid-input? input))
    (failure :cognitect.anomalies/incorrect :invalid-options
             "TypeSafe decisions calls require :model and :input with state and typed questions")
    (try
      (result
       (post! (or url default-url)
              {:headers (merge {"Content-Type" "application/json"}
                               (when api-key {"Authorization" (str "Bearer " api-key)})
                               headers)
               :body (json/write-str {:model model :state (:state input) :questions (:questions input)})
               :throw-exceptions false}))
      (catch java.net.SocketTimeoutException _
        (failure :cognitect.anomalies/interrupted :timeout "TypeSafe request timed out"))
      (catch java.net.http.HttpTimeoutException _
        (failure :cognitect.anomalies/interrupted :timeout "TypeSafe request timed out"))
      (catch java.io.IOException _
        (failure :cognitect.anomalies/unavailable :connection-failure "TypeSafe transport failed")))))
