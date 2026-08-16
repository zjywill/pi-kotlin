# Migration plan

## Source baseline

- Repository: `/Users/junyizhang/Git/pi`
- Commit: `9b3a2059171bcc74ad9d2cadeea6d186776cf2db`
- Source date: July 22, 2026
- Packages: `ai`, `agent`, `tui`, `storage/sqlite-node`, `coding-agent`,
  `protocol`, `client`, `server`
- Source size at baseline: 1,082 files and about 255,245 lines across TypeScript,
  JavaScript, JSON, and Markdown

The source commit is immutable for the first migration pass. Upstream changes
land in a later synchronization pass so that parity failures have one cause.

The latest reviewed synchronization pass reaches
`d3ab2af969d64997338253c9151190aa1bc33580` (August 16, 2026). The original
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
| Core AI messages and stream protocol | Functional slice | Message, event-stream, provider-native raw stop reason, image-generation result, UUIDv7, generic sampling parameters, tool validation, and faux-provider tests |
| Model catalog | Functional slice | Hydrated schema-v3 manifest and 39 chat-provider files verify by SHA-256; all 1,281 static chat model records plus the credential-backed dynamic Radius catalog are exposed through 40 executable chat providers, including xAI Grok 4.6/xhigh Responses metadata, Baseten GLM-5.2/Kimi-K2.6 reasoning controls, 33 credential-filtered GitHub Copilot models, and Qwen Token Plan metadata; a separate immutable catalog exposes all 45 OpenRouter image models with an independent checksum |
| Provider HTTP implementations | Functional slice | All 10 upstream chat API families plus `openrouter-images` have executable Kotlin paths. Coverage includes Google Generative AI, Google Vertex AI, Anthropic Messages plus Claude Pro/Max OAuth, OpenRouter Chat Completions and Images plus shared browser OAuth, xAI Responses plus device OAuth, encrypted reasoning replay, and mandatory pi request identity, Kimi Coding Anthropic Messages plus device OAuth, Radius `pi-messages` plus discovered browser/device OAuth and dynamic models, OpenAI Chat Completions including Kimi top-level cache-read usage, Baseten `chat_template_args`, generic sampling-parameter last-write merging, and function-payload precedence over malformed empty `custom` objects, OpenAI Responses and Azure OpenAI Responses with the same sampling merge, Mistral Conversations, Amazon Bedrock ConverseStream, OpenAI Codex Responses SSE/WebSocket plus browser/device OAuth, GitHub Copilot device OAuth and Anthropic/OpenAI Chat/OpenAI Responses delegates, Cloudflare Workers AI, and Cloudflare AI Gateway with independent payload/event/auth/image parity |
| Agent loop | Functional slice | Streaming, tool calls, parallel execution, steering, follow-up, abort, and session tests using the faux provider; coding-message projection has independent parity for bash/custom/branch/compaction messages |
| CLI argument contract | Complete | Parser tests, byte-for-byte top-level/auth/package help, installed package/model-update error contracts, provider-prefixed and slash-containing model IDs, authenticated-provider ambiguity resolution, thinking suffixes, text/linear-JSON/RPC/print modes, session selection/forking, resource/tool flags, offline behavior, quiet/verbose startup, credential print, bounded interactive OAuth/API-key login/logout, and child-process agent markers are covered by independent CLI/package and RPC runtime oracles plus installed PTYs |
| Context, skill, and prompt resources | Functional slice | Global and ancestor `AGENTS.md`/`CLAUDE.md`, nested linked-worktree context deduplication, `SYSTEM.md`/`APPEND_SYSTEM.md` content and source paths, recursive `.pi`/`.agents` skills, prompt templates, YAML frontmatter, collisions, manual skill commands, template arguments, trusted project precedence, persisted trust inheritance, CLI/RPC commands, startup Context display, and interactive reload have an independent resource-loading oracle |
| Package settings and resources | Complete | User/project `settings.json`, local/npm/git identities and managed paths, install/remove/update/list/config commands, package manifests and filters, top-level overrides, project precedence, package-sourced resources, source metadata, npm/pnpm legacy global lookup, available-update checks, scoped npm batching, targeted suggestions, upstream-specific git reconciliation, incomplete-update markers, dependency repair after failed cleanup, and source-distribution self-update have independent package-resource and installed CLI/package runtime oracles plus focused tests |
| Themes and resource composition | Functional slice | Upstream-compatible JSON validation, recursive variables, cycle/missing-reference failures, built-in dark/light themes, automatic terminal appearance, truecolor/256-color ANSI output, project/user/package/extension precedence, collision diagnostics, persisted settings, extension named/in-memory switching, and persistent-surface rerendering have independent theme-runtime and extension-theme oracles |
| JavaScript/TypeScript extensions | Complete | The bundled Node 22 JSONL host and official jiti 2.7.0 runtime cover module loading, virtual imports, tools, commands, flags, shortcuts, renderers, lifecycle hooks, resources, providers, generation-checked `context.stored`/`context.publish()` model refresh, legacy provider stores, dialogs, persistent surfaces, focused custom UI, custom editor replacement, raw terminal input, autocomplete wrapping, overlays, RPC/server UI, OAuth, bash, streaming callbacks, cancellation, and cleanup. Independent extension oracles plus the installed multi-width TUI judge cover the complete extension boundary |
| Session JSONL compatibility | Functional slice | Independent TypeScript/Kotlin JSONL parity covers current/v1/v2 parsing, rewrite, migration, branching, compaction, model/thinking state, custom/tool/bash messages, and explicit empty-leaf context |
| Built-in coding tools | Functional slice | Read, write, edit, bash, grep, find, and ls behavior tests with path normalization, root-search relativization, and truncation handling |
| Interactive terminal UI | Complete | The installed JLine process uses a persistent component tree that can switch between regular and fullscreen modes at runtime, while retaining transcript/editor state, extension listeners, overlays, settings, bounded offscreen Kitty uploads, fullscreen copy flashes, synchronized masked input, and clean exit. The TypeScript/Kotlin installed PTY matrix verifies core, editor, and overlay scenarios at 40, 80, and 120 columns, including terminal-screen row and column placement |
| TUI utilities | Functional slice | ANSI-aware text layout, grapheme/CJK/emoji width, colors, key parsing, keybindings, word navigation, kill ring, and undo tests |
| Compaction | Functional slice | Token estimation, safe cut points, split turns, tool-result truncation, standalone summaries, events, persistence, and reload tests |
| HTML export | Complete | Exact upstream standalone HTML/CSS/JavaScript, vendored Markdown and syntax-highlighting runtimes, recursive theme variables and export colors, session tree/branch/label/filter/statistics views, extension and built-in tool renderers, strict escaping, safe links, whitespace, and validated image data have byte-for-byte and browser-runtime parity evidence |
| SQLite storage | Functional slice | Schema migration, session CRUD, ordering, filtering, stats, and codec tests |
| Server/RPC | Complete | Each instance runs an independent `pi --mode rpc` child process with correlated requests, event fan-out, extension UI response routing, stderr/exit propagation, pending-request rejection, persisted error state, and restart recovery. An independent installed TypeScript/Kotlin native-provider grader covers the complete RPC command surface and public event ordering, including settings, prompts/queues, abort/retry, bash, extension UI/dialogs, compaction, queries, session branching, and export. Unix socket request/response, streaming events, persistence, piped EOF half-close, concurrent local/extension bash cancellation, and stale Agent subscription ownership are also covered |

