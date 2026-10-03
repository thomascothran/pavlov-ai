# Pavlov AI

Pavlov AI calls models for schema-validated data and provides an IO handler for returning that data as Pavlov events.

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

For the OpenAI-compatible adapter, messages are a nonempty vector of text messages, in caller-supplied order. Roles may be keywords or strings: system, developer, user, or assistant. The selected provider/model must support those roles. `:input` is shorthand for one user message; supplying both keys is an anomaly, even if one is nil. These input restrictions belong to the adapter, not `call!`. Request events accepted by `make-handler` forward both keys for provider validation. Calls and handlers retain no conversation state.

Malli vector schemas are supported. Output JSON object keys are decoded to keywords. The top-level `:cognitect.anomalies/category` key is reserved for anomalies. Connection failures, timeouts, HTTP errors, refusals, incomplete output, malformed JSON, and validation failures produce anomalies. Validation anomalies include `:explanation`. Provider/transport error text and credentials are not copied into anomalies; unexpected programming defects propagate.

## Jev: decisions instead of chat

[Jev on OpenRouter](https://openrouter.ai/blog/insights/what-is-jev/) answers specific questions about supplied information. Think of it as asking “which category fits?” or “how likely is yes?” rather than asking a chat model to write a reply.

Use `:openrouter-decisions` with two pieces of input:

- `:state`: the information to assess, as text, an object, or an array.
- `:questions`: a nonempty map of named questions. Each answer comes back under the same name.

The question definitions tell Jev what to answer. The Malli `:schema` tells Pavlov AI how to decode and validate those answers locally; it is **not** sent to Jev as JSON Schema.

### Yes/no probability (`:noul`)

Despite the unfamiliar name, `:noul` is the yes/no question type. Its answer is a number between 0 and 1: higher means “yes” is more likely. It is not a boolean.

```clojure
;; Uses the ai and http aliases from the structured-call example above.
(def decision-config
  {:provider :openrouter-decisions
   :provider-options {:post! http/post
                      :model "typesafe/jev-1.13"
                      :api-key api-key}})

(def probability [:double {:min 0 :max 1}])

(def refund-input
  {:state {:ticket "I was charged twice. Please refund the extra charge."}
   :questions {:refund {:type :noul
                        :instructions "Is the customer asking for money back?"}}})

(def refund-schema
  [:map
   [:refund [:map
             [:type [:= "noul"]]
             [:noul probability]]]])

(ai/call! (assoc decision-config :input refund-input :schema refund-schema))
;; Illustrative result: {:refund {:type "noul" :noul 0.99}}
;; Expected failures return an anomaly instead.
```

Here `0.99` is the model's estimated probability of “yes,” not an instruction to issue a refund. Your application decides what to do with that estimate. For example, after checking that the result is not an anomaly, it could flag requests with `(>= (get-in result [:refund :noul]) 0.8)` for review. Pavlov AI applies no threshold automatically.

### Pick a category (`:choice`)

Supply a criteria map: keys are the allowed labels, and values explain when each label applies.

```clojure
(ai/call!
 (assoc decision-config
        :input {:state {:ticket "I was charged twice."}
                :questions
                {:team {:type :choice
                        :instructions "Which team should handle this ticket?"
                        :criteria {:billing "Charges, payments, and refunds."
                                   :technical "Software bugs and connection problems."}}}}
        :schema [:map
                 [:team [:map
                         [:type [:= "choice"]]
                         [:choice [:enum :billing :technical]]
                         [:probabilities [:map-of :keyword probability]]
                         [:confidence probability]]]]))
;; Illustrative result:
;; {:team {:type "choice"
;;         :choice :billing
;;         :probabilities {:billing 0.9 :technical 0.1}
;;         :confidence 0.8}}
```

`:choice` is the selected label; `:probabilities` gives the distribution over labels. The schema's keyword enum decodes the provider's string label `"billing"` to `:billing`.

### Assess against ordered levels (`:score`)

Supply an ordered vector of **2–10** level descriptions, from lower to higher. Unlike `:choice`, this asks for a graded assessment rather than a category label.

```clojure
(ai/call!
 (assoc decision-config
        :input {:state {:ticket "The application crashes whenever I save my work."}
                :questions
                {:severity {:type :score
                            :instructions "How severe is this problem?"
                            :criteria ["Minor inconvenience; work can continue."
                                       "Major disruption; important work is blocked."]}}}
        :schema [:map
                 [:severity [:map
                             [:type [:= "score"]]
                             [:score :double]
                             [:legend [:map-of :keyword :string]]
                             [:probabilities [:map-of :keyword probability]]
                             [:confidence probability]]]]))
;; Illustrative result:
;; {:severity {:type "score"
;;             :score 0.6
;;             :legend {:0 "Minor inconvenience; work can continue."
;;                      :1 "Major disruption; important work is blocked."}
;;             :probabilities {:0 0.4 :1 0.6}
;;             :confidence 0.2}}
```

The answer includes a numeric `:score`, a `:legend` associating level indices with descriptions, and probabilities for those levels. For this two-level example, `0.6` lies between levels 0 and 1; it is not the integer category “level 1.” JSON object keys are decoded to keywords, so level keys appear as `:0` and `:1`.

You can include all three question types in one `:questions` map to assess the same state in a single call. Question types accept keywords or strings. All results above are illustrative, not guaranteed model outputs. Usage, IDs, and provider metadata are excluded from the returned answers map.

The adapter is JVM-only. It uses OpenRouter's native Decisions endpoint, not chat completions or tool calls, and does not accept conversation `:messages`. The default endpoint is `https://openrouter.ai/api/alpha/decisions`; `:url` and extra `:headers` may be configured. Transport uses the same Hato-shaped `:post!` as the chat adapter.

## Pavlov IO integration

### Jev decisions as events

The same input and schema can be placed in an IO request event:

```clojure
(require '[tech.thomascothran.pavlov.io :as io])

(def decision-request
  {:type :decision/requested
   :call-id [:ticket 42]
   :input refund-input
   :schema refund-schema
   :success-event-type :decision/completed
   :failure-event-type :decision/failed})

(def decision-subscriber
  (io/make-subscriber! {:decision/requested (ai/make-handler decision-config)}))
;; Success event:
;; {:type :decision/completed :call-id [:ticket 42]
;;  :data {:refund {:type "noul" :noul 0.99}}}
;; Failure event:
;; {:type :decision/failed :call-id [:ticket 42] :anomaly {...}}
```

Attach `decision-subscriber` to the bprogram's subscribers and request `decision-request` from a bthread. The subscriber calls Jev and returns the configured success or failure event. See `dev/pavlov_ai/decision.clj` for a complete task-urgency example.

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

## Development nREPL

Run `devenv up` to start the `clj` process, or start it directly with
`clojure -X:dev:test dev/go!`. The server binds only to `127.0.0.1`, chooses
an available port, and writes it to `.nrepl-port` for editor/client discovery.
This lets separate worktrees run servers without port collisions.
For a fixed port, use `clojure -X:dev:test dev/go! :port 9898`.

From an existing REPL, `(dev/start-nrepl!)` starts the server and
`(dev/stop-nrepl!)` stops it. Shutdown removes its port file.
Connect with `clj-nrepl-eval -p "$(< .nrepl-port)"`.
