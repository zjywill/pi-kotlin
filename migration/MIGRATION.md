# Migration plan

## Source baseline

- Repository: `/Users/junyizhang/Git/pi`
- Commit: `9b3a2059171bcc74ad9d2cadeea6d186776cf2db`
- Source date: July 22, 2026
- Packages: `ai`, `agent`, `tui`, `storage/sqlite-node`, `coding-agent`, `server`
- Source size at baseline: 1,082 files and about 255,245 lines across TypeScript,
  JavaScript, JSON, and Markdown

The source commit is immutable for the first migration pass. Upstream changes
land in a later synchronization pass so that parity failures have one cause.

The latest reviewed synchronization pass reaches
`cee5ff7520d8828bed9955ef00419e995d1f91e0` (July 26, 2026). The original
baseline remains recorded so regressions can be attributed either to the first
translation or to a later upstream sync.

## Migration method

1. Define independent graders before translating a subsystem.
2. Record module dependencies and migrate from leaves upward.
3. Port behavior and public contracts, not TypeScript syntax.
4. Keep external boundaries compatible: CLI flags, JSONL sessions, provider
   payloads, stream events, tool schemas, and terminal rendering.
5. Compile and run package-local tests after every batch.
6. Treat a compiling stub as unmigrated.
7. Update the compatibility matrix only with executable evidence.

## Kotlin architecture

- Java 21 runtime.
- Kotlin coroutines model asynchronous streams and parallel tool execution.
- Kotlin serialization owns JSON and JSONL contracts.
- JVM `HttpClient` is the default provider transport; provider SDKs are added
  only where protocol complexity requires them.
- Node/Bun distribution scripts become Gradle application distributions.
- TypeBox schemas become JSON Schema values with a compatibility validator.

## Dependency order

```text
pi-ai ────────┐
              ├── pi-agent-core ── pi-storage-sqlite
pi-tui ───────┼── pi-coding-agent ── pi-server
              └─────────────────────┘
```

## Acceptance gates

Each migrated subsystem must pass:

1. Kotlin compilation with warnings treated as errors.
2. Ported unit tests for stable pure behavior.
3. TypeScript/Kotlin fixture comparison for serialized or CLI contracts.
4. Integration tests for module boundaries.
5. CLI smoke tests from an installed distribution.

Provider completion additionally requires recorded request/stream fixtures and
opt-in live smoke tests. Interactive TUI completion additionally requires
terminal transcript comparison at multiple terminal widths.

## Current status

Status describes the migrated slice, not whole-package parity. "Functional
slice" means the listed behavior is implemented and executable, while upstream
features outside that slice remain migration work.

