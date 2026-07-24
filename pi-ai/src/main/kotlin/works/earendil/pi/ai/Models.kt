package works.earendil.pi.ai

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

fun interface StreamFunction {
    suspend fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream
}

interface Provider {
    val id: String
    val name: String
    val baseUrl: String?
        get() = null
    val headers: Map<String, String?>
        get() = emptyMap()
    val oauth: OAuthAuth?
        get() = null

    fun getModels(): List<Model>

    fun filterModels(
        models: List<Model>,
        credential: Credential?,
    ): List<Model> = models

    val supportsModelRefresh: Boolean
        get() = false

    suspend fun refreshModels(context: RefreshModelsContext) = Unit

    suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions = StreamOptions(),
    ): AssistantMessageEventStream

    suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions(),
    ): AssistantMessageEventStream =
        stream(
            model,
            context,
            options.stream.copy(
                reasoning = options.reasoning,
                thinkingBudgets = options.thinkingBudgets,
            ),
        )
}

data class RefreshModelsContext(
    val store: ProviderModelsStore,
    val allowNetwork: Boolean,
    val force: Boolean = false,
)

data class ModelsRefreshOptions(
    val allowNetwork: Boolean = true,
    val force: Boolean = false,
)

data class ModelsRefreshResult(
    val aborted: Boolean,
    val errors: Map<String, Throwable>,
)

