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
`d7b02636a0c7e8e615d0cff70679d18d2ff59573` (July 29, 2026). The original
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
- JVM `HttpClient` is the default provider transport; `StreamOptions.fetch` and
  `ImagesOptions.fetch` provide per-request HTTP injection for supported
  adapters, while WebSockets and SDK transports remain independent. Provider
  SDKs are added only where protocol complexity requires them.
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
| Core AI messages and stream protocol | Functional slice | Message, event-stream, provider-native raw stop reason, image-generation result, UUIDv7, tool validation, and faux-provider tests |
| Model catalog | Functional slice | Hydrated schema-v3 manifest and 37 chat-provider files verify by SHA-256; all 1,110 static chat model records plus the credential-backed dynamic Radius catalog are exposed through 38 executable chat providers, including 29 credential-filtered GitHub Copilot models and Qwen Token Plan reasoning-control metadata; a separate immutable catalog exposes all 40 OpenRouter image models with an independent checksum |
| Provider HTTP implementations | Functional slice | All 10 upstream chat API families plus `openrouter-images` have executable Kotlin paths. Coverage includes Google Generative AI, Google Vertex AI, Anthropic Messages plus Claude Pro/Max OAuth, OpenRouter Chat Completions and Images plus shared browser OAuth, xAI Chat Completions/Responses plus device OAuth, Kimi Coding Anthropic Messages plus device OAuth, Radius `pi-messages` plus discovered browser/device OAuth and dynamic models, OpenAI Chat Completions, OpenAI Responses, Azure OpenAI Responses, Mistral Conversations, Amazon Bedrock ConverseStream, OpenAI Codex Responses SSE/WebSocket plus browser/device OAuth, GitHub Copilot device OAuth and Anthropic/OpenAI Chat/OpenAI Responses delegates, Cloudflare Workers AI, and Cloudflare AI Gateway with independent payload/event/auth/image parity |
| Agent loop | Functional slice | Streaming, tool calls, parallel execution, steering, follow-up, abort, and session tests using the faux provider; coding-message projection has independent parity for bash/custom/branch/compaction messages |
| CLI argument contract | Partial | Parser tests and byte-for-byte `--help` oracle against the pinned TypeScript CLI; provider-prefixed and slash-containing model IDs, thinking suffixes, package install/remove/update/list, and interactive `/login`/`/logout` for OAuth providers are covered |
| Context, skill, and prompt resources | Functional slice | Global and ancestor `AGENTS.md`/`CLAUDE.md`, nested linked-worktree context deduplication, `SYSTEM.md`/`APPEND_SYSTEM.md` content and source paths, recursive `.pi`/`.agents` skills, prompt templates, YAML frontmatter, collisions, manual skill commands, template arguments, trusted project precedence, persisted trust inheritance, CLI/RPC commands, startup Context display, and interactive reload have an independent resource-loading oracle |
| Package settings and resources | Functional slice | User/project `settings.json`, local/npm/git package identities and managed paths, install/remove/package-update/list commands, package manifests, autoload filters, top-level resource overrides, project precedence, package-sourced skills/prompts, source metadata, and failed new-checkout cleanup have an independent package-resources oracle and package tests; config TUI, self-update, legacy lookup, available-update checks, and remaining recovery paths remain |
| Themes and resource composition | Functional slice | Upstream-compatible JSON validation, recursive variables, cycle/missing-reference failures, built-in dark/light themes, automatic terminal appearance, truecolor/256-color ANSI output, project/user/package/extension precedence, collision diagnostics, persisted settings, extension named/in-memory switching, and persistent-surface rerendering have independent theme-runtime and extension-theme oracles |
| JavaScript/TypeScript extensions | Partial | A bundled Node 22 JSONL host ships the official jiti 2.7.0 static runtime and MIT license. It loads `.js`/`.cjs`/`.mjs`/`.ts`/`.cts`/`.mts`/`.tsx`, extensionless imports, directory indexes, local TypeScript and extension-owned bare-package dependencies, ESM/CommonJS interop, and common pi/TypeBox virtual modules with `moduleCache: false`; JSX stays disabled like upstream by default. Tools, commands, flags, shortcuts, message/session-entry renderers, package discovery, lifecycle/tool hooks, command actions, project trust, resource composition, serializable and direct native provider registration, request-bound and unsolicited background registration refresh, live `ctx.scopedModels`, awaited `select`/`confirm`/`input`/`editor` dialogs with request-scoped blocking TUI timeout/AbortSignal interruption, persistent widget/header/footer component factories with stable IDs, width, `requestRender()`, replace/clear/dispose, status/git footer data, focused `ctx.ui.custom()` input loops, basic virtual `Key`/`Editor`/`CustomEditor`, RPC and server UI responses, `user_bash` direct results, function-valued `BashOperations`, native `stream`/`streamSimple`, legacy extension OAuth, native API-key `login`/`check`/`resolve`, function-valued `refreshModels(context.store)`, cancellation/lifecycle cleanup, and fire-and-forget UI events run through CLI/RPC/interactive paths with independent extension-runtime, custom-UI, renderer, shortcut, and jiti compatibility oracles. Custom editor replacement, raw terminal input, autocomplete composition, and overlay/full-screen parity remain |
| Session JSONL compatibility | Functional slice | Independent TypeScript/Kotlin JSONL parity covers current/v1/v2 parsing, rewrite, migration, branching, compaction, model/thinking state, custom/tool/bash messages, and explicit empty-leaf context |
| Built-in coding tools | Functional slice | Read, write, edit, bash, grep, find, and ls behavior tests with path and truncation handling |
| Interactive terminal UI | Partial | Installed JLine process enters a PTY; themed headers/prompts/stream output/tool labels, startup Context paths, initial `@text-file`/`@image` prompts, `/help`, session/model/thinking commands, shell commands, extension surfaces, focused custom components, basic extension editor input, and `/exit` are covered; full-screen layout, overlays, custom editor replacement, raw terminal input, autocomplete composition, and transcript parity are not ported |
| TUI utilities | Functional slice | ANSI-aware text layout, grapheme/CJK/emoji width, colors, key parsing, keybindings, word navigation, kill ring, and undo tests |
| Compaction | Functional slice | Token estimation, safe cut points, split turns, tool-result truncation, standalone summaries, events, persistence, and reload tests |
| HTML export | Partial | Standalone export, strict escaping, whitespace, and validated image data are covered; upstream theme/Markdown/highlighting parity remains |
| SQLite storage | Functional slice | Schema migration, session CRUD, ordering, filtering, stats, and codec tests |
| Server/RPC | Functional slice | Supervisor lifecycle, Unix socket request/response, streaming events, persistence, piped EOF half-close, extension UI request/response routing, user bash persistence, concurrent local/extension bash cancellation, and stale Agent subscription ownership tests |

