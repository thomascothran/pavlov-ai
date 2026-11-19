# Pavlov AI

Pavlov AI makes asking a model (an LLM, classifier or decision model) just another event. A bthread requests a question with a schema, and waits on the answer: validated data on success, an anomaly on failure.

## Why

In a Pavlov program, bthreads coordinate by requesting, waiting on and blocking events. A model call should fit that model: a bthread requests it, and the answer comes back as a success or failure event. The bthread should not need to know which provider answered or how.

Model output is also unreliable, so a bthread should never act on raw output. Pavlov AI checks every answer against a [Malli](https://github.com/metosin/malli) schema you supply. A bthread receives either data that matches the schema or a failure describing what went wrong. Timeouts, refusals, malformed replies and schema mismatches all arrive as data, not exceptions.

That gives you:

- **Typed answers.** Enums, numeric ranges, closed maps and custom predicates are enforced on every reply.
- **Failures as events.** A bthread handles a failed call like any other event.
- **Swappable providers.** Changing provider is a configuration change; bthreads and schemas stay the same.
- **Subscription or API key.** Use an API key, or a Claude or ChatGPT subscription you are already logged in to.

## Getting started

Add Pavlov AI to `deps.edn`:

```clojure
tech.thomascothran/pavlov-ai {:mvn/version "4.0.44"}
```

This example has Jev triage an email as a sales pitch, something to deal with later, or urgent. If Jev is confident it is sales, a chat model on OpenRouter drafts a polite decline and the program sends it. It calls Jev directly through TypeSafe, so it needs a TypeSafe API key in `TYPESAFE_API_KEY` and an OpenRouter API key in `OPENROUTER_API_KEY`.

```clojure
(ns triage
  (:require [acme.mail :as mail] ; made up: your own email client
            [tech.thomascothran.pavlov.ai :as ai]
            [tech.thomascothran.pavlov.bthread :as b]
            [tech.thomascothran.pavlov.bprogram.ephemeral :as bp]
            [tech.thomascothran.pavlov.bprogram.proto :as proto]
            [tech.thomascothran.pavlov.event :as event]
            [tech.thomascothran.pavlov.io :as io]))

(def email
  "From: dana@cloudsaver.example
   Subject: Cut your cloud bill by 40%
   Hi! I'm Dana from CloudSaver. We help teams like yours slash hosting costs.
   Do you have 15 minutes this week for a quick demo?")

;; Ask Jev to triage the email. If Jev is confident it is sales,
;; draft a polite decline and send it.
(def handle-email
  (b/scenario
   [{:request #{{:type :typesafe.jev/invoke!
                 :call-id :email-1
                 :input {:state {:email email}
                         :questions
                         {:triage {:type :choice
                                   :instructions "How should this email be handled?"
                                   :criteria {:sales "Unsolicited sales or marketing pitch."
                                              :later "Legitimate, but can wait."
                                              :urgent "Needs attention right away."}}}}
                 :schema [:map
                          [:triage [:map
                                    [:choice [:enum :sales :later :urgent]]
                                    [:confidence [:double {:min 0 :max 1}]]]]]
                 :success-event-type :email/triaged
                 :failure-event-type :email/triage-failed}}}

    {:wait-on #{:email/triaged :email/triage-failed}}

    (fn [{:keys [event]}]
      (if (and (= :sales (get-in event [:data :triage :choice]))
               (<= 0.8 (get-in event [:data :triage :confidence])))
        {:bid {:request #{{:type :gemini-4-neon/invoke!
                           :call-id :email-1-reply
                           :input (str "Write a short, polite reply declining this sales email.\n\n"
                                       email)
                           :schema [:map [:subject :string] [:reply :string]]
                           :success-event-type :email/decline-drafted
                           :failure-event-type :email/decline-failed}}}}
        {:bid {:request #{(assoc event :type :email/done :terminal true)}}}))

    {:wait-on #{:email/decline-drafted :email/decline-failed}}

    (fn [{:keys [event]}]
      (if (= :email/decline-drafted (event/type event))
        {:bid {:request #{{:type :email/send!
                           :to "dana@cloudsaver.example"
                           :subject (get-in event [:data :subject])
                           :body (get-in event [:data :reply])}}}}
        {:bid {:request #{(assoc event :type :email/done :terminal true)}}}))

    {:wait-on #{:email/sent}}

    (fn [{:keys [event]}]
      {:bid {:request #{(assoc event :type :email/done :terminal true)}}})]))

;; Jev, called directly through TypeSafe, answers :typesafe.jev/invoke! events.
(def jev
  (ai/make-handler
   {:provider :typesafe-decisions
    :provider-options {:model "jev-latest"
                       :api-key (System/getenv "TYPESAFE_API_KEY")}}))

;; Gemini 4 Neon, through OpenRouter, answers :gemini-4-neon/invoke! events.
(def writer
  (ai/make-handler
   {:provider :openai-compatible
    :provider-options {:url "https://openrouter.ai/api/v1/chat/completions"
                       :model "google/gemini-4-neon"
                       :api-key (System/getenv "OPENROUTER_API_KEY")}}))

;; Send the reply, then tell the program it went out.
(defn send-email [{:keys [event on-complete!]}]
  (mail/send! (select-keys event [:to :subject :body]))
  (on-complete! {:event {:type :email/sent
                         :to (:to event)
                         :subject (:subject event)}}))

(def program
  (bp/make-program!
   {:handle-email handle-email}
   {:subscribers {:models (io/make-subscriber! {:typesafe.jev/invoke! jev
                                                :gemini-4-neon/invoke! writer
                                                :email/send! send-email})}}))

@(proto/stopped program)
;; Illustrative result:
;; {:type :email/done :terminal true
;;  :to "dana@cloudsaver.example"
;;  :subject "Re: Cut your cloud bill by 40%"}
```

The bthread requests a `:typesafe.jev/invoke!` event carrying the email, the question and a schema. The IO subscriber hands that event to Jev and puts the answer back into the program as `:email/triaged`, with `:choice` decoded to `:sales`, `:later` or `:urgent` and a `:confidence` between 0 and 1. If the choice is `:sales` and the confidence is at least 0.8, the bthread then requests `:gemini-4-neon/invoke!`, which the subscriber hands to Gemini; the reply comes back as `:email/decline-drafted`, checked against `[:map [:subject :string] [:reply :string]]`. The bthread requests `:email/send!` with that subject and reply, the subscriber sends it with `mail/send!`, and `:email/sent` lets the bthread finish. Any other answer, including a less confident `:sales`, ends the program without replying. If either model call goes wrong, the bthread gets the matching failure event with an `:anomaly` instead.

The bthread doesn't know which provider answers which question. To reach Jev through OpenRouter instead of TypeSafe, switch the `jev` handler to `:openrouter-decisions` with the model `"typesafe/jev-1.13"` and an OpenRouter key; the bthread doesn't change.

## Providers

These providers are built in:

| Provider | Reaches | Sign-in |
| --- | --- | --- |
| `:openai-compatible` | Any chat completions API that supports JSON Schema structured output | API key |
| `:claude-cli` | Claude models through the Claude Code CLI | Your Claude subscription |
| `:pi-cli` | Any model the [pi](https://github.com/earendil-works/pi) CLI is logged in to, including ChatGPT subscription models | Handled by pi |
| `:openrouter-decisions` | Jev decision models on OpenRouter | OpenRouter API key |
| `:typesafe-decisions` | Jev decision models directly from TypeSafe | TypeSafe API key |

The first three are general-purpose models: you send a prompt or a conversation and get back data matching your schema. The two decision providers are different. Instead of a prompt, you give Jev some information and a set of named questions (yes/no, pick a category, or score against levels), and it answers each one with probabilities.

All built-in providers currently run on the JVM only.

### Adding a provider

Providers are not a fixed list. To add one, define a method on `tech.thomascothran.pavlov.ai.provider/call!` for a new provider keyword. It receives your `:provider-options` along with the request's `:input`, `:messages` and `:schema`, and returns `{:data value}` or an anomaly. Pavlov AI validates the data against the schema, so the new provider works with existing bthreads and handlers unchanged.

```clojure
(require '[tech.thomascothran.pavlov.ai.provider :as provider])

(defmethod provider/call! :my-provider
  [_provider {:keys [input messages schema] :as options}]
  ;; Call the model and return {:data value} or an anomaly.
  )
```

## Development

Enter the reproducible development environment with:

```shell
devenv shell
```

If you use direnv, allow the included `.envrc` instead. Inside the environment, run the test suite with:

```shell
clj-test
```

Use `test-watch` to rerun tests as files change and `clj-build` to build the JAR.

### Development nREPL

Run `devenv up` to start the `clj` process, or start it directly with
`clojure -X:dev:test dev/go!`. The server binds only to `127.0.0.1`, chooses
an available port, and writes it to `.nrepl-port` for editor/client discovery.
This lets separate worktrees run servers without port collisions.
For a fixed port, use `clojure -X:dev:test dev/go! :port 9898`.

From an existing REPL, `(dev/start-nrepl!)` starts the server and
`(dev/stop-nrepl!)` stops it. Shutdown removes its port file.
Connect with `clj-nrepl-eval -p "$(< .nrepl-port)"`.