| Area | Status | Executable evidence |
| --- | --- | --- |
| Gradle multi-module build | Functional slice | Six JVM 21 modules; `clean test installDist` passes with warnings as errors |
| Core AI messages and stream protocol | Functional slice | Message, event-stream, image-generation result, UUIDv7, tool validation, and faux-provider tests |
| Model catalog | Functional slice | Hydrated schema-v3 manifest and 37 chat-provider files verify by SHA-256; all 1,110 static chat model records plus the credential-backed dynamic Radius catalog are exposed through 38 executable chat providers, including 29 credential-filtered GitHub Copilot models; a separate immutable catalog exposes all 40 OpenRouter image models with an independent checksum |
| Provider HTTP implementations | Functional slice | All 10 upstream chat API families plus `openrouter-images` have executable Kotlin paths. Coverage includes Google Generative AI, Google Vertex AI, Anthropic Messages plus Claude Pro/Max OAuth, OpenRouter Chat Completions and Images plus shared browser OAuth, xAI Chat Completions/Responses plus device OAuth, Kimi Coding Anthropic Messages plus device OAuth, Radius `pi-messages` plus discovered browser/device OAuth and dynamic models, OpenAI Chat Completions, OpenAI Responses, Azure OpenAI Responses, Mistral Conversations, Amazon Bedrock ConverseStream, OpenAI Codex Responses SSE/WebSocket plus browser/device OAuth, GitHub Copilot device OAuth and Anthropic/OpenAI Chat/OpenAI Responses delegates, Cloudflare Workers AI, and Cloudflare AI Gateway with independent payload/event/auth/image parity |
| Agent loop | Functional slice | Streaming, tool calls, parallel execution, steering, follow-up, abort, and session tests using the faux provider; coding-message projection has independent parity for bash/custom/branch/compaction messages |
| CLI argument contract | Partial | Parser tests and byte-for-byte `--help` oracle against the pinned TypeScript CLI; provider-prefixed and slash-containing model IDs, thinking suffixes, package install/remove/update/list, and interactive `/login`/`/logout` for OAuth providers are covered |
| Context, skill, and prompt resources | Functional slice | Global and ancestor `AGENTS.md`/`CLAUDE.md`, `SYSTEM.md`/`APPEND_SYSTEM.md`, recursive `.pi`/`.agents` skills, prompt templates, YAML frontmatter, collisions, manual skill commands, template arguments, trusted project precedence, persisted trust inheritance, CLI/RPC commands, and interactive reload have an independent resource-loading oracle |
| Package settings and resources | Functional slice | User/project `settings.json`, local/npm/git package identities and managed paths, install/remove/package-update/list commands, package manifests, autoload filters, top-level resource overrides, project precedence, package-sourced skills/prompts, and source metadata have an independent package-resources oracle; config TUI, self-update, and legacy package recovery remain |
| JavaScript/TypeScript extensions | Partial | A bundled Node 22 JSONL host loads `.js`/`.ts` extensions and common pi/TypeBox virtual imports; tools, commands, flags, package discovery, lifecycle/tool hooks, command actions, and RPC fire-and-forget UI events run through CLI/RPC/interactive paths with an independent extension-runtime oracle. Full jiti syntax/import compatibility, blocking UI dialogs, shortcuts/renderers, extension resource composition, project trust handlers, live provider registration, and dynamic registration refresh remain |
| Session JSONL compatibility | Functional slice | Independent TypeScript/Kotlin JSONL parity covers current/v1/v2 parsing, rewrite, migration, branching, compaction, model/thinking state, custom/tool/bash messages, and explicit empty-leaf context |
| Built-in coding tools | Functional slice | Read, write, edit, bash, grep, find, and ls behavior tests with path and truncation handling |
| Interactive terminal UI | Partial | Installed JLine process enters a PTY; initial `@text-file`/`@image` prompts, `/help`, session/model/thinking commands, shell commands, and `/exit` are covered; full-screen upstream UI is not ported |
| TUI utilities | Functional slice | ANSI-aware text layout, grapheme/CJK/emoji width, colors, key parsing, keybindings, word navigation, kill ring, and undo tests |
| Compaction | Functional slice | Token estimation, safe cut points, split turns, tool-result truncation, standalone summaries, events, persistence, and reload tests |
| HTML export | Partial | Standalone export, strict escaping, whitespace, and validated image data are covered; upstream theme/Markdown/highlighting parity remains |
| SQLite storage | Functional slice | Schema migration, session CRUD, ordering, filtering, stats, and codec tests |
| Server/RPC | Functional slice | Supervisor lifecycle, Unix socket request/response, streaming events, persistence, and piped EOF half-close tests |

## Verification snapshot

Verified on July 27, 2026 against source commit
`cee5ff7520d8828bed9955ef00419e995d1f91e0`:

- `./gradlew clean test installDist`: passed, 282 tests, 0 failures, 0 errors,
  and 0 skipped.
- `./migration/oracle/compare-cli-help.sh`: passed with byte-for-byte CLI help
  parity.
- `./migration/oracle/compare-model-catalog-runtime.sh`: passed for bundled,
  persisted-newer, remote-newer, unavailable-catalog, and ETag/304
  revalidation behavior.
- `./migration/oracle/compare-resource-loading.sh`: passed for YAML
  frontmatter, skills, prompt templates, command expansion, source metadata,
  collision precedence, trusted/untrusted project resources, context files,
  and inherited persisted trust decisions.
- `./migration/oracle/compare-package-resources.sh`: passed for user/project
  settings, local package manifests, filters, enabled state, source metadata,
  project precedence, top-level overrides, configured-package listing, and
  scope-relative settings mutation, plus npm/git/local source parsing and
  user/project/temporary managed install paths.
- `./migration/oracle/compare-extension-runtime.sh`: passed for TypeScript
  module loading, common virtual imports, tool/command/flag/provider
  registration metadata, tool execution and partial updates, command/UI
  actions, lifecycle hooks, tool-call blocking, tool-result chaining, and
  resource-discovery event results.
- The installed `bin/pi` loaded a TypeScript extension from an installed local
  package, exposed `package-extension-smoke` before package prompt/skill
  commands in RPC `get_commands`, executed the slash command, emitted its
  `extension_ui_request`, and returned a successful prompt response.