## Verification snapshot

Verified on July 29, 2026 against source commit
`d7b02636a0c7e8e615d0cff70679d18d2ff59573`:

- `./gradlew clean test installDist`: passed, 376 tests, 0 failures, 0 errors,
  and 0 skipped.
- `./migration/oracle/compare-cli-help.sh`: passed with byte-for-byte CLI help
  parity.
- `./migration/oracle/compare-model-catalog-runtime.sh`: passed for bundled,
  persisted-newer, remote-newer, unavailable-catalog, and ETag/304
  revalidation behavior.
- `./migration/oracle/compare-resource-loading.sh`: passed for YAML
  frontmatter, skills, prompt templates, command expansion, source metadata,
  collision precedence, trusted/untrusted project resources, context files,
  file-backed system/append prompt source paths, and inherited persisted trust
  decisions.
- `./migration/oracle/compare-package-resources.sh`: passed for user/project
  settings, local package manifests, filters, enabled state, source metadata,
  project precedence, top-level overrides, configured-package listing, and
  scope-relative settings mutation, plus npm/git/local source parsing and
  user/project/temporary managed install paths.
- `./migration/oracle/compare-extension-runtime.sh`: passed for TypeScript
  module loading, common virtual imports, tool/command/flag/provider
  registration metadata, tool execution and partial updates, command/UI
  actions, lifecycle hooks, tool-call blocking, tool-result chaining, and
  resource-discovery event results, awaited dialog answers, function-valued
  `user_bash` execution, callback-provider stream events, legacy extension
  OAuth callback behavior, direct native provider registration, native API-key
  auth context, provider-store access, filtering, both native stream methods,
  named-provider `refreshModels`, and tool/command/flag/provider registrations
  scheduled after the originating command response.
