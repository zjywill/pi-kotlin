# Migration TODO

Last reviewed: July 29, 2026

## Completion gate

- Source repository: `/Users/junyizhang/Git/pi`
- Reviewed source commit: `cee5ff7520d8828bed9955ef00419e995d1f91e0`
- Current source HEAD pending full review:
  `027a5847901b5dde30270abaa1041046cd2b4b55`
- Target repository: `/Users/junyizhang/Git/pi-kotlin`
- The migration is complete only when:

  ```bash
  ./migration/audit-migration.sh full
  ```

  exits successfully and every judge in `migration/RULEBOOK.md` passes against
  the same source commit.

## Completed

- [x] AI message and stream contracts
- [x] Built-in model catalog and remote catalog refresh
- [x] Provider protocols and OAuth flows covered by the migration oracles
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

The latest implementation stage synchronized upstream credential printing,
OAuth minimum-validity refresh behavior, and OpenRouter manual redirect login.
`./gradlew clean test installDist` and 16 deterministic migration oracles pass.
The GitHub Copilot oracle is currently blocked before comparison by a
`models.dev` connection timeout while hydrating the TypeScript source archive.
Both migration audits currently stop at source drift because the new upstream
range has not yet been fully reviewed and entered in the synchronization
manifests.

## Remaining

### 1. Upstream synchronization

- [ ] Review and classify every commit in
      `cee5ff7520d8828bed9955ef00419e995d1f91e0..027a5847901b5dde30270abaa1041046cd2b4b55`
- [x] OAuth five-minute refresh window and credential print commands
- [x] OpenRouter manual redirect URL fallback
- [ ] Pending stop reason while streaming
- [ ] GitHub Copilot Claude Opus 5 metadata overrides
- [ ] Configured Bedrock profile precedence over ambient AWS keys
- [ ] Z.AI `max_tokens` request field
- [ ] Per-request fetch injection
- [ ] Extension `ctx.scopedModels` in base and TUI contexts
- [ ] Preserve resource metadata after extension reload
- [ ] Route RPC bash through `user_bash`
- [ ] Concurrent bash cancellation
- [ ] Failed git-install cleanup
- [ ] Session replacement subscription fix
- [ ] Classify eval, documentation, test-only, and known-TUI-gap commits
- [ ] Update `migration/upstream-sync.tsv` only after the whole range is
      classified
- [ ] Advance `migration/sync-state.tsv` only after all required ports and
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

Finish the upstream synchronization range before returning to bidirectional
extension dialogs. The next coherent implementation slice is the AI-provider
batch: pending stream stop reasons, GitHub Copilot Claude Opus 5 metadata,
Bedrock profile precedence, Z.AI `max_tokens`, and per-request fetch injection.

Required evidence for that stage:

- [ ] Focused tests for pending-to-terminal stream state transitions
- [ ] Provider payload coverage for Bedrock and Z.AI changes
- [ ] Per-request transport injection tests across supported HTTP providers
- [ ] GitHub Copilot metadata assertion for Claude Opus 5
- [ ] `./gradlew clean test installDist`
- [ ] All deterministic migration oracles
- [ ] GitHub Copilot oracle after `models.dev` hydration is available or made
      deterministic without weakening its catalog comparison
- [ ] `./migration/audit-migration.sh sync`
- [ ] `./migration/audit-migration.sh full` with the remaining partial areas
      reported exactly
