package works.earendil.pi.ai.providers

import java.net.http.HttpClient
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.AssistantError
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.AssistantStart
import works.earendil.pi.ai.BashExecutionMessage
import works.earendil.pi.ai.BranchSummaryMessage
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.CompactionSummaryMessage
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.CustomMessage
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.TextDelta
import works.earendil.pi.ai.TextEnd
import works.earendil.pi.ai.TextStart
import works.earendil.pi.ai.ThinkingContent
import works.earendil.pi.ai.ThinkingDelta
import works.earendil.pi.ai.ThinkingEnd
import works.earendil.pi.ai.ThinkingStart
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolCallDelta
import works.earendil.pi.ai.ToolCallEnd
import works.earendil.pi.ai.ToolCallStart
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.contentText
import works.earendil.pi.ai.createAssistantMessageEventStream
import works.earendil.pi.ai.getJsonSchemaToolParameters
import works.earendil.pi.ai.http.ProviderHttpException
import works.earendil.pi.ai.http.postSse
import works.earendil.pi.ai.resolveJsonSchemaStrictSampling

class MistralProvider(
    override val id: String,
    override val name: String,
    override val baseUrl: String,
    private val models: List<Model>,
    private val apiKeyEnvNames: List<String>,
    private val client: HttpClient = HttpClient.newHttpClient(),
) : Provider {
    override fun getModels(): List<Model> = models

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        providerScope.launch {
            runCatching {
                execute(model, context, options, stream)
            }.onFailure { error ->
                stream.push(
                    AssistantError(
                        StopReason.ERROR,
                        AssistantMessage(
                            content = emptyList(),
                            api = model.api,
                            provider = model.provider,
                            model = model.id,
                            stopReason = StopReason.ERROR,
                            errorMessage = formatMistralError(error),
                        ),
                    ),
                )
            }
        }
        return stream
    }

    override suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream {
        val apiKey = resolveApiKey(id, options.stream.apiKey, options.stream.env, apiKeyEnvNames)
        val requested = options.reasoning
        val useReasoning = model.reasoning && requested != null
        val promptMode =
            if (useReasoning && !usesMistralReasoningEffort(model)) {
                "reasoning"
            } else {
                null
            }
        val reasoningEffort =
            if (useReasoning && usesMistralReasoningEffort(model)) {
                model.thinkingLevelMap[model.clampThinkingLevel(requested)] ?: "high"
            } else {
                null
            }
        return stream(
            model,
            context,
            options.stream.copy(
                apiKey = apiKey,
                reasoning = requested,
                reasoningEffort = reasoningEffort,
                promptMode = promptMode,
                thinkingBudgets = options.thinkingBudgets,
            ),
        )
    }

    private suspend fun execute(
        model: Model,
        context: Context,
        options: StreamOptions,
        stream: AssistantMessageEventStream,
    ) {
        val apiKey = resolveApiKey(id, options.apiKey, options.env, apiKeyEnvNames)
        val body = buildMistralRequestBody(model, context, options)
        val headers =
            mergedHeaders(
                mapOf("authorization" to "Bearer $apiKey"),
                model.headers,
                options.headers,
            ).toMutableMap()
        if (shouldUseMistralPromptCaching(options) && headers.keys.none { it.equals("x-affinity", true) }) {
            headers["x-affinity"] = requireNotNull(options.sessionId)
        }

        val blocks = mutableListOf<ContentBlock>()
        var currentBlock: CurrentBlock? = null
        val toolIndexes = linkedMapOf<String, Int>()
        val toolArguments = mutableMapOf<String, String>()
        var responseId: String? = null
        var usage = Usage()
        var stopReason = StopReason.PENDING
        var rawStopReason: String? = null
        var stopError: String? = null

        fun snapshot(): AssistantMessage =
            AssistantMessage(
                content = copyBlocks(blocks),
                api = model.api,
                provider = model.provider,
                model = model.id,
                responseId = responseId,
                usage = usage,
                stopReason = stopReason,
                errorMessage = stopError,
                rawStopReason = rawStopReason,
            )

        fun finishCurrentBlock() {
            when (val current = currentBlock) {
                is CurrentBlock.Text ->
                    stream.push(
                        TextEnd(
                            current.contentIndex,
                            (blocks[current.contentIndex] as TextContent).text,
                            snapshot(),
                        ),
                    )

                is CurrentBlock.Thinking ->
                    stream.push(
                        ThinkingEnd(
                            current.contentIndex,
                            (blocks[current.contentIndex] as ThinkingContent).thinking,
                            snapshot(),
                        ),
                    )

                null -> Unit
            }
            currentBlock = null
        }

        fun appendText(delta: String) {
            if (delta.isEmpty()) return
            val current =
                (currentBlock as? CurrentBlock.Text)
                    ?: run {
                        finishCurrentBlock()
                        val index = blocks.size
                        blocks += TextContent("")
                        stream.push(TextStart(index, snapshot()))
                        CurrentBlock.Text(index).also { currentBlock = it }
                    }
            val block = blocks[current.contentIndex] as TextContent
            blocks[current.contentIndex] = block.copy(text = block.text + delta)
            stream.push(TextDelta(current.contentIndex, delta, snapshot()))
        }

        fun appendThinking(delta: String) {
            if (delta.isEmpty()) return
            val current =
                (currentBlock as? CurrentBlock.Thinking)
                    ?: run {
                        finishCurrentBlock()
                        val index = blocks.size
                        blocks += ThinkingContent("")
                        stream.push(ThinkingStart(index, snapshot()))
                        CurrentBlock.Thinking(index).also { currentBlock = it }
                    }
            val block = blocks[current.contentIndex] as ThinkingContent
            blocks[current.contentIndex] = block.copy(thinking = block.thinking + delta)
            stream.push(ThinkingDelta(current.contentIndex, delta, snapshot()))
        }

        stream.push(AssistantStart(snapshot()))
        postSse(
            client = client,
            url = "${model.baseUrl.trimEnd('/')}/v1/chat/completions",
            body = providerJson.encodeToString(JsonObject.serializer(), body),
            headers = headers,
            timeoutMs = options.timeoutMs,
            maxRetries = options.maxRetries,
            maxRetryDelayMs = options.maxRetryDelayMs,
            fetch = options.fetch,
        ) { sse ->
            if (sse.data.isBlank() || sse.data == "[DONE]") {
                return@postSse
            }
            val chunk = providerJson.parseToJsonElement(sse.data).jsonObject
            chunk.string("id")?.takeIf(String::isNotEmpty)?.let { responseId = responseId ?: it }
            chunk.obj("usage")?.let { rawUsage ->
                val promptTokens = rawUsage.int("prompt_tokens") ?: 0
                val cached = mistralCachedPromptTokens(rawUsage, promptTokens)
                val calculated =
                    calculateUsageCost(
                        model = model,
                        input = (promptTokens - cached).coerceAtLeast(0),
                        output = rawUsage.int("completion_tokens") ?: 0,
                        cacheRead = cached,
                    )
                usage =
                    calculated.copy(
                        totalTokens = rawUsage.int("total_tokens") ?: calculated.totalTokens,
                    )
            }

            val choice = chunk.array("choices")?.firstOrNull()?.jsonObject ?: return@postSse
            choice.string("finish_reason")?.let { reason ->
                rawStopReason = reason
                val mapped = mapMistralStopReason(reason)
                stopReason = mapped.first
                stopError = mapped.second
            }
            val delta = choice.obj("delta") ?: return@postSse
            when (val content = delta["content"]) {
                is JsonPrimitive ->
                    if (content !== JsonNull) {
                        appendText(content.contentOrNull.orEmpty())
                    }

                is JsonArray ->
                    content.forEach { rawItem ->
                        val item = rawItem as? JsonObject ?: return@forEach
                        when (item.string("type")) {
                            "thinking" -> {
                                val thinking =
                                    item.array("thinking")
                                        ?.joinToString("") { part ->
                                            (part as? JsonObject)?.string("text").orEmpty()
                                        }.orEmpty()
                                appendThinking(thinking)
                            }

                            "text" -> appendText(item.string("text").orEmpty())
                        }
                    }

                else -> Unit
            }

            delta.array("tool_calls")?.forEach { rawToolCall ->
                val toolCall = rawToolCall.jsonObject
                finishCurrentBlock()
                val providerIndex = toolCall.int("index") ?: 0
                val callId =
                    toolCall.string("id")
                        ?.takeIf { it.isNotEmpty() && it != "null" }
                        ?: deriveMistralToolCallId("toolcall:$providerIndex", 0)
                val key = "$callId:$providerIndex"
                val function = toolCall.obj("function") ?: JsonObject(emptyMap())
                val contentIndex =
                    toolIndexes.getOrPut(key) {
                        val index = blocks.size
                        blocks += ToolCall(callId, function.string("name").orEmpty(), JsonObject(emptyMap()))
                        toolArguments[key] = ""
                        stream.push(ToolCallStart(index, snapshot()))
                        index
                    }
                val arguments =
                    when (val rawArguments = function["arguments"]) {
                        is JsonPrimitive -> rawArguments.contentOrNull.orEmpty()
                        is JsonObject ->
                            providerJson.encodeToString(JsonObject.serializer(), rawArguments)

                        else -> "{}"
                    }
                val combined = toolArguments.getValue(key) + arguments
                toolArguments[key] = combined
                val current = blocks[contentIndex] as ToolCall
                blocks[contentIndex] =
                    current.copy(
                        name = function.string("name") ?: current.name,
                        arguments = parseJsonObjectOrEmpty(combined),
                    )
                stream.push(ToolCallDelta(contentIndex, arguments, snapshot()))
            }
        }

        finishCurrentBlock()
        toolIndexes.forEach { (key, contentIndex) ->
            val current = blocks[contentIndex] as ToolCall
            val final = current.copy(arguments = parseJsonObjectOrEmpty(toolArguments[key].orEmpty()))
            blocks[contentIndex] = final
            stream.push(ToolCallEnd(contentIndex, final, snapshot()))
        }
        val final = snapshot()
        if (stopReason == StopReason.PENDING) {
            error("Mistral stream ended without a finish reason")
        } else if (stopReason == StopReason.ERROR) {
            stream.push(
                AssistantError(
                    StopReason.ERROR,
                    final.copy(errorMessage = stopError ?: "An unknown error occurred"),
                ),
            )
        } else {
            stream.push(AssistantDone(stopReason, final))
        }
    }

    private sealed interface CurrentBlock {
        val contentIndex: Int

        data class Text(
            override val contentIndex: Int,
        ) : CurrentBlock

        data class Thinking(
            override val contentIndex: Int,
        ) : CurrentBlock
    }
}

