# Migration TODO

Last reviewed: July 30, 2026

## Completion gate

- Source repository: `/Users/junyizhang/Git/pi`
- Reviewed source commit: `05558a79280a2f1356bd390a573aeb28726d26b5`
- Target repository: `/Users/junyizhang/Git/pi-kotlin`
- The migration is complete only when:

  ```bash
  ./migration/audit-migration.sh full
  ```

  exits successfully and every judge in `migration/RULEBOOK.md` passes against
  the same source commit.

## Completed

- [x] AI message and stream contracts
  - [x] Serialized `pending` stop reason for partial assistant messages
  - [x] Provider-specific errors when a stream ends without a terminal reason
  - [x] OpenAI Responses `final_answer` provisional stop and incomplete override
  - [x] Provider-native `rawStopReason` preservation across Anthropic, Bedrock,
        Google, Vertex, OpenAI Chat, OpenAI Responses, and Mistral
  - [x] OpenAI Chat function arguments take precedence when malformed deltas
        also contain an empty `custom` payload
- [x] Built-in model catalog and remote catalog refresh
  - [x] Qwen Token Plan `enable_thinking`, supported `reasoning_effort` maps,
        and unsupported-model exclusions
- [x] Provider protocols and OAuth flows covered by the migration oracles
  - [x] GitHub Copilot Claude Opus 5 minimal-thinking metadata
  - [x] Configured Bedrock profile precedence over ambient AWS keys
  - [x] Z.AI `max_tokens` catalog metadata and runtime fallback
  - [x] Per-request HTTP transport injection for supported adapters
  - [x] Explicit custom-transport rejection for Google and Google Vertex
  - [x] Refresh OAuth credentials with less than five minutes remaining
  - [x] Enforce caller-provided minimum OAuth validity after refresh
  - [x] OpenRouter loopback and manual redirect URL login
- [x] Credential export commands
  - [x] `pi auth print-api-key`
  - [x] `pi auth print-bearer-token`
  - [x] `--min-expiry` duration parsing and 30-minute bearer default
  - [x] Credential-only stdout and typed validation errors
- [x] Agent loop
- [x] RPC command and event runtime
  - [x] Complete command response shapes, state/settings mutation, queries, and
        session operations
  - [x] Prompt, steering, follow-up, queue, abort, retry, compaction, bash, and
        extension UI event ordering
  - [x] Persist aborted assistants and preserve session entry/tree leaf state
  - [x] Independent installed TypeScript/Kotlin native-provider grader
- [x] SQLite session storage
- [x] Context files, skills, and prompt templates
  - [x] File-backed `SYSTEM.md` and `APPEND_SYSTEM.md` source paths in startup
        Context output before `AGENTS.md`
- [x] Project trust
  - [x] Stored project and inherited parent decisions
  - [x] Global `defaultProjectTrust` behavior
  - [x] Trust, trust parent, trust for session, reject, and reject for session
  - [x] Trust resolution before project packages and extensions load