class Models(
    providers: Iterable<Provider> = emptyList(),
    private val modelsStore: ModelsStore = InMemoryModelsStore(),
    private val credentials: CredentialStore = InMemoryCredentialStore(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val providersById = ConcurrentHashMap<String, Provider>()

    init {
        providers.forEach(::setProvider)
    }

    fun setProvider(provider: Provider) {
        providersById[provider.id] = provider
    }

    fun deleteProvider(id: String) {
        providersById.remove(id)
    }

    fun clearProviders() {
        providersById.clear()
    }

    fun getProviders(): List<Provider> = providersById.values.toList()

    fun getProvider(id: String): Provider? = providersById[id]

    fun getModels(provider: String? = null): List<Model> {
        if (provider != null) {
            return runCatching { providersById[provider]?.getModels().orEmpty() }.getOrDefault(emptyList())
        }
        return providersById.values.flatMap { entry ->
            runCatching(entry::getModels).getOrDefault(emptyList())
        }
    }

    fun getModel(
        provider: String,
        id: String,
    ): Model? = getModels(provider).firstOrNull { it.id == id }

    suspend fun getAvailable(providerId: String? = null): List<Model> {
        val providers =
            if (providerId == null) {
                providersById.values.toList()
            } else {
                listOfNotNull(providersById[providerId])
            }
        return providers.flatMap { provider ->
            val credential = readStoredCredential(provider.id)
            val providerModels =
                runCatching(provider::getModels).getOrDefault(emptyList())
            provider.filterModels(providerModels, credential)
        }
    }

    suspend fun getAuth(providerId: String): AuthResult? {
        val provider = providersById[providerId] ?: return null
        val stored = readStoredCredential(provider.id) ?: return null
        return resolveStoredAuth(provider, stored)
    }

    suspend fun listCredentials(): List<CredentialInfo> =
        try {
            credentials.list()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ModelsAuthException(
                code = "auth",
                message = "Credential store list failed",
                cause = error,
            )
        }

    suspend fun login(
        providerId: String,
        type: AuthType,
        interaction: AuthInteraction,
    ): Credential {
        val provider =
            providersById[providerId]
                ?: throw ModelsAuthException("provider", "Unknown provider: $providerId")
        val credential =
            when (type) {
                AuthType.OAUTH ->
                    provider.oauth?.login(interaction)
                        ?: throw ModelsAuthException(
                            "auth",
                            "${provider.name} does not support oauth login",
                        )

                AuthType.API_KEY ->
                    throw ModelsAuthException(
                        "auth",
                        "${provider.name} does not support api_key login",
                    )
            }
        try {
            credentials.modify(providerId) { credential }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ModelsAuthException(
                code = "auth",
                message = "Credential store modify failed for $providerId",
                cause = error,
            )
        }
        return credential
    }

    suspend fun logout(providerId: String) {
        try {
            credentials.delete(providerId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ModelsAuthException(
                code = "auth",
                message = "Credential store delete failed for $providerId",
                cause = error,
            )
        }
    }

    suspend fun refresh(options: ModelsRefreshOptions = ModelsRefreshOptions()): ModelsRefreshResult {
        val errors = ConcurrentHashMap<String, Throwable>()
        val refreshableProviders = providersById.values.filter(Provider::supportsModelRefresh)
        val storedEntries =
            runCatching {
                modelsStore.readAll(refreshableProviders.map(Provider::id))
            }.getOrElse { error ->
                return ModelsRefreshResult(
                    aborted = false,
                    errors = refreshableProviders.associate { it.id to error },
                )
            }
        val entries = ConcurrentHashMap(storedEntries)
        val writes = ConcurrentHashMap<String, ModelsStoreEntry>()
        val deletes = ConcurrentHashMap.newKeySet<String>()
        val refreshSemaphore = Semaphore(MAX_CONCURRENT_MODEL_REFRESHES)
        supervisorScope {
            refreshableProviders
                .map { provider ->
                    async {
                        refreshSemaphore.withPermit {
                            val store =
                                object : ProviderModelsStore {
                                    override suspend fun read(): ModelsStoreEntry? =
                                        if (provider.id in deletes) {
                                            null
                                        } else {
                                            entries[provider.id]
                                        }

                                    override suspend fun write(entry: ModelsStoreEntry) {
                                        entries[provider.id] = entry
                                        writes[provider.id] = entry
                                        deletes.remove(provider.id)
                                    }

                                    override suspend fun delete() {
                                        entries.remove(provider.id)
                                        writes.remove(provider.id)
                                        deletes += provider.id
                                    }
                                }
                            try {
                                provider.refreshModels(
                                    RefreshModelsContext(
                                        store = store,
                                        allowNetwork = options.allowNetwork,
                                        force = options.force,
                                    ),
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                errors[provider.id] = error
                                runCatching {
                                    provider.refreshModels(
                                        RefreshModelsContext(
                                            store = store,
                                            allowNetwork = false,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }.awaitAll()
        }
        runCatching {
            modelsStore.applyChanges(writes, deletes)
        }.onFailure { error ->
            (writes.keys + deletes).forEach { providerId ->
                errors.putIfAbsent(providerId, error)
            }
        }
        return ModelsRefreshResult(
            aborted = false,
            errors = errors.toSortedMap(),
        )
    }

    suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions = StreamOptions(),
    ): AssistantMessageEventStream {
        val provider =
            providersById[model.provider]
                ?: return errorStream(model, "Unknown provider: ${model.provider}")
        return try {
            val prepared = prepareStoredAuth(model, provider, options)
            provider.stream(prepared.model, context, prepared.options)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            errorStream(model, error.message ?: error::class.simpleName.orEmpty())
        }
    }

    suspend fun complete(
        model: Model,
        context: Context,
        options: StreamOptions = StreamOptions(),
    ): AssistantMessage = stream(model, context, options).result()

    suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions(),
    ): AssistantMessageEventStream {
        val provider =
            providersById[model.provider]
                ?: return errorStream(model, "Unknown provider: ${model.provider}")
        return try {
            val prepared = prepareStoredAuth(model, provider, options.stream)
            provider.streamSimple(
                prepared.model,
                context,
                options.copy(stream = prepared.options),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            errorStream(model, error.message ?: error::class.simpleName.orEmpty())
        }
    }

    suspend fun completeSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions(),
    ): AssistantMessage = streamSimple(model, context, options).result()

    private suspend fun prepareStoredAuth(
        model: Model,
        provider: Provider,
        options: StreamOptions,
    ): PreparedRequest {
        val stored = readStoredCredential(provider.id) ?: return PreparedRequest(model, options)
        if (!options.apiKey.isNullOrBlank() && stored !is OAuthCredential) {
            return PreparedRequest(model, options)
        }
        val resolution =
            resolveStoredAuth(provider, stored)
                ?: return PreparedRequest(model, options)
        return PreparedRequest(
            model =
                resolution.auth.baseUrl
                    ?.takeIf(String::isNotBlank)
                    ?.let { model.copy(baseUrl = it) }
                    ?: model,
            options =
                options.copy(
                    apiKey = resolution.auth.apiKey,
                    headers = mergeAuthHeaders(resolution.auth.headers, options.headers),
                ),
        )
    }

    private suspend fun readStoredCredential(providerId: String): Credential? =
        try {
            credentials.read(providerId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ModelsAuthException(
                code = "auth",
                message = "Credential store read failed for $providerId",
                cause = error,
            )
        }

    private suspend fun resolveStoredAuth(
        provider: Provider,
        stored: Credential,
    ): AuthResult? {
        return when (stored) {
            is ApiKeyCredential ->
                AuthResult(
                    auth =
                        ModelAuth(
                            apiKey = stored.key,
                        ),
                    source = "Stored API key",
                )

            is OAuthCredential ->
                provider.oauth?.let { resolveStoredOAuth(provider, stored, it) }
                    ?: throw ModelsAuthException(
                        code = "auth",
                        message = "Stored OAuth credential is not supported by ${provider.id}",
                    )
        }
    }

    private suspend fun resolveStoredOAuth(
        provider: Provider,
        stored: OAuthCredential,
        oauth: OAuthAuth,
    ): AuthResult? {
        var credential = stored
        if (currentTimeMillis() >= credential.expires) {
            val post =
                try {
                    credentials.modify(provider.id) { current ->
                        if (current !is OAuthCredential || currentTimeMillis() < current.expires) {
                            null
                        } else {
                            try {
                                oauth.refresh(current)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                throw ModelsAuthException(
                                    code = "oauth",
                                    message = "OAuth refresh failed for ${provider.id}",
                                    cause = error,
                                )
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ModelsAuthException) {
                    throw error
                } catch (error: Throwable) {
                    throw ModelsAuthException(
                        code = "auth",
                        message = "Credential store modify failed for ${provider.id}",
                        cause = error,
                    )
                }
            if (post !is OAuthCredential) {
                return null
            }
            credential = post
        }
        return try {
            AuthResult(
                auth = oauth.toAuth(credential),
                source = "OAuth",
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ModelsAuthException(
                code = "oauth",
                message = "OAuth auth derivation failed for ${provider.id}",
                cause = error,
            )
        }
    }

    private fun errorStream(
        model: Model,
        message: String,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        val error =
            AssistantMessage(
                content = emptyList(),
                api = model.api,
                provider = model.provider,
                model = model.id,
                stopReason = StopReason.ERROR,
                errorMessage = message,
            )
        stream.push(AssistantError(StopReason.ERROR, error))
        return stream
    }
}

private const val MAX_CONCURRENT_MODEL_REFRESHES = 8

private data class PreparedRequest(
    val model: Model,
    val options: StreamOptions,
)

private fun mergeAuthHeaders(
    auth: Map<String, String?>,
    request: Map<String, String?>,
): Map<String, String?> {
    if (auth.isEmpty()) {
        return request
    }
    val result = linkedMapOf<String, String?>()
    auth.forEach { (name, value) ->
        result[name] = value
    }
    request.forEach { (name, value) ->
        result.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(result::remove)
        result[name] = value
    }
    return result
}