## Verification snapshot

Verified on August 16, 2026 against source commit
`d3ab2af969d64997338253c9151190aa1bc33580`:

- `./gradlew clean test installDist --max-workers=1 --no-daemon`: passed, 584
  tests, 0 failures, 0 errors, and 0 skipped.
- The source checkout passed `npm ci --ignore-scripts`, `npm run build:offline`,
  and `./test.sh`: 221 test files passed with 6 skipped, covering 1,928
  passing tests with 49 skipped; the tracked source worktree remained clean.
- The hydrated schema-v3 catalog contains 39 provider files and 1,281 model
  records. Its manifest structure hash is
  `5afa7db49f850bf1636a16119baf08ec9b751398b1a6da6f04e438f95be85f3a`.
- The immutable OpenRouter image catalog contains 45 models and its independent
  catalog hash is
  `dece50d8a3c27ec0ffe2ac81ef0ef12f88db00f00015d5bfec69b98f99dc9181`.
- `./migration/oracle/compare-cli-help.sh`: passed with byte-for-byte CLI help
  parity.
- `./migration/oracle/compare-cli-package-runtime.sh`: passed for installed
  package help and error contracts, local package lifecycle and settings,
  offline native-provider print, masked API-key credential persistence, and
  config-selector settings mutation. The pinned TypeScript generic login dialog
  currently renders a `secret` prompt in clear text; Kotlin intentionally
  retains masked input, and the oracle fails if the Kotlin transcript contains
  the credential.
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
  auth context, generation-checked `context.stored`/`context.publish()` model
  refresh, legacy provider-store access, filtering, both native stream methods,
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
- `./migration/oracle/compare-interactive-tui.sh`: passed for installed
  TypeScript/Kotlin core, custom-editor, raw-input, autocomplete, and overlay
  behavior at 40, 80, and 120 columns, including actual terminal-screen
  placement.