- [x] Extension runtime foundation
  - [x] Node 22 JavaScript and TypeScript host
  - [x] Common pi and TypeBox virtual imports
  - [x] Tools, commands, flags, lifecycle hooks, and tool hooks
  - [x] Project trust and resource discovery hooks
  - [x] Serializable provider registration
  - [x] Request-bound dynamic tool, command, flag, and provider refresh
  - [x] Fire-and-forget extension UI notifications and status actions
  - [x] Live `ctx.scopedModels` in print, RPC, and TUI contexts
  - [x] Awaited `select`, `confirm`, `input`, and `editor` dialogs in TUI and RPC
  - [x] Function-valued `user_bash` `BashOperations` with streaming and cancellation
  - [x] Function-valued provider `streamSimple` with live event forwarding
  - [x] Legacy extension OAuth `login`, `refreshToken`, `getApiKey`, and `modifyModels`
  - [x] Arbitrary top-level extension OAuth credential fields
  - [x] Direct native `Provider` registration with `getModels`, `filterModels`,
        `stream`, and `streamSimple`
  - [x] Native provider API-key `login`, `check`, and `resolve` callbacks with
        request-scoped `ctx.env()` and `ctx.fileExists()`
  - [x] Function-valued `refreshModels(context.store)` with provider-scoped
        `read`, `write`, and `delete`
  - [x] Official jiti 2.7 TypeScript transpilation and module loading
  - [x] Blocking TUI dialog interruption on timeout and `AbortSignal`
  - [x] Extension shortcut registration, conflict resolution, TUI dispatch,
        `/hotkeys`, and editor-buffer preservation
  - [x] Message and session-entry renderer registration, invocation, fallback,
        persistence, live output, and session replay
  - [x] Unsolicited background registration updates after the originating
        extension invocation has returned
  - [x] Provider cancellation and extension-host lifecycle cleanup
  - [x] Custom editor replacement, text read-back, paste, submit, and restore
  - [x] Raw terminal input consume, rewrite, unsubscribe, and editor delegation
  - [x] Dynamic autocomplete composition with Kotlin base-provider delegation
  - [x] Overlay sizing, positioning, visibility, focus, queued controls, and close
- [x] Full-screen interactive terminal
  - [x] Component-tree header, transcript, widgets, editor, footer, and overlays
  - [x] Upstream-compatible editor input, history, undo, kill/yank, and autocomplete
  - [x] Multi-width installed TypeScript/Kotlin PTY and terminal-screen parity
- [x] Themes and resource composition
  - [x] Upstream-compatible theme JSON validation and variable resolution
  - [x] Built-in dark/light themes and automatic terminal appearance selection
  - [x] Truecolor and 256-color ANSI rendering
  - [x] Project/user/package/extension first-wins composition and diagnostics
  - [x] Project-over-user active-theme settings
  - [x] Named and in-memory extension theme switching
  - [x] Persistent header/widget/footer rerendering after theme changes
- [x] Upstream synchronization through `05558a79`
  - [x] Preserve package and extension metadata across resource reloads
  - [x] Route RPC `user_bash` through extension direct-result interception
  - [x] Track and cancel concurrent user bash executions independently
  - [x] Remove partial git checkouts after clone or dependency failures
  - [x] Detach stale Agent subscriptions during session replacement
  - [x] Avoid duplicate context files in nested linked worktrees
  - [x] Accept nullable array schemas with `items`
  - [x] Classify the AgentHarness v2 design document as documentation-only
  - [x] Preserve raw provider stop reasons and generic provider-stop errors
  - [x] Port Qwen Token Plan reasoning controls
  - [x] Show system prompt file sources in startup Context output
  - [x] Preserve OpenAI Chat function arguments beside empty `custom` payloads
  - [x] Classify changelog, formatting-only TUI, release, and post-release commits
  - [x] Match the upstream `OpenCode Go` provider display name

The July 30 synchronization passes advance the completed migration through
`05558a79`. They port the OpenAI Chat malformed-delta fix and the `OpenCode Go`
provider display name while classifying release and repository-metadata changes
outside the runtime migration. The installed TypeScript and Kotlin runtimes
still match across every rulebook judge. The full
`./gradlew clean test installDist --max-workers=1` gate passes with 438 tests,
all 30 deterministic migration oracles pass, and the full migration audit is
complete.

## Completion checklist

### 1. Upstream synchronization

- [x] Review and classify every commit in
      `4f0437e2d58d651dd934119ecabea2893975f62f..d7b02636a0c7e8e615d0cff70679d18d2ff59573`
- [x] Review and classify every commit in
      `d7b02636a0c7e8e615d0cff70679d18d2ff59573..71efc6f0c1909874ec8c944637a9ae7fc0e2d508`
- [x] Review and classify every migration-package commit in
      `71efc6f0c1909874ec8c944637a9ae7fc0e2d508..05558a79280a2f1356bd390a573aeb28726d26b5`
