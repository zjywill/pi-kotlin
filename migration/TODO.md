# Migration TODO

Last reviewed: July 29, 2026

## Completion gate

- Source repository: `/Users/junyizhang/Git/pi`
- Reviewed source commit: `027a5847901b5dde30270abaa1041046cd2b4b55`
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
- [x] Upstream coding-agent synchronization through `027a584`
  - [x] Preserve package and extension metadata across resource reloads
  - [x] Route RPC `user_bash` through extension direct-result interception
  - [x] Track and cancel concurrent user bash executions independently
  - [x] Remove partial git checkouts after clone or dependency failures
  - [x] Detach stale Agent subscriptions during session replacement

The latest implementation stage synchronized extension scoped-model contexts,
resource metadata reloads, RPC `user_bash` direct-result routing, concurrent
bash cancellation, failed git-install cleanup, and session replacement
subscription ownership. `./gradlew clean test installDist` passes with 331
tests and zero failures, errors, or skips; all 17 deterministic migration
oracles pass. Installed `pi` and `pi-server` process smokes pass. The sync audit
passes through `027a584`; the full audit now reaches the inventory and reports
the seven partial areas listed below.

## Remaining

### 1. Upstream synchronization

- [x] Review and classify every commit in
      `cee5ff7520d8828bed9955ef00419e995d1f91e0..027a5847901b5dde30270abaa1041046cd2b4b55`
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

- [ ] Bidirectional extension dialogs
  - [ ] Make the Node JSONL reader accept `ui_response` messages while an
        extension invocation is awaiting a result
  - [ ] Emit intermediate `ui_request` messages with stable request IDs
  - [ ] Make `ExtensionHost.request()` process intermediate UI messages and
        continue waiting for the original invocation response
  - [ ] Implement interactive responses for `select`, `confirm`, and `input`
  - [ ] Implement RPC `extension_ui_response` routing without serial-reader
        deadlocks
  - [ ] Define cancellation, timeout, EOF, shutdown, and startup behavior
  - [ ] Compare awaited results with the TypeScript extension runtime
- [ ] jiti-complete TypeScript transpilation and module-loading compatibility
- [ ] Function-based custom provider streaming and OAuth callbacks
- [ ] Extension shortcuts
- [ ] Message and session-entry renderers
- [ ] Unsolicited background registration updates
- [ ] Function-valued `user_bash` `BashOperations` over the JSON extension
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
- [ ] Extension UI request/response support over JSONL and server streams

## Next stage

Return to bidirectional extension dialogs and the extension-host callback
protocol. The next coherent slice should carry interactive UI requests and
function-valued `user_bash` operations over JSONL with stable IDs, streaming
updates, cancellation, timeout, EOF, and shutdown behavior.

Required evidence for the completed synchronization stage:

- [x] Focused tests for base and TUI `ctx.scopedModels`
- [x] Resource reload tests that retain source metadata
- [x] RPC and concurrent bash cancellation tests
- [x] Failed git-install cleanup tests
- [x] Session replacement subscription regression test
- [x] `./gradlew clean test installDist`
- [x] All 17 deterministic migration oracles
- [x] Installed CLI and server smoke tests
- [x] `./migration/audit-migration.sh sync`
- [x] `./migration/audit-migration.sh full` with the remaining partial areas
      reported exactly