- `./migration/oracle/compare-anthropic-oauth.sh`: passed with independent
  browser/manual PKCE login, authorization-code and refresh-token JSON
  requests, rotated credentials, five-minute expiry skew, Claude Code Bearer
  authentication, content negotiation and identity headers, mandatory system
  identity, `ANTHROPIC_AUTH_TOKEN` bearer headers without OAuth shaping,
  complete Messages payload parity, and canonical tool-name mapping in both
  directions.
- `./migration/oracle/compare-github-copilot.sh`: passed with independent
  enterprise-domain device OAuth, GitHub and Copilot token requests, all-model
  policy enablement, account model filtering, credential-specific
  `proxy-ep` base URL derivation, 10 Anthropic/7 Chat Completions/12 Responses
  model counts, and user/agent/vision dynamic headers.
- `./migration/oracle/compare-kimi-coding-oauth.sh`: passed with independent
  device authorization, wait-before-first-poll, pending and server-directed
  slow-down timing, exponential refresh retry, unauthorized refresh
  short-circuiting, Bearer-header derivation, and a real local Anthropic
  Messages SSE request consuming the stored OAuth credential.
- `./migration/oracle/compare-openai-codex-oauth.sh`: passed with independent
  browser, device-code, and refresh flows. It compares PKCE authorization
  parameters, state handling, authorization-code and refresh-token forms,
  device notification/request payloads, JWT account extraction, credential
  rotation, and request-auth derivation.
- `./migration/oracle/compare-openrouter-oauth.sh`: passed with an independent
  random one-shot loopback callback, ephemeral port and UUID path checks, PKCE
  authorization, permanent API-key exchange, callback response headers,
  no-op refresh, request-auth derivation, and a real local OpenRouter-compatible
  provider request consuming the stored OAuth credential.
- `./migration/oracle/compare-openrouter-images.sh`: passed with exact parity
  for the 40-model image catalog and checksum, final `/chat/completions`
  URL/headers/payload, text and valid/invalid data-URL image parsing, response
  IDs, cache-aware usage/cost, payload and response callbacks, retry behavior,
  HTTP status/body errors, missing-key results, stored OpenRouter OAuth
  consumption, and explicit API-key precedence.
- `./migration/oracle/compare-radius.sh`: passed with independent dynamic OAuth
  discovery, browser PKCE and device authorization, pending and server-directed
  slow-down timing, token refresh and expiry skew, credentialed dynamic model
  loading, legacy credential catalog restoration, and a real local
  `pi-messages` SSE request consuming the stored OAuth credential.
- `./migration/oracle/compare-xai-oauth.sh`: passed with independent device
  authorization, wait-before-first-poll, pending and server-directed slow-down
  timing, five-minute expiry skew, refresh-token preservation, default
  one-hour lifetime, request-auth derivation, and real local Chat Completions
  and Responses SSE requests consuming the stored OAuth credential.
- `./migration/oracle/compare-provider-payloads.sh`: passed with exact normalized
  JSON parity for OpenAI Chat Completions, OpenAI Responses, Azure OpenAI
  Responses, Anthropic Messages, Google Generative AI, Google Vertex AI,
  Mistral Conversations, and Amazon Bedrock ConverseStream base and reasoning
  requests plus OpenAI Codex Responses SSE, including Azure deployment names,
  Vertex thinking controls, Mistral reasoning/cache controls, Bedrock
  adaptive/fixed thinking and `streamSimple` thinking-budget reservation, and
  Codex reasoning, service tiers, verbosity, tool choice, and cache affinity.
- `./migration/oracle/compare-provider-stream-events.sh`: passed with normalized
  public transcript parity for event ordering, text/thinking/tool deltas and
  endings, terminal messages, usage, stop reasons, and replay signatures across
  the same provider protocols, including Vertex request-path/API-key parity,
  Bedrock SDK client/final-request parity, plus independent Cloudflare
  auth-resolution and final-request parity across Workers AI and all three AI
  Gateway delegates, Codex zstd/auth/session/final-request parity, and Codex
  WebSocket handshake/`response.create` frame parity.
- Azure OpenAI fixture tests cover API-key authentication, API-version query
  handling, endpoint normalization, resource/base/model precedence, deployment
  maps, and reasoning-signature replay through the shared Responses state
  machine.
- Mistral fixture tests cover Bearer authentication, `/v1/chat/completions`,
  `x-affinity`, prompt cache keys, prompt-mode versus reasoning-effort
  selection, thinking/text/tool streaming, cached-token usage, cross-provider
  tool-call ID normalization, and synthetic missing tool results.