internal fun buildMistralRequestBody(
    model: Model,
    context: Context,
    options: StreamOptions,
): JsonObject =
    buildJsonObject {
        put("model", model.id)
        put("stream", true)
        put("messages", mistralMessages(model, context))
        if (context.tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    context.tools.forEach { tool ->
                        val strict = resolveJsonSchemaStrictSampling(tool, supportsStrictMode = true)
                        add(
                            buildJsonObject {
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", tool.name)
                                        put("description", tool.description)
                                        put("parameters", getJsonSchemaToolParameters(tool, strict))
                                        put("strict", strict ?: false)
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
        options.temperature?.let { put("temperature", it) }
        options.maxTokens?.let { put("max_tokens", it) }
        options.toolChoice?.let { put("tool_choice", it) }
        options.promptMode?.let { put("prompt_mode", it) }
        options.reasoningEffort?.let { put("reasoning_effort", it) }
        if (shouldUseMistralPromptCaching(options)) {
            put("prompt_cache_key", requireNotNull(options.sessionId))
        }
    }

private fun mistralMessages(
    model: Model,
    context: Context,
): JsonArray {
    val normalizer = MistralToolCallIdNormalizer()
    val toolCallIds = mutableMapOf<String, String>()
    val encoded =
        context.messages.mapNotNull { message ->
            when (message) {
                is works.earendil.pi.ai.UserMessage ->
                    encodeMistralUserMessage(message.content, model)
                        ?.let { EncodedMistralMessage(MistralRole.USER, it) }

                is AssistantMessage -> {
                    if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) {
                        null
                    } else {
                        encodeMistralAssistantMessage(message, model, normalizer, toolCallIds)
                    }
                }

                is ToolResultMessage -> {
                    val normalizedId = toolCallIds[message.toolCallId] ?: message.toolCallId
                    EncodedMistralMessage(
                        role = MistralRole.TOOL,
                        json = encodeMistralToolResult(message, normalizedId, model),
                        toolResultId = normalizedId,
                    )
                }

                is CustomMessage ->
                    encodeMistralTextUserMessage(contentText(message.content))

                is CompactionSummaryMessage ->
                    encodeMistralTextUserMessage(message.summary)

                is BranchSummaryMessage ->
                    encodeMistralTextUserMessage(message.summary)

                is BashExecutionMessage ->
                    encodeMistralTextUserMessage("${message.command}\n${message.output}")
            }
        }

    val result = mutableListOf<JsonObject>()
    var pendingToolCalls = emptyList<MistralToolReference>()
    val existingResults = mutableSetOf<String>()

    fun flushPending() {
        pendingToolCalls.forEach { tool ->
            if (tool.id !in existingResults) {
                result +=
                    encodeMistralToolResult(
                        ToolResultMessage(
                            toolCallId = tool.id,
                            toolName = tool.name,
                            content = listOf(TextContent("No result provided")),
                            isError = true,
                        ),
                        tool.id,
                        model,
                    )
            }
        }
        pendingToolCalls = emptyList()
        existingResults.clear()
    }

    encoded.forEach { message ->
        when (message.role) {
            MistralRole.ASSISTANT -> {
                flushPending()
                pendingToolCalls = message.toolCalls
                result += message.json
            }

            MistralRole.TOOL -> {
                message.toolResultId?.let(existingResults::add)
                result += message.json
            }

            MistralRole.USER -> {
                flushPending()
                result += message.json
            }
        }
    }
    flushPending()

    return buildJsonArray {
        context.systemPrompt?.let { system ->
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", system)
                },
            )
        }
        result.forEach(::add)
    }
}

private fun encodeMistralTextUserMessage(text: String): EncodedMistralMessage =
    EncodedMistralMessage(
        MistralRole.USER,
        buildJsonObject {
            put("role", "user")
            put("content", text)
        },
    )

private fun encodeMistralUserMessage(
    content: MessageContent,
    model: Model,
): JsonObject? =
    buildJsonObject {
        put("role", "user")
        when (content) {
            is MessageContent.Text -> put("content", content.text)
            is MessageContent.Blocks -> {
                val chunks = mistralUserChunks(content.blocks, model)
                if (chunks.isEmpty()) return null
                put("content", JsonArray(chunks))
            }
        }
    }

private fun mistralUserChunks(
    blocks: List<ContentBlock>,
    model: Model,
): List<JsonObject> {
    val supportsImages = ModelInput.IMAGE in model.input
    val result = mutableListOf<JsonObject>()
    var previousWasPlaceholder = false
    blocks.forEach { block ->
        when (block) {
            is TextContent -> {
                result += mistralTextChunk(block.text)
                previousWasPlaceholder = block.text == NON_VISION_USER_IMAGE_PLACEHOLDER
            }

            is ImageContent ->
                if (supportsImages) {
                    result += mistralImageChunk(block)
                    previousWasPlaceholder = false
                } else if (!previousWasPlaceholder) {
                    result += mistralTextChunk(NON_VISION_USER_IMAGE_PLACEHOLDER)
                    previousWasPlaceholder = true
                }

            else -> Unit
        }
    }
    return result
}

private fun encodeMistralAssistantMessage(
    message: AssistantMessage,
    model: Model,
    normalizer: MistralToolCallIdNormalizer,
    toolCallIds: MutableMap<String, String>,
): EncodedMistralMessage {
    val sameModel =
        message.provider == model.provider &&
            message.api == model.api &&
            message.model == model.id
    val content = mutableListOf<JsonObject>()
    val tools = mutableListOf<MistralToolReference>()
    val rawTools = mutableListOf<JsonObject>()
    message.content.forEach { block ->
        when (block) {
            is TextContent ->
                if (block.text.isNotBlank()) {
                    content += mistralTextChunk(block.text)
                }

            is ThinkingContent ->
                if (block.thinking.isNotBlank()) {
                    if (sameModel) {
                        content +=
                            buildJsonObject {
                                put("type", "thinking")
                                put("thinking", buildJsonArray { add(mistralTextChunk(block.thinking)) })
                            }
                    } else {
                        content += mistralTextChunk(block.thinking)
                    }
                }

            is ToolCall -> {
                val normalizedId =
                    if (sameModel) {
                        block.id
                    } else {
                        normalizer.normalize(block.id).also { toolCallIds[block.id] = it }
                    }
                tools += MistralToolReference(normalizedId, block.name)
                rawTools +=
                    buildJsonObject {
                        put("id", normalizedId)
                        put("type", "function")
                        put(
                            "function",
                            buildJsonObject {
                                put("name", block.name)
                                put(
                                    "arguments",
                                    providerJson.encodeToString(JsonObject.serializer(), block.arguments),
                                )
                            },
                        )
                        put("index", 0)
                    }
            }

            is ImageContent -> Unit
        }
    }
    val json =
        buildJsonObject {
            put("role", "assistant")
            if (content.isNotEmpty()) {
                put("content", JsonArray(content))
            }
            if (rawTools.isNotEmpty()) {
                put("tool_calls", JsonArray(rawTools))
            }
            put("prefix", false)
        }
    return EncodedMistralMessage(MistralRole.ASSISTANT, json, toolCalls = tools)
}

private fun encodeMistralToolResult(
    message: ToolResultMessage,
    toolCallId: String,
    model: Model,
): JsonObject {
    val supportsImages = ModelInput.IMAGE in model.input
    val normalizedBlocks =
        if (supportsImages) {
            message.content
        } else {
            replaceMistralToolImages(message.content)
        }
    val text =
        normalizedBlocks
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text }
    val hasImages = normalizedBlocks.any { it is ImageContent }
    val toolText = mistralToolResultText(text, hasImages, supportsImages, message.isError)
    return buildJsonObject {
        put("role", "tool")
        put(
            "content",
            buildJsonArray {
                add(mistralTextChunk(toolText))
                if (supportsImages) {
                    normalizedBlocks.filterIsInstance<ImageContent>().forEach { add(mistralImageChunk(it)) }
                }
            },
        )
        put("tool_call_id", toolCallId)
        put("name", message.toolName)
    }
}

