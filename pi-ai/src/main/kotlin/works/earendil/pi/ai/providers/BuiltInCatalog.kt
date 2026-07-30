package works.earendil.pi.ai.providers

import java.security.MessageDigest
import java.net.http.HttpClient
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.AuthResult
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.CredentialStore
import works.earendil.pi.ai.InMemoryCredentialStore
import works.earendil.pi.ai.InMemoryModelsStore
import works.earendil.pi.ai.ImagesModels
import works.earendil.pi.ai.ImagesProvider
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.ModelsRefreshOptions
import works.earendil.pi.ai.ModelsStore
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.StreamOptions

data class BuiltInCatalogSnapshot(
    val schemaVersion: Int,
    val generatedAt: String?,
    val structureHash: String,
    val modelsByProvider: Map<String, List<Model>>,
    val unsupportedApis: Set<String>,
)

fun builtInCatalog(): BuiltInCatalogSnapshot = CatalogHolder.snapshot

fun builtInModels(provider: String): List<Model> = builtInCatalog().modelsByProvider[provider].orEmpty()

fun builtInProviders(): List<Provider> =
    (
        builtInCatalog()
            .modelsByProvider
            .mapNotNull { (providerId, models) ->
                val supportedModels = models.filter { it.api in SUPPORTED_APIS }
                supportedModels
                    .takeIf(List<Model>::isNotEmpty)
                    ?.let {
                        val name = PROVIDER_NAMES[providerId] ?: providerId.toDisplayName()
                        when (providerId) {
                            "cloudflare-ai-gateway" ->
                                CloudflareProvider(
                                    id = providerId,
                                    name = name,
                                    kind = CloudflareProviderKind.AI_GATEWAY,
                                    models = it,
                                )

                            "cloudflare-workers-ai" ->
                                CloudflareProvider(
                                    id = providerId,
                                    name = name,
                                    kind = CloudflareProviderKind.WORKERS_AI,
                                    models = it,
                                )

                            "amazon-bedrock" ->
                                BedrockProvider(
                                    id = providerId,
                                    name = name,
                                    models = it,
                                )

                            "openai-codex" ->
                                OpenAICodexProvider(
                                    id = providerId,
                                    name = name,
                                    models = it,
                                )

                            "github-copilot" ->
                                GitHubCopilotProvider(
                                    id = providerId,
                                    name = name,
                                    models = it,
                                )

                            "anthropic" ->
                                AnthropicProvider(
                                    id = providerId,
                                    name = name,
                                    baseUrl = it.first().baseUrl,
                                    models = it,
                                    apiKeyEnvNames =
                                        PROVIDER_API_KEY_ENV_NAMES[providerId]
                                            ?: defaultApiKeyNames(providerId),
                                    oauth = AnthropicOAuth(),
                                )

                            "openrouter" ->
                                CatalogProvider(
                                    id = providerId,
                                    name = name,
                                    models = it,
                                    apiKeyEnvNames =
                                        PROVIDER_API_KEY_ENV_NAMES[providerId]
                                            ?: defaultApiKeyNames(providerId),
                                    oauth = OpenRouterOAuth(),
                                )

                            "kimi-coding" ->
                                CatalogProvider(
                                    id = providerId,
                                    name = name,
                                    models = it,
                                    apiKeyEnvNames =
                                        PROVIDER_API_KEY_ENV_NAMES[providerId]
                                            ?: defaultApiKeyNames(providerId),
                                    oauth = KimiCodingOAuth(),
                                )

                            "xai" ->
                                CatalogProvider(
                                    id = providerId,
                                    name = name,
                                    models = it,
                                    apiKeyEnvNames =
                                        PROVIDER_API_KEY_ENV_NAMES[providerId]
                                            ?: defaultApiKeyNames(providerId),
                                    oauth = XaiOAuth(),
                                )

                            else ->
                                CatalogProvider(
                                    id = providerId,
                                    name = name,
                                    models = it,
                                    apiKeyEnvNames =
                                        PROVIDER_API_KEY_ENV_NAMES[providerId] ?: defaultApiKeyNames(providerId),
                                )
                        }
                    }
            } + radiusProvider()
    ).sortedBy(Provider::id)

fun builtInModelsCollection(): Models = Models(builtInProviders())

