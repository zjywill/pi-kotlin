package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicOAuthProviderTest {
    @Test
    fun `OAuth requests use Claude Code identity and canonical tool names`() =
        runTest {
            val fixture = anthropicOAuthFixture()
            try {
                val model =
                    model(
                        id = "claude-test",
                        api = "anthropic-messages",
                        provider = "anthropic",
                        baseUrl = fixture.baseUrl,
                    )
                val provider =
                    AnthropicProvider(
                        id = "anthropic",
                        name = "Anthropic",
                        baseUrl = fixture.baseUrl,
                        models = listOf(model),
                        apiKeyEnvNames = listOf("UNUSED"),
                    )
                val context =
                    Context(
                        systemPrompt = "Project instructions",
                        messages =
                            mutableListOf(
                                UserMessage("Run the tool", 1),
                                AssistantMessage(
                                    content =
                                        listOf(
                                            ToolCall(
                                                id = "prior-call",
                                                name = "bash",
                                                arguments = JsonObject(emptyMap()),
                                            ),
                                        ),
                                    api = "anthropic-messages",
                                    provider = "anthropic",
                                    model = "claude-test",
                                    timestamp = 2,
                                ),
                                ToolResultMessage(
                                    toolCallId = "prior-call",
                                    toolName = "bash",
                                    content = listOf(TextContent("done")),
                                    isError = false,
                                    timestamp = 3,
                                ),
                                UserMessage("Read the file", 4),
                            ),
                        tools =
                            listOf(
                                ToolDefinition(
                                    name = "read",
                                    description = "Read a file",
                                    parameters =
                                        buildJsonObject {
                                            put("type", "object")
                                            put(
                                                "properties",
                                                buildJsonObject {
                                                    put(
                                                        "path",
                                                        buildJsonObject { put("type", "string") },
                                                    )
                                                },
                                            )
                                        },
                                ),
                                ToolDefinition(
                                    name = "echo",
                                    description = "Echo",
                                    parameters = buildJsonObject { put("type", "object") },
                                ),
                            ),
                    )

                val oauthResult =
                    provider
                        .stream(
                            model,
                            context,
                            StreamOptions(
                                apiKey = "prefix-sk-ant-oat-session-token",
                                cacheRetention = CacheRetention.NONE,
                            ),
                        ).result()
                val apiKeyResult =
                    provider
                        .stream(
                            model,
                            context,
                            StreamOptions(
                                apiKey = "anthropic-api-key",
                                cacheRetention = CacheRetention.NONE,
                            ),
                        ).result()
                val copilotModel = model.copy(provider = "github-copilot")
                val copilotResult =
                    provider
                        .stream(
                            copilotModel,
                            context,
                            StreamOptions(
                                apiKey = "copilot-sk-ant-oat-shaped-token",
                                cacheRetention = CacheRetention.NONE,
                            ),
                        ).result()

                assertEquals(StopReason.TOOL_USE, oauthResult.stopReason)
                assertEquals("read", oauthResult.content.filterIsInstance<ToolCall>().single().name)
                assertEquals("Read", apiKeyResult.content.filterIsInstance<ToolCall>().single().name)
                assertEquals("Read", copilotResult.content.filterIsInstance<ToolCall>().single().name)

                val oauth = fixture.requests.first()
                assertEquals("Bearer prefix-sk-ant-oat-session-token", oauth.header("authorization"))
                assertNull(oauth.header("x-api-key"))
                assertEquals("cli", oauth.header("x-app"))
                assertEquals("claude-cli/2.1.75", oauth.header("user-agent"))
                assertEquals("2023-06-01", oauth.header("anthropic-version"))
                assertEquals("true", oauth.header("anthropic-dangerous-direct-browser-access"))
                assertEquals(
                    "claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14",
                    oauth.header("anthropic-beta"),
                )
                val oauthBody = providerJson.parseToJsonElement(oauth.body).jsonObject
                val system = oauthBody.getValue("system").jsonArray
                assertEquals(
                    "You are Claude Code, Anthropic's official CLI for Claude.",
                    system[0].jsonObject.getValue("text").jsonPrimitive.content,
                )
                assertEquals(
                    "Project instructions",
                    system[1].jsonObject.getValue("text").jsonPrimitive.content,
                )
                assertEquals(
                    listOf("Read", "echo"),
                    oauthBody
                        .getValue("tools")
                        .jsonArray
                        .map { it.jsonObject.getValue("name").jsonPrimitive.content },
                )
                val historicalTool =
                    oauthBody
                        .getValue("messages")
                        .jsonArray[1]
                        .jsonObject
                        .getValue("content")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals("Bash", historicalTool.getValue("name").jsonPrimitive.content)

                val apiKey = fixture.requests[1]
                assertEquals("anthropic-api-key", apiKey.header("x-api-key"))
                assertFalse("authorization" in apiKey.headers)
                assertEquals(
                    listOf("Project instructions"),
                    providerJson
                        .parseToJsonElement(apiKey.body)
                        .jsonObject
                        .getValue("system")
                        .jsonArray
                        .map { it.jsonObject.getValue("text").jsonPrimitive.content },
                )

                val copilot = fixture.requests.last()
                assertEquals("Bearer copilot-sk-ant-oat-shaped-token", copilot.header("authorization"))
                assertNull(copilot.header("x-app"))
                assertNull(copilot.header("x-api-key"))
                val copilotBody = providerJson.parseToJsonElement(copilot.body).jsonObject
                assertEquals(
                    listOf("Project instructions"),
                    copilotBody
                        .getValue("system")
                        .jsonArray
                        .map { it.jsonObject.getValue("text").jsonPrimitive.content },
                )
                assertEquals(
                    listOf("read", "echo"),
                    copilotBody
                        .getValue("tools")
                        .jsonArray
                        .map { it.jsonObject.getValue("name").jsonPrimitive.content },
                )
            } finally {
                fixture.close()
            }
        }

    private fun anthropicOAuthFixture(): AnthropicFixture {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val fixture = AnthropicFixture(server)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val headers =
                exchange.requestHeaders.entries.associate { (name, values) ->
                    name.lowercase() to values.joinToString(",")
                }
            fixture.requests +=
                CapturedAnthropicRequest(
                    path = exchange.requestURI.path,
                    headers = headers,
                    body = body,
                )
            val response =
                """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":1,"output_tokens":0}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-1","name":"Read","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"README.md\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":1}}

                event: message_stop
                data: {"type":"message_stop"}

                """.trimIndent()
            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("content-type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return fixture
    }

    private data class CapturedAnthropicRequest(
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private class AnthropicFixture(
        private val server: HttpServer,
    ) : AutoCloseable {
        val requests: MutableList<CapturedAnthropicRequest> =
            Collections.synchronizedList(mutableListOf())
        val baseUrl: String = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
        }
    }
}
