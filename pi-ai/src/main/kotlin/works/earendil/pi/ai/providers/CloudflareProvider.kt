package works.earendil.pi.ai.providers

import java.net.http.HttpClient
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.StreamOptions

internal enum class CloudflareProviderKind {
    WORKERS_AI,
    AI_GATEWAY,
}

internal data class ResolvedCloudflareRequest(
    val model: Model,
    val options: StreamOptions,
)

internal fun resolveCloudflareModel(
    model: Model,
    env: Map<String, String>,
): Model {
    val baseUrl =
        model.baseUrl
            .replace(
                "{$CLOUDFLARE_ACCOUNT_ID}",
                env[CLOUDFLARE_ACCOUNT_ID] ?: "{$CLOUDFLARE_ACCOUNT_ID}",
            ).replace(
                "{$CLOUDFLARE_GATEWAY_ID}",
                env[CLOUDFLARE_GATEWAY_ID] ?: "{$CLOUDFLARE_GATEWAY_ID}",
            )
    return if (baseUrl == model.baseUrl) model else model.copy(baseUrl = baseUrl)
}

internal fun resolveCloudflareRequest(
    model: Model,
    options: StreamOptions,
    kind: CloudflareProviderKind,
    environment: (String) -> String? = System::getenv,
): ResolvedCloudflareRequest {
    val apiKey =
        options.apiKey?.takeIf(String::isNotBlank)
            ?: cloudflareValue(CLOUDFLARE_API_KEY, options.env, environment)
            ?: error("Cloudflare API key is required (set $CLOUDFLARE_API_KEY)")
    val accountId =
        cloudflareValue(CLOUDFLARE_ACCOUNT_ID, options.env, environment)
            ?: error("Cloudflare account id is required (set $CLOUDFLARE_ACCOUNT_ID)")
    val gatewayId =
        if (kind == CloudflareProviderKind.AI_GATEWAY) {
            cloudflareValue(CLOUDFLARE_GATEWAY_ID, options.env, environment)
                ?: error("Cloudflare AI Gateway id is required (set $CLOUDFLARE_GATEWAY_ID)")
        } else {
            null
        }
    val resolvedEnv =
        buildMap {
            putAll(options.env)
            put(CLOUDFLARE_ACCOUNT_ID, accountId)
            gatewayId?.let { put(CLOUDFLARE_GATEWAY_ID, it) }
        }
    val headers =
        if (kind == CloudflareProviderKind.AI_GATEWAY) {
            mergeNullableHeaders(
                linkedMapOf(
                    "cf-aig-authorization" to "Bearer $apiKey",
                    "Authorization" to null,
                    "x-api-key" to null,
                ),
                options.headers,
            )
        } else {
            options.headers
        }
    return ResolvedCloudflareRequest(
        model = resolveCloudflareModel(model, resolvedEnv),
        options =
            options.copy(
                apiKey = apiKey,
                headers = headers,
                env = resolvedEnv,
            ),
    )
}

internal class CloudflareProvider(
    override val id: String,
    override val name: String,
    private val kind: CloudflareProviderKind,
    private val models: List<Model>,
    private val environment: (String) -> String? = System::getenv,
    client: HttpClient = HttpClient.newHttpClient(),
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
                            apiKeyEnvNames = emptyList(),
                            client = client,
                        )

                    "openai-responses" ->
                        OpenAIResponsesProvider(
                            id = id,
                            name = name,
                            baseUrl = apiModels.first().baseUrl,
                            models = apiModels,
                            apiKeyEnvNames = emptyList(),
                            client = client,
                        )

                    "anthropic-messages" ->
                        AnthropicProvider(
                            id = id,
                            name = name,
                            baseUrl = apiModels.first().baseUrl,
                            models = apiModels,
                            apiKeyEnvNames = emptyList(),
                            client = client,
                        )

                    else -> error("Unsupported Cloudflare API: $api")
                }
            }

    override fun getModels(): List<Model> = models

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions,
    ): AssistantMessageEventStream {
        val resolved = resolveCloudflareRequest(model, options, kind, environment)
        return requireNotNull(delegates[model.api]) {
            "Provider $id does not support API ${model.api}"
        }.stream(resolved.model, context, resolved.options)
    }
}

private fun cloudflareValue(
    name: String,
    env: Map<String, String>,
    environment: (String) -> String?,
): String? =
    env[name]?.takeIf(String::isNotBlank)
        ?: environment(name)?.takeIf(String::isNotBlank)

private fun mergeNullableHeaders(
    base: Map<String, String?>,
    override: Map<String, String?>,
): Map<String, String?> {
    val result = linkedMapOf<String, String?>()

    fun put(
        name: String,
        value: String?,
    ) {
        result.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(result::remove)
        result[name] = value
    }

    base.forEach { (name, value) -> put(name, value) }
    override.forEach { (name, value) -> put(name, value) }
    return result
}

private const val CLOUDFLARE_API_KEY = "CLOUDFLARE_API_KEY"
private const val CLOUDFLARE_ACCOUNT_ID = "CLOUDFLARE_ACCOUNT_ID"
private const val CLOUDFLARE_GATEWAY_ID = "CLOUDFLARE_GATEWAY_ID"