- Cloudflare fixture tests cover scoped credential/environment precedence,
  required account and gateway configuration, request-time URL placeholder
  resolution, Workers AI Bearer authentication, AI Gateway
  `cf-aig-authorization`, removal and explicit restoration of upstream
  authentication headers, all three delegated protocols, and cache-aware
  session-affinity headers.
- Google Vertex fixture tests cover express-mode API keys, ADC marker handling,
  Application Default Credentials and service-account token loading,
  project/location precedence, regional/global/multi-region hosts,
  collection-scoped custom endpoints, Google-compatible message conversion,
  thinking levels and budgets, stream events, response IDs, usage, and tool
  calls.
- Amazon Bedrock fixture tests cover Bearer tokens, standard AWS credential and
  profile selection, scoped access keys, skip-auth proxy credentials,
  ARN/region/endpoint precedence, reserved signed-header filtering, message and
  image conversion, prompt cache points, adaptive and fixed Claude thinking,
  GovCloud payloads, reasoning/text/tool EventStream state, usage, stop reasons,
  and synthetic tool results. Independent graders compare final Converse
  payloads plus the SDK client/request projection and public stream transcript.
- OpenAI Codex fixture tests cover ChatGPT account extraction from OAuth access
  tokens, mandatory authorization/header precedence, 64-character cache
  affinity, zstd-compressed SSE requests, `/codex/responses` URL normalization,
  reasoning and verbosity controls, strict and grammar tools, service-tier
  pricing, `response.done` termination, and `streamSimple` thinking-level
  clamping. WebSocket fixtures cover Java `HttpClient` transport, one-shot and
  session-cached connections, five-minute idle expiry, the 55-minute connection
  age limit, cached `previous_response_id` input deltas, connection-limit and
  missing-continuation retries, pre-output SSE fallback, post-output failure,
  sticky session fallback, and exact handshake/frame parity. OAuth fixtures
  additionally cover browser/manual callback parsing, state validation, device
  pending/slow-down/success polling, token exchange, refresh, double-checked
  concurrent refresh, atomic owner-only `auth.json` persistence, no ambient
  fallback after refresh failure, interactive `/login`/`/logout`, and real
  provider-request consumption of stored tokens.
- Anthropic OAuth fixture tests cover browser/manual callback parsing, PKCE
  state validation, authorization-code exchange, refresh-token rotation,
  five-minute expiry skew, real local-server Bearer requests, Claude Code beta
  and identity headers, mandatory system identity, and canonical tool names in
  declared, historical, and returned tool calls.
- OpenRouter OAuth fixture tests cover the real ephemeral loopback callback,
  PKCE code exchange, permanent credential projection, denial and nested error
  details, login timeout cleanup, no-op refresh, and catalog/remote-catalog
  OAuth capability retention.
- OpenRouter Images fixture tests cover mutable provider registration,
  best-effort model listing, in-flight dynamic refresh deduplication and
  cancellation recovery, API-key/OAuth resolution, base URL/header/environment
  merging, non-throwing terminal errors, request retries, callback replacement,
  Unicode sanitization, mixed text/image input, valid and invalid image output,
  cache-write-aware usage, and error-body passthrough.
- xAI OAuth fixture tests cover form headers and fields, trusted HTTPS
  verification URLs, complete-URL preference, RFC default and minimum poll
  intervals, pending/slow-down/denial/expiry behavior, rotated and preserved
  refresh tokens, default token lifetime, invalid fields and JSON, upstream
  error details, catalog/remote-catalog OAuth retention, and stored-credential
  requests through both xAI protocol delegates.
- Kimi Code OAuth fixture tests cover form headers and fields, request
  timeouts, default and overridden OAuth hosts, trusted HTTP(S) verification
  URLs, default/minimum poll timing, pending/slow-down/denial/expiry/server
  failures, complete token validation, four-attempt exponential refresh retry,
  unauthorized refresh short-circuiting, transport failures, Bearer-header
  derivation, catalog/remote-catalog OAuth retention, and header-owned
  Anthropic requests without an empty API key.
- Radius fixture tests cover gateway URL normalization and sanitization,
  `/v1/oauth` discovery, browser PKCE callback and manual fallback, device
  pending/slow-down/denial behavior, refresh-token rotation with a 60-second
  expiry skew, credentialed `/v1/config` loading, cached and legacy
  `gatewayConfig` restoration, payload/response hooks, debug requests, response
  diagnostics, and text/thinking/tool/error `pi-messages` SSE conversion.