- [x] OAuth five-minute refresh window and credential print commands
- [x] OpenRouter manual redirect URL fallback
- [x] Pending stop reason while streaming
- [x] GitHub Copilot Claude Opus 5 metadata overrides
- [x] Configured Bedrock profile precedence over ambient AWS keys
- [x] Z.AI `max_tokens` request field
- [x] Per-request fetch injection
- [x] Extension `ctx.scopedModels` in base and TUI contexts
- [x] Preserve resource metadata after extension reload
- [x] Route RPC bash through `user_bash` for direct replacement results
- [x] Concurrent bash cancellation
- [x] Failed git-install cleanup
- [x] Session replacement subscription fix
- [x] Classify eval, documentation, test-only, and known-TUI-gap commits
- [x] Update `migration/upstream-sync.tsv` only after the whole range is
      classified
- [x] Advance `migration/sync-state.tsv` only after all required ports and
      classifications are complete
- [x] Nested linked-worktree `AGENTS.md`/`CLAUDE.md` deduplication
- [x] Nullable array schema validation with `items`
- [x] Classify llama streaming usage, TUI image fallback, and contributor-only
      commits against their existing migration gaps
- [x] Raw Anthropic, Bedrock, Google, Vertex, OpenAI, and Mistral stop reasons
- [x] Qwen Token Plan reasoning controls and catalog metadata
- [x] Startup system/append prompt source display
- [x] OpenAI Chat function payload precedence over malformed empty `custom`
- [x] Documentation, formatting-only TUI, release, and changelog scaffolding
      classifications

### 2. CLI argument and print modes

- [x] API-key login workflow
- [x] Self-update workflow
- [x] Remaining configuration workflows
- [x] Implement and verify every parsed flag has a runtime effect

### 3. Package management

- [x] Config selector
- [x] Self-update integration
- [x] Legacy global npm package lookup
- [x] Available-update checks
- [x] Remaining git/npm recovery and error paths

### 4. Extension runtime

- [x] Bidirectional extension dialogs
  - [x] Make the Node JSONL reader accept `ui_response` messages while an
        extension invocation is awaiting a result
  - [x] Emit intermediate `ui_request` messages with stable request IDs
  - [x] Make `ExtensionHost.request()` process intermediate UI messages and
        continue waiting for the original invocation response
  - [x] Implement interactive responses for `select`, `confirm`, `input`, and
        `editor`
  - [x] Implement RPC `extension_ui_response` routing without serial-reader
        deadlocks
  - [x] Define RPC cancellation, timeout, EOF, shutdown, and startup behavior
  - [x] Compare awaited results with the TypeScript extension runtime
- [x] Interrupt a blocking TUI dialog when its extension timeout or
      `AbortSignal` fires
- [x] jiti-complete TypeScript transpilation and module-loading compatibility
- [x] Direct registration of a complete native `Provider`
- [x] Native provider API-key `login`, `check`, and `resolve` callbacks
- [x] Function-valued `refreshModels(context.store)`
- [x] Extension shortcuts
- [x] Message and session-entry renderers
- [x] Persistent extension component surfaces and focused custom UI
  - [x] String and component-factory widgets with above/below placement
  - [x] Custom header and footer factories
  - [x] Stable component IDs, terminal width, `requestRender()`, replace, clear,
        and dispose lifecycle
  - [x] Footer extension statuses, git branch read-back, and no-op branch
        listener compatibility
  - [x] Focused `ctx.ui.custom()` render/input/rerender/done loop
  - [x] Functional virtual key constants and basic `Editor`/`CustomEditor`
- [x] Remaining interactive extension integration
  - [x] Custom editor replacement parity
  - [x] Raw terminal input listeners
  - [x] Autocomplete provider composition
  - [x] Overlay positioning, visibility handles, and full-screen placement
- [x] Unsolicited background registration updates
- [x] Function-valued `user_bash` `BashOperations` over the JSON extension
      host, including streaming updates and cancellation

### 5. Themes and resource composition

