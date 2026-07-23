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
`24bace27cf308c89707cf8005b4795d873e23f17` (July 23, 2026). The original
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
| Core AI messages and stream protocol | Functional slice | Message, event-stream, UUIDv7, tool validation, and faux-provider tests |
| Model catalog | Functional slice | Hydrated schema-v3 manifest and 37 provider files verify by SHA-256; 1,109 model records load, with 1,074 models exposed across 35 providers whose protocols and authentication are currently executable |
| Provider HTTP implementations | Partial | Google Generative AI, Google Vertex AI, Anthropic Messages, OpenAI Chat Completions, OpenAI Responses, Azure OpenAI Responses, Mistral Conversations, Amazon Bedrock ConverseStream, Cloudflare Workers AI, and Cloudflare AI Gateway request/stream fixture tests, independent base/reasoning payload and public stream-transcript parity, provider-auth/request parity, and multi-protocol catalog dispatch |
| Agent loop | Functional slice | Streaming, tool calls, parallel execution, steering, follow-up, abort, and session tests using the faux provider; coding-message projection has independent parity for bash/custom/branch/compaction messages |
| CLI argument contract | Partial | Parser tests and byte-for-byte `--help` oracle against the pinned TypeScript CLI; provider-prefixed and slash-containing model IDs plus thinking suffixes are covered |
| Prompt and context resources | Partial | Global and ancestor `AGENTS.md`/`CLAUDE.md` discovery, `SYSTEM.md`/`APPEND_SYSTEM.md`, CLI overrides, trust gating, and `--no-context-files` tests |
| Session JSONL compatibility | Functional slice | Independent TypeScript/Kotlin JSONL parity covers current/v1/v2 parsing, rewrite, migration, branching, compaction, model/thinking state, custom/tool/bash messages, and explicit empty-leaf context |
| Built-in coding tools | Functional slice | Read, write, edit, bash, grep, find, and ls behavior tests with path and truncation handling |
| Interactive terminal UI | Partial | Installed JLine process enters a PTY; initial `@text-file`/`@image` prompts, `/help`, session/model/thinking commands, shell commands, and `/exit` are covered; full-screen upstream UI is not ported |
| TUI utilities | Functional slice | ANSI-aware text layout, grapheme/CJK/emoji width, colors, key parsing, keybindings, word navigation, kill ring, and undo tests |
| Compaction | Functional slice | Token estimation, safe cut points, split turns, tool-result truncation, standalone summaries, events, persistence, and reload tests |
| HTML export | Partial | Standalone export, strict escaping, whitespace, and validated image data are covered; upstream theme/Markdown/highlighting parity remains |
| SQLite storage | Functional slice | Schema migration, session CRUD, ordering, filtering, stats, and codec tests |
| Server/RPC | Functional slice | Supervisor lifecycle, Unix socket request/response, streaming events, persistence, and piped EOF half-close tests |

## Verification snapshot

Verified on July 23, 2026 against source commit
`9b3a2059171bcc74ad9d2cadeea6d186776cf2db`:

- `./gradlew clean test installDist`: passed, 149 tests, 0 failures, 0 errors,
  and 0 skipped.
- `./migration/oracle/compare-cli-help.sh`: passed with byte-for-byte CLI help
  parity.
- `./migration/oracle/compare-provider-payloads.sh`: passed with exact normalized
  JSON parity for OpenAI Chat Completions, OpenAI Responses, Azure OpenAI
  Responses, Anthropic Messages, Google Generative AI, Google Vertex AI,
  Mistral Conversations, and Amazon Bedrock ConverseStream base and reasoning
  requests, including Azure deployment names, Vertex thinking controls,
  Mistral reasoning/cache controls, and Bedrock adaptive/fixed thinking,
  including `streamSimple` thinking-budget reservation.
- `./migration/oracle/compare-provider-stream-events.sh`: passed with normalized
  public transcript parity for event ordering, text/thinking/tool deltas and
  endings, terminal messages, usage, stop reasons, and replay signatures across
  the same provider protocols, including Vertex request-path/API-key parity,
  Bedrock SDK client/final-request parity, plus independent Cloudflare
  auth-resolution and final-request parity across Workers AI and all three AI
  Gateway delegates.
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
- `./migration/oracle/compare-coding-message-projection.sh`: passed with
  normalized JSON parity for ordinary messages, custom messages, branch and
  compaction summaries, bash formatting, and `excludeFromContext` filtering.
- `./migration/oracle/compare-session-jsonl.sh`: passed with normalized JSON
  parity for current, v1, and v2 sessions, including append-only rewrite,
  migrations, branches, compaction context, model/thinking state, and explicit
  empty-leaf behavior.
- `./migration/audit-migration.sh sync`: passed for every target-package commit
  between the original baseline and
  `24bace27cf308c89707cf8005b4795d873e23f17`.
- The July 23 incremental sync adds JSON-schema and grammar constrained
  sampling, strict-tool negotiation across supported providers, OpenAI custom
  tool streaming, abortable provider retry backoff, explicit cache-write
  suppression, bracketed model-ID coverage, and RPC bash output update events.
- Installed `pi` PTY smoke: entered interactive mode, rendered `/help`, and
  preserved `openrouter/moonshotai/kimi-k2.6` through `/model`, then exited
  normally through `/exit`.
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
- The installed server exposed all 109 Amazon Bedrock models and 1,074 models
  total. State read-back preserved
  `amazon-bedrock/us.anthropic.claude-opus-4-8`, API
  `bedrock-converse-stream`, and thinking level `high`.
- Server state read-back preserved the slash-containing model ID
  `moonshotai/kimi-k2.6` and thinking level `high`.
- Piped `rpc-stream` emitted `rpc_ready` then `response` and exited with status
  0 after stdin EOF.
- The pinned TypeScript source worktree remained clean.
- A detached source snapshot at
  `24bace27cf308c89707cf8005b4795d873e23f17` passed
  `npm ci --ignore-scripts`, `npm run hydrate:model-data`,
  `npm run build:offline`, and the full `npm test` workspace suite with an
  isolated home directory. The tracked source checkout remains clean; its
  checked-in schema-v2 model data requires hydration because current source
  code expects schema 3.

## Remaining major gaps

- Implement the remaining provider protocols and authentication wrappers:
  OpenAI Codex and GitHub Copilot/OAuth.
- Extend request/stream parity as the remaining provider protocols land, and add
  opt-in live provider smoke tests.
- Port extensions, skills, prompt templates, themes, package management,
  persisted project trust, resource reload, and related CLI commands.
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
