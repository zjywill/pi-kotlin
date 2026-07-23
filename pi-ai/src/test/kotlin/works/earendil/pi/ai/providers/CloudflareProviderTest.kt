package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudflareProviderTest {
    @Test
    fun `materializes known placeholders and preserves unresolved placeholders`() {
        val model =
            model(
                id = "fixture",
                api = "openai-completions",
                provider = "cloudflare-ai-gateway",
                baseUrl =
                    "https://gateway.ai.cloudflare.com/v1/" +
                        "{CLOUDFLARE_ACCOUNT_ID}/{CLOUDFLARE_GATEWAY_ID}/compat",
            )

        assertEquals(
            "https://gateway.ai.cloudflare.com/v1/account/gateway/compat",
            resolveCloudflareModel(
                model,
                mapOf(
                    "CLOUDFLARE_ACCOUNT_ID" to "account",
                    "CLOUDFLARE_GATEWAY_ID" to "gateway",
                ),
            ).baseUrl,
        )
        assertEquals(
            "https://gateway.ai.cloudflare.com/v1/account/{CLOUDFLARE_GATEWAY_ID}/compat",
            resolveCloudflareModel(
                model,
                mapOf("CLOUDFLARE_ACCOUNT_ID" to "account"),
            ).baseUrl,
        )
    }

    @Test
    fun `scoped request configuration wins and explicit BYOK headers survive gateway auth`() {
        val model =
            model(
                id = "fixture",
                api = "openai-responses",
                provider = "cloudflare-ai-gateway",
                baseUrl = "https://gateway/{CLOUDFLARE_ACCOUNT_ID}/{CLOUDFLARE_GATEWAY_ID}/openai",
            )
        val ambient =
            mapOf(
                "CLOUDFLARE_API_KEY" to "ambient-key",
                "CLOUDFLARE_ACCOUNT_ID" to "ambient-account",
                "CLOUDFLARE_GATEWAY_ID" to "ambient-gateway",
            )
        val resolved =
            resolveCloudflareRequest(
                model,
                StreamOptions(
                    apiKey = "stored-key",
                    env =
                        mapOf(
                            "CLOUDFLARE_ACCOUNT_ID" to "scoped-account",
                            "CLOUDFLARE_GATEWAY_ID" to "scoped-gateway",
                        ),
                    headers = mapOf("authorization" to "Bearer upstream"),
                ),
                CloudflareProviderKind.AI_GATEWAY,
                ambient::get,
            )

        assertEquals(
            "https://gateway/scoped-account/scoped-gateway/openai",
            resolved.model.baseUrl,
        )
        assertEquals("stored-key", resolved.options.apiKey)
        assertEquals("scoped-account", resolved.options.env["CLOUDFLARE_ACCOUNT_ID"])
        assertEquals("scoped-gateway", resolved.options.env["CLOUDFLARE_GATEWAY_ID"])
        assertEquals("Bearer stored-key", resolved.options.headers["cf-aig-authorization"])
        assertEquals("Bearer upstream", resolved.options.headers["authorization"])
        assertNull(resolved.options.headers["x-api-key"])
    }

    @Test
    fun `requires complete provider configuration before dispatch`() {
        val model =
            model(
                id = "fixture",
                api = "openai-completions",
                provider = "cloudflare-ai-gateway",
                baseUrl = "https://gateway/{CLOUDFLARE_ACCOUNT_ID}/{CLOUDFLARE_GATEWAY_ID}/compat",
            )

        val missingAccount =
            assertFailsWith<IllegalStateException> {
                resolveCloudflareRequest(
                    model,
                    StreamOptions(apiKey = "key"),
                    CloudflareProviderKind.AI_GATEWAY,
                    { null },
                )
            }
        assertTrue(missingAccount.message.orEmpty().contains("CLOUDFLARE_ACCOUNT_ID"))

        val missingGateway =
            assertFailsWith<IllegalStateException> {
                resolveCloudflareRequest(
                    model,
                    StreamOptions(
                        apiKey = "key",
                        env = mapOf("CLOUDFLARE_ACCOUNT_ID" to "account"),
                    ),
                    CloudflareProviderKind.AI_GATEWAY,
                    { null },
                )
            }
        assertTrue(missingGateway.message.orEmpty().contains("CLOUDFLARE_GATEWAY_ID"))
    }

    @Test
    fun `dispatches workers and every gateway protocol with Cloudflare request semantics`() =
        runTest {
            val requests = Collections.synchronizedList(mutableListOf<CapturedRequest>())
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                requests +=
                    CapturedRequest(
                        path = exchange.requestURI.path,
                        headers =
                            exchange.requestHeaders.entries.associate { (name, values) ->
                                name.lowercase() to values.single()
                            },
                    )
                exchange.requestBody.readAllBytes()
                val response =
                    when {
                        exchange.requestURI.path.endsWith("/v1/messages") -> ANTHROPIC_RESPONSE
                        exchange.requestURI.path.endsWith("/responses") -> RESPONSES_RESPONSE
                        else -> CHAT_RESPONSE
                    }
                val bytes = response.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("content-type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val root = "http://127.0.0.1:${server.address.port}"
                val workers =
                    cloudflareModel(
                        id = "@cf/fixture",
                        api = "openai-completions",
                        provider = "cloudflare-workers-ai",
                        baseUrl = "$root/accounts/{CLOUDFLARE_ACCOUNT_ID}/ai/v1",
                        sessionAffinity = true,
                    )
                val gatewayChat =
                    cloudflareModel(
                        id = "workers-ai/@cf/fixture",
                        api = "openai-completions",
                        provider = "cloudflare-ai-gateway",
                        baseUrl = "$root/gateway/{CLOUDFLARE_ACCOUNT_ID}/{CLOUDFLARE_GATEWAY_ID}/compat",
                        sessionAffinity = true,
                    )
                val gatewayResponses =
                    cloudflareModel(
                        id = "gpt-fixture",
                        api = "openai-responses",
                        provider = "cloudflare-ai-gateway",
                        baseUrl = "$root/gateway/{CLOUDFLARE_ACCOUNT_ID}/{CLOUDFLARE_GATEWAY_ID}/openai",
                    )
                val gatewayAnthropic =
                    cloudflareModel(
                        id = "claude-fixture",
                        api = "anthropic-messages",
                        provider = "cloudflare-ai-gateway",
                        baseUrl = "$root/gateway/{CLOUDFLARE_ACCOUNT_ID}/{CLOUDFLARE_GATEWAY_ID}/anthropic",
                    )
                val workersProvider =
                    CloudflareProvider(
                        id = "cloudflare-workers-ai",
                        name = "Cloudflare Workers AI",
                        kind = CloudflareProviderKind.WORKERS_AI,
                        models = listOf(workers),
                        environment = { null },
                    )
                val gatewayProvider =
                    CloudflareProvider(
                        id = "cloudflare-ai-gateway",
                        name = "Cloudflare AI Gateway",
                        kind = CloudflareProviderKind.AI_GATEWAY,
                        models = listOf(gatewayChat, gatewayResponses, gatewayAnthropic),
                        environment = { null },
                    )
                val context = Context(messages = mutableListOf(UserMessage("hello")))
                val env =
                    mapOf(
                        "CLOUDFLARE_ACCOUNT_ID" to "account",
                        "CLOUDFLARE_GATEWAY_ID" to "gateway",
                    )

                workersProvider
                    .stream(
                        workers,
                        context,
                        StreamOptions(apiKey = "cf-token", env = env, sessionId = "workers-session"),
                    ).result()
                workersProvider
                    .stream(
                        workers,
                        context,
                        StreamOptions(
                            apiKey = "cf-token",
                            env = env,
                            sessionId = "disabled-session",
                            cacheRetention = CacheRetention.NONE,
                        ),
                    ).result()
                gatewayProvider
                    .stream(
                        gatewayChat,
                        context,
                        StreamOptions(apiKey = "cf-token", env = env, sessionId = "gateway-session"),
                    ).result()
                gatewayProvider
                    .stream(
                        gatewayResponses,
                        context,
                        StreamOptions(
                            apiKey = "cf-token",
                            env = env,
                            headers = mapOf("Authorization" to "Bearer upstream-token"),
                        ),
                    ).result()
                gatewayProvider
                    .stream(
                        gatewayAnthropic,
                        context,
                        StreamOptions(apiKey = "cf-token", env = env),
                    ).result()

                assertEquals(5, requests.size)
                val direct = requests[0]
                assertEquals("/accounts/account/ai/v1/chat/completions", direct.path)
                assertEquals("Bearer cf-token", direct.headers["authorization"])
                assertEquals("workers-session", direct.headers["session_id"])
                assertEquals("workers-session", direct.headers["x-client-request-id"])
                assertEquals("workers-session", direct.headers["x-session-affinity"])

                val noCache = requests[1]
                assertFalse("session_id" in noCache.headers)
                assertFalse("x-client-request-id" in noCache.headers)
                assertFalse("x-session-affinity" in noCache.headers)

                val compat = requests[2]
                assertEquals("/gateway/account/gateway/compat/chat/completions", compat.path)
                assertEquals("Bearer cf-token", compat.headers["cf-aig-authorization"])
                assertFalse("authorization" in compat.headers)
                assertFalse("x-api-key" in compat.headers)
                assertEquals("gateway-session", compat.headers["session_id"])
                assertEquals("gateway-session", compat.headers["x-client-request-id"])
                assertEquals("gateway-session", compat.headers["x-session-affinity"])

                val responses = requests[3]
                assertEquals("/gateway/account/gateway/openai/responses", responses.path)
                assertEquals("Bearer cf-token", responses.headers["cf-aig-authorization"])
                assertEquals("Bearer upstream-token", responses.headers["authorization"])
                assertFalse("x-api-key" in responses.headers)

                val anthropic = requests[4]
                assertEquals("/gateway/account/gateway/anthropic/v1/messages", anthropic.path)
                assertEquals("Bearer cf-token", anthropic.headers["cf-aig-authorization"])
                assertFalse("authorization" in anthropic.headers)
                assertFalse("x-api-key" in anthropic.headers)
            } finally {
                server.stop(0)
            }
        }

    private fun cloudflareModel(
        id: String,
        api: String,
        provider: String,
        baseUrl: String,
        sessionAffinity: Boolean = false,
    ): Model =
        model(
            id = id,
            api = api,
            provider = provider,
            baseUrl = baseUrl,
        ).copy(
            compat =
                buildJsonObject { put("sendSessionAffinityHeaders", true) }
                    .takeIf { sessionAffinity },
        )

    private data class CapturedRequest(
        val path: String,
        val headers: Map<String, String>,
    )

    private companion object {
        val CHAT_RESPONSE =
            """
            data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

            data: [DONE]

            """.trimIndent()

        val RESPONSES_RESPONSE =
            """
            data: {"type":"response.completed","response":{"id":"resp-1","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2},"output":[]}}

            """.trimIndent()

        val ANTHROPIC_RESPONSE =
            """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":1,"output_tokens":0}}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

            event: message_stop
            data: {"type":"message_stop"}

            """.trimIndent()
    }
}
