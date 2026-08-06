# Migration rulebook

## Scope

The source of truth is `/Users/junyizhang/Git/pi`. The first immutable baseline
is `9b3a2059171bcc74ad9d2cadeea6d186776cf2db`; later source commits are reviewed
through the commit recorded in `sync-state.tsv`.

## Translation decisions

1. Preserve public behavior, not TypeScript file layout.
2. Kotlin coroutines replace promises, async iterators, and abortable waits.
3. Kotlin serialization owns JSON and JSONL wire contracts.
4. Java `HttpClient` replaces generic HTTP SDKs when request and stream
   protocols are stable; provider SDKs are retained where signing or binary
   event streams make a direct implementation riskier.
5. Node/Bun packaging and subprocess internals may use JVM-native Gradle and
   process APIs, but CLI flags, exit codes, RPC envelopes, and persisted data
   remain compatible.
6. Optional JavaScript fields map to nullable Kotlin properties. Omitted fields
   must remain omitted when a provider rejects unknown values.
7. Provider compatibility flags are data, not provider-name conditionals, except
   when upstream itself derives defaults from provider identity or URL.
8. A compiling stub is `missing`, not `complete`.
9. A source-only dependency or packaging fix is `not-applicable` only when the
   corresponding behavior does not exist on the JVM path.
10. An upstream change inside an already missing subsystem is
    `covered-by-known-gap`; it does not make the overall migration complete.

## Required judges

- `./gradlew clean test installDist`
- `./migration/oracle/compare-anthropic-oauth.sh`
- `./migration/oracle/compare-cli-help.sh`
- `./migration/oracle/compare-cli-package-runtime.sh`
- `./migration/oracle/compare-coding-message-projection.sh`
- `./migration/oracle/compare-extension-custom-ui.sh`
- `./migration/oracle/compare-extension-jiti-compat.sh`
- `./migration/oracle/compare-extension-renderers.sh`
- `./migration/oracle/compare-extension-runtime.sh`
- `./migration/oracle/compare-extension-shortcuts.sh`
- `./migration/oracle/compare-extension-theme.sh`
- `./migration/oracle/compare-github-copilot.sh`
- `./migration/oracle/compare-html-builtin-tool-renderer.sh`
- `./migration/oracle/compare-html-export.sh`
- `./migration/oracle/compare-html-tool-renderer.sh`
- `./migration/oracle/compare-interactive-tui.sh`
- `./migration/oracle/compare-kimi-coding-oauth.sh`
- `./migration/oracle/compare-model-catalog-runtime.sh`
- `./migration/oracle/compare-openai-codex-oauth.sh`
- `./migration/oracle/compare-openrouter-images.sh`
- `./migration/oracle/compare-openrouter-oauth.sh`
- `./migration/oracle/compare-package-resources.sh`
- `./migration/oracle/compare-provider-payloads.sh`
- `./migration/oracle/compare-provider-stream-events.sh`
- `./migration/oracle/compare-protocol-runtime.sh`
- `./migration/oracle/compare-radius.sh`
- `./migration/oracle/compare-resource-loading.sh`
- `./migration/oracle/compare-rpc-runtime.sh`
- `./migration/oracle/compare-session-jsonl.sh`
- `./migration/oracle/compare-terminal-image-runtime.sh`
- `./migration/oracle/compare-theme-runtime.sh`
- `./migration/oracle/compare-xai-oauth.sh`
- Installed CLI smoke tests for process-level changes

## Done gate

The migration is complete only when:

1. `./migration/audit-migration.sh full` passes.
2. Every required judge passes against the same recorded source commit.
3. The original TypeScript suite has no unexplained inherited failures.
4. No `TODO(port)`, `BUG(port)`, or `PERF(port)` marker remains without an
   accepted exception in `inventory.tsv`.