- `./migration/oracle/compare-extension-jiti-compat.sh`: passed for
  extensionless imports, directory indexes, ESM/CommonJS interoperability,
  explicit `require()`, `.mts`/`.cts`/`.tsx`, imported TypeScript
  dependencies, extension-owned bare packages, pi/TypeBox virtual modules,
  and the upstream-compatible JSX-disabled boundary.
- `./migration/oracle/compare-extension-renderers.sh`: passed for ordered
  first-extension-wins selection, message and entry payloads, `expanded`,
  `outputPad`, terminal width, `Box`/`Text`/`TruncatedText` rendering,
  undefined results, and thrown renderers.
- `./migration/oracle/compare-extension-custom-ui.sh`: passed for startup
  widgets/header/footer, width-changing `requestRender()`, clear/dispose,
  focused down/enter selection, and virtual editor text submission.
- `./migration/oracle/compare-theme-runtime.sh`: passed for parsing,
  validation, variables, errors, fallbacks, ANSI modes, built-ins, automatic
  appearance selection, resource precedence, diagnostics, and settings.
- `./migration/oracle/compare-extension-theme.sh`: passed for named and
  in-memory theme switching, failed-name fallback, persistence, and immediate
  persistent-surface rerendering.
- Provider payload/stream parity passed with Qwen Token Plan reasoning controls
  and provider-native `rawStopReason` terminal fields.
- All 23 deterministic migration oracles passed against the same source
  baseline.
- The installed JLine PTY loaded a custom 256-color theme, emitted its
  `accent=201` ANSI sequence, and listed `SYSTEM.md`, `APPEND_SYSTEM.md`, then
  `AGENTS.md` in startup Context order.
- The installed `pi-server` completed `serve`, `spawn`, `status`,
  `get_available_models`, `get_state`, and `stop` in an isolated directory.
- The installed JLine PTY passed 72 columns to live message and entry
  renderers, hid a `display=false` message, and exited normally. The
  no-terminal-size path independently fell back to 80 columns.
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
  model counts, Claude Opus 5 adaptive-thinking metadata with the `minimal`
  override and 1M context, and user/agent/vision dynamic headers.
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
  endings, `pending` partial stop reasons, terminal-message validation, usage,
  final stop reasons, and replay signatures across the same provider protocols,
  including Vertex request-path/API-key parity, Bedrock SDK
  client/final-request parity, plus independent Cloudflare
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
- Provider transport fixture tests cover independent per-request injection for
  Anthropic, OpenAI Chat/Responses, Azure Responses, Mistral, Codex SSE, Pi
  Messages, and OpenRouter Images; streaming response bodies and callbacks are
  retained, while Google and Google Vertex reject unsupported custom
  transports instead of silently bypassing them.
- OpenAI Chat compatibility tests cover generated and custom Z.AI models,
  including provider and `api.z.ai`/`open.bigmodel.cn` URL fallback detection,
  and verify `max_tokens` is sent without `max_completion_tokens`.
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
  profile selection, explicit/scoped profile precedence over ambient access
  keys, ambient-key precedence when only an ambient profile exists, scoped
  access keys, skip-auth proxy credentials,
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
- The July 29, 2026 synchronization pass reviewed and classified every audited
  source commit through `027a5847901b5dde30270abaa1041046cd2b4b55`.
  `./gradlew clean test installDist` passed with 331 tests and zero failures,
  errors, or skips, and all 17 deterministic migration oracles passed.
