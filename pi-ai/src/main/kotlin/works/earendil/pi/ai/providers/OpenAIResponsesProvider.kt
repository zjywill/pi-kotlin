package works.earendil.pi.ai.providers

import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.AssistantError
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.AssistantStart
import works.earendil.pi.ai.BashExecutionMessage
import works.earendil.pi.ai.BranchSummaryMessage
import works.earendil.pi.ai.CompactionSummaryMessage
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.CustomMessage
import works.earendil.pi.ai.GrammarToolInputJsonBuffer
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.Provider
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
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.appendGrammarToolInputJsonDelta
import works.earendil.pi.ai.contentText
import works.earendil.pi.ai.createGrammarToolInputProperties
import works.earendil.pi.ai.createAssistantMessageEventStream
import works.earendil.pi.ai.getGrammarToolInput
import works.earendil.pi.ai.http.postSse
import works.earendil.pi.ai.resolveGrammarConstrainedSampling
import works.earendil.pi.ai.resolveJsonSchemaStrictSampling

class OpenAIResponsesProvider(
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
    ): AssistantMessageEventStream =
        streamWithRequest(model, context, options) {
            val apiKey = resolveApiKey(id, options.apiKey, options.env, apiKeyEnvNames)
            OpenAIResponsesHttpRequest(
                url = "${model.baseUrl.trimEnd('/')}/responses",
                modelId = model.id,
                headers =
                    mapOf("authorization" to "Bearer $apiKey") +
                        if (model.provider == "github-copilot") {
                            githubCopilotDynamicHeaders(context)
                        } else {
                            emptyMap()
                        },
            )
        }

    internal fun streamWithRequest(
        model: Model,
        context: Context,
        options: StreamOptions,
        request: () -> OpenAIResponsesHttpRequest,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        providerScope.launch {
            runCatching {
                execute(model, context, options, request(), stream)
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
                            errorMessage = error.message ?: error::class.simpleName.orEmpty(),
                        ),
                    ),
                )
            }
        }
        return stream
    }

    private suspend fun execute(
        model: Model,
        context: Context,
        options: StreamOptions,
        request: OpenAIResponsesHttpRequest,
        stream: AssistantMessageEventStream,
    ) {
        val supportsOpenAIGrammarTools =
            model.compat?.booleanValue("supportsOpenAIGrammarTools") ?: false
        val grammarToolInputProperties =
            createGrammarToolInputProperties(context.tools, supportsOpenAIGrammarTools)
        val body =
            request.body
                ?: requestBody(
                    model,
                    context,
                    options,
                    request.modelId,
                    request.promptCacheWhenDisabled,
                )
        val state =
            OpenAIResponsesEventState(
                model = model,
                stream = stream,
                grammarToolInputProperties = grammarToolInputProperties,
                usageCostMultiplier = request.usageCostMultiplier,
                pendingStopReasonMessage = request.pendingStopReasonMessage,
            )
        val bodyJson = providerJson.encodeToString(JsonObject.serializer(), body)
        if (request.eventStream != null) {
            request.eventStream.invoke(body, state::handle)
        } else {
            state.start()
            postSse(
                client,
                request.url,
                request.encodeBody?.invoke(bodyJson)
                    ?: bodyJson.toByteArray(StandardCharsets.UTF_8),
                if (request.headersAreFinal) {
                    request.headers
                } else {
                    mergedHeaders(
                        request.headers,
                        model.headers,
                        options.headers,
                    )
                },
                options.timeoutMs,
                options.maxRetries,
                options.maxRetryDelayMs,
                fetch = options.fetch,
                shouldStop = { request.stopAfterTerminal && state.sawTerminal },
            ) { sse ->
                if (sse.data.isBlank() || sse.data == "[DONE]") {
                    return@postSse
                }
                state.handle(
                    providerJson.parseToJsonElement(sse.data).jsonObject,
                    sse.event,
                )
            }
        }
        state.finish()
    }

    internal fun requestBody(
        model: Model,
        context: Context,
        options: StreamOptions,
        requestModelId: String = model.id,
        promptCacheWhenDisabled: Boolean = false,
    ): JsonObject =
        buildOpenAIResponsesRequestBodyFromInput(
            model,
            context,
            options,
            responseInput(
                model,
                context,
                createGrammarToolInputProperties(
                    context.tools,
                    model.compat?.booleanValue("supportsOpenAIGrammarTools") ?: false,
                ),
            ),
            requestModelId,
            promptCacheWhenDisabled,
        )

    internal fun responseInput(
        model: Model,
        context: Context,
        grammarToolInputProperties: Map<String, String>,
        includeSystemPrompt: Boolean = true,
    ): JsonArray =
        buildJsonArray {
            context.systemPrompt?.takeIf { includeSystemPrompt }?.let { system ->
                add(
                    buildJsonObject {
                        val supportsDeveloperRole =
                            model.compat?.get("supportsDeveloperRole")
                                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                                ?.booleanOrNull
                                ?: true
                        put("role", if (model.reasoning && supportsDeveloperRole) "developer" else "system")
                        put("content", system)
                    },
                )
            }
            context.messages.forEach { message ->
                when (message) {
                    is works.earendil.pi.ai.UserMessage ->
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", responseUserContent(message.content))
                            },
                        )

                    is AssistantMessage -> {
                        val text = contentText(message.content, "")
                        if (text.isNotEmpty()) {
                            add(
                                buildJsonObject {
                                    put("role", "assistant")
                                    put(
                                        "content",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("type", "output_text")
                                                    put("text", text)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                        message.content.filterIsInstance<ToolCall>().forEach { call ->
                            val parts = call.id.split('|', limit = 2)
                            val customInputProperty = grammarToolInputProperties[call.name]
                            add(
                                if (customInputProperty != null) {
                                    buildJsonObject {
                                        put("type", "custom_tool_call")
                                        put("call_id", parts[0])
                                        parts.getOrNull(1)?.let { put("id", it) }
                                        put("name", call.name)
                                        put(
                                            "input",
                                            getGrammarToolInput(
                                                call.name,
                                                call.arguments,
                                                customInputProperty,
                                            ),
                                        )
                                    }
                                } else {
                                    buildJsonObject {
                                        put("type", "function_call")
                                        put("call_id", parts[0])
                                        parts
                                            .getOrNull(1)
                                            ?.takeIf { it.startsWith("fc_") }
                                            ?.let { put("id", it) }
                                        put("name", call.name)
                                        put(
                                            "arguments",
                                            providerJson.encodeToString(
                                                JsonObject.serializer(),
                                                call.arguments,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }

                    is works.earendil.pi.ai.ToolResultMessage ->
                        add(
                            buildJsonObject {
                                put(
                                    "type",
                                    if (grammarToolInputProperties.containsKey(message.toolName)) {
                                        "custom_tool_call_output"
                                    } else {
                                        "function_call_output"
                                    },
                                )
                                put("call_id", message.toolCallId.substringBefore('|'))
                                put("output", contentText(message.content))
                            },
                        )

                    else ->
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "input_text")
                                                put(
                                                    "text",
                                                    when (message) {
                                                        is CustomMessage -> contentText(message.content)
                                                        is CompactionSummaryMessage -> message.summary
                                                        is BranchSummaryMessage -> message.summary
                                                        is BashExecutionMessage ->
                                                            "${message.command}\n${message.output}"
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                }
            }
        }

    private fun responseUserContent(content: MessageContent): JsonArray =
        buildJsonArray {
            when (content) {
                is MessageContent.Text ->
                    add(
                        buildJsonObject {
                            put("type", "input_text")
                            put("text", content.text)
                        },
                    )

                is MessageContent.Blocks ->
                    content.blocks.forEach { block ->
                        when (block) {
                            is TextContent ->
                                add(
                                    buildJsonObject {
                                        put("type", "input_text")
                                        put("text", block.text)
                                    },
                                )

                            is ImageContent ->
                                add(
                                    buildJsonObject {
                                        put("type", "input_image")
                                        put("image_url", "data:${block.mimeType};base64,${block.data}")
                                    },
                                )

                            else -> Unit
                        }
                    }
            }
        }

}

internal class OpenAIResponsesEventState(
    private val model: Model,
    private val stream: AssistantMessageEventStream,
    private val grammarToolInputProperties: Map<String, String>,
    private val usageCostMultiplier: (JsonObject) -> Double = { 1.0 },
    private val pendingStopReasonMessage: String = "OpenAI Responses stream ended without a stop reason",
) {
    private val blocks = mutableListOf<works.earendil.pi.ai.ContentBlock>()
    private val slots = mutableMapOf<Int, Slot>()
    private val reasoningBlocksById = mutableMapOf<String, Int>()
    private var responseId: String? = null
    private var usage = Usage()
    private var stopReason = StopReason.PENDING
    private var rawStopReason: String? = null
    private var stopError: String? = null
    private var started = false

    var sawTerminal: Boolean = false
        private set

    private fun snapshot(): AssistantMessage =
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

    private fun createSlot(
        outputIndex: Int,
        item: JsonObject,
    ): Slot? {
        slots[outputIndex]?.let { return it }
        applyMessagePhaseStopReason(item)
        val contentIndex = blocks.size
        val slot =
            when (item.string("type")) {
                "reasoning" -> {
                    blocks += ThinkingContent("")
                    stream.push(ThinkingStart(contentIndex, snapshot()))
                    Slot.Thinking(contentIndex)
                }

                "message" -> {
                    blocks += TextContent("")
                    stream.push(TextStart(contentIndex, snapshot()))
                    Slot.Text(contentIndex)
                }

                "function_call" -> {
                    val callId = item.string("call_id").orEmpty()
                    val itemId = item.string("id").orEmpty()
                    val tool =
                        Slot.Tool(
                            contentIndex,
                            id = listOf(callId, itemId).filter(String::isNotEmpty).joinToString("|"),
                            name = item.string("name").orEmpty(),
                            arguments = item.string("arguments").orEmpty(),
                        )
                    blocks += ToolCall(tool.id, tool.name, tool.argumentsJson())
                    stream.push(ToolCallStart(contentIndex, snapshot()))
                    tool
                }

                "custom_tool_call" -> {
                    val callId = item.string("call_id").orEmpty()
                    val itemId = item.string("id").orEmpty()
                    val name = item.string("name").orEmpty()
                    val inputProperty = grammarToolInputProperties[name] ?: "input"
                    val input = item.string("input").orEmpty()
                    val tool =
                        Slot.Tool(
                            contentIndex,
                            id = listOf(callId, itemId).filter(String::isNotEmpty).joinToString("|"),
                            name = name,
                            customInputProperty = inputProperty,
                            customInput = input,
                            grammarBuffer = GrammarToolInputJsonBuffer(),
                        )
                    blocks += ToolCall(tool.id, tool.name, tool.argumentsJson())
                    stream.push(ToolCallStart(contentIndex, snapshot()))
                    tool
                }

                else -> null
            }
        if (slot != null) {
            slots[outputIndex] = slot
        }
        return slot
    }

    fun start() {
        if (!started) {
            started = true
            stream.push(AssistantStart(snapshot()))
        }
    }

    fun handle(
        event: JsonObject,
        fallbackType: String? = null,
    ) {
        start()
        when (event.string("type") ?: fallbackType) {
            "response.created" -> responseId = event.obj("response")?.string("id")
            "response.output_item.added" -> {
                val outputIndex = event.int("output_index") ?: return
                val item = event.obj("item") ?: return
                createSlot(outputIndex, item)
            }

            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta",
            -> {
                val outputIndex = event.int("output_index") ?: return
                val slot = slots[outputIndex] as? Slot.Thinking ?: return
                val delta = event.string("delta").orEmpty()
                val current = blocks[slot.contentIndex] as ThinkingContent
                blocks[slot.contentIndex] = current.copy(thinking = current.thinking + delta)
                stream.push(ThinkingDelta(slot.contentIndex, delta, snapshot()))
            }

            "response.reasoning_summary_part.done" -> {
                val outputIndex = event.int("output_index") ?: return
                val slot = slots[outputIndex] as? Slot.Thinking ?: return
                val current = blocks[slot.contentIndex] as ThinkingContent
                blocks[slot.contentIndex] = current.copy(thinking = current.thinking + "\n\n")
                stream.push(ThinkingDelta(slot.contentIndex, "\n\n", snapshot()))
            }

            "response.output_text.delta",
            "response.refusal.delta",
            -> {
                val outputIndex = event.int("output_index") ?: return
                val slot = slots[outputIndex] as? Slot.Text ?: return
                val delta = event.string("delta").orEmpty()
                val current = blocks[slot.contentIndex] as TextContent
                blocks[slot.contentIndex] = current.copy(text = current.text + delta)
                stream.push(TextDelta(slot.contentIndex, delta, snapshot()))
            }

            "response.function_call_arguments.delta" -> {
                val outputIndex = event.int("output_index") ?: return
                val slot = slots[outputIndex] as? Slot.Tool ?: return
                if (slot.customInputProperty != null) {
                    return
                }
                val delta = event.string("delta").orEmpty()
                slot.arguments += delta
                blocks[slot.contentIndex] = ToolCall(slot.id, slot.name, slot.argumentsJson())
                stream.push(ToolCallDelta(slot.contentIndex, delta, snapshot()))
            }

            "response.function_call_arguments.done" -> {
                val outputIndex = event.int("output_index") ?: return
                val slot = slots[outputIndex] as? Slot.Tool ?: return
                if (slot.customInputProperty != null) {
                    return
                }
                val previous = slot.arguments
                slot.arguments = event.string("arguments") ?: previous
                blocks[slot.contentIndex] = ToolCall(slot.id, slot.name, slot.argumentsJson())
                if (slot.arguments.startsWith(previous)) {
                    val delta = slot.arguments.removePrefix(previous)
                    if (delta.isNotEmpty()) {
                        stream.push(ToolCallDelta(slot.contentIndex, delta, snapshot()))
                    }
                }
            }

            "response.custom_tool_call_input.delta" -> {
                val outputIndex = event.int("output_index") ?: return
                val slot = slots[outputIndex] as? Slot.Tool ?: return
                val inputProperty = slot.customInputProperty ?: return
                val nextInput = slot.customInput + event.string("delta").orEmpty()
                val delta =
                    appendGrammarToolInputJsonDelta(
                        requireNotNull(slot.grammarBuffer),
                        inputProperty,
                        nextInput,
                        close = false,
                    )
                slot.customInput = nextInput
                blocks[slot.contentIndex] = ToolCall(slot.id, slot.name, slot.argumentsJson())
                delta?.let { stream.push(ToolCallDelta(slot.contentIndex, it, snapshot())) }
            }

            "response.custom_tool_call_input.done" -> {
                val outputIndex = event.int("output_index") ?: return
                val slot = slots[outputIndex] as? Slot.Tool ?: return
                val inputProperty = slot.customInputProperty ?: return
                val nextInput = event.string("input") ?: slot.customInput
                val delta =
                    appendGrammarToolInputJsonDelta(
                        requireNotNull(slot.grammarBuffer),
                        inputProperty,
                        nextInput,
                        close = true,
                    )
                slot.customInput = nextInput
                blocks[slot.contentIndex] = ToolCall(slot.id, slot.name, slot.argumentsJson())
                delta?.let { stream.push(ToolCallDelta(slot.contentIndex, it, snapshot())) }
            }

            "response.output_item.done" -> {
                val outputIndex = event.int("output_index") ?: return
                val item = event.obj("item") ?: return
                applyMessagePhaseStopReason(item)
                when (val slot = slots[outputIndex] ?: createSlot(outputIndex, item)) {
                    is Slot.Text -> {
                        val text =
                            item.array("content")
                                ?.joinToString("") { content ->
                                    content.jsonObject.string("text")
                                        ?: content.jsonObject.string("refusal").orEmpty()
                                }.orEmpty()
                        if (text.isNotEmpty()) {
                            blocks[slot.contentIndex] =
                                TextContent(
                                    text = text,
                                    textSignature =
                                        buildJsonObject {
                                            put("v", 1)
                                            put("id", item.string("id").orEmpty())
                                            item.string("phase")?.let { put("phase", it) }
                                        }.let {
                                            providerJson.encodeToString(JsonObject.serializer(), it)
                                        },
                                )
                        }
                        stream.push(
                            TextEnd(
                                slot.contentIndex,
                                (blocks[slot.contentIndex] as TextContent).text,
                                snapshot(),
                            ),
                        )
                    }

                    is Slot.Thinking -> {
                        val summary =
                            item.array("summary")
                                ?.joinToString("\n\n") { it.jsonObject.string("text").orEmpty() }
                                .orEmpty()
                        val content =
                            item.array("content")
                                ?.joinToString("\n\n") { it.jsonObject.string("text").orEmpty() }
                                .orEmpty()
                        val current = blocks[slot.contentIndex] as ThinkingContent
                        blocks[slot.contentIndex] =
                            current.copy(
                                thinking = summary.ifEmpty { content }.ifEmpty { current.thinking },
                                thinkingSignature =
                                    providerJson.encodeToString(JsonObject.serializer(), item),
                            )
                        item.string("id")?.let { reasoningBlocksById[it] = slot.contentIndex }
                        stream.push(
                            ThinkingEnd(
                                slot.contentIndex,
                                (blocks[slot.contentIndex] as ThinkingContent).thinking,
                                snapshot(),
                            ),
                        )
                    }

                    is Slot.Tool -> {
                        if (slot.customInputProperty != null) {
                            val nextInput = item.string("input") ?: slot.customInput
                            appendGrammarToolInputJsonDelta(
                                requireNotNull(slot.grammarBuffer),
                                requireNotNull(slot.customInputProperty),
                                nextInput,
                                close = true,
                            )?.let { delta ->
                                slot.customInput = nextInput
                                blocks[slot.contentIndex] =
                                    ToolCall(slot.id, slot.name, slot.argumentsJson())
                                stream.push(ToolCallDelta(slot.contentIndex, delta, snapshot()))
                            }
                        } else {
                            slot.arguments = item.string("arguments") ?: slot.arguments
                        }
                        val call =
                            ToolCall(
                                slot.id,
                                item.string("name") ?: slot.name,
                                slot.argumentsJson(),
                            )
                        blocks[slot.contentIndex] = call
                        stream.push(ToolCallEnd(slot.contentIndex, call, snapshot()))
                    }

                    null -> Unit
                }
                slots.remove(outputIndex)
            }

            "response.completed",
            "response.incomplete",
            "response.done",
            -> {
                sawTerminal = true
                val response = event.obj("response") ?: JsonObject(emptyMap())
                responseId = response.string("id") ?: responseId
                response.array("output")
                    ?.mapNotNull { it as? JsonObject }
                    ?.filter { it.string("type") == "reasoning" }
                    ?.forEach { item ->
                        val encryptedContent = item.string("encrypted_content") ?: return@forEach
                        val contentIndex = reasoningBlocksById[item.string("id")] ?: return@forEach
                        val current = blocks[contentIndex] as? ThinkingContent ?: return@forEach
                        val signature =
                            current.thinkingSignature
                                ?.let {
                                    runCatching {
                                        providerJson.parseToJsonElement(it).jsonObject
                                    }.getOrNull()
                                } ?: return@forEach
                        if (signature.string("encrypted_content") == null) {
                            blocks[contentIndex] =
                                current.copy(
                                    thinkingSignature =
                                        providerJson.encodeToString(
                                            JsonObject.serializer(),
                                            JsonObject(
                                                signature +
                                                    (
                                                        "encrypted_content" to
                                                            kotlinx.serialization.json.JsonPrimitive(
                                                                encryptedContent,
                                                            )
                                                    ),
                                            ),
                                        ),
                                )
                        }
                    }
                val rawUsage = response.obj("usage")
                val details = rawUsage?.obj("input_tokens_details")
                val cached = details?.int("cached_tokens") ?: 0
                val cacheWrite = details?.int("cache_write_tokens") ?: 0
                val calculatedUsage =
                    calculateUsageCost(
                        model,
                        ((rawUsage?.int("input_tokens") ?: 0) - cached - cacheWrite)
                            .coerceAtLeast(0),
                        rawUsage?.int("output_tokens") ?: 0,
                        cached,
                        cacheWrite,
                        rawUsage?.obj("output_tokens_details")?.int("reasoning_tokens"),
                    )
                usage =
                    calculatedUsage.copy(
                        totalTokens = rawUsage?.int("total_tokens") ?: calculatedUsage.totalTokens,
                    )
                usageCostMultiplier(response)
                    .takeIf { it != 1.0 }
                    ?.let { multiplier ->
                        val cost = usage.cost
                        usage =
                            usage.copy(
                                cost =
                                    cost.copy(
                                        input = cost.input * multiplier,
                                        output = cost.output * multiplier,
                                        cacheRead = cost.cacheRead * multiplier,
                                        cacheWrite = cost.cacheWrite * multiplier,
                                        total = cost.total * multiplier,
                                    ),
                            )
                    }
                val status = response.string("status")
                rawStopReason = status
                stopReason =
                    when (status) {
                        "incomplete" -> StopReason.LENGTH
                        "failed", "cancelled" -> StopReason.ERROR
                        else -> StopReason.STOP
                    }
                if (blocks.any { it is ToolCall } && stopReason == StopReason.STOP) {
                    stopReason = StopReason.TOOL_USE
                }
            }

            "response.failed" -> {
                sawTerminal = true
                val response = event.obj("response")
                rawStopReason = response?.string("status")
                val error = response?.obj("error")
                val details = response?.obj("incomplete_details")
                stopReason = StopReason.ERROR
                stopError =
                    if (error != null) {
                        "${error.string("code") ?: "unknown"}: ${error.string("message") ?: "no message"}"
                    } else {
                        details?.string("reason")
                            ?.let { "incomplete: $it" }
                            ?: "Unknown error (no error details in response)"
                    }
            }

            "error" ->
                error(
                    "${event.string("code") ?: "unknown"}: " +
                        (event.string("message") ?: "Unknown error"),
                )
        }
    }

    fun finish() {
        check(sawTerminal) { "OpenAI Responses stream ended before a terminal response event" }
        check(stopReason != StopReason.PENDING) { pendingStopReasonMessage }
        val final = snapshot()
        if (stopReason == StopReason.ERROR) {
            stream.push(
                AssistantError(
                    StopReason.ERROR,
                    final.copy(errorMessage = stopError ?: "OpenAI response failed"),
                ),
            )
        } else {
            stream.push(AssistantDone(stopReason, final))
        }
    }

    private fun applyMessagePhaseStopReason(item: JsonObject) {
        if (item.string("type") == "message" && item.string("phase") == "final_answer") {
            stopReason = StopReason.STOP
        }
    }

    private sealed interface Slot {
        val contentIndex: Int

        data class Text(
            override val contentIndex: Int,
        ) : Slot

        data class Thinking(
            override val contentIndex: Int,
        ) : Slot

        data class Tool(
            override val contentIndex: Int,
            val id: String,
            val name: String,
            var arguments: String = "",
            var customInputProperty: String? = null,
            var customInput: String = "",
            var grammarBuffer: GrammarToolInputJsonBuffer? = null,
        ) : Slot {
            fun argumentsJson(): JsonObject =
                customInputProperty?.let { property ->
                    buildJsonObject { put(property, customInput) }
                } ?: parseJsonObjectOrEmpty(arguments)
        }
    }
}

internal data class OpenAIResponsesHttpRequest(
    val url: String,
    val modelId: String,
    val headers: Map<String, String>,
    val promptCacheWhenDisabled: Boolean = false,
    val body: JsonObject? = null,
    val headersAreFinal: Boolean = false,
    val stopAfterTerminal: Boolean = false,
    val encodeBody: ((String) -> ByteArray)? = null,
    val usageCostMultiplier: (JsonObject) -> Double = { 1.0 },
    val eventStream: (suspend (JsonObject, (JsonObject) -> Unit) -> Unit)? = null,
    val pendingStopReasonMessage: String = "OpenAI Responses stream ended without a stop reason",
)

internal fun buildOpenAIResponsesRequestBody(
    model: Model,
    context: Context,
    options: StreamOptions,
    requestModelId: String = model.id,
    promptCacheWhenDisabled: Boolean = false,
): JsonObject {
    val provider =
        OpenAIResponsesProvider(
            id = model.provider,
            name = model.provider,
            baseUrl = model.baseUrl,
            models = listOf(model),
            apiKeyEnvNames = emptyList(),
        )
    return provider.requestBody(model, context, options, requestModelId, promptCacheWhenDisabled)
}

private fun buildOpenAIResponsesRequestBodyFromInput(
    model: Model,
    context: Context,
    options: StreamOptions,
    input: JsonArray,
    requestModelId: String,
    promptCacheWhenDisabled: Boolean,
): JsonObject =
    run {
        val supportsStrictMode =
            model.compat?.booleanValue("supportsStrictMode")
                ?: (model.api == "azure-openai-responses")
        val supportsOpenAIGrammarTools =
            model.compat?.booleanValue("supportsOpenAIGrammarTools") ?: false
        buildJsonObject {
            put("model", requestModelId)
            put("input", input)
            put("stream", true)
            options.sessionId
                ?.takeIf {
                    promptCacheWhenDisabled ||
                        options.cacheRetention != works.earendil.pi.ai.CacheRetention.NONE
                }
                ?.let { put("prompt_cache_key", it.take(64)) }
            if (
                options.cacheRetention == works.earendil.pi.ai.CacheRetention.NONE &&
                model.compat?.booleanValue("supportsExplicitPromptCacheMode") == true
            ) {
                put(
                    "prompt_cache_options",
                    buildJsonObject { put("mode", "explicit") },
                )
            }
            put("store", false)
            options.maxTokens?.let { put("max_output_tokens", it.coerceAtLeast(16)) }
            options.temperature?.let { put("temperature", it) }
            if (context.tools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        context.tools.forEach { tool ->
                            val grammar =
                                resolveGrammarConstrainedSampling(tool, supportsOpenAIGrammarTools)
                            add(
                                if (grammar != null) {
                                    buildJsonObject {
                                        put("type", "custom")
                                        put("name", tool.name)
                                        put("description", tool.description)
                                        put(
                                            "format",
                                            buildJsonObject {
                                                put("type", "grammar")
                                                put("syntax", grammar.format)
                                                put("definition", grammar.definition)
                                            },
                                        )
                                    }
                                } else {
                                    buildJsonObject {
                                        val strict =
                                            resolveJsonSchemaStrictSampling(tool, supportsStrictMode)
                                        put("type", "function")
                                        put("name", tool.name)
                                        put("description", tool.description)
                                        put("parameters", tool.parameters)
                                        if (supportsStrictMode) {
                                            put("strict", strict ?: false)
                                        }
                                    }
                                },
                            )
                        }
                    },
                )
            }
            if (model.reasoning) {
                val effort =
                    options.reasoningEffort
                        ?.let { requested ->
                            works.earendil.pi.ai.ModelThinkingLevel.entries
                                .firstOrNull { it.name.equals(requested, ignoreCase = true) }
                                ?.let { model.thinkingLevelMap[it] ?: requested }
                                ?: requested
                        }
                        ?: options.reasoning?.let(model::mappedThinkingLevel)
                if (effort != null && effort != "off") {
                    put(
                        "reasoning",
                        buildJsonObject {
                            put("effort", effort)
                            put("summary", options.reasoningSummary ?: "auto")
                        },
                    )
                    put(
                        "include",
                        JsonArray(
                            listOf(
                                kotlinx.serialization.json.JsonPrimitive(
                                    "reasoning.encrypted_content",
                                ),
                            ),
                        ),
                    )
                } else {
                    model.mappedThinkingOff("none")?.let { off ->
                        put("reasoning", buildJsonObject { put("effort", off) })
                    }
                    if (model.provider == "xai") {
                        put(
                            "include",
                            JsonArray(
                                listOf(
                                    kotlinx.serialization.json.JsonPrimitive(
                                        "reasoning.encrypted_content",
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

private fun JsonObject.booleanValue(name: String): Boolean? =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
