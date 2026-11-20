(ns tech.thomascothran.pavlov.ai.provider.openai-compatible
  "OpenAI-compatible chat and schema-constrained model calls.

  provider/call! requires :url, :model and :schema. :post! (Hato-shaped
  transport, default hato.client/post), :api-key and :headers are optional.
  Supply either string :input or a nonempty vector of text :messages, not both.

  Optional request settings:
  - :effort      sent as top-level `reasoning_effort` (OpenAI; OpenRouter also
                 accepts it). Keywords are sent by name; values are not
                 validated, so use whatever the model accepts.
  - :timeout-ms  per-request timeout, passed to :post! as Hato's :timeout.
  - :extra-body  map merged into the JSON request body, e.g.
                 {:service_tier \"flex\"} or {:reasoning {:effort \"max\"}}. It
                 overrides the body field produced by :effort, but the adapter's
                 own fields (model, messages, response_format, stream) win.
  Options are never forwarded to the body except through these keys.
  Unrecognized options are ignored.

  Errors: when the provider rejects a request, the anomaly carries its error
  message (:cognitect.anomalies/message and [:error :message]) and, when
  present, [:error :provider-code], :provider-type and :provider-param.
  Provider messages may echo request input; set
  :omit-provider-error-message? true to replace them with a generic message
  while keeping code, type and param. Default false.

  The adapter converts :schema to JSON Schema for response_format and parses the model's JSON content.
  The provider/model must support that output format; no tool calls are used."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [hato.client :as http]
            [tech.thomascothran.pavlov.ai.provider :as provider]
            [tech.thomascothran.pavlov.ai.schema :as schema]
            [tech.thomascothran.pavlov.ai.schema.malli]))

(defn- authorization-headers
  [api-key]
  (when api-key
    {"Authorization" (str "Bearer " api-key)}))

(defn- request-options
  [{:keys [api-key headers body timeout-ms]}]
  (cond-> {:headers (merge {"Content-Type" "application/json"}
                           (authorization-headers api-key)
                           headers)
           :body (json/write-str (assoc body :stream false))
           :throw-exceptions false}
    timeout-ms (assoc :timeout timeout-ms)))

(defn- structured-body
  [{:keys [model effort extra-body]} messages json-schema]
  (merge (when (some? effort)
           {:reasoning_effort (provider/effort-name effort)})
         (provider/request-body
          extra-body
          {:model model
           :messages messages
           :response_format {:type "json_schema"
                             :json_schema {:name "pavlov_result"
                                           :schema json-schema}}})))

(defn- decode-response-body
  [body]
  (if (and (string? body) (seq body))
    (json/read-str body :key-fn keyword)
    body))

(defmethod provider/chat-completion! :openai-compatible
  [_provider {:keys [post! url body] :or {post! http/post} :as options}]
  {:pre [(fn? post!)
         (string? url)
         (map? body)]}
  (-> (post! url (request-options options))
      (update :body decode-response-body)))

(defn- successful-status?
  [status]
  (and (integer? status)
       (<= 200 status 299)))

(defn- failure
  [category kind message details]
  {:outcome :failure
   :cognitect.anomalies/category category
   :cognitect.anomalies/message message
   :error (merge {:kind kind
                  :message message}
                 details)})

(defn- malformed
  ([message]
   (malformed message nil))
  ([message details]
   (failure :cognitect.anomalies/fault
            :malformed-response
            message
            details)))

