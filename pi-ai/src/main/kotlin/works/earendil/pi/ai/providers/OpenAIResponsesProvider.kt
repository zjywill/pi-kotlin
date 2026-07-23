package works.earendil.pi.ai.providers

import java.net.http.HttpClient
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
import works.earendil.pi.ai.contentText
import works.earendil.pi.ai.createAssistantMessageEventStream
import works.earendil.pi.ai.http.postSse

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
        stream: AssistantMessageEventStream,
    ) {
        val apiKey = resolveApiKey(id, options.apiKey, options.env, apiKeyEnvNames)
        val body = requestBody(model, context, options)

        val blocks = mutableListOf<works.earendil.pi.ai.ContentBlock>()
        val slots = mutableMapOf<Int, Slot>()
        var responseId: String? = null
        var usage = Usage()
        var stopReason = StopReason.STOP
        var sawTerminal = false

        fun snapshot(): AssistantMessage =
            AssistantMessage(
                content = copyBlocks(blocks),
                api = model.api,
                provider = model.provider,
                model = model.id,
                responseId = responseId,
                usage = usage,
                stopReason = stopReason,
            )

        fun createSlot(
            outputIndex: Int,
            item: JsonObject,
        ): Slot? {
            slots[outputIndex]?.let { return it }
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
                        blocks += ToolCall(tool.id, tool.name, parseJsonObjectOrEmpty(tool.arguments))
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

        stream.push(AssistantStart(snapshot()))
        postSse(
            client,
            "${model.baseUrl.trimEnd('/')}/responses",
            providerJson.encodeToString(JsonObject.serializer(), body),
            mergedHeaders(
                mapOf("authorization" to "Bearer $apiKey"),
                model.headers,
                options.headers,
            ),
            options.timeoutMs,
        ) { sse ->
            if (sse.data.isBlank() || sse.data == "[DONE]") {
                return@postSse
            }
            val event = providerJson.parseToJsonElement(sse.data).jsonObject
            val type = event.string("type") ?: sse.event
            when (type) {
                "response.created" -> responseId = event.obj("response")?.string("id")
                "response.output_item.added" -> {
                    val outputIndex = event.int("output_index") ?: return@postSse
                    val item = event.obj("item") ?: return@postSse
                    createSlot(outputIndex, item)
                }

                "response.reasoning_summary_text.delta",
                "response.reasoning_text.delta",
                -> {
                    val outputIndex = event.int("output_index") ?: return@postSse
                    val slot = slots[outputIndex] as? Slot.Thinking ?: return@postSse
                    val delta = event.string("delta").orEmpty()
                    val current = blocks[slot.contentIndex] as ThinkingContent
                    blocks[slot.contentIndex] = current.copy(thinking = current.thinking + delta)
                    stream.push(ThinkingDelta(slot.contentIndex, delta, snapshot()))
                }

                "response.reasoning_summary_part.done" -> {
                    val outputIndex = event.int("output_index") ?: return@postSse
                    val slot = slots[outputIndex] as? Slot.Thinking ?: return@postSse
                    val current = blocks[slot.contentIndex] as ThinkingContent
                    blocks[slot.contentIndex] = current.copy(thinking = current.thinking + "\n\n")
                    stream.push(ThinkingDelta(slot.contentIndex, "\n\n", snapshot()))
                }

                "response.output_text.delta",
                "response.refusal.delta",
                -> {
                    val outputIndex = event.int("output_index") ?: return@postSse
                    val slot = slots[outputIndex] as? Slot.Text ?: return@postSse
                    val delta = event.string("delta").orEmpty()
                    val current = blocks[slot.contentIndex] as TextContent
                    blocks[slot.contentIndex] = current.copy(text = current.text + delta)
                    stream.push(TextDelta(slot.contentIndex, delta, snapshot()))
                }

                "response.function_call_arguments.delta" -> {
                    val outputIndex = event.int("output_index") ?: return@postSse
                    val slot = slots[outputIndex] as? Slot.Tool ?: return@postSse
                    val delta = event.string("delta").orEmpty()
                    slot.arguments += delta
                    blocks[slot.contentIndex] =
                        ToolCall(slot.id, slot.name, parseJsonObjectOrEmpty(slot.arguments))
                    stream.push(ToolCallDelta(slot.contentIndex, delta, snapshot()))
                }

                "response.function_call_arguments.done" -> {
                    val outputIndex = event.int("output_index") ?: return@postSse
                    val slot = slots[outputIndex] as? Slot.Tool ?: return@postSse
                    slot.arguments = event.string("arguments") ?: slot.arguments
                    blocks[slot.contentIndex] =
                        ToolCall(slot.id, slot.name, parseJsonObjectOrEmpty(slot.arguments))
                }

                "response.output_item.done" -> {
                    val outputIndex = event.int("output_index") ?: return@postSse
                    val item = event.obj("item") ?: return@postSse
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
                            val thinking =
                                item.array("summary")
                                    ?.joinToString("\n\n") { it.jsonObject.string("text").orEmpty() }
                                    .orEmpty()
                            val current = blocks[slot.contentIndex] as ThinkingContent
                            blocks[slot.contentIndex] =
                                current.copy(
                                    thinking = thinking.ifEmpty { current.thinking },
                                    thinkingSignature =
                                        providerJson.encodeToString(JsonObject.serializer(), item),
                                )
                            stream.push(
                                ThinkingEnd(
                                    slot.contentIndex,
                                    (blocks[slot.contentIndex] as ThinkingContent).thinking,
                                    snapshot(),
                                ),
                            )
                        }

                        is Slot.Tool -> {
                            slot.arguments = item.string("arguments") ?: slot.arguments
                            val call =
                                ToolCall(
                                    slot.id,
                                    item.string("name") ?: slot.name,
                                    parseJsonObjectOrEmpty(slot.arguments),
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
                -> {
                    sawTerminal = true
                    val response = event.obj("response") ?: JsonObject(emptyMap())
                    responseId = response.string("id") ?: responseId
                    val rawUsage = response.obj("usage")
                    val details = rawUsage?.obj("input_tokens_details")
                    val cached = details?.int("cached_tokens") ?: 0
                    val cacheWrite = details?.int("cache_write_tokens") ?: 0
                    usage =
                        calculateUsageCost(
                            model,
                            ((rawUsage?.int("input_tokens") ?: 0) - cached - cacheWrite).coerceAtLeast(0),
                            rawUsage?.int("output_tokens") ?: 0,
                            cached,
                            cacheWrite,
                            rawUsage?.obj("output_tokens_details")?.int("reasoning_tokens"),
                        )
                    stopReason =
                        when (response.string("status")) {
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
                    val error = response?.obj("error")
                    error("${error?.string("code") ?: "unknown"}: ${error?.string("message") ?: "no message"}")
                }

                "error" -> error("${event.string("code") ?: "unknown"}: ${event.string("message") ?: "Unknown error"}")
            }
        }
        check(sawTerminal) { "OpenAI Responses stream ended before a terminal response event" }
        val final = snapshot()
        if (stopReason == StopReason.ERROR) {
            stream.push(AssistantError(StopReason.ERROR, final.copy(errorMessage = "OpenAI response failed")))
        } else {
            stream.push(AssistantDone(stopReason, final))
        }
    }

    internal fun requestBody(
        model: Model,
        context: Context,
        options: StreamOptions,
    ): JsonObject =
        buildOpenAIResponsesRequestBodyFromInput(
            model,
            context,
            options,
            responseInput(model, context),
        )

    private fun responseInput(
        model: Model,
        context: Context,
    ): JsonArray =
        buildJsonArray {
            context.systemPrompt?.let { system ->
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
                            add(
                                buildJsonObject {
                                    put("type", "function_call")
                                    put("call_id", parts[0])
                                    parts.getOrNull(1)?.let { put("id", it) }
                                    put("name", call.name)
                                    put(
                                        "arguments",
                                        providerJson.encodeToString(JsonObject.serializer(), call.arguments),
                                    )
                                },
                            )
                        }
                    }

                    is works.earendil.pi.ai.ToolResultMessage ->
                        add(
                            buildJsonObject {
                                put("type", "function_call_output")
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
            var arguments: String,
        ) : Slot
    }
}

internal fun buildOpenAIResponsesRequestBody(
    model: Model,
    context: Context,
    options: StreamOptions,
): JsonObject {
    val provider =
        OpenAIResponsesProvider(
            id = model.provider,
            name = model.provider,
            baseUrl = model.baseUrl,
            models = listOf(model),
            apiKeyEnvNames = emptyList(),
        )
    return provider.requestBody(model, context, options)
}

private fun buildOpenAIResponsesRequestBodyFromInput(
    model: Model,
    context: Context,
    options: StreamOptions,
    input: JsonArray,
): JsonObject =
    buildJsonObject {
        put("model", model.id)
        put("input", input)
        put("stream", true)
        options.sessionId
            ?.takeIf { options.cacheRetention != works.earendil.pi.ai.CacheRetention.NONE }
            ?.let { put("prompt_cache_key", it.take(64)) }
        put("store", false)
        options.maxTokens?.let { put("max_output_tokens", it.coerceAtLeast(16)) }
        options.temperature?.let { put("temperature", it) }
        if (context.tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    context.tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("type", "function")
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                                put("strict", false)
                            },
                        )
                    }
                },
            )
        }
        if (model.reasoning) {
            val effort = options.reasoning?.let(model::mappedThinkingLevel)
            if (effort != null && effort != "off") {
                put(
                    "reasoning",
                    buildJsonObject {
                        put("effort", effort)
                        put("summary", "auto")
                    },
                )
                put("include", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("reasoning.encrypted_content"))))
            } else {
                model.mappedThinkingOff("none")?.let { off ->
                    put("reasoning", buildJsonObject { put("effort", off) })
                }
                if (model.provider == "xai") {
                    put(
                        "include",
                        JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("reasoning.encrypted_content"))),
                    )
                }
            }
        }
    }
