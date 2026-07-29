# Migration TODO

Last reviewed: July 29, 2026

## Completion gate

- Source repository: `/Users/junyizhang/Git/pi`
- Reviewed source commit: `d7b02636a0c7e8e615d0cff70679d18d2ff59573`
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
- [x] Themes and resource composition
  - [x] Upstream-compatible theme JSON validation and variable resolution
  - [x] Built-in dark/light themes and automatic terminal appearance selection
  - [x] Truecolor and 256-color ANSI rendering
  - [x] Project/user/package/extension first-wins composition and diagnostics
  - [x] Project-over-user active-theme settings
  - [x] Named and in-memory extension theme switching
  - [x] Persistent header/widget/footer rerendering after theme changes
- [x] Upstream coding-agent synchronization through `d7b02636`
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

The latest implementation stage migrates server process isolation and recovery
through `d7b02636`. Each Kotlin server instance now runs an independent
`pi --mode rpc` child process with correlated requests, event fan-out,
extension UI response routing, stderr/exit propagation, pending-request
rejection, and persistent error-state handling. Restart recovery matches
upstream for `starting`, `online`, `stopping`, `stopped`, and `error` records.
`./gradlew clean test installDist` passes with 380 tests, all 27 deterministic
migration oracles pass, and installed crash/restart smoke verifies that child
failure does not terminate the server. The full audit remains nonzero on the
five partial areas listed below.

## Remaining

### 1. Upstream synchronization

- [x] Review and classify every commit in
      `4f0437e2d58d651dd934119ecabea2893975f62f..d7b02636a0c7e8e615d0cff70679d18d2ff59573`
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

### 2. CLI argument and print modes

- [ ] API-key login workflow
- [ ] Self-update workflow
- [ ] Remaining configuration workflows
- [ ] Implement and verify the parsed flags that still have no runtime effect

### 3. Package management

- [ ] Config selector
- [ ] Self-update integration
- [ ] Legacy global npm package lookup
- [ ] Available-update checks
- [ ] Remaining git/npm recovery and error paths

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
- [ ] Remaining interactive extension integration
  - [ ] Custom editor replacement parity
  - [ ] Raw terminal input listeners
  - [ ] Autocomplete provider composition
  - [ ] Overlay positioning, visibility handles, and full-screen placement
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

- [ ] Full-screen component model
- [ ] Overlays and selectors
- [ ] Upstream-compatible editor and custom editor replacement behavior
- [ ] Raw terminal input and autocomplete composition
- [ ] Transcript and rendering parity at multiple terminal widths

### 7. HTML export

- [x] Theme parity
- [x] Markdown rendering parity
- [x] Syntax highlighting parity

### 8. RPC and server

- [x] Process recovery
- [ ] Full RPC command parity
- [ ] Full event parity
- [x] Extension UI request/response support over JSONL and server streams

## Next stage

Complete full RPC command and event parity, then continue the remaining
interactive extension/full-screen terminal and CLI/package gaps as separately
audited slices.

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
- [x] `./migration/audit-migration.sh full` still identifies exactly five
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
