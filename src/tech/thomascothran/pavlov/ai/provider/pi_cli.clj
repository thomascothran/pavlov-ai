(ns tech.thomascothran.pavlov.ai.provider.pi-cli
  "Schema-constrained model calls through the pi coding agent CLI (`pi -p`).

  pi reaches any model provider it is logged in to; authentication is pi's
  concern, and Pavlov AI passes no credentials. Each call is independent
  (`--no-session`); history is caller-owned.

  provider/call! requires :run!, :model-provider, :model, :effort and :schema.
  Supply either string :input or a nonempty vector of text :messages, not both.

  Options:
  - :run!            process transport, fn of {:command [...] :stdin string
                     :dir string? :timeout-ms int?} returning {:exit :out :err}.
                     Throw java.util.concurrent.TimeoutException on timeout and
                     java.io.IOException when the process cannot start.
                     `tech.thomascothran.pavlov.ai.provider.process/run-process`
                     is a ready-made implementation.
  - :model-provider  pi provider name (`--provider`), e.g. \"openai-codex\".
  - :model           model id (`--model`), e.g. \"gpt-6-luna\".
  - :effort          thinking level (`--thinking`): one of \"off\" \"minimal\"
                     \"low\" \"medium\" \"high\" \"xhigh\" \"max\" (string or keyword).
  - :tools           optional vector of tool names. Default: no tools.
  - :dir             optional working directory. Default nil: the CLI inherits
                     the JVM's working directory. It is never created. pi puts it
                     in the system prompt, and enabled tools act on it.
  - :executable      optional path to the CLI. Default \"pi\".
  - :timeout-ms      optional, passed to :run!.
  - :cache-key       optional string. Calls with the same key share a provider
                     prompt cache key. Default: derived from :model-provider,
                     :model and the system prompt.

  Leading system/developer messages become the system prompt, followed by
  transcript instructions and the response JSON Schema. All other messages are
  sent on stdin, in order, as XML-escaped `<turn role=\"...\">` blocks, so
  successive calls with growing history share a prompt prefix.

  pi has no structured-output flag, so the schema is enforced by the prompt and
  by Malli validation in `ai/call!`. The reply must be exactly one JSON value,
  optionally inside a single Markdown code fence.

  Returns {:data decoded-json} or a Cognitect anomaly. CLI and provider error
  text is never copied into anomalies."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [tech.thomascothran.pavlov.ai.provider :as provider]
            [tech.thomascothran.pavlov.ai.schema :as schema]
            [tech.thomascothran.pavlov.ai.schema.malli])
  (:import (java.io IOException PushbackReader StringReader)
           (java.nio.charset StandardCharsets)
           (java.util UUID)
           (java.util.concurrent TimeoutException)))

;; ---------------------------------------------------------------------------
;; Request