- `./migration/oracle/compare-theme-runtime.sh`: passed for parsing,
  validation, variables, errors, fallbacks, ANSI modes, built-ins, automatic
  appearance selection, resource precedence, diagnostics, and settings.
- `./migration/oracle/compare-extension-theme.sh`: passed for named and
  in-memory theme switching, failed-name fallback, persistence, and immediate
  persistent-surface rerendering.
- `./migration/oracle/compare-html-export.sh`: passed with byte-identical
  standalone default-theme output and byte-identical custom-theme output,
  including the upstream templates, Markdown/highlighting runtimes, session
  tree data, shortcut text, JSON serialization, and export colors. The
  custom-theme SHA-256 is
  `829d2e917ae7faf3e505420bab0ecf837ac59f693983f3c9eb66edd64dc11cd4`.
- `./migration/oracle/compare-html-tool-renderer.sh`: passed for extension
  `renderCall` and `renderResult` behavior, including collapsed and expanded
  output.
- `./migration/oracle/compare-html-builtin-tool-renderer.sh`: passed for
  upstream-compatible `find` and `grep` result rendering.
- `./migration/oracle/compare-server-recovery.sh`: passed for restart recovery
  of persisted `starting`, `online`, `stopping`, `stopped`, and `error`
  instances, including metadata preservation and refreshed `lastSeenAt`.
- `./migration/oracle/compare-rpc-runtime.sh`: passed with zero normalized diff
  for the installed TypeScript and Kotlin CLIs using the same native provider
  and JSONL command sequence. Coverage includes errors, state/settings,
  model/thinking, prompts/queues, abort/retry terminal lifecycle, bash,
  extension UI/dialogs, compaction, message/stat/entry/tree queries,
  fork/clone/switch/new-session, and HTML export.
- Provider payload/stream parity passed with Qwen Token Plan reasoning controls,
  provider-native `rawStopReason` terminal fields, and OpenAI function arguments
  preserved when a malformed delta also includes an empty `custom` object.
- Azure OpenAI Responses parity preserved proxy query parameters when appending
  the `/responses` path, including the source-compatible encoded
  `tenant=one%2Fresponses` request shape.
- Current synchronization coverage also passed for compaction recovery, linear
  JSON/RPC events, authenticated model disambiguation, Baseten reasoning
  requests, normalized tool-result images, protocol-v1 transport-owned
  authentication, child-process agent markers, runtime UI mode switching,
  fullscreen copy confirmation, bounded Kitty image caching, Windows truecolor,
  symlink session discovery, provider-scoped generation-safe model refresh,
  generic sampling parameters, failed-clean dependency repair, root `find`
  normalization, concurrent masked-input rendering, Google/Vertex
  `MAX_TOKENS` and provider-error preservation during tool calls, generic SGR
  mouse-release handling, host-clipboard copy failure reporting, and
  collapsed/expanded extension-tool fallback output. GitHub Copilot policy
  updates are serialized during login, and a rate-limited model catalog request
  honors `Retry-After` and retries once. All built-in xAI models route through
  Responses with Grok 4.6 as the default, and Kimi top-level `cached_tokens`
  contributes to cache-read usage.
- `./migration/oracle/compare-tool-fallback.sh`: passed with normalized
  collapsed and expanded fallback rendering, configured expansion binding,
  remaining-line counts, and transcript persistence.
- All 32 deterministic migration oracles passed against the same source
  commit.
- `./migration/audit-migration.sh sync` and
  `./migration/audit-migration.sh full` both passed through `d3ab2af96`.
- Installed `pi --export` output was byte-identical to upstream with SHA-256
  `3613ceef433cc31040a5413427db35c4fd5b1d480aab0988b1613f634809bcb6`.
- A Playwright browser smoke rendered the Markdown heading, two highlighted
  keyword spans, session tree, and tool output; retained the allowed HTTPS
  link; rejected the `javascript:` link; applied custom accent `#123456` and
  body background `rgb(17, 34, 51)`; and reported no console or page errors.
