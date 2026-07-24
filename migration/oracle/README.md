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
./migration/oracle/compare-model-catalog-runtime.sh
./migration/oracle/compare-provider-payloads.sh
./migration/oracle/compare-provider-stream-events.sh
./migration/oracle/compare-session-jsonl.sh
```

Normalizers may remove absolute paths, timestamps, and version strings. They
must not remove flags, event ordering, message content, error categories, or
serialized fields.

The model catalog runtime comparison covers bundled-versus-remote timestamp
selection, a newer persisted overlay restored without network access, and
404/501-style unavailable catalog fallback.

The provider stream comparison projects the documented public event transcript:
event type, index, delta/end content, tool calls, and terminal messages. It does
not compare `partial` object snapshots because the TypeScript implementation
queues mutable references whose observed historical state depends on consumer
timing.

Provider payload and stream comparisons cover OpenAI Chat Completions, OpenAI
Responses, Azure OpenAI Responses, Anthropic Messages, Google Generative AI,
Google Vertex AI, Mistral Conversations, Amazon Bedrock ConverseStream, and
OpenAI Codex Responses over SSE and WebSocket.
Vertex independently compares SDK
parameters, public stream events, the collection-scoped request URL, and
`x-goog-api-key`; Kotlin unit fixtures additionally cover ADC bearer tokens and
regional endpoint resolution. Cloudflare Workers AI and AI Gateway reuse the
shared protocol fixtures while independently exercising provider auth
resolution, account/gateway URL materialization, Bearer versus
`cf-aig-authorization`, upstream BYOK header preservation, and
session-affinity headers. Azure reuses the OpenAI Responses stream fixture
while independently exercising its deployment-name payload contract plus the
actual request URL, API-version query, API-key header, and absence of Bearer
authorization.

Mistral Conversations adds independent payload cases for reasoning effort,
prompt mode, and prompt caching. Its stream fixture also compares the actual
chat-completions path, Bearer authorization, and `x-affinity` header.

Bedrock adds independent payload cases for base, adaptive-thinking, and
fixed-budget thinking requests. Its SDK-boundary fixture compares Bearer auth,
region and endpoint selection, reserved-header filtering, the final Converse
request, and public reasoning/text/tool/usage stream events without requiring
live AWS credentials.

OpenAI Codex adds independent payload cases for the base request, reasoning,
service tiers, verbosity, required tool choice, cache affinity, and
`streamSimple` thinking-level clamping. Its SSE fixture compares zstd request
compression, ChatGPT account and session headers, the final
`/codex/responses` request, terminal `response.done` handling, and the shared
Responses public event transcript. Its injected WebSocket fixture independently
compares the actual handshake headers, `response.create` frame, and the same
public event transcript. Focused Kotlin tests cover connection reuse,
continuation deltas, idle and age expiry, retry boundaries, pre-output SSE
fallback, post-output failure, and sticky session fallback. Interactive OAuth
login remains a separate migration gap.