fun builtInImagesProviders(): List<ImagesProvider> = listOf(openRouterImagesProvider())

fun builtInImagesModelsCollection(
    credentialStore: CredentialStore = InMemoryCredentialStore(),
): ImagesModels =
    ImagesModels(
        providers = builtInImagesProviders(),
        credentials = credentialStore,
    )

data class BuiltInModelsOptions(
    val modelsStore: ModelsStore = InMemoryModelsStore(),
    val credentialStore: CredentialStore = InMemoryCredentialStore(),
    val catalogBaseUrl: String = "https://pi.dev",
    val allowNetwork: Boolean = false,
    val force: Boolean = false,
    val userAgent: String = "pi/0.1.0-SNAPSHOT",
    val httpClient: HttpClient = defaultRemoteCatalogHttpClient(),
)

suspend fun builtInModelsCollection(options: BuiltInModelsOptions): Models {
    val generatedAt =
        builtInCatalog()
            .generatedAt
            ?.let(Instant::parse)
            ?.toEpochMilli()
    val providers =
        builtInProviders().map { provider ->
            if (provider.id == "radius") {
                provider
            } else {
                provider.withRemoteCatalog(
                    catalogBaseUrl = options.catalogBaseUrl,
                    localGeneratedAt = generatedAt,
                    userAgent = options.userAgent,
                    client = options.httpClient,
                )
            }
        }
    return Models(providers, options.modelsStore, options.credentialStore).also { models ->
        models.refresh(
            ModelsRefreshOptions(
                allowNetwork = options.allowNetwork,
                force = options.force,
            ),
        )
    }
}

private object CatalogHolder {
    val snapshot: BuiltInCatalogSnapshot by lazy(::loadCatalog)
}

private class CatalogProvider(
    override val id: String,
    override val name: String,
    private val models: List<Model>,
    private val apiKeyEnvNames: List<String>,
    override val oauth: OAuthAuth? = null,
) : Provider {
    override val baseUrl: String? = models.firstOrNull()?.baseUrl

    private val delegates: Map<String, Provider> =
        models
            .groupBy(Model::api)
            .mapValues { (api, apiModels) ->
                when (api) {
                    "openai-completions" ->
                        OpenAIChatProvider(
                            id = id,
                            name = name,
                            baseUrl = apiModels.first().baseUrl,
                            models = apiModels,
                            apiKeyEnvNames = apiKeyEnvNames,
                        )

                    "openai-responses" ->
                        OpenAIResponsesProvider(
                            id = id,
                            name = name,
                            baseUrl = apiModels.first().baseUrl,
                            models = apiModels,
                            apiKeyEnvNames = apiKeyEnvNames,
                        )

                    "azure-openai-responses" ->
                        AzureOpenAIResponsesProvider(
                            id = id,
                            name = name,
                            models = apiModels,
                            apiKeyEnvNames = apiKeyEnvNames,
                        )

                    "anthropic-messages" ->
                        AnthropicProvider(
                            id = id,
                            name = name,
                            baseUrl = apiModels.first().baseUrl,
                            models = apiModels,
                            apiKeyEnvNames = apiKeyEnvNames,
                        )

                    "google-generative-ai" ->
                        GoogleProvider(
                            id = id,
                            name = name,
                            baseUrl = apiModels.first().baseUrl,
                            models = apiModels,
                            apiKeyEnvNames = apiKeyEnvNames,
                        )

                    "google-vertex" ->
                        GoogleVertexProvider(
                            id = id,
                            name = name,
                            models = apiModels,
                        )

                    "mistral-conversations" ->
                        MistralProvider(
                            id = id,
                            name = name,
                            baseUrl = apiModels.first().baseUrl,
                            models = apiModels,
                            apiKeyEnvNames = apiKeyEnvNames,
                        )

                    "pi-messages" ->
                        PiMessagesProvider(
                            id = id,
                            name = name,
                            baseUrl = apiModels.first().baseUrl,
                            models = apiModels,
                            apiKeyEnvNames = apiKeyEnvNames,
                        )

                    else -> error("Unsupported catalog API: $api")
                }
            }

    override fun resolveAmbientAuth(environment: (String) -> String?): AuthResult? {
        val configured =
            apiKeyEnvNames.firstNotNullOfOrNull { name ->
                environment(name)
                    ?.takeIf(String::isNotBlank)
                    ?.let { name to it }
            } ?: return null
        return AuthResult(
            auth = ModelAuth(apiKey = configured.second),
            source = configured.first,
        )
    }

    override fun getModels(): List<Model> = models

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions,
    ): AssistantMessageEventStream =
        requireNotNull(delegates[model.api]) {
            "Provider $id does not support API ${model.api}"
        }.stream(model, context, options)
}

