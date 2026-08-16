package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XaiProviderTest {
    @Test
    fun `responses requests force pi user agent and include encrypted reasoning`() =
        runTest {
            val fixture = fixtureServer()
            try {
                val model =
                    builtInModels("xai")
                        .single { it.id == "grok-4.5" }
                        .copy(baseUrl = fixture.baseUrl)
                val provider =
                    OpenAIResponsesProvider(
                        id = "xai",
                        name = "xAI",
                        baseUrl = fixture.baseUrl,
                        models = listOf(model),
                        apiKeyEnvNames = listOf("UNUSED"),
                    )

                provider.stream(
                    model,
                    Context(messages = mutableListOf(UserMessage("hello"))),
                    StreamOptions(
                        apiKey = "test",
                        headers = mapOf("User-Agent" to "custom-agent"),
                    ),
                ).result()

                val body = providerJson.parseToJsonElement(fixture.requestBody).jsonObject
                assertEquals("/responses", fixture.path)
                assertEquals("Bearer test", fixture.headers["authorization"])
                assertEquals(getPiUserAgent(), fixture.headers["user-agent"])
                assertEquals(false, body.getValue("store").jsonPrimitive.content.toBoolean())
                assertFalse("reasoning" in body)
                assertEquals(
                    JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content"))),
                    body.getValue("include"),
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `grok 46 sends xhigh reasoning through responses`() {
        val model = builtInModels("xai").single { it.id == "grok-4.6" }
        val body =
            buildOpenAIResponsesRequestBody(
                model,
                Context(messages = mutableListOf(UserMessage("hello"))),
                StreamOptions(reasoningEffort = "xhigh"),
            )

        assertEquals(
            "xhigh",
            body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content,
        )
        assertEquals(
            JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content"))),
            body.getValue("include"),
        )
    }

    @Test
    fun `custom xai completions requests force pi user agent`() =
        runTest {
            val fixture = fixtureServer(chat = true)
            try {
                val model =
                    Model(
                        id = "grok-custom",
                        name = "Grok Custom",
                        api = "openai-completions",
                        provider = "xai",
                        baseUrl = fixture.baseUrl,
                        reasoning = false,
                        input = listOf(ModelInput.TEXT),
                        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                        contextWindow = 128_000,
                        maxTokens = 16_384,
                    )
                val provider =
                    OpenAIChatProvider(
                        id = "xai",
                        name = "xAI",
                        baseUrl = fixture.baseUrl,
                        models = listOf(model),
                        apiKeyEnvNames = listOf("UNUSED"),
                    )

                provider.stream(
                    model,
                    Context(messages = mutableListOf(UserMessage("hello"))),
                    StreamOptions(
                        apiKey = "test",
                        headers = mapOf("user-agent" to "custom-agent"),
                    ),
                ).result()

                assertEquals("/chat/completions", fixture.path)
                assertEquals(getPiUserAgent(), fixture.headers["user-agent"])
                assertTrue(fixture.requestBody.contains("\"model\":\"grok-custom\""))
            } finally {
                fixture.close()
            }
        }

    private fun fixtureServer(chat: Boolean = false): FixtureServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val fixture = FixtureServer(server)
        server.createContext("/") { exchange ->
            fixture.path = exchange.requestURI.path
            fixture.headers =
                exchange.requestHeaders.entries.associate { (name, values) ->
                    name.lowercase() to values.joinToString(",")
                }
            fixture.requestBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val response =
                if (chat) {
                    """
                    data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

                    data: [DONE]

                    """.trimIndent()
                } else {
                    """
                    data: {"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg-1","content":[]}}

                    data: {"type":"response.output_text.delta","output_index":0,"delta":"ok"}

                    data: {"type":"response.completed","response":{"id":"resp-1","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2},"output":[]}}

                    """.trimIndent()
                }
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
        var path: String = ""
        var headers: Map<String, String> = emptyMap()
        var requestBody: String = ""
        val baseUrl: String = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
        }
    }
}