private fun replaceMistralToolImages(blocks: List<ContentBlock>): List<ContentBlock> {
    val result = mutableListOf<ContentBlock>()
    var previousWasPlaceholder = false
    blocks.forEach { block ->
        if (block is ImageContent) {
            if (!previousWasPlaceholder) {
                result += TextContent(NON_VISION_TOOL_IMAGE_PLACEHOLDER)
            }
            previousWasPlaceholder = true
        } else {
            result += block
            previousWasPlaceholder =
                block is TextContent && block.text == NON_VISION_TOOL_IMAGE_PLACEHOLDER
        }
    }
    return result
}

private fun mistralToolResultText(
    text: String,
    hasImages: Boolean,
    supportsImages: Boolean,
    isError: Boolean,
): String {
    val trimmed = text.trim()
    val errorPrefix = if (isError) "[tool error] " else ""
    if (trimmed.isNotEmpty()) {
        val imageSuffix =
            if (hasImages && !supportsImages) {
                "\n[tool image omitted: model does not support images]"
            } else {
                ""
            }
        return "$errorPrefix$trimmed$imageSuffix"
    }
    if (hasImages) {
        if (supportsImages) {
            return if (isError) "[tool error] (see attached image)" else "(see attached image)"
        }
        return if (isError) {
            "[tool error] (image omitted: model does not support images)"
        } else {
            "(image omitted: model does not support images)"
        }
    }
    return if (isError) "[tool error] (no tool output)" else "(no tool output)"
}