- [x] Parse upstream-compatible theme files
- [x] Apply themes to terminal rendering
- [x] Verify package and extension theme precedence
- [x] Verify named and in-memory extension theme switching
- [x] Verify truecolor/256-color output and automatic light/dark selection

### 6. Interactive terminal

- [x] Full-screen component model
- [x] Overlays and selectors
- [x] Upstream-compatible editor and custom editor replacement behavior
- [x] Raw terminal input and autocomplete composition
- [x] Transcript and rendering parity at multiple terminal widths

### 7. HTML export

- [x] Theme parity
- [x] Markdown rendering parity
- [x] Syntax highlighting parity

### 8. RPC and server

- [x] Process recovery
- [x] Full RPC command parity
- [x] Full event parity
- [x] Extension UI request/response support over JSONL and server streams

## Follow-up

No migration gaps remain against source commit `05558a79`. Future work starts
only when the TypeScript source advances and a new synchronization range is
recorded.

Evidence for the latest July 30 upstream synchronization:

- [x] Source fast-forwarded from `71efc6f0` through `05558a79` with a clean
      TypeScript worktree
- [x] Contributor-approval metadata classified outside the migrated package
      paths
- [x] Kotlin provider registry and regression test expose `OpenCode Go`
- [x] Secret input is cleared before its mask is removed, and asynchronous TUI
      tests wait for the active input prompt before sending keys
- [x] `./gradlew clean test installDist --max-workers=1` with 438 tests and no
      failures, errors, or skips
- [x] All 30 deterministic migration oracles
- [x] `./migration/audit-migration.sh sync` through `05558a79`
- [x] `./migration/audit-migration.sh full`

Evidence for the earlier July 30 OpenAI tool-call synchronization:

- [x] Source model data hydration and `npm run build:offline` at `71efc6f0`
      with a clean TypeScript worktree
- [x] Upstream `openai-completions-tool-choice.test.ts`: 45 tests passed
- [x] Kotlin malformed empty-`custom` regression test passed
- [x] `./migration/oracle/compare-provider-stream-events.sh` includes the
      malformed function/custom payload and passes with zero diff
- [x] `./gradlew clean test installDist --max-workers=1` with 438 tests and no
      failures, errors, or skips
- [x] All 30 deterministic migration oracles
- [x] Installed `pi` native-provider print smoke
- [x] Installed `pi-server` lifecycle, 776-model offline catalog, direct RPC,
      `rpc-stream`, stop, and empty-list read-back
- [x] `./migration/audit-migration.sh sync` through `71efc6f0`
- [x] `./migration/audit-migration.sh full`

Evidence for the completed interactive extension and full-screen terminal stage:

- [x] Full-screen TUI is the default installed interactive console
- [x] Persistent extension header, widgets, footer, and transcript component tree
- [x] Custom editor set/read/paste/input/submit/restore behavior
- [x] Raw terminal consume/rewrite/unsubscribe behavior
- [x] Extension autocomplete wrapping and Kotlin base-provider delegation
- [x] Overlay width, top-right placement, focus/unfocus, hide/show, input, close,
      queued controls, and final screen coordinates
- [x] Exact slash-command argument submission while autocomplete is active
- [x] `./migration/oracle/compare-interactive-tui.sh` across TypeScript and
      Kotlin at 40, 80, and 120 columns
- [x] `./gradlew clean test installDist` with 437 tests and no failures, errors,
      or skips
- [x] All 30 deterministic migration oracles
- [x] `./migration/audit-migration.sh sync` through `d7b02636`
- [x] `./migration/audit-migration.sh full`

Evidence for the completed CLI and package-management stage:

- [x] Byte-identical installed help for `install`, `remove`, `update`, `list`,
      and `config`
- [x] Matching exit codes and stdout/stderr for package and `update --models`
      parsing errors
- [x] Matching local package install/list/remove output and `settings.json`
      mutations
- [x] Matching `--offline` native-provider print output
- [x] Installed TypeScript/Kotlin API-key PTYs persist the same owner-only
      `auth.json` without leaking the secret into transcripts
