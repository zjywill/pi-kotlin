package works.earendil.pi.ai.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.ModelThinkingLevel
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.ThinkingBudgets
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.UserMessage

fun main() {
    val context =
        Context(
            systemPrompt = "system",
            messages = mutableListOf(UserMessage("hello", timestamp = 1)),
            tools =
                listOf(
                    ToolDefinition(
                        name = "echo",
                        description = "Echo",
                        parameters =
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put(
                                            "value",
                                            buildJsonObject { put("type", "string") },
                                        )
                                    },
                                )
                                put("required", JsonArray(listOf(JsonPrimitive("value"))))
                            },
                    ),
                ),
        )
    val options =
        StreamOptions(
            temperature = 0.25,
            maxTokens = 123,
            apiKey = "test",
            cacheRetention = CacheRetention.NONE,
        )
    val payloads =
        buildJsonObject {
            put(
                "openai-completions",
                buildOpenAIChatRequestBody(fixtureModel("openai-completions"), context, options),
            )
            put(
                "openai-responses",
                buildOpenAIResponsesRequestBody(fixtureModel("openai-responses"), context, options),
            )
            put(
                "azure-openai-responses",
                buildAzureOpenAIResponsesRequestBody(
                    fixtureModel(
                        "azure-openai-responses",
                        provider = "azure-openai-responses",
                    ).copy(baseUrl = ""),
                    context,
                    options.copy(
                        sessionId = "x".repeat(67),
                        azureBaseUrl = "https://fixture.invalid/v1",
                        azureDeploymentName = "fixture-deployment",
                    ),
                ),
            )
            put(
                "anthropic-messages",
                buildAnthropicRequestBody(fixtureModel("anthropic-messages"), context, options),
            )
            put(
                "google-generative-ai",
                buildGoogleRequestBody(
                    fixtureModel("google-generative-ai", provider = "google"),
                    context,
                    options,
                ),
            )
            put(
                "google-vertex",
                buildGoogleVertexParams(
                    fixtureModel("google-vertex", provider = "google-vertex"),
                    context,
                    options,
                ),
            )
            put(
                "mistral-conversations",
                buildMistralRequestBody(
                    fixtureModel("mistral-conversations", provider = "mistral"),
                    context,
                    options,
                ),
            )
            put(
                "bedrock-converse-stream",
                buildBedrockRequestBody(
                    fixtureModel("bedrock-converse-stream", provider = "amazon-bedrock"),
                    context,
                    options,
                    { null },
                ),
            )
            put(
                "openai-responses-reasoning",
                buildOpenAIResponsesRequestBody(
                    fixtureModel("openai-responses").copy(
                        reasoning = true,
                        thinkingLevelMap =
                            mapOf(
                                ModelThinkingLevel.OFF to "none",
                                ModelThinkingLevel.HIGH to "high",
                            ),
                    ),
                    context,
                    options.copy(temperature = null, reasoning = ThinkingLevel.HIGH),
                ),
            )
            put(
                "anthropic-messages-reasoning",
                buildAnthropicRequestBody(
                    fixtureModel("anthropic-messages").copy(
                        reasoning = true,
                        compat = buildJsonObject { put("forceAdaptiveThinking", true) },
                    ),
                    context,
                    options.copy(temperature = null, reasoning = ThinkingLevel.HIGH),
                ),
            )
            put(
                "azure-openai-responses-reasoning",
                buildAzureOpenAIResponsesRequestBody(
                    fixtureModel(
                        "azure-openai-responses",
                        provider = "azure-openai-responses",
                    ).copy(
                        baseUrl = "",
                        reasoning = true,
                        thinkingLevelMap =
                            mapOf(
                                ModelThinkingLevel.OFF to "none",
                                ModelThinkingLevel.HIGH to "high",
                            ),
                    ),
                    context,
                    options.copy(
                        temperature = null,
                        reasoningEffort = "high",
                        reasoningSummary = "detailed",
                        azureBaseUrl = "https://fixture.invalid/v1",
                        azureDeploymentName = "reasoning-deployment",
                    ),
                ),
            )
            put(
                "google-generative-ai-reasoning",
                buildGoogleRequestBody(
                    fixtureModel("google-generative-ai", provider = "google").copy(
                        id = "gemini-3.1-pro-preview",
                        reasoning = true,
                    ),
                    context,
                    options.copy(temperature = null, reasoning = ThinkingLevel.MEDIUM),
                ),
            )
            put(
                "google-vertex-reasoning",
                buildGoogleVertexParams(
                    fixtureModel("google-vertex", provider = "google-vertex").copy(
                        id = "gemini-3.1-pro-preview",
                        reasoning = true,
                        thinkingLevelMap =
                            mapOf(
                                ModelThinkingLevel.OFF to null,
                                ModelThinkingLevel.MINIMAL to null,
                                ModelThinkingLevel.LOW to "LOW",
                                ModelThinkingLevel.MEDIUM to null,
                                ModelThinkingLevel.HIGH to "HIGH",
                            ),
                    ),
                    context,
                    options.copy(temperature = null, reasoning = ThinkingLevel.HIGH),
                ),
            )
            put(
                "google-vertex-thinking-disabled",
                buildGoogleVertexParams(
                    fixtureModel("google-vertex", provider = "google-vertex").copy(
                        id = "gemini-3-flash-preview",
                        reasoning = true,
                        thinkingLevelMap = mapOf(ModelThinkingLevel.OFF to null),
                    ),
                    context,
                    options.copy(temperature = null),
                ),
            )
            put(
                "openai-completions-reasoning",
                buildOpenAIChatRequestBody(
                    fixtureModel("openai-completions", provider = "deepseek").copy(
                        baseUrl = "https://api.deepseek.com",
                        reasoning = true,
                        compat =
                            buildJsonObject {
                                put("supportsStore", false)
                                put("supportsDeveloperRole", false)
                                put("requiresReasoningContentOnAssistantMessages", true)
                                put("thinkingFormat", "deepseek")
                            },
                    ),
                    context,
                    options.copy(temperature = null, reasoning = ThinkingLevel.HIGH),
                ),
            )
            put(
                "mistral-conversations-reasoning-effort",
                buildMistralRequestBody(
                    fixtureModel("mistral-conversations", provider = "mistral").copy(
                        id = "mistral-small-2603",
                        reasoning = true,
                    ),
                    context,
                    options.copy(
                        temperature = null,
                        cacheRetention = CacheRetention.SHORT,
                        sessionId = "session-123",
                        reasoningEffort = "high",
                    ),
                ),
            )
            put(
                "mistral-conversations-prompt-mode",
                buildMistralRequestBody(
                    fixtureModel("mistral-conversations", provider = "mistral").copy(
                        id = "magistral-medium-latest",
                        reasoning = true,
                    ),
                    context,
                    options.copy(
                        temperature = null,
                        promptMode = "reasoning",
                    ),
                ),
            )
            put(
                "bedrock-converse-stream-adaptive-thinking",
                buildBedrockRequestBody(
                    fixtureModel("bedrock-converse-stream", provider = "amazon-bedrock").copy(
                        id = "global.anthropic.claude-opus-4-8-v1",
                        name = "Claude Opus 4.8",
                        reasoning = true,
                        thinkingLevelMap =
                            mapOf(
                                ModelThinkingLevel.XHIGH to "xhigh",
                                ModelThinkingLevel.MAX to "max",
                            ),
                    ),
                    context,
                    options.copy(temperature = null, reasoning = ThinkingLevel.XHIGH),
                    { null },
                ),
            )
            put(
                "bedrock-converse-stream-fixed-thinking",
                buildBedrockRequestBody(
                    fixtureModel("bedrock-converse-stream", provider = "amazon-bedrock").copy(
                        id = "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
                        name = "Claude Sonnet 4.5",
                        reasoning = true,
                    ),
                    context,
                    options.copy(temperature = null, reasoning = ThinkingLevel.MEDIUM),
                    { null },
                ),
            )
            val simpleFixedModel =
                fixtureModel("bedrock-converse-stream", provider = "amazon-bedrock").copy(
                    id = "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
                    name = "Claude Sonnet 4.5",
                    reasoning = true,
                )
            put(
                "bedrock-converse-stream-simple-fixed-thinking",
                buildBedrockRequestBody(
                    simpleFixedModel,
                    context,
                    bedrockSimpleStreamOptions(
                        simpleFixedModel,
                        context,
                        SimpleStreamOptions(
                            stream = options.copy(temperature = null),
                            reasoning = ThinkingLevel.MEDIUM,
                            thinkingBudgets = ThinkingBudgets(medium = 4_096),
                        ),
                    ),
                    { null },
                ),
            )
        }
    println(providerJson.encodeToString(JsonObject.serializer(), payloads))
}

private fun fixtureModel(
    api: String,
    provider: String = "fixture",
) = model(
    id = "fixture",
    name = "Fixture",
    api = api,
    provider = provider,
    baseUrl = "https://fixture.invalid/v1",
    contextWindow = 128_000,
    maxTokens = 16_384,
)
