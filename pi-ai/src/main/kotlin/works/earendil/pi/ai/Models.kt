package works.earendil.pi.ai

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock

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
    val apiKey: ApiKeyAuth?
        get() = null

    fun resolveAmbientAuth(environment: (String) -> String?): AuthResult? = null

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
                samplingParams = mergeSamplingParams(model.samplingParams, options.stream.samplingParams),
                reasoning = options.reasoning,
                thinkingBudgets = options.thinkingBudgets,
            ),
        )

    val supportsDeferredResponses: Boolean
        get() = false

    suspend fun fetchDeferred(
        model: Model,
        handle: DeferredHandle,
        options: DeferredFetchOptions = DeferredFetchOptions(),
    ): AssistantMessageEventStream =
        throw UnsupportedOperationException("Provider $id does not support deferred responses")

    suspend fun cancelDeferred(
        model: Model,
        handle: DeferredHandle,
        options: DeferredCancelOptions = DeferredCancelOptions(),
    ) {
        throw UnsupportedOperationException("Provider $id does not support deferred responses")
    }
}

data class RefreshModelsContext(
    val credential: Credential? = null,
    val stored: ModelsStoreEntry? = null,
    val store: ProviderModelsStore,
    val allowNetwork: Boolean,
    val force: Boolean = false,
    val publish: suspend (ModelsPublication) -> Boolean = { publication ->
        when (val persistence = publication.persistence) {
            ModelsPersistence.None -> Unit
            ModelsPersistence.Delete -> store.delete()
            is ModelsPersistence.Write -> store.write(persistence.entry)
        }
        publication.update?.invoke()
        true
    },
)

sealed interface ModelsPersistence {
    data object None : ModelsPersistence

    data object Delete : ModelsPersistence

    data class Write(
        val entry: ModelsStoreEntry,
    ) : ModelsPersistence
}

data class ModelsPublication(
    val persistence: ModelsPersistence = ModelsPersistence.None,
    val update: (() -> Unit)? = null,
)

data class ModelsRefreshOptions(
    val allowNetwork: Boolean = true,
    val providers: Set<String>? = null,
    val force: Boolean = false,
)

data class ModelsRefreshResult(
    val aborted: Boolean,
    val errors: Map<String, Throwable>,
)

class CredentialSynchronizationException(
    val providerId: String,
    val operation: String,
    val credential: Credential?,
    cause: Throwable,
) : RuntimeException(
        "Credential $operation committed for $providerId, but local synchronization failed",
        cause,
)

