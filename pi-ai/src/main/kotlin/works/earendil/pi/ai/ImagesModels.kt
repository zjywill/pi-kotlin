package works.earendil.pi.ai

import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

interface ImagesProvider {
    val id: String
    val name: String
    val apiKeyEnvNames: List<String>
        get() = emptyList()
    val oauth: OAuthAuth?
        get() = null

    fun getModels(): List<ImagesModel>

    val supportsModelRefresh: Boolean
        get() = false

    suspend fun refreshModels() = Unit

    suspend fun generateImages(
        model: ImagesModel,
        context: ImagesContext,
        options: ImagesOptions = ImagesOptions(),
    ): AssistantImages
}

data class ImagesAuthOverrides(
    val apiKey: String? = null,
    val env: Map<String, String> = emptyMap(),
    val minOAuthValidityMs: Long? = null,
)

class ImagesModelsException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ImagesModels(
    providers: Iterable<ImagesProvider> = emptyList(),
    private val credentials: CredentialStore = InMemoryCredentialStore(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val environment: (String) -> String? = System::getenv,
) {
    private val providerLock = Any()
    private val providersById = linkedMapOf<String, ImagesProvider>()

    init {
        providers.forEach(::setProvider)
    }

    fun setProvider(provider: ImagesProvider) {
        synchronized(providerLock) {
            providersById[provider.id] = provider
        }
    }

    fun deleteProvider(id: String) {
        synchronized(providerLock) {
            providersById.remove(id)
        }
    }

    fun clearProviders() {
        synchronized(providerLock) {
            providersById.clear()
        }
    }

    fun getProviders(): List<ImagesProvider> =
        synchronized(providerLock) {
            providersById.values.toList()
        }

    fun getProvider(id: String): ImagesProvider? =
        synchronized(providerLock) {
            providersById[id]
        }

    fun getModels(provider: String? = null): List<ImagesModel> {
        if (provider != null) {
            return runCatching { getProvider(provider)?.getModels().orEmpty() }
                .getOrDefault(emptyList())
        }
        return getProviders().flatMap { entry ->
            runCatching(entry::getModels).getOrDefault(emptyList())
        }
    }

    fun getModel(
        provider: String,
        id: String,
    ): ImagesModel? = getModels(provider).firstOrNull { it.id == id }

    suspend fun refresh(provider: String? = null) {
        if (provider != null) {
            val entry = getProvider(provider) ?: return
            if (!entry.supportsModelRefresh) return
            try {
                entry.refreshModels()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ImagesModelsException) {
                throw error
            } catch (error: Throwable) {
                throw ImagesModelsException(
                    code = "model_source",
                    message = "Model refresh failed for $provider",
                    cause = error,
                )
            }
            return
        }

        supervisorScope {
            getProviders()
                .filter(ImagesProvider::supportsModelRefresh)
                .map { entry ->
                    async {
                        runCatching { entry.refreshModels() }
                    }
                }.awaitAll()
        }
    }

    suspend fun getAuth(
        providerId: String,
        overrides: ImagesAuthOverrides = ImagesAuthOverrides(),
    ): AuthResult? {
        val provider = getProvider(providerId) ?: return null
        return resolveAuth(provider, overrides)
    }

    suspend fun getAuth(
        model: ImagesModel,
        overrides: ImagesAuthOverrides = ImagesAuthOverrides(),
    ): AuthResult? = getAuth(model.provider, overrides)

    suspend fun generateImages(
        model: ImagesModel,
        context: ImagesContext,
        options: ImagesOptions = ImagesOptions(),
    ): AssistantImages =
        try {
            val provider =
                getProvider(model.provider)
                    ?: throw ImagesModelsException(
                        code = "provider",
                        message = "Unknown provider: ${model.provider}",
                    )
            val resolution =
                getAuth(
                    model,
                    ImagesAuthOverrides(
                        apiKey = options.apiKey,
                        env = options.env,
                    ),
                )
            val auth = resolution?.auth
            if (auth == null) {
                provider.generateImages(model, context, options)
            } else {
                val requestModel =
                    auth.baseUrl
                        ?.takeIf(String::isNotBlank)
                        ?.let { model.copy(baseUrl = it) }
                        ?: model
                provider.generateImages(
                    requestModel,
                    context,
                    options.copy(
                        apiKey = options.apiKey ?: auth.apiKey,
                        headers = mergeImagesHeaders(auth.headers, options.headers),
                        env = resolution.env + options.env,
                    ),
                )
            }
        } catch (error: CancellationException) {
            imageFailure(model, ImagesStopReason.ABORTED, error.message ?: "Request aborted")
        } catch (error: Throwable) {
            imageFailure(
                model,
                ImagesStopReason.ERROR,
                error.message ?: error::class.simpleName.orEmpty(),
            )
        }

    private suspend fun resolveAuth(
        provider: ImagesProvider,
        overrides: ImagesAuthOverrides,
    ): AuthResult? {
        if (overrides.apiKey != null && provider.apiKeyEnvNames.isNotEmpty()) {
            return resolveApiKeyAuth(
                provider = provider,
                credential = ApiKeyCredential(overrides.apiKey, overrides.env),
                requestEnv = overrides.env,
                explicit = true,
            )
        }

        val stored =
            try {
                credentials.read(provider.id)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw ImagesModelsException(
                    code = "auth",
                    message = "Credential store read failed for ${provider.id}",
                    cause = error,
                )
            }
        return when (stored) {
            is OAuthCredential ->
                resolveStoredOAuth(
                    provider = provider,
                    stored = stored,
                    minOAuthValidityMs = overrides.minOAuthValidityMs,
                )
            is ApiKeyCredential ->
                resolveApiKeyAuth(
                    provider = provider,
                    credential = stored,
                    requestEnv = overrides.env,
                    explicit = false,
                )

            null ->
                resolveApiKeyAuth(
                    provider = provider,
                    credential = null,
                    requestEnv = overrides.env,
                    explicit = false,
                )
        }
    }

    private fun resolveApiKeyAuth(
        provider: ImagesProvider,
        credential: ApiKeyCredential?,
        requestEnv: Map<String, String>,
        explicit: Boolean,
    ): AuthResult? {
        if (provider.apiKeyEnvNames.isEmpty()) return null
        val resolvedEnv = credential?.env.orEmpty() + requestEnv
        val key =
            credential
                ?.key
                ?.takeIf(String::isNotBlank)
                ?: provider.apiKeyEnvNames.firstNotNullOfOrNull { name ->
                    resolvedEnv[name]?.takeIf(String::isNotBlank)
                        ?: environment(name)?.takeIf(String::isNotBlank)
                }
                ?: return null
        return AuthResult(
            auth = ModelAuth(apiKey = key),
            source =
                when {
                    explicit -> "Explicit API key"
                    !credential?.key.isNullOrBlank() -> "stored credential"
                    else ->
                        provider.apiKeyEnvNames.firstOrNull { name ->
                            resolvedEnv[name]?.takeIf(String::isNotBlank) == key ||
                                environment(name)?.takeIf(String::isNotBlank) == key
                        }.orEmpty()
                },
            env = resolvedEnv,
        )
    }

    private suspend fun resolveStoredOAuth(
        provider: ImagesProvider,
        stored: OAuthCredential,
        minOAuthValidityMs: Long? = null,
    ): AuthResult? {
        val oauth = provider.oauth ?: return null
        val minimumValidityMs =
            maxOf(
                DEFAULT_IMAGES_OAUTH_MINIMUM_VALIDITY_MS,
                minOAuthValidityMs ?: 0L,
            )
        fun expiresSoon(credential: OAuthCredential): Boolean =
            credential.expires - currentTimeMillis() <= minimumValidityMs

        var credential = stored
        if (expiresSoon(credential)) {
            val post =
                try {
                    credentials.modify(provider.id) { current ->
                        if (
                            current !is OAuthCredential ||
                            current.expires - currentTimeMillis() > minimumValidityMs
                        ) {
                            null
                        } else {
                            try {
                                withTimeoutOrNull(DEFAULT_IMAGES_OAUTH_REFRESH_TIMEOUT_MS) {
                                    oauth.refresh(current)
                                } ?: throw TimeoutException(
                                    "OAuth refresh timed out after ${DEFAULT_IMAGES_OAUTH_REFRESH_TIMEOUT_MS}ms",
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                throw ImagesModelsException(
                                    code = "oauth",
                                    message = "OAuth refresh failed for ${provider.id}",
                                    cause = error,
                                )
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: ImagesModelsException) {
                    throw error
                } catch (error: Throwable) {
                    throw ImagesModelsException(
                        code = "auth",
                        message = "Credential store modify failed for ${provider.id}",
                        cause = error,
                    )
                }
            if (post !is OAuthCredential) return null
            credential = post
            if (minOAuthValidityMs != null && expiresSoon(credential)) {
                throw ImagesModelsException(
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
            throw ImagesModelsException(
                code = "oauth",
                message = "OAuth auth derivation failed for ${provider.id}",
                cause = error,
            )
        }
    }
}

private const val DEFAULT_IMAGES_OAUTH_MINIMUM_VALIDITY_MS = 5 * 60 * 1_000L

class ConfigurableImagesProvider(
    override val id: String,
    override val name: String = id,
    override val apiKeyEnvNames: List<String> = emptyList(),
    override val oauth: OAuthAuth? = null,
    initialModels: List<ImagesModel> = emptyList(),
    private val refreshSource: (suspend () -> List<ImagesModel>)? = null,
    private val generate: ImagesFunction,
) : ImagesProvider {
    @Volatile
    private var models: List<ImagesModel> = initialModels.toList()
    private val refreshMutex = Mutex()
    private var inFlightRefresh: CompletableDeferred<Unit>? = null

    override fun getModels(): List<ImagesModel> = models

    override val supportsModelRefresh: Boolean
        get() = refreshSource != null

    override suspend fun refreshModels() {
        val source = refreshSource ?: return
        val refresh =
            refreshMutex.withLock {
                inFlightRefresh?.let { return@withLock it to false }
                CompletableDeferred<Unit>()
                    .also { inFlightRefresh = it }
                    .let { it to true }
            }
        if (!refresh.second) {
            refresh.first.await()
            return
        }
        try {
            val refreshedModels = source()
            models = refreshedModels.toList()
            refresh.first.complete(Unit)
        } catch (error: Throwable) {
            refresh.first.completeExceptionally(error)
            throw error
        } finally {
            withContext(NonCancellable) {
                refreshMutex.withLock {
                    if (inFlightRefresh === refresh.first) {
                        inFlightRefresh = null
                    }
                }
            }
        }
    }

    override suspend fun generateImages(
        model: ImagesModel,
        context: ImagesContext,
        options: ImagesOptions,
    ): AssistantImages = generate.generateImages(model, context, options)
}

private fun mergeImagesHeaders(
    auth: Map<String, String?>,
    request: Map<String, String?>,
): Map<String, String?> {
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

internal fun imageFailure(
    model: ImagesModel,
    reason: ImagesStopReason,
    message: String,
): AssistantImages =
    AssistantImages(
        api = model.api,
        provider = model.provider,
        model = model.id,
        output = emptyList(),
        stopReason = reason,
        errorMessage = message,
    )

private const val DEFAULT_IMAGES_OAUTH_REFRESH_TIMEOUT_MS = 15_000L