private fun mistralTextChunk(text: String): JsonObject =
    buildJsonObject {
        put("type", "text")
        put("text", text)
    }

private fun mistralImageChunk(image: ImageContent): JsonObject =
    buildJsonObject {
        put("type", "image_url")
        put("image_url", "data:${image.mimeType};base64,${image.data}")
    }

private fun shouldUseMistralPromptCaching(options: StreamOptions): Boolean =
    options.cacheRetention != CacheRetention.NONE && !options.sessionId.isNullOrEmpty()

private fun usesMistralReasoningEffort(model: Model): Boolean =
    model.id in
        setOf(
            "mistral-small-2603",
            "mistral-small-latest",
            "mistral-medium-3.5",
        )

private fun mistralCachedPromptTokens(
    usage: JsonObject,
    promptTokens: Int,
): Int {
    val cached =
        usage.obj("prompt_tokens_details")?.int("cached_tokens")
            ?: usage.obj("prompt_token_details")?.int("cached_tokens")
            ?: usage.int("num_cached_tokens")
            ?: 0
    return cached.coerceIn(0, promptTokens)
}

internal fun mapMistralStopReason(reason: String): Pair<StopReason, String?> =
    when (reason) {
        "stop" -> StopReason.STOP to null
        "length", "model_length" -> StopReason.LENGTH to null
        "tool_calls" -> StopReason.TOOL_USE to null
        "error" -> StopReason.ERROR to "Provider stopped with: error"
        else -> StopReason.ERROR to "Provider stopped with: $reason"
    }