(def ^:private efforts #{"off" "minimal" "low" "medium" "high" "xhigh" "max"})
(def ^:private roles #{"system" "developer" "user" "assistant"})
(def ^:private instruction-roles #{"system" "developer"})

(def ^:private transcript-instructions
  (str "The user message is a conversation transcript. Each message is a "
       "<turn role=\"...\"> block whose content is XML-escaped "
       "(&lt; &gt; &amp;). Respond as the assistant to the conversation so far."))

(defn- schema-instructions
  [json-schema]
  (str "Your entire reply must be exactly one JSON value that conforms to the "
       "JSON Schema below. Do not add any other text and do not use Markdown.\n\n"
       "JSON Schema:\n" (json/write-str json-schema)))

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
  (str "<turn role=\"" role "\">" (escape-content content) "</turn>"))

(defn- prompt-parts
  "Split MESSAGES into a system prompt and the stdin transcript, or nil when no
  turn remains after the leading system/developer messages."
  [messages json-schema]
  (let [[leading turns] (split-with #(instruction-roles (:role %)) messages)]
    (when (seq turns)
      {:system-prompt (str/join "\n\n" (concat (map :content leading)
                                               [transcript-instructions
                                                (schema-instructions json-schema)]))
       :stdin (str/join "\n" (map turn-block turns))})))

(defn- tool-name? [x]
  (and (text? x) (not (re-find #"^[+-]|[,\s]" x))))

(defn- valid-options?
  [{:keys [run! model-provider model effort tools dir executable timeout-ms]
    :as options}]
  (and (ifn? run!)
       (text? model-provider)
       (text? model)
       (contains? efforts (named effort))
       (or (nil? tools) (and (vector? tools) (every? tool-name? tools)))
       (or (nil? dir) (text? dir))
       (or (nil? executable) (text? executable))
       (or (nil? timeout-ms) (pos-int? timeout-ms))
       (or (not (contains? options :cache-key)) (text? (:cache-key options)))))

(defn- uuid-of [& parts]
  (str (UUID/nameUUIDFromBytes
        (.getBytes ^String (str/join "\u0000" parts) StandardCharsets/UTF_8))))

(defn- session-id
  "Deterministic, UUID-shaped session id: pi's provider prompt-cache key."
  [{:keys [model-provider model cache-key]} system-prompt]
  (if cache-key
    (uuid-of "pavlov-ai/pi-cli/cache-key" cache-key)
    (uuid-of "pavlov-ai/pi-cli" model-provider model system-prompt)))

(defn- command
  [{:keys [executable model-provider model effort tools] :as options} system-prompt]
  (-> [(or executable "pi")
       "-p" "--mode" "json"
       "--no-session"
       "--no-extensions" "--no-skills" "--no-prompt-templates" "--no-themes"
       "--no-context-files" "--no-mcp" "--no-approve" "--offline"]
      (into (if (seq tools) ["--tools" (str/join "," tools)] ["--no-tools"]))
      (into ["--provider" model-provider
             "--model" model
             "--thinking" (named effort)
             "--session-id" (session-id options system-prompt)
             "--system-prompt" system-prompt])))

;; ---------------------------------------------------------------------------
;; Response

(defn- parse-events
  "Parse JSONL OUT into events, or nil when any line is not JSON."
  [out]
  (when (string? out)
    (try
      (into []
            (comp (remove str/blank?)
                  (map #(json/read-str % :key-fn keyword)))
            (str/split-lines out))
      (catch Exception _ nil))))

(defn- final-assistant-message
  [events]
  (->> events
       (filter #(and (map? %)
                     (= "message_end" (:type %))
                     (= "assistant" (get-in % [:message :role]))))
       last
       :message))

(defn- reply-text
  [{:keys [content]}]
  (let [texts (when (sequential? content)
                (keep #(when (and (map? %) (= "text" (:type %)) (string? (:text %)))
                         (:text %))
                      content))]
    (when (seq texts) (str/join texts))))

(defn- unfence
  "The body of a reply wrapped in a single Markdown code fence, else the
  trimmed reply."
  [s]
  (let [trimmed (str/trim s)]
    (if-let [[_ body] (re-matches #"(?s)```(?:json)?[ \t]*\n(.*?)\n?```" trimmed)]
      (when-not (str/includes? body "```") body)
      trimmed)))

(defn- read-json
  "{:value v} when S is exactly one JSON value, else nil."
  [s]
  (when s
    (try
      ;; data.json needs a pushback buffer of 64, as in json/read-str.
      (let [reader (PushbackReader. (StringReader. s) 64)
            value (json/read reader :key-fn keyword)]
        (when (str/blank? (slurp reader)) {:value value}))
      (catch Exception _ nil))))

(defn- malformed
  [message]
  (failure :cognitect.anomalies/fault :malformed-response message {}))

(defn- stop-failure
  [stop-reason]
  (case stop-reason
    "error" (failure :cognitect.anomalies/fault :provider-error
                     "pi reported a provider error" {})
    "aborted" (failure :cognitect.anomalies/interrupted :aborted
                       "pi aborted the response" {})
    "length" (failure :cognitect.anomalies/fault :truncated
                      "pi response hit the output limit" {})
    (malformed "pi response ended for an unexpected reason")))

(defn- interpret
  [{:keys [exit out]}]
  (let [events (parse-events out)
        message (final-assistant-message events)
        stop-reason (:stopReason message)]
    (cond
      (and message (not= "stop" stop-reason)) (stop-failure stop-reason)

      (not= 0 exit)
      (failure :cognitect.anomalies/fault :process-failed
               "pi exited unsuccessfully"
               (cond-> {} (integer? exit) (assoc :exit exit)))

      (nil? events) (malformed "pi output was not valid JSONL")
      (nil? message) (malformed "pi produced no assistant message")

      :else
      (if-let [text (reply-text message)]
        (if-let [{:keys [value]} (read-json (unfence text))]
          {:data value}
          (malformed "pi reply was not exactly one JSON value"))
        (malformed "pi reply has no text")))))

;; ---------------------------------------------------------------------------
;; Provider

(defmethod provider/call! :pi-cli
  [_provider {:keys [run! dir timeout-ms] response-schema :schema :as options}]
  (let [converted (try
                    {:schema (schema/->json-schema response-schema)}
                    (catch Exception _
                      (failure :cognitect.anomalies/incorrect :invalid-schema
                               "Response schema cannot be converted to JSON Schema" {})))
        parts (when-not (provider/anomaly? converted)
                (some-> (request-messages options)
                        (prompt-parts (:schema converted))))]
    (cond
      (provider/anomaly? converted) converted

      (not (and (valid-options? options) parts))
      (failure :cognitect.anomalies/incorrect :invalid-options
               (str "pi CLI calls require :run!, :model-provider, :model, a "
                    "supported :effort and either string :input or text "
                    ":messages with at least one turn after the leading "
                    "system/developer messages")
               {})

      :else
      (try
        (interpret
         (run! (cond-> {:command (command options (:system-prompt parts))
                        :stdin (:stdin parts)}
                 dir (assoc :dir dir)
                 timeout-ms (assoc :timeout-ms timeout-ms))))
        (catch TimeoutException _
          (failure :cognitect.anomalies/interrupted :timeout
                   "pi CLI call timed out" {}))
        (catch IOException _
          (failure :cognitect.anomalies/unavailable :process-unavailable
                   "pi CLI could not be run" {}))))))
