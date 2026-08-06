package works.earendil.pi.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.providers.buildOpenAIChatRequestBody
import works.earendil.pi.ai.providers.buildOpenAIResponsesRequestBody

class SamplingOptionsTest {
    @Test
    fun `OpenAI compatible payloads apply sampling params last`() {
        val options =
            StreamOptions(
                temperature = 0.0,
                samplingParams =
                    JsonObject(
                        mapOf(
                            "temperature" to JsonPrimitive(1.0),
                            "top_p" to JsonPrimitive(0.95),
                            "top_k" to JsonPrimitive(0),
                        ),
                    ),
            )

        val chat = buildOpenAIChatRequestBody(model("openai-completions"), Context(), options)
        val responses = buildOpenAIResponsesRequestBody(model("openai-responses"), Context(), options)

        assertEquals(1.0, chat.getValue("temperature").jsonPrimitive.double)
        assertEquals(0.95, chat.getValue("top_p").jsonPrimitive.double)
        assertEquals(0, chat.getValue("top_k").jsonPrimitive.content.toInt())
        assertEquals(1.0, responses.getValue("temperature").jsonPrimitive.double)
        assertEquals(0.95, responses.getValue("top_p").jsonPrimitive.double)
    }

    @Test
    fun `simple stream merges request sampling keys over model defaults`() =
        runTest {
            var captured: StreamOptions? = null
            val provider =
                object : Provider {
                    override val id = "capture"
                    override val name = "Capture"

                    override fun getModels(): List<Model> = emptyList()

                    override suspend fun stream(
                        model: Model,
                        context: Context,
                        options: StreamOptions,
                    ): AssistantMessageEventStream {
                        captured = options
                        return createAssistantMessageEventStream()
                    }
                }
            val model =
                model("openai-completions").copy(
                    provider = provider.id,
                    samplingParams =
                        JsonObject(
                            mapOf(
                                "top_p" to JsonPrimitive(0.95),
                                "min_p" to JsonPrimitive(0.05),
                            ),
                        ),
                )

            provider.streamSimple(
                model,
                Context(),
                SimpleStreamOptions(
                    stream =
                        StreamOptions(
                            samplingParams = JsonObject(mapOf("top_p" to JsonPrimitive(0.5))),
                        ),
                ),
            )

            assertEquals(0.5, captured?.samplingParams?.getValue("top_p")?.jsonPrimitive?.double)
            assertEquals(0.05, captured?.samplingParams?.getValue("min_p")?.jsonPrimitive?.double)
        }

    @Test
    fun `OpenAI completions caps vLLM thinking budget and leaves answer room`() {
        val reasoningModel =
            model("openai-completions").copy(
                reasoning = true,
                maxTokens = 10_000,
                compat = buildJsonObject { put("supportsThinkingTokenBudget", true) },
            )

        val payload =
            buildOpenAIChatRequestBody(
                reasoningModel,
                Context(),
                StreamOptions(
                    maxTokens = 5_000,
                    reasoningEffort = "high",
                    thinkingBudgets = ThinkingBudgets(high = 8_000),
                ),
            )

        assertEquals(3_976, payload.getValue("thinking_token_budget").jsonPrimitive.int)
    }

    private fun model(api: String): Model =
        Model(
            id = "sampling-model",
            name = "Sampling Model",
            api = api,
            provider = "sampling",
            baseUrl = "https://example.invalid/v1",
            reasoning = false,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 8_192,
            maxTokens = 1_024,
        )
}