private fun formatMistralError(error: Throwable): String =
    when (error) {
        is ProviderHttpException -> "Mistral API error (${error.status}): ${error.message.orEmpty()}"
        else -> error.message ?: error::class.simpleName.orEmpty()
    }

private data class EncodedMistralMessage(
    val role: MistralRole,
    val json: JsonObject,
    val toolCalls: List<MistralToolReference> = emptyList(),
    val toolResultId: String? = null,
)

private data class MistralToolReference(
    val id: String,
    val name: String,
)

private enum class MistralRole {
    USER,
    ASSISTANT,
    TOOL,
}

private class MistralToolCallIdNormalizer {
    private val normalizedByOriginal = mutableMapOf<String, String>()
    private val originalByNormalized = mutableMapOf<String, String>()

    fun normalize(id: String): String {
        normalizedByOriginal[id]?.let { return it }
        var attempt = 0
        while (true) {
            val candidate = deriveMistralToolCallId(id, attempt)
            val owner = originalByNormalized[candidate]
            if (owner == null || owner == id) {
                normalizedByOriginal[id] = candidate
                originalByNormalized[candidate] = id
                return candidate
            }
            attempt += 1
        }
    }
}

private fun deriveMistralToolCallId(
    id: String,
    attempt: Int,
): String {
    val normalized = id.filter(Char::isLetterOrDigit)
    if (attempt == 0 && normalized.length == MISTRAL_TOOL_CALL_ID_LENGTH) {
        return normalized
    }
    val seedBase = normalized.ifEmpty { id }
    val seed = if (attempt == 0) seedBase else "$seedBase:$attempt"
    return mistralShortHash(seed)
        .filter(Char::isLetterOrDigit)
        .take(MISTRAL_TOOL_CALL_ID_LENGTH)
}

