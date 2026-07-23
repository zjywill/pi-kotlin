package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ThinkingContent
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MistralProviderTest {
    @Test
    fun `streams thinking text tools usage and cache affinity`() =
        runTest {
            val fixture = fixtureServer(MISTRAL_STREAM)
            try {
                val model =
                    model(
                        id = "fixture",
                        api = "mistral-conversations",
                        provider = "mistral",
                        baseUrl = fixture.baseUrl,
                    )
                val provider =
                    MistralProvider(
                        id = "mistral",
                        name = "Mistral",
                        baseUrl = fixture.baseUrl,
                        models = listOf(model),
                        apiKeyEnvNames = listOf("MISTRAL_API_KEY"),
                    )
                val result =
                    provider.stream(
                        model,
                        Context(messages = mutableListOf(UserMessage("hello"))),
                        StreamOptions(
                            apiKey = "secret",
                            cacheRetention = CacheRetention.SHORT,
                            sessionId = "session-123",
                        ),
                    ).result()

                assertEquals(StopReason.TOOL_USE, result.stopReason)
                assertEquals("think", (result.content[0] as ThinkingContent).thinking)
                assertEquals("hello world", (result.content[1] as TextContent).text)
                assertEquals("echo", (result.content[2] as ToolCall).name)
                assertEquals(
                    "ok",
                    (result.content[2] as ToolCall).arguments.getValue("value").jsonPrimitive.content,
                )
                assertEquals("mistral-1", result.responseId)
                assertEquals(8, result.usage.input)
                assertEquals(2, result.usage.cacheRead)
                assertEquals(4, result.usage.output)
                assertEquals(14, result.usage.totalTokens)
                val request = fixture.requests.single()
                assertEquals("/v1/chat/completions", request.path)
                assertEquals("Bearer secret", request.headers["authorization"])
                assertEquals("session-123", request.headers["x-affinity"])
                assertEquals("session-123", request.body.getValue("prompt_cache_key").jsonPrimitive.content)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `stream simple selects reasoning effort or prompt mode by model`() =
        runTest {
            val fixture = fixtureServer(MISTRAL_DONE, expectedRequests = 2)
            try {
                val small =
                    model(
                        id = "mistral-small-2603",
                        api = "mistral-conversations",
                        provider = "mistral",
                        baseUrl = fixture.baseUrl,
                        reasoning = true,
                    )
                val magistral =
                    model(
                        id = "magistral-medium-latest",
                        api = "mistral-conversations",
                        provider = "mistral",
                        baseUrl = fixture.baseUrl,
                        reasoning = true,
                    )
                val provider =
                    MistralProvider(
                        id = "mistral",
                        name = "Mistral",
                        baseUrl = fixture.baseUrl,
                        models = listOf(small, magistral),
                        apiKeyEnvNames = listOf("MISTRAL_API_KEY"),
                    )
                val options =
                    SimpleStreamOptions(
                        stream = StreamOptions(apiKey = "secret", maxTokens = 123),
                        reasoning = ThinkingLevel.MEDIUM,
                    )

                provider.streamSimple(small, Context(messages = mutableListOf(UserMessage("hi"))), options).result()
                provider.streamSimple(magistral, Context(messages = mutableListOf(UserMessage("hi"))), options).result()

                assertEquals("high", fixture.requests[0].body.getValue("reasoning_effort").jsonPrimitive.content)
                assertFalse("prompt_mode" in fixture.requests[0].body)
                assertEquals("reasoning", fixture.requests[1].body.getValue("prompt_mode").jsonPrimitive.content)
                assertFalse("reasoning_effort" in fixture.requests[1].body)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `normalizes foreign tool ids and inserts missing tool results`() {
        val model =
            model(
                id = "fixture",
                api = "mistral-conversations",
                provider = "mistral",
                baseUrl = "https://fixture.invalid",
            )
        val body =
            buildMistralRequestBody(
                model,
                Context(
                    messages =
                        mutableListOf(
                            AssistantMessage(
                                content =
                                    listOf(
                                        ToolCall(
                                            id = "foreign|tool:id!",
                                            name = "echo",
                                            arguments = JsonObject(emptyMap()),
                                        ),
                                    ),
                                api = "openai-responses",
                                provider = "openai",
                                model = "other",
                                stopReason = StopReason.TOOL_USE,
                            ),
                        ),
                ),
                StreamOptions(apiKey = "secret"),
            )

        val messages = body.getValue("messages").jsonArray
        val assistant = messages[0].jsonObject
        val toolCall = assistant.getValue("tool_calls").jsonArray.single().jsonObject
        val normalizedId = toolCall.getValue("id").jsonPrimitive.content
        val syntheticResult = messages[1].jsonObject
        val syntheticText =
            syntheticResult
                .getValue("content")
                .jsonArray
                .single()
                .jsonObject
                .getValue("text")
                .jsonPrimitive
                .content

        assertEquals(9, normalizedId.length)
        assertTrue(normalizedId.all(Char::isLetterOrDigit))
        assertEquals("1ysscgh1v", normalizedId)
        assertEquals(normalizedId, syntheticResult.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("[tool error] No result provided", syntheticText)
    }

    private fun fixtureServer(
        response: String,
        expectedRequests: Int = 1,
    ): FixtureServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val fixture = FixtureServer(server, expectedRequests)
        server.createContext("/") { exchange ->
            val headers =
                exchange.requestHeaders.entries.associate { (name, values) ->
                    name.lowercase() to values.single()
                }
            val body =
                providerJson
                    .parseToJsonElement(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
                    .jsonObject
            fixture.requests += CapturedRequest(exchange.requestURI.path, headers, body)
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("content-type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return fixture
    }

    private data class CapturedRequest(
        val path: String,
        val headers: Map<String, String>,
        val body: JsonObject,
    )

    private class FixtureServer(
        private val server: HttpServer,
        expectedRequests: Int,
    ) : AutoCloseable {
        val requests = Collections.synchronizedList(ArrayList<CapturedRequest>(expectedRequests))
        val baseUrl: String = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
        }
    }

    private companion object {
        val MISTRAL_STREAM =
            """
            data: {"id":"mistral-1","model":"fixture","choices":[{"index":0,"delta":{"content":[{"type":"thinking","thinking":[{"type":"text","text":"think"}]}]},"finish_reason":null}]}

            data: {"id":"mistral-1","model":"fixture","choices":[{"index":0,"delta":{"content":"hello "},"finish_reason":null}]}

            data: {"id":"mistral-1","model":"fixture","choices":[{"index":0,"delta":{"content":[{"type":"text","text":"world"}]},"finish_reason":null}]}

            data: {"id":"mistral-1","model":"fixture","choices":[{"index":0,"delta":{"content":null,"tool_calls":[{"id":"abc123456","type":"function","function":{"name":"echo","arguments":"{\"value\":\""},"index":0}]},"finish_reason":null}]}

            data: {"id":"mistral-1","model":"fixture","usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14,"prompt_tokens_details":{"cached_tokens":2}},"choices":[{"index":0,"delta":{"content":null,"tool_calls":[{"id":"abc123456","type":"function","function":{"name":"echo","arguments":"ok\"}"},"index":0}]},"finish_reason":"tool_calls"}]}

            data: [DONE]

            """.trimIndent()

        val MISTRAL_DONE =
            """
            data: {"id":"mistral-done","model":"fixture","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}]}

            data: [DONE]

            """.trimIndent()
    }
}
