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
| Model catalog | Functional slice | Pinned schema-v2 manifest and 37 provider files verify by SHA-256; 1,108 model records load, with 821 models exposed across 29 providers whose protocols and authentication are currently executable |
| Provider HTTP implementations | Partial | Google Generative AI, Anthropic Messages, OpenAI Chat Completions, and OpenAI Responses request/stream fixture tests, independent base/reasoning payload and public stream-transcript parity, and multi-protocol catalog dispatch |
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

- `./gradlew clean test installDist`: passed, 107 tests, 0 failures, 0 errors,
  and 0 skipped.
- `./migration/oracle/compare-cli-help.sh`: passed with byte-for-byte CLI help
  parity.
- `./migration/oracle/compare-provider-payloads.sh`: passed with exact normalized
  JSON parity for OpenAI Chat Completions, OpenAI Responses, Anthropic Messages,
  and Google Generative AI base and reasoning requests.
- `./migration/oracle/compare-provider-stream-events.sh`: passed with normalized
  public transcript parity for event ordering, text/thinking/tool deltas and
  endings, terminal messages, usage, stop reasons, and replay signatures across
  the same four provider protocols.
- `./migration/oracle/compare-coding-message-projection.sh`: passed with
  normalized JSON parity for ordinary messages, custom messages, branch and
  compaction summaries, bash formatting, and `excludeFromContext` filtering.
- `./migration/oracle/compare-session-jsonl.sh`: passed with normalized JSON
  parity for current, v1, and v2 sessions, including append-only rewrite,
  migrations, branches, compaction context, model/thinking state, and explicit
  empty-leaf behavior.
- Installed `pi` PTY smoke: entered interactive mode, rendered `/help`, and
  preserved `openrouter/moonshotai/kimi-k2.6` through `/model`, then exited
  normally through `/exit`.
- Installed `pi-server` process smoke: `serve`, `spawn`, `list`, `status`,
  `rpc set_model`, `rpc set_thinking_level`, `rpc-stream get_state`, and `stop`
  all succeeded.
- Server state read-back preserved the slash-containing model ID
  `moonshotai/kimi-k2.6` and thinking level `high`.
- Piped `rpc-stream` emitted `rpc_ready` then `response` and exited with status
  0 after stdin EOF.
- The pinned TypeScript source worktree remained clean.

## Remaining major gaps

- Implement the remaining provider protocols and authentication wrappers:
  Bedrock, Azure OpenAI, Google Vertex, Mistral, OpenAI Codex, Cloudflare, and
  GitHub Copilot/OAuth.
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
