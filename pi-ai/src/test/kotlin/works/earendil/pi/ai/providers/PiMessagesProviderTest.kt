package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantMessageDiagnostic
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PiMessagesProviderTest {
    @Test
    fun `streams text and tools and sends the pi messages payload`() =
        runTest {
            val request = AtomicReference<RecordedRequest>()
            val server =
                server { exchange ->
                    request.set(exchange.record())
                    exchange.respondSse(
                        """
                        data: {"type":"start"}

                        data: {"type":"text_start","contentIndex":0}

                        data: {"type":"text_delta","contentIndex":0,"delta":"Hel"}

                        data: {"type":"text_delta","contentIndex":0,"delta":"lo"}

                        data: {"type":"text_end","contentIndex":0,"content":"Hello","contentSignature":"sig"}

                        data: {"type":"toolcall_start","contentIndex":1,"id":"call_1","toolName":"read"}

                        data: {"type":"toolcall_delta","contentIndex":1,"delta":"{\"path\":"}

                        data: {"type":"toolcall_delta","contentIndex":1,"delta":"\"a.txt\"}"}

                        data: {"type":"toolcall_end","contentIndex":1,"toolCall":{"type":"toolCall","id":"call_1","name":"read","arguments":{"path":"a.txt"}}}

                        data: {"type":"done","reason":"toolUse","usage":{"input":10,"output":5,"cacheRead":0,"cacheWrite":0,"totalTokens":15,"cost":{"input":0.1,"output":0.2,"cacheRead":0.0,"cacheWrite":0.0,"total":0.3}},"responseId":"resp_1"}

                        """.trimIndent(),
                    )
                }
            try {
                val provider = provider(server)
                val stream =
                    provider.stream(
                        provider.getModels().single(),
                        Context(messages = mutableListOf(UserMessage("Hello", timestamp = 1))),
                        StreamOptions(
                            apiKey = "test-key",
                            maxTokens = 100,
                            sessionId = "session-1",
                            headers = mapOf("x-custom" to "1"),
                            toolChoice = buildJsonObject { put("type", "auto") },
                        ),
                    )
                val events = async { stream.events.toList() }
                val message = stream.result()

                assertEquals(StopReason.TOOL_USE, message.stopReason)
                assertEquals("resp_1", message.responseId)
                assertEquals(15, message.usage.totalTokens)
                assertEquals(
                    listOf(
                        TextContent("Hello", textSignature = "sig"),
                        ToolCall("call_1", "read", buildJsonObject { put("path", "a.txt") }),
                    ),
                    message.content,
                )
                assertTrue(events.await().any { it is works.earendil.pi.ai.ToolCallEnd })

                val captured = assertNotNull(request.get())
                assertEquals("/v1/messages", captured.path)
                assertEquals("Bearer test-key", captured.headers["Authorization"])
                assertEquals("1", captured.headers["X-custom"])
                assertEquals("auto", captured.body.string("model"))
                assertEquals("Hello", captured.body.obj("context")?.array("messages")?.single()?.jsonObject?.string("content"))
                val options = assertNotNull(captured.body.obj("options"))
                assertEquals(100, options.int("maxTokens"))
                assertEquals("session-1", options.string("sessionId"))
                assertTrue("cacheRetention" !in options)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `supports debug response callbacks and payload replacement`() =
        runTest {
            val request = AtomicReference<RecordedRequest>()
            val responseHeader = AtomicReference<String>()
            val server =
                server { exchange ->
                    request.set(exchange.record())
                    exchange.responseHeaders.add("x-pi-gateway-upstream-provider", "anthropic")
                    exchange.respondSse(
                        """
                        data: {"type":"done","reason":"stop","usage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,"cost":{"input":0.0,"output":0.0,"cacheRead":0.0,"cacheWrite":0.0,"total":0.0}}}

                        """.trimIndent(),
                    )
                }
            try {
                val provider = provider(server)
                val result =
                    provider.stream(
                        provider.getModels().single(),
                        Context(messages = mutableListOf(UserMessage("Hi", timestamp = 1))),
                        StreamOptions(
                            apiKey = "test",
                            debug = true,
                            onPayload = { payload, _ ->
                                JsonObject(payload.jsonObject + ("extra" to kotlinx.serialization.json.JsonPrimitive(true)))
                            },
                            onResponse = { response, _ ->
                                responseHeader.set(response.headers["x-pi-gateway-upstream-provider"])
                            },
                        ),
                    ).result()

                assertEquals(StopReason.STOP, result.stopReason)
                assertEquals("/v1/messages?debug=1", request.get().path)
                assertEquals(true, request.get().body.getValue("extra").jsonPrimitive.boolean)
                assertEquals("anthropic", responseHeader.get())
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `surfaces structured HTTP errors as diagnostics`() =
        runTest {
            val server =
                server { exchange ->
                    exchange.requestBody.readAllBytes()
                    exchange.respondJson(
                        401,
                        """{"error":{"message":"Token expired","code":"unauthorized","details":{"retry":false}}}""",
                    )
                }
            try {
                val provider = provider(server)
                val result =
                    provider.stream(
                        provider.getModels().single(),
                        Context(messages = mutableListOf(UserMessage("Hi"))),
                        StreamOptions(apiKey = "stale"),
                    ).result()

                assertEquals(StopReason.ERROR, result.stopReason)
                assertTrue(result.errorMessage.orEmpty().contains("401 Unauthorized"))
                assertTrue(result.errorMessage.orEmpty().contains("Token expired"))
                val diagnostic = assertIs<AssistantMessageDiagnostic>(result.diagnostics?.single())
                assertEquals("pi_messages_response_failure", diagnostic.type)
                assertEquals(401, diagnostic.details?.int("status"))
                assertEquals("unauthorized", diagnostic.details?.obj("error")?.string("code"))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `propagates server errors and rewrite diagnostics`() =
        runTest {
            val server =
                server { exchange ->
                    exchange.requestBody.readAllBytes()
                    exchange.respondSse(
                        """
                        data: {"type":"start"}

                        data: {"type":"thinking_start","contentIndex":0}

                        data: {"type":"thinking_delta","contentIndex":0,"delta":"hmm"}

                        data: {"type":"thinking_end","contentIndex":0,"content":"hmm","contentSignature":"thinking-sig","redacted":false}

                        data: {"type":"error","reason":"error","usage":{"input":3,"output":2,"cacheRead":0,"cacheWrite":0,"totalTokens":5,"cost":{"input":0.0,"output":0.0,"cacheRead":0.0,"cacheWrite":0.0,"total":0.0}},"errorMessage":"Upstream failed","rewrite":{"policyId":"policy","policyVersion":2,"changed":true,"tokenCountChange":-4,"messageCountChange":-1,"systemPromptChanged":false}}

                        """.trimIndent(),
                    )
                }
            try {
                val provider = provider(server)
                val result =
                    provider.stream(
                        provider.getModels().single(),
                        Context(messages = mutableListOf(UserMessage("Hi"))),
                        StreamOptions(apiKey = "test"),
                    ).result()

                assertEquals(StopReason.ERROR, result.stopReason)
                assertEquals("Upstream failed", result.errorMessage)
                assertEquals("pi_messages_rewrite", result.diagnostics?.single()?.type)
                assertEquals("policy", result.diagnostics?.single()?.details?.string("policyId"))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `errors on missing auth and missing terminal event`() =
        runTest {
            val missingAuthProvider =
                PiMessagesProvider(
                    id = "radius",
                    name = "Radius",
                    baseUrl = "http://127.0.0.1:1/v1",
                    models = listOf(model("http://127.0.0.1:1/v1")),
                    apiKeyEnvNames = emptyList(),
                    environment = { null },
                )
            assertTrue(
                missingAuthProvider
                    .stream(
                        missingAuthProvider.getModels().single(),
                        Context(messages = mutableListOf(UserMessage("Hi"))),
                        StreamOptions(),
                    ).result()
                    .errorMessage
                    .orEmpty()
                    .contains("No API key provided"),
            )

            val server =
                server { exchange ->
                    exchange.requestBody.readAllBytes()
                    exchange.respondSse(
                        """
                        data: {"type":"start"}

                        data: {"type":"text_start","contentIndex":0}

                        data: {"type":"text_delta","contentIndex":0,"delta":"partial"}

                        """.trimIndent(),
                    )
                }
            try {
                val provider = provider(server)
                val result =
                    provider.stream(
                        provider.getModels().single(),
                        Context(messages = mutableListOf(UserMessage("Hi"))),
                        StreamOptions(apiKey = "test"),
                    ).result()

                assertEquals(StopReason.ERROR, result.stopReason)
                assertTrue(result.errorMessage.orEmpty().contains("stream ended without a terminal event"))
            } finally {
                server.stop(0)
            }
        }

    private fun provider(server: HttpServer): PiMessagesProvider {
        val baseUrl = "http://127.0.0.1:${server.address.port}/v1"
        return PiMessagesProvider(
            id = "radius",
            name = "Radius",
            baseUrl = baseUrl,
            models = listOf(model(baseUrl)),
            apiKeyEnvNames = listOf("RADIUS_API_KEY"),
            environment = { null },
        )
    }

    private fun model(baseUrl: String): Model =
        Model(
            id = "auto",
            name = "Radius Auto",
            api = "pi-messages",
            provider = "radius",
            baseUrl = baseUrl,
            reasoning = false,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(1.0, 2.0, 0.1, 0.2),
            contextWindow = 128_000,
            maxTokens = 16_384,
        )

    private fun server(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer
            .create(InetSocketAddress("127.0.0.1", 0), 0)
            .apply {
                createContext("/", handler)
                start()
            }

    private fun HttpExchange.record(): RecordedRequest {
        val headers =
            requestHeaders.entries.associate { (name, values) ->
                name to values.joinToString(", ")
            }
        val body = providerJson.parseToJsonElement(requestBody.readAllBytes().toString(StandardCharsets.UTF_8)).jsonObject
        return RecordedRequest(requestURI.toString(), headers, body)
    }

    private fun HttpExchange.respondSse(body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("content-type", "text/event-stream")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.respondJson(
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("content-type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private data class RecordedRequest(
        val path: String,
        val headers: Map<String, String>,
        val body: JsonObject,
    )
}