fun protocolProvider(
    id: String,
    name: String,
    models: List<Model>,
    apiKeyEnvNames: List<String> = emptyList(),
    oauth: OAuthAuth? = null,
): Provider =
    CatalogProvider(
        id = id,
        name = name,
        models = models,
        apiKeyEnvNames = apiKeyEnvNames,
        oauth = oauth,
    )

private fun loadCatalog(): BuiltInCatalogSnapshot {
    val manifestBytes = readCatalogResource(".manifest.json")
    val manifest = providerJson.parseToJsonElement(manifestBytes.decodeToString()).jsonObject
    val expectedFiles = manifest.getValue("files").jsonObject
    val modelsByProvider = linkedMapOf<String, List<Model>>()
    val unsupportedApis = linkedSetOf<String>()

    expectedFiles.entries.sortedBy(Map.Entry<String, *>::key).forEach { (fileName, hashValue) ->
        val bytes = readCatalogResource(fileName)
        val expectedHash = hashValue.jsonPrimitive.content
        require(bytes.sha256() == expectedHash) {
            "Built-in model catalog checksum mismatch: $fileName"
        }
        val providerId = fileName.removeSuffix(".json")
        val groups = providerJson.parseToJsonElement(bytes.decodeToString()).jsonObject
        val models =
            groups.flatMap { (api, rawModels) ->
                if (api !in SUPPORTED_APIS) {
                    unsupportedApis += api
                }
                rawModels.jsonObject.values.map { rawModel ->
                    providerJson.decodeFromJsonElement(Model.serializer(), rawModel)
                }
            }
        require(models.all { it.provider == providerId }) {
            "Built-in model catalog provider mismatch: $providerId"
        }
        require(models.map(Model::id).distinct().size == models.size) {
            "Built-in model catalog contains duplicate model ids: $providerId"
        }
        modelsByProvider[providerId] = models
    }

    return BuiltInCatalogSnapshot(
        schemaVersion = manifest.getValue("schemaVersion").jsonPrimitive.int,
        generatedAt = manifest["generatedAt"]?.jsonPrimitive?.contentOrNull,
        structureHash = manifest.getValue("structureHash").jsonPrimitive.contentOrNull.orEmpty(),
        modelsByProvider = modelsByProvider,
        unsupportedApis = unsupportedApis,
    )
}

private fun readCatalogResource(fileName: String): ByteArray {
    val resource = "$CATALOG_RESOURCE_ROOT/$fileName"
    return requireNotNull(BuiltInCatalogSnapshot::class.java.getResourceAsStream(resource)) {
        "Missing built-in model catalog resource: $resource"
    }.use { it.readAllBytes() }
}

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

private fun String.toDisplayName(): String =
    split('-').joinToString(" ") { word ->
        word.replaceFirstChar(Char::uppercaseChar)
    }

private fun defaultApiKeyNames(provider: String): List<String> =
    listOf(provider.uppercase().replace('-', '_') + "_API_KEY")

