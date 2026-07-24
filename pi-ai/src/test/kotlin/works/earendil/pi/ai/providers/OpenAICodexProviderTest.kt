package works.earendil.pi.ai.providers

import com.github.luben.zstd.Zstd
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.ModelThinkingLevel
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.Transport
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAICodexProviderTest {
    @Test
    fun `builds Codex payload with instructions cache tools reasoning and service tier`() {
        val model = codexModel()
        val body =
            buildOpenAICodexRequestBody(
                model,
                Context(
                    systemPrompt = "system",
                    messages = mutableListOf(UserMessage("hello", timestamp = 1)),
                    tools =
                        listOf(
                            ToolDefinition(
                                name = "echo",
                                description = "Echo",
                                parameters = buildJsonObject { put("type", "object") },
                            ),
                        ),
                ),
                StreamOptions(
                    temperature = 0.25,
                    maxTokens = 123,
                    cacheRetention = CacheRetention.SHORT,
                    sessionId = "x".repeat(67),
                    reasoningEffort = "xhigh",
                    reasoningSummary = "detailed",
                    serviceTier = "priority",
                    textVerbosity = "high",
                    toolChoice = JsonPrimitive("required"),
                ),
            )

        assertEquals("gpt-5.5", body.getValue("model").jsonPrimitive.content)
        assertEquals("system", body.getValue("instructions").jsonPrimitive.content)
        assertEquals("x".repeat(64), body.getValue("prompt_cache_key").jsonPrimitive.content)
        assertEquals("high", body.getValue("text").jsonObject.getValue("verbosity").jsonPrimitive.content)
        assertEquals("priority", body.getValue("service_tier").jsonPrimitive.content)
        assertEquals("required", body.getValue("tool_choice").jsonPrimitive.content)
        assertEquals("xhigh", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertEquals("detailed", body.getValue("reasoning").jsonObject.getValue("summary").jsonPrimitive.content)
        assertEquals(
            "hello",
            body.getValue("input").jsonArray.single().jsonObject
                .getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content,
        )
        assertIs<JsonNull>(
            body.getValue("tools").jsonArray.single().jsonObject.getValue("strict"),
        )
        assertFalse("max_output_tokens" in body)
    }

    @Test
    fun `omits cache affinity and reasoning when disabled`() {
        val body =
            buildOpenAICodexRequestBody(
                codexModel().copy(
                    thinkingLevelMap = mapOf(ModelThinkingLevel.OFF to null),
                ),
                Context(messages = mutableListOf(UserMessage("hello"))),
                StreamOptions(
                    cacheRetention = CacheRetention.NONE,
                    sessionId = "ignored",
                    reasoningEffort = "none",
                ),
            )

        assertFalse("prompt_cache_key" in body)
        assertFalse("reasoning" in body)
    }

    @Test
    fun `extracts account id and forces signed Codex headers`() {
        val token = codexToken("acc-test")
        val model =
            codexModel().copy(
                headers =
                    mapOf(
                        "Authorization" to "blocked",
                        "ChatGPT-Account-ID" to "blocked",
                        "x-model" to "model",
                    ),
            )
        val options =
            StreamOptions(
                sessionId = "session-123",
                headers =
                    mapOf(
                        "authorization" to "also-blocked",
                        "originator" to "blocked",
                        "x-model" to null,
                        "x-option" to "option",
                    ),
            )
        val headers =
            openAICodexSseHeaders(
                model = model,
                options = options,
                accountId = extractOpenAICodexAccountId(token),
                token = token,
                userAgent = "pi (fixture)",
            )

        assertEquals("Bearer $token", headers.caseInsensitive("authorization"))
        assertEquals("acc-test", headers.caseInsensitive("chatgpt-account-id"))
        assertEquals("pi", headers.caseInsensitive("originator"))
        assertEquals("pi (fixture)", headers.caseInsensitive("user-agent"))
        assertEquals("responses=experimental", headers.caseInsensitive("openai-beta"))
        assertEquals("session-123", headers.caseInsensitive("session-id"))
        assertEquals("session-123", headers.caseInsensitive("x-client-request-id"))
        assertEquals("zstd", headers.caseInsensitive("content-encoding"))
        assertEquals("option", headers.caseInsensitive("x-option"))
        assertNull(headers.caseInsensitive("x-model"))
    }

    @Test
    fun `streams compressed SSE response and applies Codex pricing`() =
        runTest {
            val capturedPath = AtomicReference<String>()
            val capturedHeaders = AtomicReference<Map<String, String>>()
            val capturedBody = AtomicReference<JsonObject>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                runCatching {
                    capturedPath.set(exchange.requestURI.path)
                    capturedHeaders.set(
                        exchange.requestHeaders.entries.associate { (name, values) ->
                            name.lowercase() to values.joinToString(",")
                        },
                    )
                    val compressed = exchange.requestBody.readAllBytes()
                    capturedBody.set(
                        providerJson
                            .parseToJsonElement(
                                Zstd.decompress(compressed).toString(StandardCharsets.UTF_8),
                            ).jsonObject,
                    )
                    """
                    data: {"type":"response.created","response":{"id":"resp-1"}}

                    data: {"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg-1","content":[]}}

                    data: {"type":"response.output_text.delta","output_index":0,"delta":"hello"}

                    data: {"type":"response.output_item.done","output_index":0,"item":{"type":"message","id":"msg-1","content":[{"type":"output_text","text":"hello"}]}}

                    data: {"type":"response.done","response":{"id":"resp-1","status":"completed","service_tier":"default","usage":{"input_tokens":10,"output_tokens":4,"total_tokens":14,"input_tokens_details":{"cached_tokens":2}},"output":[]}}

                    """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                }.fold(
                    onSuccess = { response ->
                        exchange.responseHeaders.add("content-type", "text/event-stream")
                        exchange.sendResponseHeaders(200, response.size.toLong())
                        exchange.responseBody.use { it.write(response) }
                    },
                    onFailure = { error ->
                        val response =
                            error.stackTraceToString().toByteArray(StandardCharsets.UTF_8)
                        exchange.sendResponseHeaders(500, response.size.toLong())
                        exchange.responseBody.use { it.write(response) }
                    },
                )
            }
            server.start()
            try {
                val token = codexToken("acc-stream")
                val model =
                    codexModel().copy(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                        cost = ModelCost(input = 1.0, output = 2.0, cacheRead = 0.5, cacheWrite = 0.0),
                    )
                val provider =
                    OpenAICodexProvider(
                        id = "openai-codex",
                        name = "OpenAI Codex",
                        models = listOf(model),
                        userAgent = { "pi (fixture)" },
                    )
                val stream =
                    provider.stream(
                        model,
                        Context(messages = mutableListOf(UserMessage("hello"))),
                        StreamOptions(
                            apiKey = token,
                            transport = Transport.SSE,
                            sessionId = "session-123",
                            serviceTier = "priority",
                        ),
                    )
                val events = stream.events.toList()
                val result = stream.result()

                assertEquals(StopReason.STOP, result.stopReason, result.errorMessage)
                assertEquals("hello", (result.content.single() as TextContent).text)
                assertEquals("resp-1", result.responseId)
                assertEquals(8, result.usage.input)
                assertEquals(2, result.usage.cacheRead)
                assertEquals(4, result.usage.output)
                assertEquals(2.0e-5, result.usage.cost.input, 1.0e-12)
                assertEquals(2.0e-5, result.usage.cost.output, 1.0e-12)
                assertEquals(2.5e-6, result.usage.cost.cacheRead, 1.0e-12)
                assertTrue(events.last() is AssistantDone)
                assertEquals("/codex/responses", capturedPath.get())
                assertEquals("Bearer $token", capturedHeaders.get()["authorization"])
                assertEquals("acc-stream", capturedHeaders.get()["chatgpt-account-id"])
                assertEquals("zstd", capturedHeaders.get()["content-encoding"])
                assertEquals("session-123", capturedHeaders.get()["session-id"])
                assertEquals("gpt-5.5", capturedBody.get().getValue("model").jsonPrimitive.content)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `streamSimple clamps minimal reasoning to model low mapping`() =
        runTest {
            val capturedBody = AtomicReference<JsonObject>()
            val server = codexFixtureServer(capturedBody)
            try {
                val model =
                    codexModel().copy(
                        id = "gpt-5.4",
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                    )
                val provider =
                    OpenAICodexProvider(
                        id = "openai-codex",
                        name = "OpenAI Codex",
                        models = listOf(model),
                    )
                val result =
                    provider.streamSimple(
                        model,
                        Context(messages = mutableListOf(UserMessage("hello"))),
                        SimpleStreamOptions(
                            stream =
                                StreamOptions(
                                    apiKey = codexToken("acc-simple"),
                                    transport = Transport.SSE,
                                ),
                            reasoning = ThinkingLevel.MINIMAL,
                        ),
                    ).result()

                assertEquals(StopReason.STOP, result.stopReason)
                assertEquals(
                    "low",
                    capturedBody.get().getValue("reasoning").jsonObject
                        .getValue("effort").jsonPrimitive.content,
                )
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `rejects invalid tokens and explicit websocket transport`() =
        runTest {
            val model = codexModel()
            val provider =
                OpenAICodexProvider(
                    id = "openai-codex",
                    name = "OpenAI Codex",
                    models = listOf(model),
                )
            val invalid =
                provider.stream(
                    model,
                    Context(messages = mutableListOf(UserMessage("hello"))),
                    StreamOptions(apiKey = "invalid", transport = Transport.SSE),
                ).result()
            val websocket =
                provider.stream(
                    model,
                    Context(messages = mutableListOf(UserMessage("hello"))),
                    StreamOptions(apiKey = codexToken("acc"), transport = Transport.WEBSOCKET),
                ).result()

            assertEquals(StopReason.ERROR, invalid.stopReason)
            assertEquals("Failed to extract accountId from token", invalid.errorMessage)
            assertEquals(StopReason.ERROR, websocket.stopReason)
            assertTrue(websocket.errorMessage.orEmpty().contains("WebSocket transport has not been migrated"))
        }

    private fun codexFixtureServer(capturedBody: AtomicReference<JsonObject>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            capturedBody.set(
                providerJson
                    .parseToJsonElement(
                        Zstd.decompress(exchange.requestBody.readAllBytes()).toString(StandardCharsets.UTF_8),
                    ).jsonObject,
            )
            val response =
                """
                data: {"type":"response.completed","response":{"id":"resp-1","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2},"output":[]}}

                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("content-type", "text/event-stream")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        return server
    }

    private fun codexModel(): Model =
        Model(
            id = "gpt-5.5",
            name = "GPT-5.5",
            api = "openai-codex-responses",
            provider = "openai-codex",
            baseUrl = "https://chatgpt.com/backend-api",
            reasoning = true,
            thinkingLevelMap =
                mapOf(
                    ModelThinkingLevel.MINIMAL to "low",
                    ModelThinkingLevel.XHIGH to "xhigh",
                ),
            input = listOf(ModelInput.TEXT, ModelInput.IMAGE),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 272_000,
            maxTokens = 128_000,
        )

    private fun codexToken(accountId: String): String {
        val payload =
            providerJson.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put(
                        "https://api.openai.com/auth",
                        buildJsonObject {
                            put("chatgpt_account_id", accountId)
                        },
                    )
                },
            )
        val encoded =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        return "aaa.$encoded.bbb"
    }
}

private fun Map<String, String>.caseInsensitive(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
