# Claude CLI provider

## Context

Pavlov AI dispatches model calls through `provider/call!` by provider keyword.
Existing providers (`:openai-compatible`, `:openrouter-decisions`,
`:typesafe-decisions`) are HTTP adapters with an injected Hato-shaped `:post!`.
They return `{:data decoded-value}` or a Cognitect anomaly; `ai/call!` decodes
and validates `:data` against the caller's Malli schema.

## Problem

Use a Claude subscription as a provider by shelling out to `claude -p`, without
an API key and without sessions. From a bthread's perspective it must behave
exactly like the OpenRouter adapter: same `:input`/`:messages`/`:schema`
contract, caller-owned history, schema-validated data or an anomaly. Calls
should be cheap: prompt-cache hits across independent calls, and no tools.

## Decisions

- **Claude only.** Codex is dropped for now. Notes for later: it can be stripped
  to zero tools only via a patched `model_catalog_json` plus many feature and
  config overrides. It gets no cross-call cache hits (`prompt_cache_key` is the
  per-call thread id). ChatGPT-subscription use outside Codex is not an approved
  route.
- **Scope: `provider/call!` only** (structured output). There is no
  `chat-completion!`/agent-loop support: the CLI's tools are its own, not
  Pavlov actions.
- **Provider keyword `:claude-cli`**, in a JVM-only namespace
  `provider/claude_cli.clj`, required from `ai.cljc` like the other adapters.
- **Subscription auth, no API key.** Do not use `--bare`, because it requires
  `ANTHROPIC_API_KEY`.
- **No session:** `--no-session-persistence`.
- **Process transport is injected as `:run!`**, analogous to `:post!`. It
  takes the args and stdin, and returns `{:exit :out :err}`. Tests use fakes.
- **`:model` and `:effort` are required.** No defaults.
- **Tools are off by default** (`--tools ""`) and configurable. Enabling tools
  in `-p` mode also needs `--allowedTools`, because there is no interactive
  approval.
- **Isolation flags:** `--strict-mcp-config`, `--setting-sources ""` and
  `--disable-slash-commands`. Verified result: `tools: []`, `mcp_servers: []`,
  and no `AGENTS.md`/`CLAUDE.md` in the prompt. With `--json-schema`, init
  lists only the CLI's internal `StructuredOutput` tool (verified).
- **Always pass `--system-prompt`.** It replaces Claude Code's default prompt.
- **Structured output:** `--json-schema` with a schema from
  `schema/->json-schema`. Read the parsed `structured_output` from the
  result. It is implemented by an internal tool, so `num_turns` is 2; that is
  expected.
- **Message history (an internal adapter concern):**
  - **leading** system/developer messages go to `--system-prompt` (joined in
    order); system/developer messages after the first user/assistant turn stay
    in place as `<turn role="system">`/`<turn role="developer">` blocks, which
    keeps order and a stable cached prefix;
  - the remaining turns go in **one** user message via
    `--input-format stream-json`, with **one text block per turn**, each
    role-tagged (e.g. `<turn role="assistant">…</turn>`, content escaped);
  - `:input` is a single user turn;
  - Rejected alternatives:
    - separate stream-json messages: assistant messages are ignored, and each
      user message triggers its own model call;
    - one big text block: no cache hits as history grows.
  - Verified: block-per-turn reads the previous call's prefix from cache.
- **Wire format:** `--input-format stream-json` requires `--output-format
  stream-json` and `--verbose` (verified; `json` output is rejected). Read the
  final `{"type":"result"}` event; it carries `is_error`, `subtype` and
  `structured_output`.
- **Environment:** no clearing needed. Verified that `-p` works with
  `CLAUDECODE=1` (running nested inside Claude Code).
- **Working directory:** optional `:dir`, `nil` by default. When `nil`, no
  directory is passed to `:run!` and none is created; the CLI inherits the
  JVM's working directory. The adapter never creates directories. Verified: the
  working directory affects the cached prefix *before* the messages (a
  different directory read 735 of 1,062 tokens from cache), so calls only
  share cache when they run from the same directory. Within one JVM that holds
  automatically; set `:dir` to share the cache across processes, or to keep
  enabled tools away from the project's files. Document this in the README.
- **`:run!` is required** (like `:post!`); ship a ready-made
  `ProcessBuilder` runner that callers pass in.
- **No metadata key.** Other `call!` adapters return only `{:data …}`.
- **Caching:** keep the system prompt, schema, flags and **working directory**
  stable. Changing the working directory changes the prefix and misses the
  cache.
- **Errors become anomalies**, following existing conventions. Do not copy
  CLI/provider error text (which may echo the input) into anomalies.
- **Usage reporting is out of scope.** It is a general, cross-provider
  feature. Return `{:data …}` only; consider an optional metadata key if that
  makes provider results more consistent (cf. `:provider-metadata` in
  `normalize-chat-completion`).

## Checklist

- [x] Write failing tests in `test/.../provider/claude_cli_test.clj` using a fake `:run!`:
  - [x] argv contains the tool/isolation/session flags, `--model`, `--effort`, `--system-prompt`, `--json-schema`
  - [x] `:input` → single user turn; `:messages` → system prompt + block-per-turn user message
  - [x] role validation matches `:openai-compatible` (system/developer/user/assistant; keyword or string); both `:input` and `:messages` → anomaly
  - [x] turn content is escaped so it cannot forge a turn boundary
  - [x] success → `{:data structured_output}`
  - [x] missing `:run!`/`:model`/`:effort` → `:invalid-options` anomaly
  - [x] non-zero exit, `is_error`/`subtype` errors, missing/invalid JSON, missing `structured_output` → anomalies without copied error text
  - [x] timeout and missing binary → `:interrupted` / `:unavailable` anomalies
  - [x] configurable tools: default `""`; a configured list sets `--tools` and `--allowedTools`
- [x] Decide the rule for system/developer messages that appear mid-conversation (leading → system prompt; later → in-place turns)
- [x] Decide on the working directory (optional `:dir`, `nil` by default; inherit the JVM's; never create it)
- [x] Verify `--input-format stream-json` with `--output-format json` (rejected; use `stream-json --verbose` and read the `result` event)
- [x] Check whether to clear `CLAUDECODE` and similar env vars when nested inside Claude Code (not needed)
- [x] Implement `provider/claude_cli.clj` (`defmethod provider/call! :claude-cli`)
- [x] Provide a default `:run!` built on `ProcessBuilder` (stdin, timeout, kill the process tree), or document how to supply one
- [x] Require the namespace from `ai.cljc` (`#?(:clj …)`)
- [x] Decide on an optional metadata key in provider results (no; return `{:data …}` only)
- [x] Add a dev/REPL example under `dev/`; manually verify a real call, cache hits across calls, and `tools: []`
- [x] Document `:claude-cli` in `README.md` (options, no tools by default, subscription auth, caching notes)
- [x] Run `clj-test`
