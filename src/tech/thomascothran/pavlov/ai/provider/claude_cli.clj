(ns tech.thomascothran.pavlov.ai.provider.claude-cli
  "Schema-constrained model calls through the Claude Code CLI (`claude -p`).

  Uses the logged-in Claude subscription: no API key and never `--bare`. Each
  call is independent (`--no-session-persistence`); history is caller-owned.

  provider/call! requires :run!, :model and :schema. Supply either
  string :input or a nonempty vector of text :messages, not both.

  Options:
  - :run!        process transport, fn of {:command [...] :stdin string
                 :dir string? :timeout-ms int?} returning {:exit :out :err}.
                 Throw java.util.concurrent.TimeoutException on timeout and
                 java.io.IOException when the process cannot start.
                 `run-process` is a ready-made implementation.
  - :model       model name or alias, e.g. \"sonnet\" or \"haiku\".
  - :effort      optional effort level (`--effort`), string or keyword, e.g.
                 \"high\". Passed through unvalidated; the CLI decides what it
                 accepts. Omitted: the CLI's default.
  - :tools       optional vector of built-in tool names. Default: no tools.
                 Configured tools are also pre-approved, because `-p` mode has
                 no interactive approval.
  - :dir         optional working directory. Default nil: the CLI inherits the
                 JVM's working directory. It is never created. The directory is
                 part of the cached prompt prefix, and enabled tools act on it.
  - :executable  optional path to the CLI. Default \"claude\".
  - :timeout-ms  optional, passed to :run!.

  Leading system/developer messages become the system prompt. All other
  messages are sent, in order, as one user message with one XML-escaped
  `<turn role=\"...\">` text block per message, so successive calls with
  growing history reuse the cached prefix.

  Returns {:data structured-output} or a Cognitect anomaly. When the CLI fails
  (error result, non-zero exit, or it cannot start), the anomaly message is the
  CLI's own error text (result text or stderr), truncated; set
  :omit-provider-error-message? true for a generic message instead. Reply text
  is never copied. Note that the CLI ignores an unknown :effort with a stderr
  warning and uses its default, so that call succeeds."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [tech.thomascothran.pavlov.ai.provider :as provider]
            [tech.thomascothran.pavlov.ai.provider.process :as process]
            [tech.thomascothran.pavlov.ai.schema :as schema]
            [tech.thomascothran.pavlov.ai.schema.malli])
  (:import (java.io IOException)
           (java.util.concurrent TimeoutException)))

;; ---------------------------------------------------------------------------
;; Default process runner

(def run-process
  "Default :run! for the :claude-cli provider. Alias of
  `tech.thomascothran.pavlov.ai.provider.process/run-process`."
  process/run-process)

;; ---------------------------------------------------------------------------
;; Request

