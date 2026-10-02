(ns tech.thomascothran.pavlov.ai.provider.openrouter-decisions
  "OpenRouter's native Decisions API; no chat or tool-call protocol."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
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

(defn- result [{:keys [status body]}]
  (cond
    (not (integer? status))
    (failure :cognitect.anomalies/fault :malformed-http-response
             "Decisions response is missing an integer HTTP status")
    (not (<= 200 status 299))
    (assoc (failure (status-category status) :provider-http-error
                    "Decisions API returned an unsuccessful HTTP status") :status status)
    :else
    (let [parsed (try {:body (if (string? body) (json/read-str body :key-fn keyword) body)}
                      (catch Exception _
                        (failure :cognitect.anomalies/fault :invalid-json
                                 "Decisions response is not valid JSON")))
          decoded (:body parsed)]
      (cond
        (provider/anomaly? parsed) parsed
        (:error decoded)
        (failure :cognitect.anomalies/fault :provider-error "Decisions API returned an error")
        (not (and (map? decoded) (map? (:answers decoded)) (seq (:answers decoded))))
        (failure :cognitect.anomalies/fault :malformed-response
                 "Decisions response is missing an answers object")
        ;; The shared adapter contract is JSON text, not a chat message. Only
        ;; answers cross this boundary; usage and provider metadata stay here.
        :else {:json (json/write-str (:answers decoded))}))))

(defmethod provider/structured-output! :openrouter-decisions
  [_provider {:keys [post! url model input messages api-key headers]}]
  (if-not (and (ifn? post!) (text? model) (or (nil? url) (text? url))
               (nil? messages) (valid-input? input))
    (failure :cognitect.anomalies/incorrect :invalid-options
             "Decisions calls require :post!, :model and :input with state and typed questions")
    (try
      (result
       (post! (or url default-url)
              {:headers (merge {"Content-Type" "application/json"}
                               (when api-key {"Authorization" (str "Bearer " api-key)})
                               headers)
               :body (json/write-str {:model model :state (:state input) :questions (:questions input)})
               :throw-exceptions false}))
      (catch java.net.SocketTimeoutException _
        (failure :cognitect.anomalies/interrupted :timeout "Decisions request timed out"))
      (catch java.net.http.HttpTimeoutException _
        (failure :cognitect.anomalies/interrupted :timeout "Decisions request timed out"))
      (catch java.io.IOException _
        (failure :cognitect.anomalies/unavailable :connection-failure "Decisions transport failed")))))