private const val CATALOG_RESOURCE_ROOT = "/works/earendil/pi/ai/providers/data"
private val SUPPORTED_APIS =
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
private val PROVIDER_NAMES =
    mapOf(
        "ant-ling" to "Ant Ling",
        "amazon-bedrock" to "Amazon Bedrock",
        "anthropic" to "Anthropic",
        "azure-openai-responses" to "Azure OpenAI",
        "cerebras" to "Cerebras",
        "cloudflare-ai-gateway" to "Cloudflare AI Gateway",
        "cloudflare-workers-ai" to "Cloudflare Workers AI",
        "deepseek" to "DeepSeek",
        "fireworks" to "Fireworks",
        "google" to "Google",
        "google-vertex" to "Google Vertex AI",
        "groq" to "Groq",
        "github-copilot" to "GitHub Copilot",
        "huggingface" to "Hugging Face",
        "kimi-coding" to "Kimi For Coding",
        "minimax" to "MiniMax",
        "minimax-cn" to "MiniMax CN",
        "mistral" to "Mistral",
        "moonshotai" to "Moonshot AI",
        "moonshotai-cn" to "Moonshot AI CN",
        "nvidia" to "NVIDIA",
        "openai" to "OpenAI",
        "openai-codex" to "OpenAI Codex",
        "opencode" to "OpenCode Zen",
        "opencode-go" to "OpenCode Go",
        "openrouter" to "OpenRouter",
        "qwen-token-plan" to "Qwen Token Plan",
        "qwen-token-plan-cn" to "Qwen Token Plan CN",
        "radius" to "Radius",
        "together" to "Together AI",
        "vercel-ai-gateway" to "Vercel AI Gateway",
        "xai" to "xAI",
        "xiaomi" to "Xiaomi MiMo",
        "xiaomi-token-plan-ams" to "Xiaomi Token Plan AMS",
        "xiaomi-token-plan-cn" to "Xiaomi Token Plan CN",
        "xiaomi-token-plan-sgp" to "Xiaomi Token Plan SGP",
        "zai" to "Z.AI",
        "zai-coding-cn" to "Z.AI Coding CN",
    )
private val PROVIDER_API_KEY_ENV_NAMES =
    mapOf(
        "ant-ling" to listOf("ANT_LING_API_KEY"),
        "anthropic" to listOf("ANTHROPIC_OAUTH_TOKEN", "ANTHROPIC_API_KEY"),
        "azure-openai-responses" to listOf("AZURE_OPENAI_API_KEY"),
        "cerebras" to listOf("CEREBRAS_API_KEY"),
        "cloudflare-ai-gateway" to listOf("CLOUDFLARE_API_KEY"),
        "cloudflare-workers-ai" to listOf("CLOUDFLARE_API_KEY"),
        "deepseek" to listOf("DEEPSEEK_API_KEY"),
        "fireworks" to listOf("FIREWORKS_API_KEY"),
        "google" to listOf("GEMINI_API_KEY"),
        "google-vertex" to listOf("GOOGLE_CLOUD_API_KEY"),
        "groq" to listOf("GROQ_API_KEY"),
        "huggingface" to listOf("HF_TOKEN"),
        "kimi-coding" to listOf("KIMI_API_KEY"),
        "minimax" to listOf("MINIMAX_API_KEY"),
        "minimax-cn" to listOf("MINIMAX_CN_API_KEY"),
        "mistral" to listOf("MISTRAL_API_KEY"),
        "moonshotai" to listOf("MOONSHOT_API_KEY"),
        "moonshotai-cn" to listOf("MOONSHOT_API_KEY"),
        "nvidia" to listOf("NVIDIA_API_KEY"),
        "openai" to listOf("OPENAI_API_KEY"),
        "opencode" to listOf("OPENCODE_API_KEY"),
        "opencode-go" to listOf("OPENCODE_API_KEY"),
        "openrouter" to listOf("OPENROUTER_API_KEY"),
        "qwen-token-plan" to listOf("QWEN_TOKEN_PLAN_API_KEY"),
        "qwen-token-plan-cn" to listOf("QWEN_TOKEN_PLAN_CN_API_KEY"),
        "together" to listOf("TOGETHER_API_KEY"),
        "vercel-ai-gateway" to listOf("AI_GATEWAY_API_KEY"),
        "xai" to listOf("XAI_API_KEY"),
        "xiaomi" to listOf("XIAOMI_API_KEY"),
        "xiaomi-token-plan-ams" to listOf("XIAOMI_TOKEN_PLAN_AMS_API_KEY"),
        "xiaomi-token-plan-cn" to listOf("XIAOMI_TOKEN_PLAN_CN_API_KEY"),
        "xiaomi-token-plan-sgp" to listOf("XIAOMI_TOKEN_PLAN_SGP_API_KEY"),
        "zai" to listOf("ZAI_API_KEY"),
        "zai-coding-cn" to listOf("ZAI_CODING_CN_API_KEY"),
    )
