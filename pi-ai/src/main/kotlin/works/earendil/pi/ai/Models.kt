package works.earendil.pi.ai

import java.util.concurrent.ConcurrentHashMap

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

class Models(
    providers: Iterable<Provider> = emptyList(),
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
