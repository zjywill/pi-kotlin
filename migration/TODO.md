# Migration TODO

Last reviewed: July 29, 2026

## Completion gate

- Source repository: `/Users/junyizhang/Git/pi`
- Reviewed source commit: `4f0437e2d58d651dd934119ecabea2893975f62f`
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
- [x] Built-in model catalog and remote catalog refresh
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
- [x] Upstream coding-agent synchronization through `4f0437e2`
  - [x] Preserve package and extension metadata across resource reloads
  - [x] Route RPC `user_bash` through extension direct-result interception
  - [x] Track and cancel concurrent user bash executions independently
  - [x] Remove partial git checkouts after clone or dependency failures
  - [x] Detach stale Agent subscriptions during session replacement
  - [x] Avoid duplicate context files in nested linked worktrees
  - [x] Accept nullable array schemas with `items`
  - [x] Classify the AgentHarness v2 design document as documentation-only

The latest implementation stage ports extension message and session-entry
renderers end to end. The Node host exports stable renderer IDs, executes the
first matching extension renderer, passes upstream-shaped payloads and render
options, and calls the returned JavaScript component's `render(width)`. Kotlin
persists custom messages in `custom_message` entries, still accepts legacy
message entries, renders startup and live extension actions without duplicate
startup output, replays the current branch after resume, hides `display=false`
messages, preserves message default fallback, and distinguishes hidden entry
results from explicit entry renderer failures. `./gradlew clean test
installDist` passes with 364 tests and all 20 deterministic migration oracles
pass. The sync audit reaches `4f0437e2`; the full audit remains nonzero on the
seven partial areas listed below.

## Remaining

### 1. Upstream synchronization

- [x] Review and classify every commit in
      `027a5847901b5dde30270abaa1041046cd2b4b55..4f0437e2d58d651dd934119ecabea2893975f62f`
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
- [ ] Custom extension UI components
- [x] Unsolicited background registration updates
- [x] Function-valued `user_bash` `BashOperations` over the JSON extension
      host, including streaming updates and cancellation

### 5. Themes and resource composition

- [ ] Parse upstream-compatible theme files
- [ ] Apply themes to terminal rendering
- [ ] Verify package and extension theme precedence

### 6. Interactive terminal

- [ ] Full-screen component model
- [ ] Overlays and selectors
- [ ] Upstream-compatible editor behavior
- [ ] Transcript and rendering parity at multiple terminal widths

### 7. HTML export

- [ ] Theme parity
- [ ] Markdown rendering parity
- [ ] Syntax highlighting parity

### 8. RPC and server

- [ ] Process recovery
- [ ] Full RPC command parity
- [ ] Full event parity
- [x] Extension UI request/response support over JSONL and server streams

## Next stage

Implement custom extension UI components while keeping theme parsing, the
full-screen terminal model, and the other global partial areas as separately
audited slices.

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