- GitHub Copilot fixture tests cover personal and enterprise endpoint
  normalization, trusted verification URLs, wait-before-first-poll,
  pending/slow-down/deadline behavior, five-minute expiry skew, best-effort
  policy enablement, `/models` picker/policy/tool filtering, credential
  serialization, account-aware CLI/RPC model selection, `proxy-ep` and
  enterprise fallback base URLs, and real local-server requests through all
  three protocol delegates. Anthropic requests use Bearer authentication and
  Copilot-selective beta headers; all delegates apply static integration and
  dynamic initiator/vision headers.
- `./migration/oracle/compare-coding-message-projection.sh`: passed with
  normalized JSON parity for ordinary messages, custom messages, branch and
  compaction summaries, bash formatting, and `excludeFromContext` filtering.
- `./migration/oracle/compare-session-jsonl.sh`: passed with normalized JSON
  parity for current, v1, and v2 sessions, including append-only rewrite,
  migrations, branches, compaction context, model/thinking state, and explicit
  empty-leaf behavior.
- `./migration/audit-migration.sh sync`: passed for every target-package commit
  between the original baseline and
  `cee5ff7520d8828bed9955ef00419e995d1f91e0`.
- The July 23 incremental sync adds JSON-schema and grammar constrained
  sampling, strict-tool negotiation across supported providers, OpenAI custom
  tool streaming, abortable provider retry backoff, explicit cache-write
  suppression, bracketed model-ID coverage, and RPC bash output update events.
- The July 26 incremental sync adds Anthropic bearer-token authentication,
  gateway-owned Radius OAuth endpoints, Claude Opus 5 and Bedrock inference
  profile behavior, 40 OpenRouter image models, ETag model-catalog
  revalidation, auth-cause diagnostics, and directory-safe context discovery.
- Installed `pi` PTY smoke: entered interactive mode, rendered `/help`, and
  preserved `openrouter/moonshotai/kimi-k2.6` through `/model`, then exited
  normally through `/exit`.
- Installed authentication smoke: a fixture OpenAI Codex OAuth credential in a
  temporary `PI_CODING_AGENT_DIR` was loaded by `/logout openai-codex`, removed
  without touching ambient variables, and persisted as `{}` with mode `0600`.
- Installed GitHub Copilot authentication smoke: an enterprise OAuth fixture
  with an account-specific model list was loaded by `/logout github-copilot`,
  removed without touching ambient variables, and persisted as `{}` with mode
  `0600`.
- Installed Anthropic authentication smoke: an isolated OAuth fixture was
  loaded by `/logout anthropic`, removed without touching ambient variables,
  and persisted as `{}` with mode `0600`.
- Installed OpenRouter authentication smoke: an isolated permanent OAuth-key
  fixture was loaded by `/logout openrouter`, removed without touching ambient
  variables, and persisted as `{}` with mode `0600`.
- Installed image-catalog smoke loaded the distributed `pi-ai` JAR through the
  `pi-server` installation and exposed one OpenRouter image provider with all
  40 bundled models and OAuth support.
- Installed xAI authentication smoke: an isolated OAuth fixture was loaded by
  `/logout xai`, removed without touching ambient variables, and persisted as
  `{}` with mode `0600`.
- Installed Kimi Code authentication smoke: an isolated OAuth fixture was
  loaded by `/logout kimi-coding`, removed without touching ambient variables,
  and persisted as `{}` with mode `0600`.
- Installed Radius authentication smoke: an isolated OAuth fixture with a
  legacy gateway config was loaded by `/logout radius`, removed without
  touching ambient variables, and persisted as `{}` with mode `0600`.
- Installed `pi-server` process smoke: `serve`, `spawn`, `list`, `status`,
  `rpc set_model`, `rpc set_thinking_level`, `rpc-stream get_state`, and `stop`
  all succeeded.
- The installed server exposed `azure-openai-responses/gpt-5`; state read-back
  preserved the Azure provider/API/model selection and thinking level `high`.
- The installed server exposed `mistral/mistral-small-2603`; state read-back
  preserved the Mistral provider/API/model selection and thinking level `high`.
- The installed server exposed all 42 Cloudflare AI Gateway models and all 13
  Cloudflare Workers AI models. State read-back preserved
  `cloudflare-workers-ai/@cf/moonshotai/kimi-k2.6` and
  `cloudflare-ai-gateway/workers-ai/@cf/moonshotai/kimi-k2.6`, their
  OpenAI-compatible API selection, and thinking level `high`.
