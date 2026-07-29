package works.earendil.pi.ai.providers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.test.runTest
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.ImagesContext
import works.earendil.pi.ai.ImagesModel
import works.earendil.pi.ai.ImagesOptions
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.ProviderHttpRequest
import works.earendil.pi.ai.ProviderHttpTransport
import works.earendil.pi.ai.ProviderHttpTransportResponse
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.Transport
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.ai.contentText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FetchOptionTest {
    private val context = Context(messages = mutableListOf(UserMessage("hello")))

    @Test
    fun `fetch transport is selected independently for each request`() =
        runTest {
            val model = model("openai-completions")
            val provider =
                OpenAIChatProvider(
                    id = "test-provider",
                    name = "Test",
                    baseUrl = model.baseUrl,
                    models = listOf(model),
                    apiKeyEnvNames = emptyList(),
                )
            val first = RecordingFetch(200, chatSse("first"))
            val second = RecordingFetch(200, chatSse("second"))

            val firstResult =
                provider.stream(
                    model,
                    context,
                    StreamOptions(apiKey = "test", fetch = first),
                ).result()
            val secondResult =
                provider.stream(
                    model,
                    context,
                    StreamOptions(apiKey = "test", fetch = second),
                ).result()

            assertEquals("first", contentText(firstResult.content))
            assertEquals("second", contentText(secondResult.content))
            assertEquals(1, first.requests.size)
            assertEquals(1, second.requests.size)
        }

    @Test
    fun `supported HTTP adapters use the request fetch transport`() =
        runTest {
            val fetch = RecordingFetch(401, """{"error":{"message":"custom rejection"}}""")

            val anthropicModel = model("anthropic-messages")
            AnthropicProvider(
                "test-provider",
                "Test",
                anthropicModel.baseUrl,
                listOf(anthropicModel),
                emptyList(),
            ).stream(
                anthropicModel,
                context,
                StreamOptions(apiKey = "test", fetch = fetch, maxRetries = 0),
            ).result()

            val chatModel = model("openai-completions")
            OpenAIChatProvider(
                "test-provider",
                "Test",
                chatModel.baseUrl,
                listOf(chatModel),
                emptyList(),
            ).stream(
                chatModel,
                context,
                StreamOptions(apiKey = "test", fetch = fetch, maxRetries = 0),
            ).result()

            val responsesModel = model("openai-responses")
            OpenAIResponsesProvider(
                "test-provider",
                "Test",
                responsesModel.baseUrl,
                listOf(responsesModel),
                emptyList(),
            ).stream(
                responsesModel,
                context,
                StreamOptions(apiKey = "test", fetch = fetch, maxRetries = 0),
            ).result()

            val azureModel = model("azure-openai-responses")
            AzureOpenAIResponsesProvider(
                "test-provider",
                "Test",
                listOf(azureModel),
                emptyList(),
            ).stream(
                azureModel,
                context,
                StreamOptions(apiKey = "test", fetch = fetch, maxRetries = 0),
            ).result()

            val mistralModel = model("mistral-conversations")
            MistralProvider(
                "test-provider",
                "Test",
                mistralModel.baseUrl,
                listOf(mistralModel),
                emptyList(),
            ).stream(
                mistralModel,
                context,
                StreamOptions(apiKey = "test", fetch = fetch, maxRetries = 0),
            ).result()

            val codexModel = model("openai-codex-responses", provider = "openai-codex")
            OpenAICodexProvider(
                id = "openai-codex",
                name = "OpenAI Codex",
                models = listOf(codexModel),
            ).stream(
                codexModel,
                context,
                StreamOptions(
                    apiKey = codexToken(),
                    fetch = fetch,
                    transport = Transport.SSE,
                    maxRetries = 0,
                ),
            ).result()

            val piModel = model("pi-messages")
            PiMessagesProvider(
                "test-provider",
                "Test",
                piModel.baseUrl,
                listOf(piModel),
                emptyList(),
                environment = { null },
            ).stream(
                piModel,
                context,
                StreamOptions(apiKey = "test", fetch = fetch, maxRetries = 0),
            ).result()

            generateOpenRouterImages(
                imageModel(),
                ImagesContext(listOf(TextContent("draw"))),
                ImagesOptions(apiKey = "test", fetch = fetch, maxRetries = 0),
            )

            assertEquals(8, fetch.requests.size)
            assertTrue(fetch.requests.any { it.url.endsWith("/v1/messages") })
            assertTrue(fetch.requests.any { it.url.endsWith("/chat/completions") })
            assertTrue(fetch.requests.any { it.url.contains("/responses") })
            assertTrue(fetch.requests.any { it.url.endsWith("/v1/chat/completions") })
            assertTrue(fetch.requests.any { it.url.endsWith("/codex/responses") })
            assertTrue(fetch.requests.any { it.url.endsWith("/messages") })
        }

    @Test
    fun `pi messages keeps streaming body and response callback with custom fetch`() =
        runTest {
            val fetch =
                RecordingFetch(
                    status = 200,
                    body =
                        """
                        data: {"type":"start"}

                        data: {"type":"done","reason":"stop","usage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,"cost":{"input":0.0,"output":0.0,"cacheRead":0.0,"cacheWrite":0.0,"total":0.0}}}

                        """.trimIndent(),
                    headers = mapOf("x-upstream" to listOf("custom")),
                )
            val model = model("pi-messages")
            val provider =
                PiMessagesProvider(
                    "test-provider",
                    "Test",
                    model.baseUrl,
                    listOf(model),
                    emptyList(),
                    environment = { null },
                )
            var callbackValue: String? = null

            val result =
                provider.stream(
                    model,
                    context,
                    StreamOptions(
                        apiKey = "test",
                        fetch = fetch,
                        onResponse = { response, _ ->
                            callbackValue = response.headers["x-upstream"]
                        },
                    ),
                ).result()

            assertEquals(StopReason.STOP, result.stopReason)
            assertEquals("custom", callbackValue)
            assertTrue(fetch.requests.single().body.toString(StandardCharsets.UTF_8).contains("\"model\""))
        }

    @Test
    fun `google adapters reject custom fetch instead of bypassing it`() =
        runTest {
            val fetch = RecordingFetch(200, "")
            val googleModel = model("google-generative-ai")
            val google =
                GoogleProvider(
                    "test-provider",
                    "Test",
                    googleModel.baseUrl,
                    listOf(googleModel),
                    emptyList(),
                ).stream(
                    googleModel,
                    context,
                    StreamOptions(apiKey = "test", fetch = fetch),
                ).result()
            val vertexModel = model("google-vertex")
            val vertex =
                GoogleVertexProvider(
                    "test-provider",
                    "Test",
                    listOf(vertexModel),
                    environment = { null },
                    accessTokenProvider = { "unused" },
                ).stream(
                    vertexModel,
                    context,
                    StreamOptions(apiKey = "test", fetch = fetch),
                ).result()

            assertTrue(
                google.errorMessage.orEmpty().contains(
                    "Custom fetch is not supported by the Google Generative AI adapter",
                ),
            )
            assertTrue(
                vertex.errorMessage.orEmpty().contains(
                    "Custom fetch is not supported by the Google Vertex adapter",
                ),
            )
            assertTrue(fetch.requests.isEmpty())
        }

    private fun model(
        api: String,
        provider: String = "test-provider",
    ) = Model(
        id = "test-model",
        name = "Test Model",
        api = api,
        provider = provider,
        baseUrl = "https://upstream.invalid/v1",
        reasoning = false,
        input = listOf(ModelInput.TEXT),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 10_000,
        maxTokens = 1_000,
    )

    private fun imageModel() =
        ImagesModel(
            id = "test-image",
            name = "Test Image",
            api = "openrouter-images",
            provider = "openrouter",
            baseUrl = "https://upstream.invalid/v1",
            input = listOf(ModelInput.TEXT),
            output = listOf(ModelInput.IMAGE),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        )

    private fun chatSse(text: String): String =
        """
        data: {"choices":[{"delta":{"content":"$text"},"finish_reason":"stop"}]}

        data: [DONE]

        """.trimIndent()

    private fun codexToken(): String {
        val payload =
            Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                    """{"https://api.openai.com/auth":{"chatgpt_account_id":"account"}}"""
                        .toByteArray(StandardCharsets.UTF_8),
                )
        return "header.$payload.signature"
    }

    private class RecordingFetch(
        private val status: Int,
        private val body: String,
        private val headers: Map<String, List<String>> =
            mapOf("content-type" to listOf("application/json")),
    ) : ProviderHttpTransport {
        val requests = mutableListOf<ProviderHttpRequest>()

        override suspend fun fetch(request: ProviderHttpRequest): ProviderHttpTransportResponse {
            requests += request
            return ProviderHttpTransportResponse(
                status = status,
                headers = headers,
                body = ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8)),
            )
        }
    }
}