- The installed JLine PTY loaded a custom 256-color theme, emitted its
  `accent=201` ANSI sequence, and listed `SYSTEM.md`, `APPEND_SYSTEM.md`, then
  `AGENTS.md` in startup Context order.
- The installed `pi` distribution returned its version and complete help,
  including Baseten credentials and runtime UI mode.
- The `pi-server` Unix socket integration tests completed session lifecycle,
  shared runtime, durable ID, and backend substitution checks.
- The installed `pi --mode rpc` parity smoke completed isolated spawn/status,
  direct and streamed state reads, stop, empty-list read-back, process recovery,
  and extension UI/event routing. No standalone `pi-server` executable is
  produced because the Gradle module is library-only.
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
  for the 45-model image catalog and checksum, final `/chat/completions`
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
  one-hour lifetime, request-auth derivation, and real local Grok 4.3/Grok 4.6
  Responses requests covering low/xhigh effort, encrypted reasoning, and the
  mandatory pi User-Agent while consuming the stored OAuth credential.
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
- The July 30 synchronization pass reviewed every source commit from
  `d7b02636` through `71efc6f0`, ported the OpenAI malformed function/custom
  delta fix, and classified the changelog, formatting-only TUI, release, and
  post-release commits as non-runtime changes.
- The latest July 30 synchronization pass fast-forwarded the source from
  `71efc6f0` through `05558a79`. The contributor-approval metadata change is
  outside the migrated package paths, and Kotlin now exposes the upstream
  `OpenCode Go` provider display name with regression coverage.
- `./migration/audit-migration.sh sync` and
  `./migration/audit-migration.sh full` pass through `05558a79`.
- The latest August 3 synchronization pass fast-forwarded the source from
  `a0014c1a` through `a96fb984`. Kotlin ports failed-clean dependency repair,
  caller-owned provider-scoped model refresh with generation-checked
  publication, generic sampling parameters, and the new root `find` regression
  coverage; the contributor-approval commit is outside migrated package paths.
- The same pass updated OAuth/auth oracles for the required cancellation
  signals and preserved masked Kotlin API-key input despite the pinned
  TypeScript login dialog's inherited clear-text rendering regression.
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
- The final full-screen implementation makes the TUI component tree the default
  interactive console, with persistent header/widget/footer surfaces, transcript,
  editor, autocomplete, and overlays.
- Custom editor replacement supports set/read/paste/input/submit/restore. Raw
  terminal listeners can consume, rewrite, and unsubscribe before editor input,
  and extension autocomplete wrappers delegate to the Kotlin base provider.
- Overlay rendering resolves percentage width and margins before invoking the
  extension component, serializes queued/live controls, delays the first frame
  until queued state is applied, and preserves focus across hide/show/unfocus.
- The installed TypeScript/Kotlin PTY matrix passed core, editor, and overlay
  scenarios at 40, 80, and 120 columns; terminal emulation verified the overlay
  row and column rather than only matching raw output.
- Every parsed CLI field now reaches the text/JSON/RPC/interactive, session,
  model/tool/resource, trust, offline, or extension runtime that owns it.
  `quietStartup` is loaded from settings and `--verbose` overrides it.
- Interactive API-key login uses masked JLine input, honors offline refresh,
  persists owner-only credentials, and exits cleanly after the next prompt is
  ready.
- Package commands preserve upstream help, validation priority, exit codes, and
  local install/list/remove settings behavior. The full-screen config selector
  supports global/project modes, search, navigation, top-level and package
  resource toggles, and three-state project overrides.
- Source distributions self-update only through a clean tracked checkout,
  fetch and fast-forward their configured upstream, rebuild `installDist`, and
  support already-current and forced reinstall paths.
- Legacy npm/pnpm lookup, unpinned npm/git update discovery, user/project npm
  batching, targeted source-prefix suggestions, upstream-specific git fetch/
  reset/clean, and dependency reinstall only after HEAD changes are covered by
  focused tests.
- Interactive startup checks package updates asynchronously when online and
  reports available updates above the active JLine prompt.

## Remaining major gaps

None against the pinned source commit `b1efcf7d7`. A future source update must
start a new synchronization range and rerun the complete rulebook.

## Completeness audit

The migration inventory is complete through `b1efcf7d7`; no row in
`migration/inventory.tsv` remains `partial` or `missing`.

```bash
./migration/audit-migration.sh sync  # verifies reviewed upstream coverage
./migration/audit-migration.sh full  # verifies every inventory area is complete
```

The rulebook in `migration/RULEBOOK.md` defines what may be treated as a JVM
native replacement and what requires behavior parity.