- [x] Installed TypeScript/Kotlin config-selector PTYs toggle the same global
      resource and persist identical settings
- [x] Source-checkout self-update with dirty-worktree refusal, upstream
      fast-forward, already-current handling, and forced reinstall
- [x] npm/pnpm legacy global lookup, available-update checks, scoped npm
      batching, targeted suggestions, and upstream-specific git recovery
- [x] Asynchronous online startup update checks report available packages above
      the active JLine prompt
- [x] `./migration/oracle/compare-cli-package-runtime.sh` with zero diff
- [x] `./gradlew clean test installDist` with 405 tests and no failures,
      errors, or skips
- [x] All 29 deterministic migration oracles
- [x] `./migration/audit-migration.sh sync` through `d7b02636`
- [x] `./migration/audit-migration.sh full` identifies exactly two remaining
      partial areas

Evidence for the completed RPC command/event parity stage:

- [x] Same direct native provider fixture loaded by installed TypeScript and
      Kotlin CLIs
- [x] Parse/unknown errors, state, model, thinking, settings, prompts, steering,
      follow-up, queues, abort, retry, bash, dialogs, compaction, entries/tree,
      fork/clone/switch/new-session, and HTML export compared
- [x] Full default-field message/event encoding and upstream-compatible text
      block payloads
- [x] Aborted assistant terminal lifecycle and session persistence
- [x] Compaction retry/settings/result parity and explicit `fromHook=false`
- [x] Stable session leaf IDs, last-assistant text, branch names, and initial
      model/thinking entries
- [x] Focused AgentLoop and RPC runtime tests
- [x] `./migration/oracle/compare-rpc-runtime.sh` with zero diff
- [x] `./gradlew clean test installDist` with 384 tests and no failures,
      errors, or skips
- [x] All 28 deterministic migration oracles
- [x] Installed `pi-server` spawn, status, direct `get_state`, streamed
      `get_state`, stop, and empty-list smoke
- [x] `./migration/audit-migration.sh sync` through `d7b02636`
- [x] `./migration/audit-migration.sh full` identifies exactly four remaining
      partial areas

Evidence for the completed server process recovery stage:

- [x] One independent `pi --mode rpc` child process per server instance
- [x] Correlated JSONL requests and responses with generated IDs
- [x] Event and extension UI request fan-out over `rpc-stream`
- [x] Extension UI responses routed without waiting for a command response
- [x] Child stderr and exit propagation with pending-request rejection
- [x] Unexpected child exit persists the instance as `error` while the server
      remains available
- [x] Server restart converts persisted `starting` and `online` instances to
      `stopped` while preserving other statuses and metadata
- [x] Independent TypeScript/Kotlin server recovery oracle
- [x] `./gradlew clean test installDist` with 380 tests and no failures,
      errors, or skips
- [x] All 27 deterministic migration oracles
- [x] Installed lifecycle, child crash, and server restart recovery smoke
- [x] `./migration/audit-migration.sh sync` through `d7b02636`
- [x] `./migration/audit-migration.sh full` now identifies exactly four
      partial areas

Evidence for the completed HTML export stage:

- [x] Exact upstream standalone HTML, CSS, JavaScript, `marked`, and
      `highlight.js` resources
- [x] Recursive theme variables, export colors, truecolor, and 256-color
      conversion
- [x] Session tree, branches, labels, filters, statistics, Markdown, syntax
      highlighting, tool output, and XSS-safe links
- [x] Extension `renderCall`/`renderResult` output in collapsed and expanded
      states
- [x] Upstream-compatible built-in `find` and `grep` tool rendering
- [x] `./gradlew clean test installDist` with 378 tests and no failures,
      errors, or skips
- [x] All 26 deterministic migration oracles, including the three HTML export
      graders
- [x] Installed `pi --export` byte-identical to upstream with SHA-256
      `3613ceef433cc31040a5413427db35c4fd5b1d480aab0988b1613f634809bcb6`
