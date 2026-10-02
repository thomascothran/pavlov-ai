# Pavlov AI

Pavlov AI calls models for schema-validated data and provides an IO handler for returning that data as Pavlov events. Agent orchestration is a separate, optional layer.

## Structured model calls

`ai/call!` performs one non-streaming call and returns decoded data conforming to the supplied Malli schema, or a Cognitect anomaly. JSON Schema guides the provider; local validation against the original Malli schema enforces the result, including predicates that JSON Schema cannot express.

```clojure
(require '[tech.thomascothran.pavlov.ai :as ai]
         '[hato.client :as http])

(def provider-config
  {:provider :openai-compatible
   :provider-options {:post! http/post
                      :url "https://your-provider.example/chat/completions"
                      :model "your-structured-output-model"
                      :api-key api-key}})

(ai/call! (assoc provider-config
                 :input "Classify this review: Excellent."
                 :schema [:map {:closed true}
                          [:sentiment [:enum :positive :neutral :negative]]
                          [:confidence [:double {:min 0 :max 1}]]]))
;; => {:sentiment :positive :confidence 0.9}, or an anomaly
```

The OpenAI-compatible adapter uses `response_format` with `json_schema`; it does **not** use tools, function calls, tool-call IDs, or tool-result rounds. The provider/model must support this output format and the supplied JSON Schema. Provider restrictions can reject otherwise valid Malli schemas; those API failures return anomalies. No provider strict mode is assumed. Calls do not retry automatically.

Malli vector schemas are supported. Output JSON object keys are decoded to keywords. The top-level `:cognitect.anomalies/category` key is reserved for anomalies. Connection failures, timeouts, HTTP errors, refusals, incomplete output, malformed JSON, and validation failures produce anomalies. Validation anomalies include `:explanation`. Provider/transport error text and credentials are not copied into anomalies; unexpected programming defects propagate.

## Pavlov IO integration

`ai/make-handler` captures provider configuration and accepts the IO callback contract:

```clojure
(def handler (ai/make-handler provider-config))

(handler {:event {:type :review/classify
                  :call-id [:review 42]
                  :input "Classify this review: Excellent."
                  :schema [:map [:sentiment [:enum :positive :neutral :negative]]]
                  :success-event-type :review/classified
                  :failure-event-type :review/classification-failed}
          :on-complete! (fn [{:keys [event]}] (println event))})
;; Success: {:type :review/classified :call-id [:review 42] :data {...}}
;; Failure: {:type :review/classification-failed :call-id [:review 42] :anomaly {...}}
```

Register the handler under the request event type with `io/make-subscriber!` in a Pavlov version providing that function. The currently pinned Pavlov 4.0.294 release JAR does not include the IO namespace; this handler can also be used with a caller-supplied dispatcher/callback. `call!` and the handler are synchronous; dispatch them outside the bprogram's synchronous step. The current OpenAI-compatible transport implementation is JVM-only. Handler configuration supplies credentials; request events cannot override it.

## Existing agent API

The main design rule is that an agent bthread should stay pure: it requests LLM/tool work as Pavlov events, and separate runtime bthreads or subscribers perform side effects and answer with configured response event types.

The agent API and its tool-call normalization are legacy, separate code paths; they are not required by structured model calls.

## Development

Pavlov AI is maintained independently from the [Pavlov](https://github.com/thomascothran/pavlov) repository and uses its released library artifact.

Enter the reproducible development environment with:

```shell
devenv shell
```

If you use direnv, allow the included `.envrc` instead. Inside the environment, run the test suite with:

```shell
clj-test
```

Use `test-watch` to rerun tests as files change and `clj-build` to build the JAR.

## Addressed event types

Agents should usually use event types that include the agent id instead of relying on shared event types plus ad-hoc filtering.

For an agent with id `:assistant`, default event types are shaped like:

```clojure
[:pavlov.ai.agent/invoke :assistant]
[:pavlov.ai.agent/responded :assistant]
[:pavlov.ai.agent/failed :assistant]
[:pavlov.ai.llm/call :assistant]
[:pavlov.ai.llm/response-received :assistant]
[:pavlov.ai.llm/response-failed :assistant]
[:pavlov.ai.tool/registered :assistant]
[:pavlov.ai.skill/registered :assistant]
```

This keeps Pavlov subscriptions precise: the bthread waits on exactly the event types that can wake it.

## LLM call requests

The agent emits an LLM call request that declares response event **types**, not full response event maps:

```clojure
{:type [:pavlov.ai.llm/call :assistant]
 :agent-id :assistant
 :conversation-id :conversation/main
 :call-id [:assistant :conversation/main 1]
 :system "You are helpful."
 :messages [{:role :user :content "Hello"}]
 :tools []
 :success-event-type [:pavlov.ai.llm/response-received :assistant]
 :failure-event-type [:pavlov.ai.llm/response-failed :assistant]}
```

A runtime bridge performs the side effect and then requests an event of the declared success or failure type, including the relevant correlation fields such as `:call-id` and `:conversation-id`.