class Models(
    providers: Iterable<Provider> = emptyList(),
    private val modelsStore: ModelsStore = InMemoryModelsStore(),
    private val credentials: CredentialStore = InMemoryCredentialStore(),
    private val environment: (String) -> String? = System::getenv,
    authContext: AuthContext? = null,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val providersById = ConcurrentHashMap<String, Provider>()
    private val refreshGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val publicationMutexes = ConcurrentHashMap<String, Mutex>()
    private val authContext =
        authContext
            ?: object : AuthContext {
                override suspend fun env(name: String): String? = environment(name)

                override suspend fun fileExists(path: String): Boolean {
                    val expanded =
                        when {
                            path == "~" -> System.getProperty("user.home")
                            path.startsWith("~/") ->
                                Path
                                    .of(System.getProperty("user.home"))
                                    .resolve(path.removePrefix("~/"))
                                    .toString()

                            else -> path
                        }
                    return Files.exists(Path.of(expanded))
                }
            }

    init {
        providers.forEach(::setProvider)
    }

    fun setProvider(provider: Provider) {
        supersedeProviderRefresh(provider.id)
        providersById[provider.id] = provider
    }

    fun deleteProvider(id: String) {
        supersedeProviderRefresh(id)
        providersById.remove(id)
    }

    fun clearProviders() {
        (providersById.keys + refreshGenerations.keys).forEach(::supersedeProviderRefresh)
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
            if (checkProviderAuth(provider, credential) == null) {
                return@flatMap emptyList()
            }
            val providerModels =
                runCatching(provider::getModels).getOrDefault(emptyList())
            provider.filterModels(providerModels, credential)
        }
    }

    suspend fun checkAuth(providerId: String): AuthCheck? {
        val provider = providersById[providerId] ?: return null
        return checkProviderAuth(provider, readStoredCredential(providerId))
    }

    suspend fun getAuth(
        providerId: String,
        overrides: AuthResolutionOverrides = AuthResolutionOverrides(),
    ): AuthResult? {
        val provider = providersById[providerId] ?: return null
        val stored = readStoredCredential(provider.id)
        val context = overlayAuthContext(overrides.env)
        val apiKeyMethod = provider.apiKey
        if (
            overrides.apiKey != null &&
            apiKeyMethod != null &&
            stored !is OAuthCredential
        ) {
            return resolveApiKey(
                provider = provider,
                method = apiKeyMethod,
                context = context,
                credential =
                    ApiKeyCredential(
                        key = overrides.apiKey,
                        env = overrides.env,
                    ),
            )
        }
        return if (stored == null) {
            provider.apiKey
                ?.let { method ->
                    resolveApiKey(
                        provider = provider,
                        method = method,
                        context = context,
                        credential = null,
                    )
                }
                ?: provider.resolveAmbientAuth { name ->
                    overrides.env[name]?.takeIf(String::isNotBlank)
                        ?: environment(name)?.takeIf(String::isNotBlank)
                }
        } else {
            resolveStoredAuth(
                provider = provider,
                stored = stored,
                context = context,
                minOAuthValidityMs = overrides.minOAuthValidityMs,
                overrideEnvironment = overrides.env,
            )
        }
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
                    provider.apiKey
                        ?.takeIf(ApiKeyAuth::supportsLogin)
                        ?.login(interaction)
                        ?: throw ModelsAuthException(
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
        val selectedProviders = options.providers
        val refreshableProviders =
            providersById.values.filter { provider ->
                provider.supportsModelRefresh &&
                    (selectedProviders == null || provider.id in selectedProviders)
            }
        val refreshSemaphore = Semaphore(MAX_CONCURRENT_MODEL_REFRESHES)
        supervisorScope {
            refreshableProviders
                .map { provider ->
                    async {
                        refreshSemaphore.withPermit {
                            refreshProvider(provider, options, errors)
                        }
                    }
                }.awaitAll()
        }
        return ModelsRefreshResult(
            aborted = false,
            errors = errors.toSortedMap(),
        )
    }

    private suspend fun refreshProvider(
        provider: Provider,
        options: ModelsRefreshOptions,
        errors: ConcurrentHashMap<String, Throwable>,
    ) {
        val generation = supersedeProviderRefresh(provider.id)
        try {
            var storedCredential: Credential? = null
            var credentialError: Throwable? = null
            try {
                storedCredential = readStoredCredential(provider.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                credentialError = error
            }
            runProviderRefreshPhase(
                provider = provider,
                credential = storedCredential,
                allowNetwork = false,
                force = false,
                generation = generation,
            )
            credentialError?.let { throw it }
            if (!options.allowNetwork) {
                return
            }
            val credential =
                resolveRefreshCredential(
                    provider = provider,
                    stored = storedCredential,
                )
            if (credential == null && (provider.apiKey != null || provider.oauth != null)) {
                return
            }
            runProviderRefreshPhase(
                provider = provider,
                credential = credential,
                allowNetwork = true,
                force = options.force,
                generation = generation,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            errors[provider.id] = error
        }
    }

    private suspend fun runProviderRefreshPhase(
        provider: Provider,
        credential: Credential?,
        allowNetwork: Boolean,
        force: Boolean,
        generation: Long,
    ) {
        val stored = modelsStore.read(provider.id)
        val publish: suspend (ModelsPublication) -> Boolean = { publication ->
            publishProviderModels(provider.id, generation, publication)
        }
        val store =
            object : ProviderModelsStore {
                override suspend fun read(): ModelsStoreEntry? = stored

                override suspend fun write(entry: ModelsStoreEntry) {
                    publish(ModelsPublication(ModelsPersistence.Write(entry)))
                }

                override suspend fun delete() {
                    publish(ModelsPublication(ModelsPersistence.Delete))
                }
            }
        provider.refreshModels(
            RefreshModelsContext(
                credential = credential,
                stored = stored,
                store = store,
                publish = publish,
                allowNetwork = allowNetwork,
                force = force,
            ),
        )
    }

    private suspend fun publishProviderModels(
        providerId: String,
        generation: Long,
        publication: ModelsPublication,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        return publicationMutexes.computeIfAbsent(providerId) { Mutex() }.withLock {
            if (refreshGeneration(providerId) != generation) {
                return@withLock false
            }
            when (val persistence = publication.persistence) {
                ModelsPersistence.None -> Unit
                ModelsPersistence.Delete -> modelsStore.delete(providerId)
                is ModelsPersistence.Write -> modelsStore.write(providerId, persistence.entry)
            }
            currentCoroutineContext().ensureActive()
            if (refreshGeneration(providerId) != generation) {
                return@withLock false
            }
            publication.update?.invoke()
            true
        }
    }

    private fun supersedeProviderRefresh(providerId: String): Long =
        refreshGenerations.computeIfAbsent(providerId) { AtomicLong() }.incrementAndGet()

    private fun refreshGeneration(providerId: String): Long =
        refreshGenerations[providerId]?.get() ?: 0L

    private suspend fun resolveRefreshCredential(
        provider: Provider,
        stored: Credential?,
    ): Credential? =
        when (stored) {
            is ApiKeyCredential ->
                provider.apiKey
                    ?.let { method ->
                        resolveApiKey(
                            provider = provider,
                            method = method,
                            context = authContext,
                            credential = stored,
                        )
                    }?.let { result ->
                        ApiKeyCredential(
                            key = result.auth.apiKey,
                            env = result.env,
                        )
                    }
                    ?: stored

            is OAuthCredential -> {
                val oauth = provider.oauth
                when {
                    currentTimeMillis() < stored.expires -> stored
                    oauth == null -> null
                    else -> refreshStoredOAuth(provider, oauth)
                }
            }

            null ->
                provider.apiKey
                    ?.let { method ->
                        resolveApiKey(
                            provider = provider,
                            method = method,
                            context = authContext,
                            credential = null,
                        )
                    }?.let { result ->
                        ApiKeyCredential(
                            key = result.auth.apiKey,
                            env = result.env,
                        )
                    }
                    ?: if (provider.apiKey == null) {
                        provider.resolveAmbientAuth(environment)?.let { result ->
                            ApiKeyCredential(
                                key = result.auth.apiKey,
                                env = result.env,
                            )
                        }
                    } else {
                        null
                    }
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

    suspend fun fetchDeferred(
        model: Model,
        handle: DeferredHandle,
        options: DeferredFetchOptions = DeferredFetchOptions(),
    ): AssistantMessage {
        val provider =
            providersById[model.provider]
                ?: throw ModelsAuthException("provider", "Unknown provider: ${model.provider}")
        if (!provider.supportsDeferredResponses) {
            throw ModelsAuthException(
                "provider",
                "Provider ${model.provider} does not support deferred responses",
            )
        }
        val prepared = prepareStoredAuth(model, provider, options.request)
        return provider
            .fetchDeferred(
                prepared.model,
                handle,
                options.copy(request = prepared.options),
            ).result()
    }

    suspend fun cancelDeferred(
        model: Model,
        handle: DeferredHandle,
        options: DeferredCancelOptions = DeferredCancelOptions(),
    ) {
        val provider =
            providersById[model.provider]
                ?: throw ModelsAuthException("provider", "Unknown provider: ${model.provider}")
        if (!provider.supportsDeferredResponses) {
            throw ModelsAuthException(
                "provider",
                "Provider ${model.provider} does not support deferred responses",
            )
        }
        val prepared = prepareStoredAuth(model, provider, options.request)
        provider.cancelDeferred(
            prepared.model,
            handle,
            options.copy(request = prepared.options),
        )
    }

    private suspend fun prepareStoredAuth(
        model: Model,
        provider: Provider,
        options: StreamOptions,
    ): PreparedRequest {
        if (
            !options.apiKey.isNullOrBlank() &&
            provider.apiKey == null &&
            readStoredCredential(provider.id) == null
        ) {
            return PreparedRequest(model, options)
        }
        val resolution =
            getAuth(
                provider.id,
                AuthResolutionOverrides(
                    apiKey = options.apiKey,
                    env = options.env,
                ),
            )
                ?: return PreparedRequest(model, options)
        return PreparedRequest(
            model =
                resolution.auth.baseUrl
                    ?.takeIf(String::isNotBlank)
                    ?.let { model.copy(baseUrl = it) }
                    ?: model,
            options =
                options.copy(
                    apiKey = resolution.auth.apiKey ?: options.apiKey,
                    headers = mergeAuthHeaders(resolution.auth.headers, options.headers),
                    env = resolution.env + options.env,
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
        context: AuthContext = authContext,
        minOAuthValidityMs: Long? = null,
        overrideEnvironment: Map<String, String> = emptyMap(),
    ): AuthResult? {
        return when (stored) {
            is ApiKeyCredential ->
                provider.apiKey
                    ?.let { method ->
                        resolveApiKey(
                            provider = provider,
                            method = method,
                            context = context,
                            credential =
                                stored.copy(
                                    env = stored.env + overrideEnvironment,
                                ),
                        )
                    }
                    ?: AuthResult(
                        auth =
                            ModelAuth(
                                apiKey = stored.key,
                            ),
                        source = "Stored API key",
                        env = stored.env + overrideEnvironment,
                    )

            is OAuthCredential ->
                provider.oauth?.let {
                    resolveStoredOAuth(
                        provider = provider,
                        stored = stored,
                        oauth = it,
                        minOAuthValidityMs = minOAuthValidityMs,
                    )
                }
                    ?: throw ModelsAuthException(
                        code = "auth",
                        message = "Stored OAuth credential is not supported by ${provider.id}",
                    )
        }
    }

    private suspend fun checkProviderAuth(
        provider: Provider,
        credential: Credential?,
    ): AuthCheck? {
        if (credential is OAuthCredential) {
            return provider.oauth?.let {
                AuthCheck(
                    source = "OAuth",
                    type = AuthType.OAUTH,
                )
            }
        }
        val method = provider.apiKey
        if (method != null) {
            return try {
                method.check(authContext, credential as? ApiKeyCredential)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw ModelsAuthException(
                    code = "auth",
                    message = "API key auth check failed for provider ${provider.id}",
                    cause = error,
                )
            }
        }
        if (credential is ApiKeyCredential) {
            return credential.key
                ?.takeIf(String::isNotBlank)
                ?.let {
                    AuthCheck(
                        source = "Stored API key",
                        type = AuthType.API_KEY,
                    )
                }
        }
        return provider.resolveAmbientAuth(environment)?.let { result ->
            AuthCheck(
                source = result.source,
                type = AuthType.API_KEY,
            )
        } ?: if (provider.oauth == null) {
            AuthCheck(
                source = provider.name,
                type = AuthType.API_KEY,
            )
        } else {
            null
        }
    }

    private suspend fun resolveApiKey(
        provider: Provider,
        method: ApiKeyAuth,
        context: AuthContext,
        credential: ApiKeyCredential?,
    ): AuthResult? =
        try {
            method.resolve(context, credential)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw ModelsAuthException(
                code = "auth",
                message = "API key auth failed for provider ${provider.id}",
                cause = error,
            )
        }

    private fun overlayAuthContext(overrides: Map<String, String>): AuthContext =
        if (overrides.isEmpty()) {
            authContext
        } else {
            object : AuthContext {
                override suspend fun env(name: String): String? =
                    overrides[name]?.takeIf(String::isNotBlank)
                        ?: authContext.env(name)

                override suspend fun fileExists(path: String): Boolean =
                    authContext.fileExists(path)
            }
        }

    private suspend fun resolveStoredOAuth(
        provider: Provider,
        stored: OAuthCredential,
        oauth: OAuthAuth,
        minOAuthValidityMs: Long? = null,
    ): AuthResult? {
        val minimumValidityMs =
            maxOf(
                DEFAULT_OAUTH_MINIMUM_VALIDITY_MS,
                minOAuthValidityMs ?: 0L,
            )
        fun expiresSoon(credential: OAuthCredential): Boolean =
            credential.expires - currentTimeMillis() <= minimumValidityMs

        var credential = stored
        if (expiresSoon(credential)) {
            val post = refreshStoredOAuth(provider, oauth, minimumValidityMs)
            if (post !is OAuthCredential) {
                return null
            }
            credential = post
            if (minOAuthValidityMs != null && expiresSoon(credential)) {
                throw ModelsAuthException(
                    code = "oauth",
                    message = "OAuth refresh returned a token that expires too soon for ${provider.id}",
                )
            }
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

    private suspend fun refreshStoredOAuth(
        provider: Provider,
        oauth: OAuthAuth,
        minimumValidityMs: Long = 0L,
    ): Credential? =
        try {
            credentials.modify(provider.id) { current ->
                if (
                    current !is OAuthCredential ||
                    current.expires - currentTimeMillis() > minimumValidityMs
                ) {
                    null
                } else {
                    try {
                        withTimeoutOrNull(DEFAULT_OAUTH_REFRESH_TIMEOUT_MS) {
                            oauth.refresh(current)
                        } ?: throw TimeoutException(
                            "OAuth refresh timed out after ${DEFAULT_OAUTH_REFRESH_TIMEOUT_MS}ms",
                        )
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
private const val DEFAULT_OAUTH_MINIMUM_VALIDITY_MS = 5 * 60 * 1_000L
private const val DEFAULT_OAUTH_REFRESH_TIMEOUT_MS = 15_000L

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

private fun mergeSamplingParams(
    model: kotlinx.serialization.json.JsonObject?,
    request: kotlinx.serialization.json.JsonObject?,
): kotlinx.serialization.json.JsonObject? =
    if (model == null && request == null) {
        null
    } else {
        kotlinx.serialization.json.JsonObject(model.orEmpty() + request.orEmpty())
    }