- The installed server exposed all 12 Google Vertex AI models. State read-back
  preserved `google-vertex/gemini-3-flash-preview`, API `google-vertex`, and
  thinking level `high`.
- The installed server exposed all 109 Amazon Bedrock models and 1,081 models
  total. State read-back preserved
  `amazon-bedrock/us.anthropic.claude-opus-4-8`, API
  `bedrock-converse-stream`, and thinking level `high`.
- The installed server exposed all 7 OpenAI Codex models and 1,081 models
  total. State read-back preserved `openai-codex/gpt-5.5`, API
  `openai-codex-responses`, and thinking level `high`.
- With a GitHub Copilot OAuth fixture allowing only `claude-sonnet-4.6`, the
  installed server exposed 1 Copilot model and 1,082 models total. RPC
  `set_model` and state read-back preserved provider `github-copilot`, API
  `anthropic-messages`, and thinking level `high`.
- With an isolated Anthropic OAuth fixture, the installed server exposed
  `anthropic/claude-sonnet-4-6`. RPC `set_model`,
  `set_thinking_level`, and piped `rpc-stream get_state` preserved provider
  `anthropic`, API `anthropic-messages`, and thinking level `high`.
- With an isolated OpenRouter OAuth fixture, the installed server exposed all
  274 OpenRouter models. RPC `set_model`, `set_thinking_level`, and piped
  `rpc-stream get_state` preserved
  `openrouter/moonshotai/kimi-k2.6`, API `openai-completions`, and thinking
  level `high`.
- With an isolated xAI OAuth fixture, the installed server exposed all 3 xAI
  models and 1,110 models total. RPC `set_model`, `set_thinking_level`, and
  piped `rpc-stream get_state` preserved `xai/grok-4.5`, API
  `openai-responses`, and thinking level `high`.
- With an isolated Kimi Code OAuth fixture, the installed server exposed all 3
  Kimi Coding models and 1,110 models total. RPC `set_model`,
  `set_thinking_level`, and piped `rpc-stream get_state` preserved
  `kimi-coding/k3`, API `anthropic-messages`, and thinking level `high`.
- With an isolated Radius OAuth fixture and legacy gateway config, the
  installed server restored `radius/auto` into the model store and exposed
  1 Radius model and 1,110 models total. RPC `set_model`,
  `set_thinking_level`, and piped `rpc-stream get_state` preserved
  `radius/auto`, API `pi-messages`, and thinking level `high`.
- Server state read-back preserved the slash-containing model ID
  `moonshotai/kimi-k2.6` and thinking level `high`.
- Piped `rpc-stream` emitted `rpc_ready` then `response` and exited with status
  0 after stdin EOF.
- The pinned TypeScript source worktree remained clean.
- A detached source snapshot at
  `cee5ff7520d8828bed9955ef00419e995d1f91e0` passed
  `npm ci --ignore-scripts`, `npm run hydrate:model-data`,
  `npm run build:offline`, and the full `npm test` workspace suite with an
  isolated home directory. The tracked source checkout remains clean; its
  checked-in schema-v2 model data requires hydration because current source
  code expects schema 3.

## Remaining major gaps

- Finish extension parity beyond the migrated Node host: jiti-complete
  transpilation/imports, blocking interactive/RPC dialogs, shortcuts and custom
  renderers, extension-provided resource composition, `project_trust`, live
  provider registration, and dynamic registration refresh. Theme
  parsing/rendering, interactive trust selection, the package config selector,
  self-update, and remaining package recovery paths also remain. Core package
  manifests/filters/settings, skills, prompt templates, persisted trust lookup,
  and interactive resource reload are migrated.
- Port the full-screen terminal component and rendering model, then compare
  terminal transcripts at multiple widths.
- Close CLI behavior gaps for options that are parsed or documented but do not
  yet have complete runtime behavior.
- Match upstream HTML export theming, Markdown rendering, and syntax
  highlighting.
- Expand process-level server compatibility and restart/recovery coverage.

## Completeness audit

The migration is not complete while any row in `migration/inventory.tsv` is
`partial` or `missing`. This is intentional: compilation and the existing
oracles prove the migrated slices, but cannot prove features that have no target
implementation.

```bash
./migration/audit-migration.sh sync  # verifies reviewed upstream coverage
./migration/audit-migration.sh full  # exits non-zero until all areas are complete
```

The rulebook in `migration/RULEBOOK.md` defines what may be treated as a JVM
native replacement and what requires behavior parity.
