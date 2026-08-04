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
        val luna = openAI.single { it.id == "gpt-5.6-luna" }
        val terra = openAI.single { it.id == "gpt-5.6-terra" }
        val fireworks = catalog.modelsByProvider.getValue("fireworks")
        val fireworksKimi = fireworks.single { it.id == "accounts/fireworks/models/kimi-k3" }
        val fireworksKimiFast = fireworks.single { it.id == "accounts/fireworks/routers/kimi-k3-fast" }
        val copilotOpus5 =
            catalog.modelsByProvider
                .getValue("github-copilot")
                .single { it.id == "claude-opus-5" }
        val qwenTokenPlan = catalog.modelsByProvider.getValue("qwen-token-plan")
        val qwenDeepSeek = qwenTokenPlan.single { it.id == "deepseek-v4-flash" }
        val qwen38 = qwenTokenPlan.single { it.id == "qwen3.8-max-preview" }
        val qwenUnsupported = qwenTokenPlan.single { it.id == "qwen3.7-plus" }

        assertEquals(3, catalog.schemaVersion)
        Instant.parse(assertNotNull(catalog.generatedAt))
        assertEquals(
            "8f42becde9d67b0f50730e36639d319dd0da3f4c26c0ca8d2c6e8c93a5a566a0",
            catalog.structureHash,
        )
        assertEquals(38, catalog.modelsByProvider.size)
        assertEquals(1_151, catalog.modelsByProvider.values.sumOf(List<works.earendil.pi.ai.Model>::size))
        assertEquals("openai-responses", sol.api)
        assertEquals(272_000, sol.contextWindow)
        assertEquals(128_000, sol.maxTokens)
        assertEquals(5.0, sol.cost.input)
        assertEquals("max", sol.thinkingLevelMap[works.earendil.pi.ai.ModelThinkingLevel.MAX])
        assertEquals(0.2, luna.cost.input)
        assertEquals(1.2, luna.cost.output)
        assertEquals(2.0, terra.cost.input)
        assertEquals(12.0, terra.cost.output)
        listOf(fireworksKimi, fireworksKimiFast).forEach { model ->
            assertEquals("openai-completions", model.api)
            assertEquals("https://api.fireworks.ai/inference/v1", model.baseUrl)
            assertEquals(
                "openai",
                model.compat?.get("thinkingFormat")?.toString()?.trim('"'),
            )
            assertEquals(
                "kimi",
                model.compat?.get("deferredToolsMode")?.toString()?.trim('"'),
            )
            assertEquals("max", model.thinkingLevelMap[works.earendil.pi.ai.ModelThinkingLevel.MAX])
        }
        assertEquals("anthropic-messages", copilotOpus5.api)
        assertEquals(1_000_000, copilotOpus5.contextWindow)
        assertEquals(
            "low",
            copilotOpus5.thinkingLevelMap[works.earendil.pi.ai.ModelThinkingLevel.MINIMAL],
        )
        assertEquals(
            "xhigh",
            copilotOpus5.thinkingLevelMap[works.earendil.pi.ai.ModelThinkingLevel.XHIGH],
        )
        assertEquals(
            "max",
            copilotOpus5.thinkingLevelMap[works.earendil.pi.ai.ModelThinkingLevel.MAX],
        )
        assertEquals(
            "qwen",
            qwenDeepSeek.compat?.get("thinkingFormat")?.toString()?.trim('"'),
        )
        assertEquals(
            true,
            qwenDeepSeek.compat?.get("supportsReasoningEffort")?.toString()?.toBoolean(),
        )
        assertEquals(
            "high",
            qwenDeepSeek.thinkingLevelMap[works.earendil.pi.ai.ModelThinkingLevel.HIGH],
        )
        assertEquals(
            "max",
            qwenDeepSeek.thinkingLevelMap[works.earendil.pi.ai.ModelThinkingLevel.MAX],
        )
        assertEquals(
            "xhigh",
            qwen38.thinkingLevelMap[works.earendil.pi.ai.ModelThinkingLevel.XHIGH],
        )
        assertEquals(
            null,
            qwen38.thinkingLevelMap[works.earendil.pi.ai.ModelThinkingLevel.HIGH],
        )
        assertFalse(
            qwenUnsupported.compat
                ?.get("supportsReasoningEffort")
                ?.toString()
                ?.toBoolean()
                ?: false,
        )
        assertTrue(qwenUnsupported.thinkingLevelMap.isEmpty())
        assertTrue(catalog.unsupportedApis.isEmpty())
    }

    @Test
    fun `providers expose every executable protocol including dynamic Radius`() {
        val providers = builtInProviders()
        val ids = providers.map { it.id }.toSet()

        assertEquals(39, providers.size)
        assertEquals(1_151, providers.sumOf { it.getModels().size })
        assertTrue(
            ids.containsAll(
                setOf(
                    "amazon-bedrock",
                    "anthropic",
                    "azure-openai-responses",
                    "baseten",
                    "cloudflare-ai-gateway",
                    "cloudflare-workers-ai",
                    "deepseek",
                    "google",
                    "google-vertex",
                    "mistral",
                    "github-copilot",
                    "openai",
                    "openai-codex",
                    "openrouter",
                    "radius",
                    "xai",
                ),
            ),
        )
        val copilot = providers.single { it.id == "github-copilot" }
        assertEquals("GitHub Copilot", copilot.name)
        assertEquals(29, copilot.getModels().size)
        assertEquals(
            mapOf(
                "anthropic-messages" to 10,
                "openai-completions" to 7,
                "openai-responses" to 12,
            ),
            copilot.getModels().groupingBy { it.api }.eachCount(),
        )
        assertNotNull(copilot.oauth)
        val azure = providers.single { it.id == "azure-openai-responses" }
        assertEquals("Azure OpenAI", azure.name)
        assertEquals(38, azure.getModels().size)
        val mistral = providers.single { it.id == "mistral" }
        assertEquals("Mistral", mistral.name)
        assertEquals(31, mistral.getModels().size)
        val cloudflareGateway = providers.single { it.id == "cloudflare-ai-gateway" }
        assertEquals("Cloudflare AI Gateway", cloudflareGateway.name)
        assertEquals(43, cloudflareGateway.getModels().size)
        val cloudflareWorkers = providers.single { it.id == "cloudflare-workers-ai" }
        assertEquals("Cloudflare Workers AI", cloudflareWorkers.name)
        assertEquals(13, cloudflareWorkers.getModels().size)
        val vertex = providers.single { it.id == "google-vertex" }
        assertEquals("Google Vertex AI", vertex.name)
        assertEquals(12, vertex.getModels().size)
        val bedrock = providers.single { it.id == "amazon-bedrock" }
        assertEquals("Amazon Bedrock", bedrock.name)
        assertEquals(114, bedrock.getModels().size)
        assertNotNull(bedrock.getModels().singleOrNull { it.id == "global.anthropic.claude-opus-5" })
        assertFalse(bedrock.getModels().any { it.id == "anthropic.claude-opus-5" })
        val codex = providers.single { it.id == "openai-codex" }
        assertEquals("OpenAI Codex", codex.name)
        assertEquals(7, codex.getModels().size)
        val opencodeGo = providers.single { it.id == "opencode-go" }
        assertEquals("OpenCode Go", opencodeGo.name)
        assertNotNull(providers.single { it.id == "anthropic" }.oauth)
        val openRouter = providers.single { it.id == "openrouter" }
        assertEquals("Sign in with OpenRouter", assertNotNull(openRouter.oauth).loginLabel)
        val kimiCoding = providers.single { it.id == "kimi-coding" }
        assertEquals("Sign in with Kimi Code", assertNotNull(kimiCoding.oauth).loginLabel)
        val xai = providers.single { it.id == "xai" }
        assertEquals("Sign in with SuperGrok or X Premium", assertNotNull(xai.oauth).loginLabel)
        val radius = providers.single { it.id == "radius" }
        assertEquals("Radius", radius.name)
        assertEquals(emptyList(), radius.getModels())
        assertNotNull(radius.oauth)
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
    fun `remote catalog wrapper retains OAuth capabilities`() =
        runTest {
            val models = builtInModelsCollection(BuiltInModelsOptions())

            assertNotNull(models.getProvider("openai-codex")?.oauth)
            assertNotNull(models.getProvider("openrouter")?.oauth)
            assertNotNull(models.getProvider("kimi-coding")?.oauth)
            assertNotNull(models.getProvider("radius")?.oauth)
            assertNotNull(models.getProvider("xai")?.oauth)
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
                "pi-messages",
            )
    }
}