(defn- http-status-category
  [status]
  (cond
    (#{401 403} status) :cognitect.anomalies/forbidden
    (= 404 status) :cognitect.anomalies/not-found
    (= 408 status) :cognitect.anomalies/interrupted
    (= 409 status) :cognitect.anomalies/conflict
    (= 429 status) :cognitect.anomalies/busy
    (<= 500 status 599) :cognitect.anomalies/unavailable
    (<= 400 status 499) :cognitect.anomalies/incorrect
    :else :cognitect.anomalies/fault))

(defn- provider-error
  "A failure carrying the provider's own error message, code, type and param
  from an OpenAI-style {\"error\": {...}} BODY, so that callers can see why a
  request was rejected. Falls back to a generic message when BODY has none."
  [category kind status body]
  (let [error (when (map? body) (:error body))
        message (provider/error-message
                 {}
                 (cond
                   (map? error) (:message error)
                   (string? error) error
                   :else nil)
                 (str "Provider returned HTTP status " status))
        error (when (map? error) error)]
    (failure category
             kind
             message
             (cond-> {:status status}
               (:code error) (assoc :provider-code (:code error))
               (:type error) (assoc :provider-type (:type error))
               (:param error) (assoc :provider-param (:param error))))))

(defn- decode-error-body
  "BODY decoded as JSON, or nil when it is not JSON."
  [body]
  (try (decode-response-body body) (catch Exception _ nil)))

(defn- decode-action-arguments
  [provider-call-id arguments]
  (let [decoded
        (cond
          (map? arguments)
          arguments

          (string? arguments)
          (try
            (json/read-str arguments :key-fn keyword)
            (catch Exception _error
              (malformed
               "OpenAI-compatible action arguments are not valid JSON"
               {:provider-call-id provider-call-id})))

          :else
          (malformed
           "OpenAI-compatible action arguments must be a JSON object"
           {:provider-call-id provider-call-id}))]
    (cond
      (provider/anomaly? decoded)
      decoded

      (map? decoded)
      decoded

      :else
      (malformed
       "OpenAI-compatible action arguments must be a JSON object"
       {:provider-call-id provider-call-id}))))

(defn- normalize-action-call
  [{:keys [id type function]}]
  (cond
    (not (and (string? id) (seq id)))
    (malformed "OpenAI-compatible action call is missing an id")

    (not= "function" type)
    (malformed "OpenAI-compatible action call is not a function"
               {:provider-call-id id})

    (not (and (string? (:name function))
              (seq (:name function))))
    (malformed "OpenAI-compatible action call is missing a function name"
               {:provider-call-id id})

    :else
    (-> (decode-action-arguments id (:arguments function))
        ((provider/bail-on-anomaly
          (fn [arguments]
            {:provider-call-id id
             :name (:name function)
             :arguments arguments}))))))

(defn- normalize-action-calls
  [tool-calls]
  (cond
    (nil? tool-calls)
    []

    (sequential? tool-calls)
    (reduce (fn [action-calls tool-call]
              (let [action-call (normalize-action-call tool-call)]
                (if (provider/anomaly? action-call)
                  (reduced action-call)
                  (conj action-calls action-call))))
            []
            tool-calls)

    :else
    (malformed "OpenAI-compatible message has invalid tool_calls")))

(defn- normalize-finish-reason
  [finish-reason]
  (cond
    (nil? finish-reason)
    nil

    (or (string? finish-reason) (keyword? finish-reason))
    (let [normalized (-> finish-reason
                         name
                         (str/replace "_" "-")
                         keyword)]
      (if (= :tool-calls normalized)
        :action-calls
        normalized))

    :else
    (malformed "OpenAI-compatible response has an invalid finish reason")))

(defn- normalize-usage
  [usage]
  (cond
    (nil? usage)
    nil

    (map? usage)
    (cond-> {}
      (contains? usage :prompt_tokens)
      (assoc :input-tokens (:prompt_tokens usage))

      (contains? usage :completion_tokens)
      (assoc :output-tokens (:completion_tokens usage))

      (contains? usage :total_tokens)
      (assoc :total-tokens (:total_tokens usage)))

    :else
    (malformed "OpenAI-compatible response has invalid usage data")))

(defn- provider-metadata
  [body]
  (cond-> {}
    (contains? body :id)
    (assoc :response-id (:id body))

    (contains? body :model)
    (assoc :model (:model body))))

(defn- completion-state
  [body]
  (if-not (map? body)
    (malformed "OpenAI-compatible response body is not an object")
    (let [choice (first (:choices body))
          provider-message (:message choice)]
      (if-not (map? provider-message)
        (malformed
         "OpenAI-compatible response is missing choices[0].message")
        {:body body
         :choice choice
         :provider-message provider-message}))))

(defn- add-message
  [{:keys [provider-message] :as state}]
  (let [content (:content provider-message)]
    (cond
      (not= "assistant" (:role provider-message))
      (malformed
       "OpenAI-compatible response message is not from the assistant")

      (not (or (nil? content) (string? content)))
      (malformed "OpenAI-compatible response content is not text")

      :else
      (assoc state :message {:role "assistant"
                             :content content}))))

(defn- add-action-calls
  [{:keys [provider-message] :as state}]
  (let [action-calls (normalize-action-calls (:tool_calls provider-message))]
    (if (provider/anomaly? action-calls)
      action-calls
      (assoc state :action-calls action-calls))))

(defn- require-completion-content
  [{:keys [message action-calls] :as state}]
  (if (or (seq action-calls)
          (seq (:content message)))
    state
    (malformed
     "OpenAI-compatible response contains neither content nor actions")))

(defn- add-finish-reason
  [{:keys [choice] :as state}]
  (let [finish-reason (normalize-finish-reason (:finish_reason choice))]
    (if (provider/anomaly? finish-reason)
      finish-reason
      (assoc state :finish-reason finish-reason))))

(defn- add-usage
  [{:keys [body] :as state}]
  (let [usage (normalize-usage (:usage body))]
    (if (provider/anomaly? usage)
      usage
      (assoc state :usage usage))))

(defn- completion-success
  [{:keys [body message action-calls finish-reason usage]}]
  {:outcome :success
   :message message
   :action-calls action-calls
   :finish-reason finish-reason
   :usage usage
   :provider-metadata (provider-metadata body)})

(defn- normalize-success
  [body]
  (-> body
      completion-state
      ((provider/bail-on-anomaly add-message))
      ((provider/bail-on-anomaly add-action-calls))
      ((provider/bail-on-anomaly require-completion-content))
      ((provider/bail-on-anomaly add-finish-reason))
      ((provider/bail-on-anomaly add-usage))
      ((provider/bail-on-anomaly completion-success))))

(defmethod provider/normalize-chat-completion :openai-compatible
  [_provider {:keys [status body] :as response}]
  {:pre [(map? response)]}
  (cond
    (not (integer? status))
    (failure :cognitect.anomalies/fault
             :malformed-http-response
             "OpenAI-compatible HTTP response is missing an integer status"
             {})

    (not (successful-status? status))
    (provider-error (http-status-category status)
                    :provider-http-error
                    status
                    body)

    (and (map? body) (:error body))
    (provider-error :cognitect.anomalies/fault
                    :provider-error
                    status
                    body)

    :else
    (normalize-success body)))

(comment
  (def openrouter-api-key
    (delay (read-line)))

  (def r
    (provider/chat-completion!
     :openai-compatible
     {:url "https://openrouter.ai/api/v1/chat/completions"
      :api-key @openrouter-api-key
      :body {:model "deepseek/deepseek-v4-flash"
             :messages [{:role "user"
                         :content "Say hello in one short sentence."}]}}))
  (provider/normalize-chat-completion :openai-compatible r))

(defn- omit-provider-message
  "Replace the provider's free-text message in FAILURE with a generic one."
  [failure status]
  (let [message (str "Provider returned an error (HTTP status " status ")")]
    (-> failure
        (assoc :cognitect.anomalies/message message)
        (assoc-in [:error :message] message))))

(defn- interpret-structured-response
  [{:keys [status body]}]
  (cond
    (not (integer? status))
    (failure :cognitect.anomalies/fault :malformed-http-response
             "Provider response is missing an integer status" {})

    (not (successful-status? status))
    (assoc (provider-error (http-status-category status) :provider-http-error
                           status (decode-error-body body))
           :status status)

    :else
    (let [decoded (try
                    (decode-response-body body)
                    (catch Exception _
                      (malformed "Provider response is not valid JSON")))
          choice (first (when (sequential? (:choices decoded)) (:choices decoded)))
          message (:message choice)]
      (cond
        (provider/anomaly? decoded) decoded
        (:error decoded)
        (provider-error :cognitect.anomalies/fault :provider-error
                        status decoded)
        (:refusal message)
        (failure :cognitect.anomalies/forbidden :refusal
                 "Model refused the request" {})
        (= "content_filter" (:finish_reason choice))
        (failure :cognitect.anomalies/forbidden :content-filter
                 "Model output was filtered" {})
        (not= "stop" (:finish_reason choice))
        (failure :cognitect.anomalies/fault :incomplete-output
                 "Model output did not complete normally" {})
        (or (not= "assistant" (:role message))
            (seq (:tool_calls message))
            (not (string? (:content message))))
        (malformed "Provider did not return structured content")
        :else
        (try
          {:data (json/read-str (:content message) :key-fn keyword)}
          (catch Exception _
            {:cognitect.anomalies/category :cognitect.anomalies/incorrect
             :cognitect.anomalies/message "Model output is not valid JSON"
             :kind :invalid-json}))))))

(defn- structured-response
  "Interpret RESPONSE, omitting the provider's error message when OPTIONS ask."
  [{:keys [omit-provider-error-message?]} response]
  (let [result (interpret-structured-response response)]
    (if (and omit-provider-error-message?
             (#{:provider-http-error :provider-error} (get-in result [:error :kind])))
      (omit-provider-message result (:status response))
      result)))

(defn- request-messages
  [{:keys [input messages] :as options}]
  (cond
    (and (contains? options :input) (contains? options :messages)) nil
    (contains? options :input)
    (when (string? input) [{:role "user" :content input}])
    (and (vector? messages) (seq messages))
    (let [normalized (mapv (fn [message]
                             (update message :role
                                     #(if (keyword? %) (name %) %)))
                           (filter map? messages))]
      (when (and (= (count normalized) (count messages))
                 (every? #(and (#{"system" "developer" "user" "assistant"} (:role %))
                               (string? (:content %))
                               (not (contains? % :tool_calls))) normalized))
        normalized))
    :else nil))

(defmethod provider/call! :openai-compatible
  [_provider {:keys [post! url model] response-schema :schema
              :or {post! http/post} :as options}]
  (let [converted (try
                    {:schema (schema/->json-schema response-schema)}
                    (catch Exception _
                      {:cognitect.anomalies/category :cognitect.anomalies/incorrect
                       :cognitect.anomalies/message "Response schema cannot be converted to JSON Schema"
                       :kind :invalid-schema}))
        messages (request-messages options)]
    (cond
      (provider/anomaly? converted) converted
      (not (and (ifn? post!) (string? url) (string? model) messages
                (provider/valid-extra-body? (:extra-body options))
                (or (nil? (:timeout-ms options)) (pos-int? (:timeout-ms options)))))
      (failure :cognitect.anomalies/incorrect :invalid-options
               (str "Structured calls require transport options, either string "
                    ":input or nonempty text :messages, a map :extra-body and a "
                    "positive :timeout-ms when given") {})
      :else
      (try
        (structured-response
         options
         (post! url
                (request-options
                 (assoc options :body
                        (structured-body options messages (:schema converted))))))
        (catch java.net.SocketTimeoutException _
          (failure :cognitect.anomalies/interrupted :timeout
                   "Model request timed out" {}))
        (catch java.net.http.HttpTimeoutException _
          (failure :cognitect.anomalies/interrupted :timeout
                   "Model request timed out" {}))
        (catch java.io.IOException _
          (failure :cognitect.anomalies/unavailable :connection-failure
                   "Model transport failed" {}))))))