- Installed `pi` RPC mode resolved
  `--models google/gemini-3-flash-preview:high` to the requested model and
  thinking level. Installed `pi-server` completed `serve`, `spawn`, `status`,
  `rpc get_available_models`, `rpc get_state`, and `stop`; the isolated
  instance exposed 1,110 available models.
- The source coding-agent Vitest suite reached 1,640 passing tests and 48 skips,
  with two full-suite-only reftable filesystem-watcher timeouts in
  `footer-data-provider.test.ts`. That file predates the synchronized range,
  the source worktree remained clean, and an isolated rerun of the whole file
  passed all 8 tests.
- `./migration/audit-migration.sh sync` passes through `d7b02636`.
  `./migration/audit-migration.sh full` intentionally remains nonzero on six
  partial areas: CLI workflows, package management, extension runtime,
  interactive terminal, HTML export, and RPC/server parity.
- The next July 29 synchronization pass reviewed every source commit through
  `cced6a21da273b26ee4a23a803680614bbe8dd1e`. Kotlin now avoids duplicate
  context files in nested linked worktrees and accepts nullable array schemas
  with `items`; llama streaming usage and TUI image fallback remain classified
  under their existing extension and full-screen TUI gaps.
- Source commit `4f0437e2d58d651dd934119ecabea2893975f62f` adds only
  the AgentHarness v2 design document and is classified as not applicable to
  the current runtime migration.
- Source-focused verification after `npm ci` passed 33 resource-loader tests,
  4 tool-validation tests, and 67 terminal-image tests. The source worktree
  remained clean.
- The bidirectional extension host supports correlated dialog requests,
  RPC/server responses while the originating command is blocked, Node-side
  timeout and abort handling, JSONL EOF/shutdown cancellation, and
  function-valued `BashOperations` with streaming output, cancellation, and
  streaming UTF-8 decoding across raw chunk boundaries. The extension-runtime
  oracle compares the same dialog and bash fixture against TypeScript.
- The provider callback bridge returns a Kotlin stream immediately while Node
  iterates JavaScript events, projects model/context/options into
  `streamSimple`, and bridges legacy OAuth login, refresh, API-key derivation,
  and credential-dependent model projection. Caller cancellation, host close,
  and runtime shutdown abort active provider operations even when extension
  code ignores `AbortSignal`.
- Extension OAuth credentials retain arbitrary fields across Node/Kotlin calls
  and persist them at the top level of `auth.json`, matching the TypeScript
  credential shape.
- Focused upstream coding-agent tests passed 94 model-registry,
  `modifyModels`, and auth-option cases.
- The installed `pi` distribution loaded the migration fixture and returned
  `callback:installed-smoke:callback-key:high` through its function-valued
  provider.
- The installed `pi` JSONL process loaded the migration fixture, completed
  awaited `select`, `confirm`, `input`, and `editor` requests, streamed both
  `BashOperations` output chunks, returned exit code 7, and exited cleanly
  after stdin EOF.
- The installed `pi-server` completed `serve`, `spawn`, `status`,
  `get_available_models`, `get_state`, piped `rpc-stream`, and `stop`. The
  isolated instance loaded the callback extension, exposed 1,112 models
  including `callback-provider/callback-model`, and emitted `rpc_ready` then
  `response` before a clean half-close and lifecycle shutdown.
- The direct native provider bridge now exposes `getModels`, `filterModels`,
  `stream`, and `streamSimple`; native API-key login/check/resolve can call
  correlated `ctx.env()` and `ctx.fileExists()` operations and project
  provider-specific keys, headers, environment, and base URLs.
- Function-valued `refreshModels` receives a provider-scoped store with
  `read`, `write`, and `delete`. Native providers can update their published
  model set after refresh, while named providers publish returned models
  without implicit Kotlin persistence.
- Focused upstream verification passed 94 provider-registry/auth cases and all
  28 `models-runtime` cases. The installed `pi` native-provider smoke returned
  `simple:native-initial:native-key`.