private fun mistralShortHash(value: String): String {
    var h1 = 0xdeadbeef.toInt()
    var h2 = 0x41c6ce57
    value.forEach { char ->
        h1 = mistralImul(h1 xor char.code, 2_654_435_761L.toInt())
        h2 = mistralImul(h2 xor char.code, 1_597_334_677)
    }
    h1 =
        mistralImul(h1 xor (h1 ushr 16), 2_246_822_507L.toInt()) xor
        mistralImul(h2 xor (h2 ushr 13), 3_266_489_909L.toInt())
    h2 =
        mistralImul(h2 xor (h2 ushr 16), 2_246_822_507L.toInt()) xor
        mistralImul(h1 xor (h1 ushr 13), 3_266_489_909L.toInt())
    return Integer.toUnsignedString(h2, 36) + Integer.toUnsignedString(h1, 36)
}

private fun mistralImul(
    left: Int,
    right: Int,
): Int = (left.toLong() * right.toLong()).toInt()

private const val MISTRAL_TOOL_CALL_ID_LENGTH = 9
private const val NON_VISION_USER_IMAGE_PLACEHOLDER = "(image omitted: model does not support images)"
private const val NON_VISION_TOOL_IMAGE_PLACEHOLDER = "(tool image omitted: model does not support images)"
