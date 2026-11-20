# TODO

## Provider options: shared conventions and request passthrough

### Problem

Callers could not reach provider features that the adapters did not name
explicitly. Each adapter destructured a fixed set of options and built its
request from those alone; everything else was silently dropped. Found while
building Jeeves: a role like `{:profile :openrouter/claude :effort :high}` had
no effect on OpenRouter, while the same role on `:pi-cli` worked.

Shared settings also had no common shape: `:effort` was required and validated
against different value sets by `:pi-cli` and `:claude-cli`, and unsupported by
`:openai-compatible`.

### Decisions

- **Plain shared keys**, starting with `:effort` and `:timeout-ms`. No
  namespacing; both keys already existed on the CLI adapters.
- **No effort vocabulary in code.** Models name effort levels differently, and
  mapping them ourselves is a maintenance burden. `:effort` is passed through
  unchanged (keywords by name) to the right place; the provider/CLI rejects
  values it does not accept, which surfaces as the usual anomaly. `:effort` is
  optional everywhere; omitted means the provider's default.
  - `:pi-cli` → `--thinking`
  - `:claude-cli` → `--effort`
  - `:openai-compatible` → top-level `reasoning_effort`. OpenAI uses this
    field and OpenRouter accepts it as an OpenAI-style alias of
    `reasoning.effort`, so no dialect switch is needed. OpenRouter's nested
    form (e.g. `max`) is reachable through `:extra-body`.
  - Decisions adapters: not sent (no such field).
- **`:timeout-ms`** on HTTP adapters becomes Hato's per-request `:timeout`.
- **`:extra-body`** map on the HTTP adapters, shallow-merged into the request
  body. Top-level keys are keywordized so string keys cannot duplicate adapter
  keys. Adapter-owned fields (`model`, `messages`, `response_format`,
  `stream`; `model`, `state`, `questions`) win; `:extra-body` overrides the
  field produced by `:effort`. Flex tier: `:extra-body {:service_tier "flex"}`.
- **Unrecognized options are ignored**, not rejected, to avoid brittleness.
  Only the passthrough keys reach the wire, so `:post!`, `:api-key` etc. are
  never serialized. Malformed `:extra-body` (not a map) or `:timeout-ms` (not
  a positive int) is still an `:incorrect` anomaly.
- **Provider error messages in anomalies**, on all five providers. With
  `:effort` unvalidated locally, a rejected value was undiagnosable ("Provider
  returned an unsuccessful HTTP status"). `call!` now copies the provider's
  error message, code, type and param into the anomaly, reversing the earlier
  "never copy provider error text" rule. The CLIs copy stderr / error-result
  text and start failures; the decision APIs copy `error.message` (OpenRouter)
  or `detail` (TypeSafe, string, object or validation list). Text is truncated
  to 2,000 characters. Reply text, non-JSON error bodies and credentials are
  never copied.
  `claude` and `pi` ignore an unknown effort (stderr warning, default effort,
  exit 0); we keep that a success rather than parse warnings.
  `:omit-provider-error-message? true` replaces the message with a generic
  one and keeps the structured fields. Off by default.
- **Per-event overrides in `make-handler`.** A request event may carry
  `:provider-options`, shallow-merged over the handler's. Denylist, not
  allowlist, so new call settings are overridable without code changes:
  `:url`, `:api-key`, `:headers`, `:post!`, `:run!`, `:executable` are ignored
  (`ai/handler-fixed-provider-options`), so an event cannot send credentials
  elsewhere or run another program. `:provider` is not overridable.
  `:tools` and `:dir` are overridable.
- **No CLI `:extra-args`** for now: it could undo the isolation flags the CLI
  adapters rely on. Revisit when there is a concrete need.

### Status

- [x] Implemented in all five adapters, documented in adapter docstrings and
      README "Common options".
- [x] Unit tests: effort placement and passthrough, optional effort, timeout,
      extra-body merge/override rules, no leakage of non-wire options.
- [x] Live check against OpenRouter (`openai/gpt-6-luna`):
      - `:effort :none` → 0 reasoning tokens; `:effort :high` → 267.
      - `:effort :ultra-mega` → HTTP 400 from OpenRouter, `:incorrect` anomaly.
      - `:extra-body {:reasoning {:effort "max"}}` accepted (330 reasoning
        tokens); `:timeout-ms` reached Hato.
      - `:extra-body {:service_tier "flex"}` reached routing (the `openai/flex`
        endpoint was considered) but was filtered out by this account's ZDR
        guardrail → 404. The passthrough works; serving flex needs an account
        or model that allows it. No OpenAI direct key available to test.
- [x] Provider error details in anomalies on all providers, with
      `:omit-provider-error-message?`; unit tested and checked live
      (OpenRouter bad effort lists accepted values; bad model on both
      decision APIs; missing CLI binary).
- [x] devenv `clj-repl` loads `OPENROUTER_API_KEY` / `TYPESAFE_API_KEY` from
      `~/.secrets` for the REPL process only.
- [x] Request events override handler `:provider-options` (except
      destination, credentials and transport); tested and documented.

### Follow-ups (out of scope)

- Flex tier not yet seen served: needs an OpenAI key, or an OpenRouter
  account/model whose ZDR settings allow a flex endpoint.