- The installed `pi-server` discovered `native-provider/native-initial`, set it
  as the active model, preserved `provider=native-provider` and
  `api=native-api` on state read-back, streamed a response, and shut down
  cleanly.
- Extension actions produced outside an active host request are pushed by the
  Node process and separated from normal responses by a dedicated Kotlin stdout
  reader together with the current registration snapshot. Provider actions are
  applied before tools, commands, and flags are refreshed.
- The extension-runtime oracle schedules tool, command, flag, and provider
  registration after a command response, then invokes the new tool and command
  on both the TypeScript and Kotlin runtimes. Host and RPC regressions cover
  provider discovery and tool activation without another extension invocation.
- The installed `pi` RPC distribution ran `/schedule-background`, then exposed
  `background-command` and `background-provider/background-model` without a
  second extension invocation.
- The installed `pi-server` completed spawn, background command/provider
  discovery, command invocation, status, stop, and missing-instance read-back.
- The extension host now extracts a content-addressed bundle containing the
  official jiti 2.7.0 static runtime and license, then loads extensions through
  `jiti.import(..., { default: true })` with `moduleCache: false`.
- Installed `pi --mode rpc` and `pi-server` each discovered all eight supported
  jiti fixture commands. Server validation also covered spawn, status, stop,
  and missing-instance read-back.
- Direct TUI extension handlers now run on request-scoped workers while the
  host response loop remains free to receive `ui_cancel`. Timeout and explicit
  AbortSignal cancellation interrupt the active JLine reader, wait for dialog
  cleanup, and suppress late responses.
- The installed JLine PTY ran `/cancel-dialogs`, cancelled a blocking input on
  timeout and a blocking selector on `AbortController.abort()`, printed
  `dialog-cancelled:timeout|aborted`, restored the main prompt, and exited
  normally.
- Installed RPC JSONL and server `rpc-stream` emitted the input, select, and
  notification events without client dialog responses, completed the slash
  command, and retained clean EOF/status/stop behavior.
- Extension shortcut registrations now carry stable host IDs and descriptions.
  Resolution uses upstream-compatible default and user keybindings, protects
  reserved actions, warns for allowed non-reserved overrides, normalizes key
  case, and lets the later extension win.
- The independent shortcut oracle compares default and rebound keymaps,
  diagnostics, selected extension ownership, and actual handler actions
  between the TypeScript runner and Kotlin host.
- The installed JLine PTY rendered the extension in `/hotkeys`, dispatched raw
  `Ctrl-Y`, opened a blocking input dialog, printed `shortcut:Ada`, restored a
  partial `/ex` editor buffer, and exited after receiving only `it`.
- Persistent extension widget, header, and footer factories now render through
  the Node host with stable component IDs, terminal width, request-driven
  rerenders, replacement/clear disposal, extension statuses, and git-branch
  footer data.
- Focused `ctx.ui.custom()` components render one frame per input, receive
  canonical terminal sequences, rerender until `done(result)`, dispose, and
  restore the linear JLine prompt. The virtual TUI bridge includes key constants
  and a basic text `Editor`/`CustomEditor`.
- The independent custom UI oracle compares startup surfaces, a width-changing
  `requestRender()`, clear/dispose behavior, down/enter selection, and text
  editor submission between the TypeScript runner and Kotlin host.
- The installed 67-column JLine PTY rendered header/footer/widgets, selected
  `beta`, submitted `Ada` through the virtual editor, restored the main prompt,
  and exited normally.

## Remaining major gaps

- Finish extension parity beyond the migrated Node host by adding custom editor
  replacement, raw terminal input listeners, autocomplete composition, and
  overlay/full-screen placement. The package config selector, self-update, and
  remaining package recovery paths also remain. Core package
  manifests/filters/settings, skills, prompt templates, persisted trust
  lookup, extension resource composition, awaited RPC/TUI dialogs,
  function-valued `user_bash`, direct native and named provider callbacks,
  legacy extension OAuth, and interactive resource reload are migrated.
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