(def ^:private roles #{"system" "developer" "user" "assistant"})
(def ^:private instruction-roles #{"system" "developer"})

(def ^:private transcript-instructions
  (str "The user message is a conversation transcript. Each message is a "
       "<turn role=\"...\"> block whose content is XML-escaped "
       "(&lt; &gt; &amp;). Respond as the assistant to the conversation so far."))

(defn- text? [x] (and (string? x) (not (str/blank? x))))

(defn- named [x] (if (keyword? x) (name x) x))

(defn- failure
  [category kind message details]
  {:outcome :failure
   :cognitect.anomalies/category category
   :cognitect.anomalies/message message
   :error (merge {:kind kind :message message} details)})

(defn- request-messages
  [{:keys [input messages] :as options}]
  (cond
    (and (contains? options :input) (contains? options :messages)) nil
    (contains? options :input)
    (when (string? input) [{:role "user" :content input}])
    (and (vector? messages) (seq messages) (every? map? messages))
    (let [normalized (mapv #(update % :role named) messages)]
      (when (every? #(and (roles (:role %))
                          (string? (:content %))
                          (not (contains? % :tool_calls)))
                    normalized)
        normalized))
    :else nil))

(defn- escape-content [s]
  (str/escape s {\& "&amp;" \< "&lt;" \> "&gt;"}))

(defn- turn-block [{:keys [role content]}]
  {:type "text"
   :text (str "<turn role=\"" role "\">" (escape-content content) "</turn>")})

(defn- prompt-parts
  "Split MESSAGES into a system prompt and turn blocks, or nil when no turn
  remains after the leading system/developer messages."
  [messages]
  (let [[leading turns] (split-with #(instruction-roles (:role %)) messages)]
    (when (seq turns)
      {:system-prompt (str/join "\n\n" (concat (map :content leading)
                                               [transcript-instructions]))
       :stdin (str (json/write-str {:type "user"
                                    :message {:role "user"
                                              :content (mapv turn-block turns)}})
                   "\n")})))

(defn- valid-options?
  [{:keys [run! model effort tools dir executable timeout-ms]}]
  (and (ifn? run!)
       (text? model)
       (or (nil? effort) (text? (named effort)))
       (or (nil? tools) (and (vector? tools) (every? text? tools)))
       (or (nil? dir) (text? dir))
       (or (nil? executable) (text? executable))
       (or (nil? timeout-ms) (pos-int? timeout-ms))))

(defn- command
  [{:keys [executable model effort tools]} system-prompt json-schema]
  (let [tool-list (str/join "," tools)]
    (cond-> [(or executable "claude")
             "-p" "--verbose"
             "--input-format" "stream-json"
             "--output-format" "stream-json"
             "--no-session-persistence"
             "--tools" tool-list
             "--strict-mcp-config"
             "--setting-sources" ""
             "--disable-slash-commands"
             "--model" model
             "--system-prompt" system-prompt
             "--json-schema" (json/write-str json-schema)]
      (some? effort) (into ["--effort" (provider/effort-name effort)])
      (seq tools) (into ["--allowedTools" tool-list]))))

;; ---------------------------------------------------------------------------
;; Response

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

(defn- parse-events
  "Parse stream-json OUT into events, or nil when any line is not JSON."
  [out]
  (when (string? out)
    (try
      (into []
            (comp (remove str/blank?)
                  (map #(json/read-str % :key-fn keyword)))
            (str/split-lines out))
      (catch Exception _ nil))))

(defn- result-error-text
  "The error text of a failed result event: its :result text, else its
  :errors strings."
  [{:keys [result errors]}]
  (cond
    (and (string? result) (not (str/blank? result))) result
    (sequential? errors) (some->> (filter string? errors) seq (str/join "; "))
    :else nil))

(defn- provider-error
  [options {:keys [api_error_status subtype] :as result}]
  (let [text (result-error-text result)]
    (if (integer? api_error_status)
      (failure (http-status-category api_error_status) :provider-error
               (provider/error-message
                options text
                (str "Claude CLI reported provider status " api_error_status))
               {:status api_error_status})
      (failure :cognitect.anomalies/fault :provider-error
               (provider/error-message options text "Claude CLI reported an error")
               (cond-> {} (string? subtype) (assoc :subtype subtype))))))

(defn- malformed
  [message]
  (failure :cognitect.anomalies/fault :malformed-response message {}))

(defn- interpret
  [options {:keys [exit out err]}]
  (let [events (parse-events out)
        result (last (filter #(and (map? %) (= "result" (:type %))) events))]
    (cond
      (and result (or (true? (:is_error result))
                      (not= "success" (:subtype result))))
      (provider-error options result)

      (not= 0 exit)
      (failure :cognitect.anomalies/fault :process-failed
               (provider/error-message options err "Claude CLI exited unsuccessfully")
               (cond-> {} (integer? exit) (assoc :exit exit)))

      (nil? events) (malformed "Claude CLI output was not valid stream-json")
      (nil? result) (malformed "Claude CLI produced no result event")
      (not (contains? result :structured_output))
      (malformed "Claude CLI result has no structured output")

      :else {:data (:structured_output result)})))

;; ---------------------------------------------------------------------------
;; Provider

(defmethod provider/call! :claude-cli
  [_provider {:keys [run! dir timeout-ms] response-schema :schema :as options}]
  (let [converted (try
                    {:schema (schema/->json-schema response-schema)}
                    (catch Exception _
                      (failure :cognitect.anomalies/incorrect :invalid-schema
                               "Response schema cannot be converted to JSON Schema" {})))
        parts (some-> (request-messages options) prompt-parts)]
    (cond
      (provider/anomaly? converted) converted

      (not (and (valid-options? options) parts))
      (failure :cognitect.anomalies/incorrect :invalid-options
               (str "Claude CLI calls require :run!, :model "
                    "and either string :input or text :messages with at least "
                    "one turn after the leading system/developer messages")
               {})

      :else
      (try
        (interpret
         options
         (run! (cond-> {:command (command options (:system-prompt parts) (:schema converted))
                        :stdin (:stdin parts)}
                 dir (assoc :dir dir)
                 timeout-ms (assoc :timeout-ms timeout-ms))))
        (catch TimeoutException _
          (failure :cognitect.anomalies/interrupted :timeout
                   "Claude CLI call timed out" {}))
        (catch IOException e
          (failure :cognitect.anomalies/unavailable :process-unavailable
                   (provider/error-message options (ex-message e)
                                           "Claude CLI could not be run")
                   {}))))))
