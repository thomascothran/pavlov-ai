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

Supply `:messages` instead of `:input` to include instructions and conversation history:

```clojure
(ai/call! (assoc provider-config
                 :schema [:map [:sentiment [:enum :positive :neutral :negative]]]
                 :messages [{:role :system :content "Classify reviews consistently."}
                            {:role :user :content "Earlier review"}
                            {:role :assistant :content "{\"sentiment\":\"neutral\"}"}
                            {:role :user :content "Excellent."}]))
```

Messages are a nonempty vector of text messages, in caller-supplied order. Roles may be keywords or strings: system, developer, user, or assistant. The selected provider/model must support those roles. `:input` is shorthand for one user message; supplying both keys is an anomaly, even if one is nil. Request events accepted by `make-handler` support the same alternatives. Calls and handlers retain no conversation state and add no agent loop.

Malli vector schemas are supported. Output JSON object keys are decoded to keywords. The top-level `:cognitect.anomalies/category` key is reserved for anomalies. Connection failures, timeouts, HTTP errors, refusals, incomplete output, malformed JSON, and validation failures produce anomalies. Validation anomalies include `:explanation`. Provider/transport error text and credentials are not copied into anomalies; unexpected programming defects propagate.

## Pavlov IO integration

### Jev on OpenRouter

Use `:openrouter-decisions` for [OpenRouter's native Jev Decisions API](https://openrouter.ai/blog/insights/what-is-jev/). It accepts structured `:input` containing `:state` (text, an object, or an array) and a nonempty `:questions` map. Each question has `:type` and `:instructions`; choice questions supply a criteria map of option descriptions, score questions supply an ordered vector of level descriptions, and noul questions ask for a yes/no probability. Question types accept strings or keywords.

```clojure
(def decision-config
  {:provider :openrouter-decisions
   :provider-options {:post! http/post
                      :model "typesafe/jev-1.13"
                      :api-key api-key}})

(def decision-request
  {:type :decision/requested
   :call-id [:ticket 42]
   :input {:state {:ticket "I was charged twice."}
           :questions {:refund {:type :noul
                                :instructions "Is the customer asking for money back?"}}}
   :schema [:map
            [:refund [:map
                      [:type [:= "noul"]]
                      [:noul [:double {:min 0 :max 1}]]]]]
   :success-event-type :decision/completed
   :failure-event-type :decision/failed})

(ai/call! (merge decision-config (select-keys decision-request [:input :schema])))
;; => {:refund {:type "noul" :noul 0.99}}, or an anomaly

(def decision-subscriber
  (io/make-subscriber! {:decision/requested (ai/make-handler decision-config)}))
```

Require `tech.thomascothran.pavlov.io` as `io` for the subscriber example. The default endpoint is `https://openrouter.ai/api/alpha/decisions`; `:url` and extra `:headers` may be configured. Transport is injected with the same Hato-shaped `:post!` used by the chat adapter. Conversation `:messages` are not accepted by this provider.

Jev's questions determine its output shape. The Malli schema validates the returned **answers map**, including choice labels, probabilities, and confidence where applicable; it is not sent to Jev as JSON Schema. Usage, IDs, and provider metadata are excluded from the result. A Malli enum of keywords can decode choice labels into keywords, just as for LLM results. There is no chat-completion envelope, tool protocol, automatic thresholding, or agent loop. The adapter is JVM-only and uses the shared data-or-anomaly and IO event contracts.

### Chat models

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

The pinned Pavlov 4.0.297 release includes the IO namespace. Register the handler under the request event type:

```clojure
(require '[tech.thomascothran.pavlov.io :as io])

(def model-subscriber
  (io/make-subscriber! {:review/classify (ai/make-handler provider-config)}))
```

Attach `model-subscriber` to the bprogram's subscribers. Use `bprogram.ephemeral/make-program!` for programs waiting on external responses: `execute!` adds a deadlock detector that can terminate while IO is pending. `call!` and the handler are synchronous; the JVM IO subscriber dispatches them outside the bprogram's synchronous step by default. The current OpenAI-compatible transport implementation is JVM-only. Handler configuration supplies credentials; request events cannot override it.

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
