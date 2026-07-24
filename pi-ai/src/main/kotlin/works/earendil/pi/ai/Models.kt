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

    fun getModels(): List<Model>

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
        return runCatching {
            provider.stream(model, context, options)
        }.getOrElse { error ->
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
        return runCatching {
            provider.streamSimple(model, context, options)
        }.getOrElse { error ->
            errorStream(model, error.message ?: error::class.simpleName.orEmpty())
        }
    }

    suspend fun completeSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions(),
    ): AssistantMessage = streamSimple(model, context, options).result()

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
