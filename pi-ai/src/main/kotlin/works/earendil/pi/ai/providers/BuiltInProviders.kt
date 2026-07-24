package works.earendil.pi.ai.providers

import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.Provider

fun openAIProvider(
    models: List<Model> =
        builtInModels("openai").filter { it.api == "openai-responses" },
): OpenAIResponsesProvider =
    OpenAIResponsesProvider(
        id = "openai",
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        models = models,
        apiKeyEnvNames = listOf("OPENAI_API_KEY"),
    )

fun azureOpenAIResponsesProvider(
    models: List<Model> = builtInModels("azure-openai-responses"),
): AzureOpenAIResponsesProvider =
    AzureOpenAIResponsesProvider(
        id = "azure-openai-responses",
        name = "Azure OpenAI",
        models = models,
        apiKeyEnvNames = listOf("AZURE_OPENAI_API_KEY"),
    )

fun googleProvider(
    models: List<Model> =
        builtInModels("google").filter { it.api == "google-generative-ai" },
): GoogleProvider =
    GoogleProvider(
        id = "google",
        name = "Google",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        models = models,
        apiKeyEnvNames = listOf("GEMINI_API_KEY"),
    )

fun googleVertexProvider(
    models: List<Model> = builtInModels("google-vertex"),
): GoogleVertexProvider =
    GoogleVertexProvider(
        id = "google-vertex",
        name = "Google Vertex AI",
        models = models,
    )

fun amazonBedrockProvider(
    models: List<Model> = builtInModels("amazon-bedrock"),
): Provider =
    BedrockProvider(
        id = "amazon-bedrock",
        name = "Amazon Bedrock",
        models = models,
    )

fun openAICodexProvider(
    models: List<Model> = builtInModels("openai-codex"),
): OpenAICodexProvider =
    OpenAICodexProvider(
        id = "openai-codex",
        name = "OpenAI Codex",
        models = models,
    )

fun githubCopilotProvider(
    models: List<Model> = builtInModels("github-copilot"),
): GitHubCopilotProvider =
    GitHubCopilotProvider(
        id = "github-copilot",
        name = "GitHub Copilot",
        models = models,
    )

fun anthropicProvider(
    models: List<Model> =
        builtInModels("anthropic").filter { it.api == "anthropic-messages" },
): AnthropicProvider =
    AnthropicProvider(
        id = "anthropic",
        name = "Anthropic",
        baseUrl = "https://api.anthropic.com",
        models = models,
        apiKeyEnvNames = listOf("ANTHROPIC_OAUTH_TOKEN", "ANTHROPIC_API_KEY"),
    )

fun mistralProvider(
    models: List<Model> = builtInModels("mistral"),
): MistralProvider =
    MistralProvider(
        id = "mistral",
        name = "Mistral",
        baseUrl = "https://api.mistral.ai",
        models = models,
        apiKeyEnvNames = listOf("MISTRAL_API_KEY"),
    )

fun model(
    id: String,
    name: String = id,
    api: String,
    provider: String,
    baseUrl: String,
    reasoning: Boolean = false,
    input: List<ModelInput> = listOf(ModelInput.TEXT),
    contextWindow: Int = 128_000,
    maxTokens: Int = 16_384,
    cost: ModelCost = ModelCost(0.0, 0.0, 0.0, 0.0),
): Model =
    Model(
        id = id,
        name = name,
        api = api,
        provider = provider,
        baseUrl = baseUrl,
        reasoning = reasoning,
        input = input,
        cost = cost,
        contextWindow = contextWindow,
        maxTokens = maxTokens,
    )
