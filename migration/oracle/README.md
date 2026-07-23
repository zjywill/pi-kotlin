# Compatibility oracle

The oracle compares public outputs produced by the pinned TypeScript checkout
and the Kotlin migration. It is intentionally outside the implementation
modules.

Prerequisites:

```bash
cd /Users/junyizhang/Git/pi
npm ci --ignore-scripts
```

The session JSONL comparison builds the pinned TypeScript `pi-ai` and
`pi-agent-core` packages on demand when their ignored `dist/` directories are
missing.

Run the available comparisons from the Kotlin repository:

```bash
./migration/oracle/compare-cli-help.sh
./migration/oracle/compare-coding-message-projection.sh
./migration/oracle/compare-provider-payloads.sh
./migration/oracle/compare-provider-stream-events.sh
./migration/oracle/compare-session-jsonl.sh
```

Normalizers may remove absolute paths, timestamps, and version strings. They
must not remove flags, event ordering, message content, error categories, or
serialized fields.

The provider stream comparison projects the documented public event transcript:
event type, index, delta/end content, tool calls, and terminal messages. It does
not compare `partial` object snapshots because the TypeScript implementation
queues mutable references whose observed historical state depends on consumer
timing.

Provider payload and stream comparisons cover OpenAI Chat Completions, OpenAI
Responses, Azure OpenAI Responses, Anthropic Messages, and Google Generative AI.
Azure reuses the OpenAI Responses stream fixture while independently exercising
its deployment-name payload contract plus the actual request URL, API-version
query, API-key header, and absence of Bearer authorization.
