package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.InMemoryCredentialStore
import works.earendil.pi.ai.InMemoryModelsStore
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubCopilotProviderTest {
    @Test
    fun `dynamic headers cover empty agent and vision contexts`() {
        assertEquals(
            "user",
            githubCopilotDynamicHeaders(Context())["X-Initiator"],
        )
        assertEquals(
            "agent",
            githubCopilotDynamicHeaders(
                Context(
                    messages =
                        mutableListOf(
                            AssistantMessage(
                                content = listOf(TextContent("done")),
                                api = "openai-responses",
                                provider = "github-copilot",
                                model = "gpt-5.4",
                            ),
                        ),
                ),
            )["X-Initiator"],
        )
        val imageHeaders =
            githubCopilotDynamicHeaders(
                Context(
                    messages =
                        mutableListOf(
                            UserMessage(
                                MessageContent.Blocks(
                                    listOf(
                                        TextContent("inspect"),
                                        ImageContent("aGVsbG8=", "image/png"),
                                    ),
                                ),
                            ),
                        ),
                ),
            )
        assertEquals("true", imageHeaders["Copilot-Vision-Request"])
        assertEquals("conversation-edits", imageHeaders["Openai-Intent"])
    }

    @Test
    fun `provider delegates all protocols with Copilot auth and headers`() =
        runTest {
            val fixture = copilotFixture()
            try {
                val models = testCopilotModels(fixture.baseUrl)
                val provider =
                    GitHubCopilotProvider(
                        id = "github-copilot",
                        name = "GitHub Copilot",
                        models = models,
                    )
                val imageContext =
                    Context(
                        messages =
                            mutableListOf(
                                UserMessage(
                                    listOf(
                                        TextContent("inspect"),
                                        ImageContent("aGVsbG8=", "image/png"),
                                    ),
                                ),
                            ),
                    )
                val userContext = Context(messages = mutableListOf(UserMessage("hello")))

                assertEquals(
                    StopReason.STOP,
                    provider
                        .stream(
                            models.single { it.api == "openai-completions" },
                            imageContext,
                            StreamOptions(apiKey = "copilot-token"),
                        ).result()
                        .stopReason,
                )
                assertEquals(
                    StopReason.STOP,
                    provider
                        .stream(
                            models.single { it.api == "openai-responses" },
                            userContext,
                            StreamOptions(apiKey = "copilot-token"),
                        ).result()
                        .stopReason,
                )
                assertEquals(
                    StopReason.STOP,
                    provider
                        .stream(
                            models.single { it.api == "anthropic-messages" },
                            userContext,
                            StreamOptions(
                                apiKey = "copilot-token",
                                interleavedThinking = true,
                            ),
                        ).result()
                        .stopReason,
                )

                val chat = fixture.requests.single { it.path == "/chat/completions" }
                assertEquals("Bearer copilot-token", chat.header("authorization"))
                assertEquals("user", chat.header("x-initiator"))
                assertEquals("conversation-edits", chat.header("openai-intent"))
                assertEquals("true", chat.header("copilot-vision-request"))
                assertEquals("vscode-chat", chat.header("copilot-integration-id"))

                val responses = fixture.requests.single { it.path == "/responses" }
                assertEquals("Bearer copilot-token", responses.header("authorization"))
                assertEquals("user", responses.header("x-initiator"))
                assertEquals("conversation-edits", responses.header("openai-intent"))
                assertEquals("vscode-chat", responses.header("copilot-integration-id"))

                val anthropic = fixture.requests.single { it.path == "/v1/messages" }
                assertEquals("Bearer copilot-token", anthropic.header("authorization"))
                assertNull(anthropic.header("x-api-key"))
                assertEquals("2023-06-01", anthropic.header("anthropic-version"))
                assertEquals("true", anthropic.header("anthropic-dangerous-direct-browser-access"))
                assertEquals("user", anthropic.header("x-initiator"))
                assertEquals("conversation-edits", anthropic.header("openai-intent"))
                assertFalse(
                    anthropic
                        .header("anthropic-beta")
                        .orEmpty()
                        .contains("interleaved-thinking-2025-05-14"),
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `stored Copilot credential filters models and rewrites request base url`() =
        runTest {
            val fixture = copilotFixture()
            try {
                val catalogModels = testCopilotModels("https://catalog.invalid")
                val oauth =
                    object : OAuthAuth {
                        override val name: String = "GitHub Copilot"

                        override suspend fun login(
                            interaction: works.earendil.pi.ai.AuthInteraction,
                        ): OAuthCredential = error("not used")

                        override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
                            credential

                        override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
                            ModelAuth(
                                apiKey = credential.access,
                                baseUrl = fixture.baseUrl,
                            )
                    }
                val provider =
                    GitHubCopilotProvider(
                        id = "github-copilot",
                        name = "GitHub Copilot",
                        models = catalogModels,
                        oauth = oauth,
                    )
                val selected = catalogModels.single { it.api == "openai-responses" }
                val credential =
                    OAuthCredential(
                        access = "stored-copilot-token",
                        refresh = "ghu-refresh",
                        expires = Long.MAX_VALUE,
                        enterpriseUrl = "company.ghe.com",
                        availableModelIds = listOf(selected.id),
                    )
                val runtime =
                    Models(
                        providers = listOf(provider),
                        modelsStore = InMemoryModelsStore(),
                        credentials =
                            InMemoryCredentialStore(
                                mapOf("github-copilot" to credential),
                            ),
                    )

                assertEquals(
                    listOf(selected.id),
                    runtime.getAvailable("github-copilot").map(Model::id),
                )
                val result =
                    runtime.complete(
                        selected,
                        Context(messages = mutableListOf(UserMessage("hello"))),
                    )

                assertEquals(StopReason.STOP, result.stopReason)
                val request = fixture.requests.single { it.path == "/responses" }
                assertEquals("Bearer stored-copilot-token", request.header("authorization"))
                assertTrue(request.body.contains("\"model\":\"${selected.id}\""))
            } finally {
                fixture.close()
            }
        }

    private fun testCopilotModels(baseUrl: String): List<Model> =
        listOf(
            model(
                id = "gpt-4.1",
                api = "openai-completions",
                provider = "github-copilot",
                baseUrl = baseUrl,
            ),
            model(
                id = "gpt-5.4",
                api = "openai-responses",
                provider = "github-copilot",
                baseUrl = baseUrl,
                reasoning = true,
            ),
            model(
                id = "claude-sonnet-4.6",
                api = "anthropic-messages",
                provider = "github-copilot",
                baseUrl = baseUrl,
                reasoning = true,
            ).copy(
                compat =
                    buildJsonObject {
                        put("forceAdaptiveThinking", true)
                        put("supportsEagerToolInputStreaming", false)
                    },
            ),
        ).map { it.copy(headers = GITHUB_COPILOT_HEADERS) }

    private fun copilotFixture(): CopilotFixture {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val fixture = CopilotFixture(server)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val headers =
                exchange.requestHeaders.entries.associate { (name, values) ->
                    name.lowercase() to values.joinToString(",")
                }
            fixture.requests +=
                CapturedRequest(
                    path = exchange.requestURI.path,
                    headers = headers,
                    body = body,
                )
            val response =
                when (exchange.requestURI.path) {
                    "/chat/completions" ->
                        """
                        data: {"choices":[{"delta":{"content":"chat"},"finish_reason":"stop"}]}

                        data: [DONE]

                        """.trimIndent()

                    "/responses" ->
                        """
                        data: {"type":"response.completed","response":{"id":"resp-1","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2},"output":[]}}

                        """.trimIndent()

                    "/v1/messages" ->
                        """
                        event: message_start
                        data: {"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":1,"output_tokens":0}}}

                        event: message_delta
                        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """.trimIndent()

                    else -> error("Unexpected path: ${exchange.requestURI.path}")
                }
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
        val body: String,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private class CopilotFixture(
        private val server: HttpServer,
    ) : AutoCloseable {
        val requests: MutableList<CapturedRequest> =
            Collections.synchronizedList(mutableListOf())
        val baseUrl: String = "http://127.0.0.1:${server.address.port}"

        override fun close() {
            server.stop(0)
        }
    }
}
