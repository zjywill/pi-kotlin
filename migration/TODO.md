# Migration TODO

Last reviewed: July 27, 2026

## Completion gate

- Source repository: `/Users/junyizhang/Git/pi`
- Reviewed source commit: `cee5ff7520d8828bed9955ef00419e995d1f91e0`
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

The latest completed stage is commit `8d26333`, which added interactive project
trust and request-bound dynamic extension registration refresh. Its validation
included focused runtime tests, `./gradlew clean test installDist`, all 17
migration oracles, the source synchronization audit, and an installed PTY
smoke test.

## Remaining

### 1. CLI argument and print modes

- [ ] API-key login workflow
- [ ] Self-update workflow
- [ ] Remaining configuration workflows
- [ ] Implement and verify the parsed flags that still have no runtime effect

### 2. Package management

- [ ] Config selector
- [ ] Self-update integration
- [ ] Legacy global npm package lookup
- [ ] Available-update checks
- [ ] Remaining git/npm recovery and error paths

### 3. Extension runtime

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

### 4. Themes and resource composition

- [ ] Parse upstream-compatible theme files
- [ ] Apply themes to terminal rendering
- [ ] Verify package and extension theme precedence

### 5. Interactive terminal

- [ ] Full-screen component model
- [ ] Overlays and selectors
- [ ] Upstream-compatible editor behavior
- [ ] Transcript and rendering parity at multiple terminal widths

### 6. HTML export

- [ ] Theme parity
- [ ] Markdown rendering parity
- [ ] Syntax highlighting parity

### 7. RPC and server

- [ ] Process recovery
- [ ] Full RPC command parity
- [ ] Full event parity
- [ ] Extension UI request/response support over JSONL and server streams

## Next stage

Resume with bidirectional extension dialogs. The current blocker is structural:
the Node host processes stdin serially, so an extension awaiting
`ctx.ui.select()`, `ctx.ui.confirm()`, or `ctx.ui.input()` cannot receive a
response until its own handler returns. The next implementation must make the
host protocol reentrant rather than returning placeholder values.

Required evidence for that stage:

- [ ] Node host test proving a command continues only after a UI response
- [ ] Interactive runtime tests for select, confirm, input, and cancellation
- [ ] RPC test proving `extension_ui_request` and `extension_ui_response`
      complete the original command
- [ ] TypeScript/Kotlin extension-runtime oracle comparison
- [ ] Installed PTY smoke using an extension that awaits all three dialogs
- [ ] `./gradlew clean test installDist`
- [ ] All migration oracles
- [ ] `./migration/audit-migration.sh sync`
- [ ] `./migration/audit-migration.sh full` with the remaining partial areas
      reported exactly
