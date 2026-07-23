package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AzureOpenAIResponsesProviderTest {
    @Test
    fun `normalizes Azure roots and preserves explicit proxy paths`() {
        val normalized =
            mapOf(
                "https://resource.openai.azure.com" to
                    "https://resource.openai.azure.com/openai/v1",
                "https://resource.cognitiveservices.azure.com/" to
                    "https://resource.cognitiveservices.azure.com/openai/v1",
                "https://resource.ai.azure.com/openai" to
                    "https://resource.ai.azure.com/openai/v1",
                "https://resource.services.ai.azure.com/openai/v1/responses" to
                    "https://resource.services.ai.azure.com/openai/v1",
                "https://resource.openai.azure.com/openai?api-version=old" to
                    "https://resource.openai.azure.com/openai/v1",
            )
        normalized.forEach { (baseUrl, expected) ->
            assertEquals(expected, normalizeAzureOpenAIBaseUrl(baseUrl))
        }
        assertEquals(
            "https://resource.openai.azure.com/openai/v1",
            normalizeAzureOpenAIBaseUrl("https://resource.openai.azure.com/openai/v1"),
        )
        assertEquals(
            "https://proxy.example.com/custom/v1?tenant=one",
            normalizeAzureOpenAIBaseUrl("https://proxy.example.com/custom/v1?tenant=one"),
        )
        assertFailsWith<IllegalStateException> {
            normalizeAzureOpenAIBaseUrl("not-a-url")
        }
    }

    @Test
    fun `resolves explicit env resource and model configuration in priority order`() {
        val model =
            model(
                id = "fixture",
                api = "azure-openai-responses",
                provider = "azure-openai-responses",
                baseUrl = "https://model.example.com/v1",
            )
        val explicit =
            resolveAzureOpenAIConfig(
                model,
                StreamOptions(
                    azureBaseUrl = "https://explicit.example.com/v1",
                    azureResourceName = "ignored-resource",
                    azureApiVersion = "explicit-version",
                    env =
                        mapOf(
                            "AZURE_OPENAI_BASE_URL" to "https://env.example.com/v1",
                            "AZURE_OPENAI_RESOURCE_NAME" to "env-resource",
                            "AZURE_OPENAI_API_VERSION" to "env-version",
                        ),
                ),
            )
        assertEquals("https://explicit.example.com/v1", explicit.baseUrl)
        assertEquals("explicit-version", explicit.apiVersion)

        val fromResource =
            resolveAzureOpenAIConfig(
                model.copy(baseUrl = ""),
                StreamOptions(
                    env =
                        mapOf(
                            "AZURE_OPENAI_RESOURCE_NAME" to "resource-name",
                            "AZURE_OPENAI_API_VERSION" to "2026-01-01",
                        ),
                ),
            )
        assertEquals("https://resource-name.openai.azure.com/openai/v1", fromResource.baseUrl)
        assertEquals("2026-01-01", fromResource.apiVersion)

        val fromModel = resolveAzureOpenAIConfig(model, StreamOptions())
        assertEquals("https://model.example.com/v1", fromModel.baseUrl)
        assertEquals("v1", fromModel.apiVersion)
    }

    @Test
    fun `resolves deployment from explicit option then map then model id`() {
        val model =
            model(
                id = "gpt-fixture",
                api = "azure-openai-responses",
                provider = "azure-openai-responses",
                baseUrl = "",
            )
        assertEquals(
            "explicit-deployment",
            resolveAzureDeploymentName(
                model,
                StreamOptions(
                    azureDeploymentName = "explicit-deployment",
                    env = mapOf("AZURE_OPENAI_DEPLOYMENT_NAME_MAP" to "gpt-fixture=mapped"),
                ),
            ),
        )
        assertEquals(
            "mapped-deployment",
            resolveAzureDeploymentName(
                model,
                StreamOptions(
                    env =
                        mapOf(
                            "AZURE_OPENAI_DEPLOYMENT_NAME_MAP" to
                                "other=ignored, gpt-fixture = mapped-deployment,invalid",
                        ),
                ),
            ),
        )
        assertEquals("gpt-fixture", resolveAzureDeploymentName(model, StreamOptions()))
    }

    @Test
    fun `sends Azure auth version deployment and cache key`() =
        runTest {
            val request = AtomicReference<CapturedRequest>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                request.set(
                    CapturedRequest(
                        path = exchange.requestURI.path,
                        query = exchange.requestURI.rawQuery,
                        headers =
                            exchange.requestHeaders.entries.associate { (key, value) ->
                                key.lowercase() to value.single()
                            },
                        body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8),
                    ),
                )
                val response =
                    """
                    data: {"type":"response.completed","response":{"id":"resp-1","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2},"output":[]}}

                    """.trimIndent()
                val bytes = response.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("content-type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val model =
                    model(
                        id = "gpt-fixture",
                        api = "azure-openai-responses",
                        provider = "azure-openai-responses",
                        baseUrl = "",
                    )
                val provider =
                    AzureOpenAIResponsesProvider(
                        id = "azure-openai-responses",
                        name = "Azure OpenAI",
                        models = listOf(model),
                        apiKeyEnvNames = listOf("AZURE_OPENAI_API_KEY"),
                    )
                provider.stream(
                    model,
                    Context(messages = mutableListOf(UserMessage("hello"))),
                    StreamOptions(
                        apiKey = "secret",
                        cacheRetention = CacheRetention.NONE,
                        sessionId = "x".repeat(67),
                        azureBaseUrl = "http://127.0.0.1:${server.address.port}/proxy?tenant=one",
                        azureApiVersion = "2026-07-01-preview",
                        azureDeploymentName = "deployed-model",
                    ),
                ).result()

                val captured = request.get()
                assertEquals("/proxy", captured.path)
                assertEquals("api-version=2026-07-01-preview", captured.query)
                assertEquals("secret", captured.headers["api-key"])
                assertFalse("authorization" in captured.headers)
                val body = providerJson.parseToJsonElement(captured.body).jsonObject
                assertEquals("deployed-model", body.getValue("model").jsonPrimitive.content)
                assertEquals("x".repeat(64), body.getValue("prompt_cache_key").jsonPrimitive.content)
                assertTrue(body.getValue("store").jsonPrimitive.content == "false")
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `requires an endpoint when neither options environment nor model provide one`() {
        val model =
            model(
                id = "fixture",
                api = "azure-openai-responses",
                provider = "azure-openai-responses",
                baseUrl = "",
            )
        val error =
            assertFailsWith<IllegalStateException> {
                resolveAzureOpenAIConfig(
                    model,
                    StreamOptions(
                        env =
                            mapOf(
                                "AZURE_OPENAI_BASE_URL" to "",
                                "AZURE_OPENAI_RESOURCE_NAME" to "",
                            ),
                    ),
                )
            }
        assertTrue(error.message.orEmpty().contains("Azure OpenAI base URL is required"))
    }

    private data class CapturedRequest(
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: String,
    )
}
