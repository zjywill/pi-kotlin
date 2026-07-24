package works.earendil.pi.ai.providers

import java.net.http.HttpClient
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Credential
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StreamOptions

class GitHubCopilotProvider(
    override val id: String,
    override val name: String,
    private val models: List<Model>,
    private val client: HttpClient = HttpClient.newHttpClient(),
    override val oauth: OAuthAuth = GitHubCopilotOAuth(),
) : Provider {
    override val baseUrl: String? = models.firstOrNull()?.baseUrl

    private val delegates: Map<String, Provider> =
        models
            .groupBy(Model::api)
            .mapValues { (api, apiModels) ->
                when (api) {
                    "anthropic-messages" ->
                        AnthropicProvider(
                            id = id,
                            name = name,
                            baseUrl = requireNotNull(baseUrl),
                            models = apiModels,
                            apiKeyEnvNames = COPILOT_API_KEY_ENV_NAMES,
                            client = client,
                        )

                    "openai-completions" ->
                        OpenAIChatProvider(
                            id = id,
                            name = name,
                            baseUrl = requireNotNull(baseUrl),
                            models = apiModels,
                            apiKeyEnvNames = COPILOT_API_KEY_ENV_NAMES,
                            client = client,
                        )

                    "openai-responses" ->
                        OpenAIResponsesProvider(
                            id = id,
                            name = name,
                            baseUrl = requireNotNull(baseUrl),
                            models = apiModels,
                            apiKeyEnvNames = COPILOT_API_KEY_ENV_NAMES,
                            client = client,
                        )

                    else -> error("Unsupported GitHub Copilot API: $api")
                }
            }

    override fun getModels(): List<Model> = models

    override fun filterModels(
        models: List<Model>,
        credential: Credential?,
    ): List<Model> {
        if (credential !is OAuthCredential) {
            return models
        }
        val available = credential.availableModelIds ?: return models
        val availableIds = available.toSet()
        return models.filter { it.id in availableIds }
    }

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions,
    ): AssistantMessageEventStream =
        requireNotNull(delegates[model.api]) {
            "Provider $id does not support API ${model.api}"
        }.stream(model, context, options)

    override suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream =
        requireNotNull(delegates[model.api]) {
            "Provider $id does not support API ${model.api}"
        }.streamSimple(model, context, options)
}

private val COPILOT_API_KEY_ENV_NAMES = listOf("COPILOT_GITHUB_TOKEN")
