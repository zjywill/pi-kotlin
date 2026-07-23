package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderFixtureTest {
    @Test
    fun `openai chat provider streams text tools and usage`() =
        runTest {
            val fixture =
                fixtureServer(
                    """
                    data: {"choices":[{"delta":{"content":"hello "}}]}

                    data: {"choices":[{"delta":{"content":"world"}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"echo","arguments":"{\"value\":\"ok\"}"}}]},"finish_reason":"tool_calls"}]}

                    data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":2}}}

                    data: [DONE]

                    """.trimIndent(),
                )
            try {
                val model =
                    model(
                        id = "fixture",
                        api = "openai-completions",
                        provider = "fixture",
                        baseUrl = fixture.baseUrl,
                    )
                val provider =
                    OpenAIChatProvider(
                        "fixture",
                        "Fixture",
                        fixture.baseUrl,
                        listOf(model),
                        listOf("UNUSED"),
                    )
                val stream =
                    provider.stream(
                        model,
                        Context(
                            messages = mutableListOf(UserMessage("hi")),
                            tools =
                                listOf(
                                    ToolDefinition(
                                        "echo",
                                        "Echo",
                                        buildJsonObject { put("type", "object") },
                                    ),
                                ),
                        ),
                        StreamOptions(apiKey = "test"),
                    )
                val events = stream.events.toList()
                val result = stream.result()

                assertEquals(StopReason.TOOL_USE, result.stopReason)
                assertEquals("hello world", result.content.filterIsInstance<TextContent>().single().text)
                assertEquals("ok", result.content.filterIsInstance<ToolCall>().single().arguments["value"]
                    ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
                assertEquals("echo", result.content.filterIsInstance<ToolCall>().single().name)
                assertEquals(8, result.usage.input)
                assertEquals(2, result.usage.cacheRead)
                assertEquals(0, result.usage.reasoning)
                assertEquals(14, result.usage.totalTokens)
                assertTrue(events.last() is AssistantDone)
                assertTrue(fixture.requestBody.contains("\"tools\""))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `anthropic provider streams text and tool input`() =
        runTest {
            val fixture =
                fixtureServer(
                    """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":7,"output_tokens":0}}}

                    event: content_block_start
                    data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":0}

                    event: content_block_start
                    data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tool-1","name":"echo","input":{}}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"value\":\"ok\"}"}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":1}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """.trimIndent(),
                )
            try {
                val model =
                    model(
                        id = "fixture",
                        api = "anthropic-messages",
                        provider = "fixture",
                        baseUrl = fixture.baseUrl,
                    )
                val provider =
                    AnthropicProvider(
                        "fixture",
                        "Fixture",
                        fixture.baseUrl,
                        listOf(model),
                        listOf("UNUSED"),
                    )
                val result =
                    provider.stream(
                        model,
                        Context(messages = mutableListOf(UserMessage("hi"))),
                        StreamOptions(apiKey = "test"),
                    ).result()

                assertEquals(StopReason.TOOL_USE, result.stopReason)
                assertEquals("hello", (result.content[0] as TextContent).text)
                assertEquals("echo", (result.content[1] as ToolCall).name)
                assertEquals(
                    "ok",
                    ((result.content[1] as ToolCall).arguments["value"] as kotlinx.serialization.json.JsonPrimitive)
                        .content,
                )
                assertEquals("msg-1", result.responseId)
                assertEquals(7, result.usage.input)
                assertEquals(5, result.usage.output)
                assertEquals(0, result.usage.cacheWrite1h)
                assertTrue(fixture.requestBody.contains("\"max_tokens\""))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `openai responses provider handles output slots and terminal usage`() =
        runTest {
            val fixture =
                fixtureServer(
                    """
                    data: {"type":"response.created","response":{"id":"resp-1"}}

                    data: {"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg-1","content":[]}}

                    data: {"type":"response.output_text.delta","output_index":0,"delta":"hello"}

                    data: {"type":"response.output_item.done","output_index":0,"item":{"type":"message","id":"msg-1","content":[{"type":"output_text","text":"hello"}]}}

                    data: {"type":"response.completed","response":{"id":"resp-1","status":"completed","usage":{"input_tokens":12,"output_tokens":3,"total_tokens":15,"input_tokens_details":{"cached_tokens":2},"output_tokens_details":{"reasoning_tokens":1}},"output":[]}}

                    """.trimIndent(),
                )
            try {
                val model =
                    model(
                        id = "fixture",
                        api = "openai-responses",
                        provider = "fixture",
                        baseUrl = fixture.baseUrl,
                    )
                val provider =
                    OpenAIResponsesProvider(
                        "fixture",
                        "Fixture",
                        fixture.baseUrl,
                        listOf(model),
                        listOf("UNUSED"),
                    )
                val result =
                    provider.stream(
                        model,
                        Context(messages = mutableListOf(UserMessage("hi"))),
                        StreamOptions(apiKey = "test"),
                    ).result()

                assertEquals("hello", (result.content.single() as TextContent).text)
                assertEquals(
                    """{"v":1,"id":"msg-1"}""",
                    (result.content.single() as TextContent).textSignature,
                )
                assertEquals("resp-1", result.responseId)
                assertEquals(10, result.usage.input)
                assertEquals(2, result.usage.cacheRead)
                assertEquals(1, result.usage.reasoning)
                assertTrue(fixture.requestBody.contains("\"store\":false"))
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `google provider streams thought text and function calls`() =
        runTest {
            val fixture =
                fixtureServer(
                    """
                    data: {"responseId":"google-1","candidates":[{"content":{"parts":[{"text":"think","thought":true,"thoughtSignature":"sig"}]}}]}

                    data: {"candidates":[{"content":{"parts":[{"text":"answer"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":2,"thoughtsTokenCount":1,"cachedContentTokenCount":3,"totalTokenCount":12}}

                    data: {"candidates":[{"content":{"parts":[{"functionCall":{"id":"call-1","name":"echo","args":{"value":"ok"}}}]},"finishReason":"STOP"}]}

                    """.trimIndent(),
                )
            try {
                val model =
                    model(
                        id = "fixture",
                        api = "google-generative-ai",
                        provider = "fixture",
                        baseUrl = fixture.baseUrl,
                    )
                val provider =
                    GoogleProvider(
                        "fixture",
                        "Fixture",
                        fixture.baseUrl,
                        listOf(model),
                        listOf("UNUSED"),
                    )
                val result =
                    provider.stream(
                        model,
                        Context(messages = mutableListOf(UserMessage("hi"))),
                        StreamOptions(apiKey = "test"),
                    ).result()

                assertEquals(StopReason.TOOL_USE, result.stopReason)
                assertEquals("think", (result.content[0] as works.earendil.pi.ai.ThinkingContent).thinking)
                assertEquals("answer", (result.content[1] as TextContent).text)
                assertEquals("echo", (result.content[2] as ToolCall).name)
                assertEquals(6, result.usage.input)
                assertEquals(3, result.usage.output)
                assertEquals("google-1", result.responseId)
                assertTrue(fixture.requestBody.contains("\"contents\""))
            } finally {
                fixture.close()
            }
        }

    private fun fixtureServer(response: String): FixtureServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val fixture = FixtureServer(server)
        server.createContext("/") { exchange ->
            fixture.requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("content-type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return fixture
    }

    private class FixtureServer(
        private val server: HttpServer,
    ) : AutoCloseable {
        var requestBody: String = ""
        val baseUrl: String = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
        }
    }
}
