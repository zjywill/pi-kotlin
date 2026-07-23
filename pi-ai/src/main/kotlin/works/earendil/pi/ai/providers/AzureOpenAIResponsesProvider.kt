package works.earendil.pi.ai.providers

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.StreamOptions

class AzureOpenAIResponsesProvider(
    override val id: String,
    override val name: String,
    private val models: List<Model>,
    private val apiKeyEnvNames: List<String>,
    client: HttpClient = HttpClient.newHttpClient(),
) : Provider {
    override val baseUrl: String? = models.firstOrNull()?.baseUrl

    private val delegate =
        OpenAIResponsesProvider(
            id = id,
            name = name,
            baseUrl = baseUrl.orEmpty(),
            models = models,
            apiKeyEnvNames = apiKeyEnvNames,
            client = client,
        )

    override fun getModels(): List<Model> = models

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions,
    ): AssistantMessageEventStream =
        delegate.streamWithRequest(model, context, options) {
            val apiKey = resolveApiKey(id, options.apiKey, options.env, apiKeyEnvNames)
            val config = resolveAzureOpenAIConfig(model, options)
            OpenAIResponsesHttpRequest(
                url = appendAzureResponsesPath(config.baseUrl, config.apiVersion),
                modelId = resolveAzureDeploymentName(model, options),
                headers = mapOf("api-key" to apiKey),
                promptCacheWhenDisabled = true,
            )
        }
}

internal data class AzureOpenAIConfig(
    val baseUrl: String,
    val apiVersion: String,
)

internal fun buildAzureOpenAIResponsesRequestBody(
    model: Model,
    context: Context,
    options: StreamOptions,
): kotlinx.serialization.json.JsonObject =
    buildOpenAIResponsesRequestBody(
        model,
        context,
        options,
        requestModelId = resolveAzureDeploymentName(model, options),
        promptCacheWhenDisabled = true,
    )

internal fun resolveAzureDeploymentName(
    model: Model,
    options: StreamOptions,
): String {
    options.azureDeploymentName?.takeIf(String::isNotEmpty)?.let { return it }
    return parseAzureDeploymentNameMap(providerEnvValue("AZURE_OPENAI_DEPLOYMENT_NAME_MAP", options.env))[model.id]
        ?: model.id
}

internal fun parseAzureDeploymentNameMap(value: String?): Map<String, String> =
    buildMap {
        value
            ?.split(',')
            ?.forEach { entry ->
                val parts = entry.trim().split('=', limit = 2)
                if (parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) {
                    put(parts[0].trim(), parts[1].trim())
                }
            }
    }

internal fun resolveAzureOpenAIConfig(
    model: Model,
    options: StreamOptions,
): AzureOpenAIConfig {
    val apiVersion =
        options.azureApiVersion?.takeIf(String::isNotEmpty)
            ?: providerEnvValue("AZURE_OPENAI_API_VERSION", options.env)
            ?: DEFAULT_AZURE_OPENAI_API_VERSION
    val explicitBaseUrl =
        options.azureBaseUrl?.trim()?.takeIf(String::isNotEmpty)
            ?: providerEnvValue("AZURE_OPENAI_BASE_URL", options.env)?.trim()?.takeIf(String::isNotEmpty)
    val resourceName =
        options.azureResourceName?.takeIf(String::isNotEmpty)
            ?: providerEnvValue("AZURE_OPENAI_RESOURCE_NAME", options.env)
    val resolvedBaseUrl =
        explicitBaseUrl
            ?: resourceName?.let { "https://$it.openai.azure.com/openai/v1" }
            ?: model.baseUrl.takeIf(String::isNotEmpty)
            ?: error(
                "Azure OpenAI base URL is required. Set AZURE_OPENAI_BASE_URL or " +
                    "AZURE_OPENAI_RESOURCE_NAME, or pass azureBaseUrl, azureResourceName, or model.baseUrl.",
            )
    return AzureOpenAIConfig(
        baseUrl = normalizeAzureOpenAIBaseUrl(resolvedBaseUrl),
        apiVersion = apiVersion,
    )
}

internal fun normalizeAzureOpenAIBaseUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    val uri =
        runCatching { URI(trimmed) }
            .getOrElse { error("Invalid Azure OpenAI base URL: $baseUrl") }
    if (!uri.isAbsolute || uri.host == null) {
        error("Invalid Azure OpenAI base URL: $baseUrl")
    }
    val host = uri.host.lowercase()
    val isAzureHost =
        host.endsWith(".openai.azure.com") ||
            host.endsWith(".cognitiveservices.azure.com") ||
            host.endsWith(".ai.azure.com")
    val normalizedPath = uri.path.trimEnd('/')
    if (
        isAzureHost &&
        normalizedPath in setOf("", "/", "/openai", "/openai/v1/responses")
    ) {
        return URI(
            uri.scheme,
            uri.userInfo,
            uri.host,
            uri.port,
            "/openai/v1",
            null,
            null,
        ).toString()
    }
    return trimmed
}

internal fun appendAzureResponsesPath(
    baseUrl: String,
    apiVersion: String,
): String {
    val combined = URI(baseUrl + if (baseUrl.endsWith('/')) "responses" else "/responses")
    val encodedVersion =
        URLEncoder
            .encode(apiVersion, StandardCharsets.UTF_8)
            .replace("+", "%20")
    return URI(
        combined.scheme,
        combined.rawAuthority,
        combined.rawPath,
        "api-version=$encodedVersion",
        combined.rawFragment,
    ).toASCIIString()
}

private fun providerEnvValue(
    name: String,
    env: Map<String, String>,
): String? =
    env[name]?.takeIf(String::isNotEmpty)
        ?: System.getenv(name)?.takeIf(String::isNotEmpty)

private const val DEFAULT_AZURE_OPENAI_API_VERSION = "v1"