- [x] Playwright smoke for Markdown, syntax highlighting, theme colors, session
      navigation, tool output, allowed HTTPS links, rejected `javascript:`
      links, and zero console/page errors
- [x] `./migration/audit-migration.sh sync` through `d7b02636`
- [x] `./migration/audit-migration.sh full` confirms exactly five remaining
      partial areas

Evidence for the completed theme and upstream synchronization stage:

- [x] Upstream-compatible theme parsing, validation, variables, and fallbacks
- [x] Built-in and custom light/dark selection with truecolor and 256-color ANSI
- [x] Project/user/package/extension theme precedence and collision diagnostics
- [x] Named and in-memory extension theme switching with persistent rerendering
- [x] Provider-native raw stop reasons and generic provider-stop errors
- [x] Qwen Token Plan reasoning controls and catalog metadata
- [x] Startup Context paths for `SYSTEM.md`, `APPEND_SYSTEM.md`, and `AGENTS.md`
- [x] `./gradlew clean test installDist` with 376 tests and no failures,
      errors, or skips
- [x] All 23 deterministic migration oracles
- [x] Installed JLine PTY custom-theme and startup Context smoke
- [x] Installed `pi-server` lifecycle and state smoke
- [x] `./migration/audit-migration.sh sync` through `d7b02636`
- [x] `./migration/audit-migration.sh full` confirms exactly six remaining
      partial areas

Evidence for the completed extension renderer stage:

- [x] Stable message and entry renderer IDs with first-extension-wins selection
- [x] Upstream-compatible message and entry payload shapes
- [x] `expanded`, `outputPad`, and terminal-width propagation
- [x] Actual JavaScript `component.render(width)` execution
- [x] Functional `Container`, `Box`, `Text`, `Spacer`, `TruncatedText`, and
      text-oriented `Markdown` renderer bridge
- [x] Message undefined/throw fallback and `display=false` hiding
- [x] Entry undefined hiding and explicit renderer-error output
- [x] Immediate rendering for live `sendMessage()` and `appendEntry()` actions
- [x] Startup action rendering without duplicate transcript output
- [x] Persisted `custom_message` replay and legacy custom-message compatibility
- [x] Independent TypeScript/Kotlin renderer oracle with ordered shared fixtures
- [x] `pi-coding-agent` with 141 tests and no failures, errors, or skips
- [x] `./gradlew clean test installDist` with 364 tests and no failures,
      errors, or skips
- [x] All 20 deterministic migration oracles
- [x] Installed JLine PTY passes 72 columns to both renderer kinds, hides the
      non-display message, and exits normally
- [x] Dumb-terminal installed PTY falls back to 80 columns
- [x] `./migration/audit-migration.sh sync` through `4f0437e2`
- [x] `./migration/audit-migration.sh full` confirms exactly seven remaining
      partial areas

Evidence for the completed custom extension UI stage:

- [x] String and factory widgets plus custom header/footer component surfaces
- [x] Stable component IDs and terminal-width propagation
- [x] Component `requestRender()` updates and replace/clear/dispose lifecycle
- [x] Footer status and git-branch data provider subset
- [x] Focused `ctx.ui.custom()` input loop with up/down/enter/escape mappings
- [x] Functional virtual `Key`, basic `Editor`, and `CustomEditor` inheritance
- [x] Startup surface collection without duplicate header/widget/footer output
- [x] RPC/print component factories and custom UI retain upstream no-op behavior
- [x] Independent TypeScript/Kotlin custom UI oracle with shared fixtures
- [x] `pi-coding-agent` with 146 tests and no failures, errors, or skips
- [x] `./gradlew clean test installDist` with 369 tests and no failures,
      errors, or skips
- [x] All 21 deterministic migration oracles
- [x] Installed JLine PTY passes 67 columns to all surface/custom/editor frames,
      selects `beta`, submits `Ada`, restores the prompt, and exits normally
- [x] `./migration/audit-migration.sh sync` through `4f0437e2`
- [x] `./migration/audit-migration.sh full` confirms exactly seven remaining
      partial areas
