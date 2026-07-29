package works.earendil.pi.ai

import java.util.ArrayDeque
import kotlin.math.ceil
import kotlinx.serialization.json.Json

data class FauxModelDefinition(
    val id: String,
    val name: String = id,
    val reasoning: Boolean = false,
    val input: List<ModelInput> = listOf(ModelInput.TEXT),
    val cost: ModelCost = ModelCost(0.0, 0.0, 0.0, 0.0),
    val contextWindow: Int = 128_000,
    val maxTokens: Int = 16_384,
)

data class FauxState(
    var callCount: Int = 0,
)

sealed interface FauxResponseStep {
    data class Message(
        val value: AssistantMessage,
    ) : FauxResponseStep

    data class Factory(
        val create: suspend (Context, StreamOptions, FauxState, Model) -> AssistantMessage,
    ) : FauxResponseStep
}

class FauxProvider(
    override val id: String = "faux",
    override val name: String = "Faux",
    override val oauth: OAuthAuth? = null,
    private val api: String = "faux",
    definitions: List<FauxModelDefinition> =
        listOf(
            FauxModelDefinition(
                id = "faux-1",
                name = "Faux Model",
            ),
        ),
) : Provider {
    private val responses = ArrayDeque<FauxResponseStep>()
    private val promptCache = mutableMapOf<String, String>()
    private val models =
        definitions.map { definition ->
            Model(
                id = definition.id,
                name = definition.name,
                api = api,
                provider = id,
                baseUrl = "http://localhost:0",
                reasoning = definition.reasoning,
                input = definition.input,
                cost = definition.cost,
                contextWindow = definition.contextWindow,
                maxTokens = definition.maxTokens,
            )
        }

    val state = FauxState()

    override fun getModels(): List<Model> = models

    fun getModel(modelId: String? = null): Model? =
        if (modelId == null) {
            models.firstOrNull()
        } else {
            models.firstOrNull { it.id == modelId }
        }

    @Synchronized
    fun setResponses(steps: List<FauxResponseStep>) {
        responses.clear()
        responses.addAll(steps)
    }

    @Synchronized
    fun appendResponses(steps: List<FauxResponseStep>) {
        responses.addAll(steps)
    }

    @Synchronized
    fun pendingResponseCount(): Int = responses.size

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        val step =
            synchronized(this) {
                state.callCount++
                responses.pollFirst()
            }

        val response =
            try {
                when (step) {
                    is FauxResponseStep.Message -> step.value
                    is FauxResponseStep.Factory -> step.create(context, options, state, model)
                    null -> error("No more faux responses queued")
                }
            } catch (error: Throwable) {
                val errorMessage =
                    AssistantMessage(
                        content = emptyList(),
                        api = api,
                        provider = id,
                        model = model.id,
                        stopReason = StopReason.ERROR,
                        errorMessage = error.message ?: error::class.simpleName.orEmpty(),
                    )
                stream.push(AssistantError(StopReason.ERROR, errorMessage))
                return stream
            }

        val finalMessage =
            response.copy(
                api = api,
                provider = id,
                model = model.id,
                usage = estimateUsage(response, context, options),
            )
        emitContentEvents(stream, finalMessage)
        if (finalMessage.stopReason == StopReason.PENDING) {
            val error =
                finalMessage.copy(
                    stopReason = StopReason.ERROR,
                    errorMessage = "Faux response ended without a stop reason",
                )
            stream.push(AssistantError(StopReason.ERROR, error))
            return stream
        }
        val terminal =
            if (finalMessage.stopReason == StopReason.ERROR || finalMessage.stopReason == StopReason.ABORTED) {
                AssistantError(finalMessage.stopReason, finalMessage)
            } else {
                AssistantDone(finalMessage.stopReason, finalMessage)
            }
        stream.push(terminal)
        return stream
    }

    private fun emitContentEvents(
        stream: AssistantMessageEventStream,
        finalMessage: AssistantMessage,
    ) {
        var partial =
            finalMessage.copy(
                content = emptyList(),
                stopReason = StopReason.PENDING,
            )
        stream.push(AssistantStart(partial))
        for ((index, block) in finalMessage.content.withIndex()) {
            partial = partial.copy(content = partial.content + emptyVersion(block))
            when (block) {
                is TextContent -> {
                    stream.push(TextStart(index, partial))
                    partial = partial.copy(content = partial.content.dropLast(1) + block)
                    stream.push(TextDelta(index, block.text, partial))
                    stream.push(TextEnd(index, block.text, partial))
                }

                is ThinkingContent -> {
                    stream.push(ThinkingStart(index, partial))
                    partial = partial.copy(content = partial.content.dropLast(1) + block)
                    stream.push(ThinkingDelta(index, block.thinking, partial))
                    stream.push(ThinkingEnd(index, block.thinking, partial))
                }

                is ToolCall -> {
                    stream.push(ToolCallStart(index, partial))
                    partial = partial.copy(content = partial.content.dropLast(1) + block)
                    stream.push(ToolCallDelta(index, Json.encodeToString(block.arguments), partial))
                    stream.push(ToolCallEnd(index, block, partial))
                }

                is ImageContent -> {
                    partial = partial.copy(content = partial.content.dropLast(1) + block)
                }
            }
        }
    }

    private fun emptyVersion(block: ContentBlock): ContentBlock =
        when (block) {
            is TextContent -> block.copy(text = "")
            is ThinkingContent -> block.copy(thinking = "")
            is ToolCall -> block.copy(arguments = kotlinx.serialization.json.JsonObject(emptyMap()))
            is ImageContent -> block
        }

    private fun estimateUsage(
        response: AssistantMessage,
        context: Context,
        options: StreamOptions,
    ): Usage {
        val prompt = serializeContext(context)
        val promptTokens = estimateTokens(prompt)
        val outputTokens = estimateTokens(contentText(response.content))
        var input = promptTokens
        var cacheRead = 0
        var cacheWrite = 0
        val sessionId = options.sessionId
        if (sessionId != null && options.cacheRetention != CacheRetention.NONE) {
            val previous = promptCache[sessionId]
            if (previous == null) {
                cacheWrite = promptTokens
            } else {
                val common = previous.zip(prompt).takeWhile { (a, b) -> a == b }.size
                cacheRead = estimateTokens(previous.take(common))
                cacheWrite = estimateTokens(prompt.drop(common))
                input = (promptTokens - cacheRead).coerceAtLeast(0)
            }
            promptCache[sessionId] = prompt
        }
        return Usage(
            input = input,
            output = outputTokens,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            totalTokens = input + outputTokens + cacheRead + cacheWrite,
        )
    }

    private fun serializeContext(context: Context): String =
        buildList {
            context.systemPrompt?.let { add("system:$it") }
            context.messages.forEach { message ->
                val text =
                    when (message) {
                        is UserMessage -> contentText(message.content)
                        is AssistantMessage -> contentText(message.content)
                        is ToolResultMessage -> "${message.toolName}\n${contentText(message.content)}"
                        is CustomMessage -> contentText(message.content)
                        is CompactionSummaryMessage -> message.summary
                        is BranchSummaryMessage -> message.summary
                        is BashExecutionMessage -> "${message.command}\n${message.output}"
                    }
                add("${message::class.simpleName}:$text")
            }
            if (context.tools.isNotEmpty()) {
                add("tools:${context.tools}")
            }
        }.joinToString("\n\n")

    private fun estimateTokens(text: String): Int = ceil(text.length / 4.0).toInt()
}

fun fauxText(text: String): TextContent = TextContent(text)

fun fauxThinking(thinking: String): ThinkingContent = ThinkingContent(thinking)

fun fauxToolCall(
    name: String,
    arguments: kotlinx.serialization.json.JsonObject,
    id: String = "tool:${uuidv7()}",
): ToolCall = ToolCall(id = id, name = name, arguments = arguments)

fun fauxAssistantMessage(
    content: List<ContentBlock>,
    stopReason: StopReason = StopReason.STOP,
    errorMessage: String? = null,
    responseId: String? = null,
    timestamp: Long = System.currentTimeMillis(),
): AssistantMessage =
    AssistantMessage(
        content = content,
        api = "faux",
        provider = "faux",
        model = "faux-1",
        stopReason = stopReason,
        errorMessage = errorMessage,
        responseId = responseId,
        timestamp = timestamp,
    )

fun fauxAssistantMessage(
    text: String,
    stopReason: StopReason = StopReason.STOP,
): AssistantMessage = fauxAssistantMessage(listOf(fauxText(text)), stopReason)
