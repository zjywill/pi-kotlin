package works.earendil.pi.ai.providers

import java.net.http.HttpClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import works.earendil.pi.ai.ApiKeyCredential
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Credential
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelsPersistence
import works.earendil.pi.ai.ModelsPublication
import works.earendil.pi.ai.ModelsStoreEntry
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.RefreshModelsContext
import works.earendil.pi.ai.StreamOptions

class RadiusProvider(
    override val id: String = "radius",
    override val name: String = "Radius",
    gateway: String = DEFAULT_RADIUS_GATEWAY,
    private val client: HttpClient = defaultRadiusHttpClient(),
    private val environment: (String) -> String? = System::getenv,
    override val oauth: OAuthAuth = RadiusOAuth(name = name, gateway = gateway),
) : Provider {
    private val gateway = normalizeRadiusGatewayUrl(gateway)

    @Volatile
    private var models: List<Model> = emptyList()

    override val baseUrl: String = this.gateway
    override val supportsModelRefresh: Boolean = true

    override fun getModels(): List<Model> = models

    override fun filterModels(
        models: List<Model>,
        credential: Credential?,
    ): List<Model> =
        if (radiusApiKey(credential) != null) {
            models
        } else {
            emptyList()
        }

    override suspend fun refreshModels(context: RefreshModelsContext) = refreshCatalog(context)

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions,
    ): AssistantMessageEventStream =
        streamPiMessages(
            model = model,
            context = context,
            options = options,
            apiKeyEnvNames = listOf("RADIUS_API_KEY"),
            client = client,
            environment = environment,
        )

    private suspend fun refreshCatalog(context: RefreshModelsContext) {
        val stored = context.stored ?: context.store.read()
        if (stored != null) {
            val restored = stored.models.filter { model -> model.provider == id }
            if (!context.publish(ModelsPublication(update = { models = restored }))) {
                return
            }
        } else {
            val legacy = getRadiusModels(id, context.credential as? OAuthCredential)
            if (legacy.isNotEmpty()) {
                if (
                    !context.publish(
                        ModelsPublication(
                            persistence =
                                ModelsPersistence.Write(
                                    ModelsStoreEntry(
                                        models = legacy,
                                        checkedAt = System.currentTimeMillis(),
                                    ),
                                ),
                            update = { models = legacy },
                        ),
                    )
                ) {
                    return
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (!context.allowNetwork) {
            return
        }
        val apiKey = radiusApiKey(context.credential) ?: return
        val config = loadRadiusGatewayConfig(gateway, apiKey, client)
        currentCoroutineContext().ensureActive()
        val refreshed = getRadiusModelsFromConfig(id, config)
        context.publish(
            ModelsPublication(
                persistence =
                    ModelsPersistence.Write(
                        ModelsStoreEntry(
                            models = refreshed,
                            checkedAt = System.currentTimeMillis(),
                        ),
                    ),
                update = { models = refreshed },
            ),
        )
    }

    private fun radiusApiKey(credential: Credential?): String? =
        when (credential) {
            is OAuthCredential -> credential.access.takeIf(String::isNotBlank)
            is ApiKeyCredential -> credential.key?.takeIf(String::isNotBlank)
            null -> environment("RADIUS_API_KEY")?.takeIf(String::isNotBlank)
        }
}

fun radiusProvider(
    id: String = "radius",
    name: String = "Radius",
    gateway: String = DEFAULT_RADIUS_GATEWAY,
): RadiusProvider =
    RadiusProvider(
        id = id,
        name = name,
        gateway = gateway,
    )
