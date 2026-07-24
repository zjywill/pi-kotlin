package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.test.runTest
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuiltInCatalogTest {
    @Test
    fun `catalog matches the pinned manifest and model metadata`() {
        val catalog = builtInCatalog()
        val openAI = catalog.modelsByProvider.getValue("openai")
        val sol = openAI.single { it.id == "gpt-5.6-sol" }

        assertEquals(3, catalog.schemaVersion)
        Instant.parse(assertNotNull(catalog.generatedAt))
        assertEquals(
            "57655e637e6c882faa1975fa64df872aef3803dc2a1214b7ee0b58c57387354f",
            catalog.structureHash,
        )
        assertEquals(37, catalog.modelsByProvider.size)
        assertEquals(1_109, catalog.modelsByProvider.values.sumOf(List<works.earendil.pi.ai.Model>::size))
        assertEquals("openai-responses", sol.api)
        assertEquals(272_000, sol.contextWindow)
        assertEquals(128_000, sol.maxTokens)
        assertEquals(5.0, sol.cost.input)
        assertEquals("max", sol.thinkingLevelMap[works.earendil.pi.ai.ModelThinkingLevel.MAX])
        assertTrue(catalog.unsupportedApis.isEmpty())
    }

    @Test
    fun `providers expose only executable protocols and unsupported auth providers stay hidden`() {
        val providers = builtInProviders()
        val ids = providers.map { it.id }.toSet()

        assertEquals(36, providers.size)
        assertEquals(1_081, providers.sumOf { it.getModels().size })
        assertTrue(
            ids.containsAll(
                setOf(
                    "amazon-bedrock",
                    "anthropic",
                    "azure-openai-responses",
                    "cloudflare-ai-gateway",
                    "cloudflare-workers-ai",
                    "deepseek",
                    "google",
                    "google-vertex",
                    "mistral",
                    "openai",
                    "openai-codex",
                    "openrouter",
                    "xai",
                ),
            ),
        )
        assertFalse("github-copilot" in ids)
        val azure = providers.single { it.id == "azure-openai-responses" }
        assertEquals("Azure OpenAI", azure.name)
        assertEquals(46, azure.getModels().size)
        val mistral = providers.single { it.id == "mistral" }
        assertEquals("Mistral", mistral.name)
        assertEquals(30, mistral.getModels().size)
        val cloudflareGateway = providers.single { it.id == "cloudflare-ai-gateway" }
        assertEquals("Cloudflare AI Gateway", cloudflareGateway.name)
        assertEquals(42, cloudflareGateway.getModels().size)
        val cloudflareWorkers = providers.single { it.id == "cloudflare-workers-ai" }
        assertEquals("Cloudflare Workers AI", cloudflareWorkers.name)
        assertEquals(13, cloudflareWorkers.getModels().size)
        val vertex = providers.single { it.id == "google-vertex" }
        assertEquals("Google Vertex AI", vertex.name)
        assertEquals(12, vertex.getModels().size)
        val bedrock = providers.single { it.id == "amazon-bedrock" }
        assertEquals("Amazon Bedrock", bedrock.name)
        assertEquals(109, bedrock.getModels().size)
        val codex = providers.single { it.id == "openai-codex" }
        assertEquals("OpenAI Codex", codex.name)
        assertEquals(7, codex.getModels().size)
        assertTrue(providers.flatMap(works.earendil.pi.ai.Provider::getModels).all { it.api in SUPPORTED_TEST_APIS })
    }

    @Test
    fun `multi protocol providers retain every supported model without duplicate ids`() {
        val xai = builtInProviders().single { it.id == "xai" }
        val opencode = builtInProviders().single { it.id == "opencode" }

        assertTrue(xai.getModels().any { it.api == "openai-completions" })
        assertTrue(xai.getModels().any { it.api == "openai-responses" })
        assertTrue(opencode.getModels().any { it.api == "anthropic-messages" })
        assertTrue(opencode.getModels().any { it.api == "google-generative-ai" })
        assertTrue(opencode.getModels().any { it.api == "openai-completions" })
        assertTrue(opencode.getModels().any { it.api == "openai-responses" })
        assertEquals(xai.getModels().size, xai.getModels().map { it.id }.distinct().size)
        assertNotNull(builtInModelsCollection().getModel("openai", "gpt-5.5"))
    }

    @Test
    fun `multi protocol provider dispatches each model to its catalog api`() =
        runTest {
            val paths = Collections.synchronizedList(mutableListOf<String>())
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                paths += exchange.requestURI.path
                exchange.requestBody.readAllBytes()
                val response =
                    if (exchange.requestURI.path.endsWith("/chat/completions")) {
                        """
                        data: {"choices":[{"delta":{"content":"chat"},"finish_reason":"stop"}]}

                        data: [DONE]

                        """.trimIndent()
                    } else {
                        """
                        data: {"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg-1","content":[]}}

                        data: {"type":"response.output_text.delta","output_index":0,"delta":"responses"}

                        data: {"type":"response.completed","response":{"id":"resp-1","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2},"output":[]}}

                        """.trimIndent()
                    }
                val bytes = response.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("content-type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val provider = builtInProviders().single { it.id == "xai" }
                val baseUrl = "http://127.0.0.1:${server.address.port}"
                val chatModel = provider.getModels().single { it.id == "grok-4.3" }.copy(baseUrl = baseUrl)
                val responsesModel = provider.getModels().single { it.id == "grok-4.5" }.copy(baseUrl = baseUrl)

                val chat =
                    provider.stream(
                        chatModel,
                        Context(messages = mutableListOf(UserMessage("hello"))),
                        StreamOptions(apiKey = "test"),
                    ).result()
                val responses =
                    provider.stream(
                        responsesModel,
                        Context(messages = mutableListOf(UserMessage("hello"))),
                        StreamOptions(apiKey = "test"),
                    ).result()

                assertEquals("chat", (chat.content.single() as TextContent).text)
                assertEquals("responses", (responses.content.single() as TextContent).text)
                assertEquals(listOf("/chat/completions", "/responses"), paths)
            } finally {
                server.stop(0)
            }
        }

    private companion object {
        val SUPPORTED_TEST_APIS =
            setOf(
                "anthropic-messages",
                "azure-openai-responses",
                "bedrock-converse-stream",
                "google-generative-ai",
                "google-vertex",
                "mistral-conversations",
                "openai-completions",
                "openai-codex-responses",
                "openai-responses",
            )
    }
}
