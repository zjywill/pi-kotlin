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
./migration/oracle/compare-anthropic-oauth.sh
./migration/oracle/compare-cli-help.sh
./migration/oracle/compare-coding-message-projection.sh
./migration/oracle/compare-extension-runtime.sh
./migration/oracle/compare-github-copilot.sh
./migration/oracle/compare-kimi-coding-oauth.sh
./migration/oracle/compare-model-catalog-runtime.sh
./migration/oracle/compare-openai-codex-oauth.sh
./migration/oracle/compare-openrouter-images.sh
./migration/oracle/compare-openrouter-oauth.sh
./migration/oracle/compare-package-resources.sh
./migration/oracle/compare-provider-payloads.sh
./migration/oracle/compare-provider-stream-events.sh
./migration/oracle/compare-radius.sh
./migration/oracle/compare-resource-loading.sh
./migration/oracle/compare-session-jsonl.sh
./migration/oracle/compare-xai-oauth.sh
```

Normalizers may remove absolute paths, timestamps, and version strings. They
must not remove flags, event ordering, message content, error categories, or
serialized fields.

The Anthropic OAuth comparison covers browser/manual PKCE authorization,
authorization-code and refresh-token JSON requests, refresh-token rotation,
five-minute expiry skew, Claude Code Bearer authentication, content negotiation
and identity headers, the mandatory system identity, and canonical tool-name
mapping in requests and responses.

The OpenRouter OAuth comparison covers the random one-shot loopback callback,
PKCE authorization, permanent API-key exchange, callback response headers,
no-op refresh, request-auth derivation, and a real local OpenRouter-compatible
provider request using the stored OAuth credential.

The OpenRouter Images comparison covers the exact 40-model generated catalog
and checksum, final `/chat/completions` URL/headers/payload, text and data-URL
image parsing, invalid image filtering, response ID, cache-aware usage and
cost, payload/response callbacks, retry behavior, HTTP error-body passthrough,
missing-key errors, stored OAuth consumption, and explicit API-key precedence.

The xAI OAuth comparison covers device authorization, wait-before-first-poll,
pending and server-directed slow-down timing, refresh-token preservation,
default token lifetime, request-auth derivation, and real local Chat
Completions and Responses SSE requests using the stored OAuth credential.

The Kimi Code OAuth comparison covers device authorization,
wait-before-first-poll, pending and server-directed slow-down timing,
exponential refresh retries, unauthorized refresh short-circuiting,
Bearer-header derivation, and a real local Anthropic Messages SSE request using
the stored OAuth credential.

The Radius comparison covers dynamic `/v1/oauth` discovery, browser PKCE and
device authorization, pending and server-directed slow-down timing, refresh
credentials with a 60-second expiry skew, authenticated `/v1/config` model
loading, cached and legacy credential catalog restoration, and a real local
`pi-messages` SSE request using the stored OAuth credential. It compares the
final request, public text/tool/terminal events, usage, response ID, and Radius
rewrite diagnostics.

The resource-loading comparison covers YAML frontmatter, quoted prompt
arguments, positional/default/slice substitution, project-over-user collision
precedence, `.pi` and `.agents` skill discovery, manual-only skills, prompt
templates, source metadata, context/system/append prompts, trusted versus
untrusted project resources, and inherited persisted trust decisions.

The package-resources comparison covers user/project settings, local package
manifests, package filters, enabled/disabled resource state, source metadata,
project precedence, top-level overrides, configured-package listing, and
scope-relative settings mutation. It also compares documented npm/git/local
source parsing and user/project/temporary managed install paths.

The extension-runtime comparison loads the same TypeScript fixture through the
upstream jiti loader and the Kotlin distribution's Node JSONL host. It compares
common pi/TypeBox imports, tool schemas and execution updates, commands, flags,
static provider registration metadata, lifecycle actions, system-prompt
replacement, tool-call blocking, tool-result chaining, and
`resources_discover` event results.

The model catalog runtime comparison covers bundled-versus-remote timestamp
selection, a newer persisted overlay restored without network access, and
404/501-style unavailable catalog fallback.

The GitHub Copilot comparison covers enterprise-domain device OAuth, the
GitHub-to-Copilot token exchange, policy enablement for every known model,
account-specific model filtering, `proxy-ep` API URL derivation, catalog
protocol counts, and `X-Initiator`/vision request headers. When the pinned
checkout contains the intentionally stale pre-hydration model-data schema, the
comparison hydrates a temporary source archive and leaves the source checkout
unchanged.

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
adds a separate browser/device/refresh grader that compares PKCE authorization
parameters, code and refresh exchanges, device events and request payloads,
JWT account extraction, credential rotation, and request-auth derivation.
GitHub Copilot has a separate device-flow grader because its long-lived GitHub
token, short-lived Copilot token, enterprise endpoints, policy calls, and
account model catalog differ from OpenAI Codex OAuth.
