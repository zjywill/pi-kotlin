package works.earendil.pi.ai.providers

import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

class GoogleProvider(
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
        require(options.fetch == null) {
            "Custom fetch is not supported by the Google Generative AI adapter"
        }
        val apiKey = resolveApiKey(id, options.apiKey, options.env, apiKeyEnvNames)
        val body = requestBody(model, context, options)
        val blocks = mutableListOf<works.earendil.pi.ai.ContentBlock>()
        var currentIndex: Int? = null
        var currentThinking = false
        var responseId: String? = null
        var stopReason = StopReason.PENDING
        var rawStopReason: String? = null
        var usage = Usage()

        fun snapshot(): AssistantMessage =
            AssistantMessage(
                content = copyBlocks(blocks),
                api = model.api,
                provider = model.provider,
                model = model.id,
                responseId = responseId,
                usage = usage,
                stopReason = stopReason,
                rawStopReason = rawStopReason,
            )

        fun finishCurrent() {
            val index = currentIndex ?: return
            when (val block = blocks[index]) {
                is TextContent -> stream.push(TextEnd(index, block.text, snapshot()))
                is ThinkingContent -> stream.push(ThinkingEnd(index, block.thinking, snapshot()))
                else -> Unit
            }
            currentIndex = null
        }

        stream.push(AssistantStart(snapshot()))
        val encodedModel = URLEncoder.encode(model.id, StandardCharsets.UTF_8).replace("+", "%20")
        val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8).replace("+", "%20")
        postSse(
            client,
            "${model.baseUrl.trimEnd('/')}/models/$encodedModel:streamGenerateContent?alt=sse&key=$encodedKey",
            providerJson.encodeToString(JsonObject.serializer(), body),
            mergedHeaders(emptyMap(), model.headers, options.headers),
            options.timeoutMs,
            options.maxRetries,
            options.maxRetryDelayMs,
        ) { sse ->
            if (sse.data.isBlank()) {
                return@postSse
            }
            val chunk = providerJson.parseToJsonElement(sse.data).jsonObject
            responseId = chunk.string("responseId") ?: responseId
            val candidate = chunk.array("candidates")?.firstOrNull()?.jsonObject
            candidate?.obj("content")?.array("parts")?.forEach { rawPart ->
                val part = rawPart.jsonObject
                part.string("text")?.let { text ->
                    val thinking = part["thought"]?.toString() == "true"
                    if (currentIndex == null || thinking != currentThinking) {
                        finishCurrent()
                        currentThinking = thinking
                        currentIndex = blocks.size
                        if (thinking) {
                            blocks += ThinkingContent("", part.string("thoughtSignature"))
                            stream.push(ThinkingStart(requireNotNull(currentIndex), snapshot()))
                        } else {
                            blocks += TextContent("", part.string("thoughtSignature"))
                            stream.push(TextStart(requireNotNull(currentIndex), snapshot()))
                        }
                    }
                    val index = requireNotNull(currentIndex)
                    if (thinking) {
                        val current = blocks[index] as ThinkingContent
                        blocks[index] =
                            current.copy(
                                thinking = current.thinking + text,
                                thinkingSignature = part.string("thoughtSignature") ?: current.thinkingSignature,
                            )
                        stream.push(ThinkingDelta(index, text, snapshot()))
                    } else {
                        val current = blocks[index] as TextContent
                        blocks[index] =
                            current.copy(
                                text = current.text + text,
                                textSignature = part.string("thoughtSignature") ?: current.textSignature,
                            )
                        stream.push(TextDelta(index, text, snapshot()))
                    }
                }
                part.obj("functionCall")?.let { function ->
                    finishCurrent()
                    val call =
                        ToolCall(
                            id =
                                function.string("id")
                                    ?: "${function.string("name").orEmpty()}_${System.currentTimeMillis()}_${toolCounter.incrementAndGet()}",
                            name = function.string("name").orEmpty(),
                            arguments = function.obj("args") ?: JsonObject(emptyMap()),
                            thoughtSignature = part.string("thoughtSignature"),
                        )
                    val index = blocks.size
                    blocks += call
                    stream.push(ToolCallStart(index, snapshot()))
                    stream.push(
                        ToolCallDelta(
                            index,
                            providerJson.encodeToString(JsonObject.serializer(), call.arguments),
                            snapshot(),
                        ),
                    )
                    stream.push(ToolCallEnd(index, call, snapshot()))
                }
            }
            candidate?.string("finishReason")?.let { reason ->
                rawStopReason = reason
                stopReason = mapGoogleStopReason(reason)
                if (blocks.any { it is ToolCall }) {
                    stopReason = StopReason.TOOL_USE
                }
            }
            chunk.obj("usageMetadata")?.let { rawUsage ->
                val cached = rawUsage.int("cachedContentTokenCount") ?: 0
                val thoughts = rawUsage.int("thoughtsTokenCount") ?: 0
                usage =
                    calculateUsageCost(
                        model,
                        ((rawUsage.int("promptTokenCount") ?: 0) - cached).coerceAtLeast(0),
                        (rawUsage.int("candidatesTokenCount") ?: 0) + thoughts,
                        cached,
                        reasoning = thoughts,
                    )
            }
        }
        finishCurrent()
        val final = snapshot()
        if (stopReason == StopReason.PENDING) {
            error("Google stream ended without a finish reason")
        } else if (stopReason == StopReason.ERROR) {
            stream.push(
                AssistantError(
                    StopReason.ERROR,
                    final.copy(
                        errorMessage =
                            rawStopReason?.let { "Provider stopped with: $it" }
                                ?: "An unknown error occurred",
                    ),
                ),
            )
        } else {
            stream.push(AssistantDone(stopReason, final))
        }
    }

    internal fun requestBody(
        model: Model,
        context: Context,
        options: StreamOptions,
    ): JsonObject =
        buildGoogleRequestBodyFromContents(
            model,
            context,
            options,
            googleContents(model, context),
        )

    private fun googleContents(
        model: Model,
        context: Context,
    ): JsonArray =
        buildJsonArray {
            context.messages.forEach { message ->
                when (message) {
                    is works.earendil.pi.ai.UserMessage ->
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("parts", googleUserParts(message.content))
                            },
                        )

                    is AssistantMessage -> googleAssistantContent(model, message)?.let(::add)

                    is works.earendil.pi.ai.ToolResultMessage ->
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put(
                                                    "functionResponse",
                                                    buildJsonObject {
                                                        if (requiresGoogleToolCallId(model.id)) {
                                                            put("id", normalizeGoogleToolCallId(message.toolCallId))
                                                        }
                                                        put("name", message.toolName)
                                                        put(
                                                            "response",
                                                            buildJsonObject {
                                                                put("output", contentText(message.content))
                                                                put("isError", message.isError)
                                                            },
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )

                    else ->
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
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

    private fun googleAssistantContent(
        model: Model,
        message: AssistantMessage,
    ): JsonObject? {
        val sameProviderAndModel = message.provider == model.provider && message.model == model.id
        val parts =
            buildJsonArray {
                message.content.forEach { block ->
                    when (block) {
                        is TextContent -> {
                            val signature =
                                validGoogleHistorySignature(
                                    sameProviderAndModel,
                                    block.textSignature,
                                )
                            if (block.text.isBlank() && signature == null) {
                                return@forEach
                            }
                            add(
                                buildJsonObject {
                                    put("text", sanitizeGoogleSurrogates(block.text))
                                    signature?.let { put("thoughtSignature", it) }
                                },
                            )
                        }

                        is ThinkingContent -> {
                            if (sameProviderAndModel) {
                                val signature =
                                    validGoogleHistorySignature(
                                        true,
                                        block.thinkingSignature,
                                    )
                                if (block.thinking.isBlank() && signature == null) {
                                    return@forEach
                                }
                                add(
                                    buildJsonObject {
                                        put("text", sanitizeGoogleSurrogates(block.thinking))
                                        put("thought", true)
                                        signature?.let { put("thoughtSignature", it) }
                                    },
                                )
                            } else if (block.thinking.isNotBlank()) {
                                add(buildJsonObject { put("text", sanitizeGoogleSurrogates(block.thinking)) })
                            }
                        }

                        is ToolCall ->
                            add(
                                buildJsonObject {
                                    put(
                                        "functionCall",
                                        buildJsonObject {
                                            if (requiresGoogleToolCallId(model.id)) {
                                                put("id", normalizeGoogleToolCallId(block.id))
                                            }
                                            put("name", block.name)
                                            put("args", block.arguments)
                                        },
                                    )
                                    validGoogleHistorySignature(
                                        sameProviderAndModel,
                                        block.thoughtSignature,
                                    )?.let { put("thoughtSignature", it) }
                                },
                            )

                        else -> Unit
                    }
                }
            }
        if (parts.isEmpty()) {
            return null
        }
        return buildJsonObject {
            put("role", "model")
            put("parts", parts)
        }
    }

    private fun googleUserParts(content: MessageContent): JsonArray =
        buildJsonArray {
            when (content) {
                is MessageContent.Text -> add(buildJsonObject { put("text", content.text) })
                is MessageContent.Blocks ->
                    content.blocks.forEach { block ->
                        when (block) {
                            is TextContent -> add(buildJsonObject { put("text", block.text) })
                            is ImageContent ->
                                add(
                                    buildJsonObject {
                                        put(
                                            "inlineData",
                                            buildJsonObject {
                                                put("mimeType", block.mimeType)
                                                put("data", block.data)
                                            },
                                        )
                                    },
                                )

                            else -> Unit
                        }
                    }
            }
        }
}

private fun validGoogleHistorySignature(
    sameProviderAndModel: Boolean,
    signature: String?,
): String? {
    if (!sameProviderAndModel || signature.isNullOrEmpty() || signature.length % 4 != 0) {
        return null
    }
    return runCatching {
        Base64.getDecoder().decode(signature)
        signature
    }.getOrNull()
}

private fun sanitizeGoogleSurrogates(value: String): String {
    val output = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val current = value[index]
        when {
            Character.isHighSurrogate(current) -> {
                val next = value.getOrNull(index + 1)
                if (next != null && Character.isLowSurrogate(next)) {
                    output.append(current).append(next)
                    index += 2
                } else {
                    index++
                }
            }

            Character.isLowSurrogate(current) -> index++
            else -> {
                output.append(current)
                index++
            }
        }
    }
    return output.toString()
}

internal fun requiresGoogleToolCallId(modelId: String): Boolean {
    val geminiMajorVersion =
        Regex("^gemini(?:-live)?-(\\d+)")
            .find(modelId.lowercase())
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    return modelId.startsWith("claude-") ||
        modelId.startsWith("gpt-oss-") ||
        geminiMajorVersion?.let { it >= 3 } == true
}

internal fun normalizeGoogleToolCallId(id: String): String =
    id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

internal fun buildGoogleRequestBody(
    model: Model,
    context: Context,
    options: StreamOptions,
): JsonObject {
    val provider =
        GoogleProvider(
            id = model.provider,
            name = model.provider,
            baseUrl = model.baseUrl,
            models = listOf(model),
            apiKeyEnvNames = emptyList(),
        )
    return provider.requestBody(model, context, options)
}

private fun buildGoogleRequestBodyFromContents(
    model: Model,
    context: Context,
    options: StreamOptions,
    contents: JsonArray,
): JsonObject {
    val thinkingConfig =
        if (!model.reasoning) {
            null
        } else if (options.reasoning == null) {
            disabledGoogleThinkingConfig(model)
        } else {
            val clamped = model.clampThinkingLevel(options.reasoning)
            if (usesGoogleThinkingLevels(model)) {
                buildJsonObject {
                    put("includeThoughts", true)
                    put("thinkingLevel", googleThinkingLevel(model, clamped))
                }
            } else {
                buildJsonObject {
                    put("includeThoughts", true)
                    put("thinkingBudget", googleThinkingBudget(model, clamped, options.thinkingBudgets))
                }
            }
        }
    return buildJsonObject {
        put("contents", contents)
        put(
            "generationConfig",
            buildJsonObject {
                options.temperature?.let { put("temperature", it) }
                options.maxTokens?.let { put("maxOutputTokens", it) }
                thinkingConfig?.let { put("thinkingConfig", it) }
            },
        )
        context.systemPrompt?.let { system ->
            put(
                "systemInstruction",
                buildJsonObject {
                    put(
                        "parts",
                        buildJsonArray {
                            add(buildJsonObject { put("text", system) })
                        },
                    )
                },
            )
        }
        if (context.tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "functionDeclarations",
                                buildJsonArray {
                                    context.tools.forEach { tool ->
                                        add(
                                            buildJsonObject {
                                                put("name", tool.name)
                                                put("description", tool.description)
                                                put("parametersJsonSchema", tool.parameters)
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                },
            )
            resolveGoogleFunctionCallingMode(model.id, context.tools, options.toolChoice)?.let { mode ->
                put(
                    "toolConfig",
                    buildJsonObject {
                        put(
                            "functionCallingConfig",
                            buildJsonObject { put("mode", mode) },
                        )
                    },
                )
            }
        }
    }
}

private fun usesGoogleThinkingLevels(model: Model): Boolean =
    isGemini3Pro(model) || isGemini3Flash(model) || isGemma4(model)

private fun disabledGoogleThinkingConfig(model: Model): JsonObject =
    when {
        isGemini3Pro(model) ->
            buildJsonObject { put("thinkingLevel", "LOW") }

        isGemini3Flash(model) || isGemma4(model) ->
            buildJsonObject { put("thinkingLevel", "MINIMAL") }

        else ->
            buildJsonObject { put("thinkingBudget", 0) }
    }

private fun googleThinkingLevel(
    model: Model,
    level: works.earendil.pi.ai.ModelThinkingLevel,
): String =
    when {
        isGemini3Pro(model) ->
            when (level) {
                works.earendil.pi.ai.ModelThinkingLevel.MINIMAL,
                works.earendil.pi.ai.ModelThinkingLevel.LOW,
                -> "LOW"

                else -> "HIGH"
            }

        isGemma4(model) ->
            when (level) {
                works.earendil.pi.ai.ModelThinkingLevel.MINIMAL,
                works.earendil.pi.ai.ModelThinkingLevel.LOW,
                -> "MINIMAL"

                else -> "HIGH"
            }

        else -> level.name
    }

private fun googleThinkingBudget(
    model: Model,
    level: works.earendil.pi.ai.ModelThinkingLevel,
    custom: works.earendil.pi.ai.ThinkingBudgets?,
): Int {
    val customBudget =
        when (level) {
            works.earendil.pi.ai.ModelThinkingLevel.MINIMAL -> custom?.minimal
            works.earendil.pi.ai.ModelThinkingLevel.LOW -> custom?.low
            works.earendil.pi.ai.ModelThinkingLevel.MEDIUM -> custom?.medium
            else -> custom?.high
        }
    if (customBudget != null) {
        return customBudget
    }
    return when {
        "2.5-pro" in model.id ->
            when (level) {
                works.earendil.pi.ai.ModelThinkingLevel.MINIMAL -> 128
                works.earendil.pi.ai.ModelThinkingLevel.LOW -> 2_048
                works.earendil.pi.ai.ModelThinkingLevel.MEDIUM -> 8_192
                else -> 32_768
            }

        "2.5-flash-lite" in model.id ->
            when (level) {
                works.earendil.pi.ai.ModelThinkingLevel.MINIMAL -> 512
                works.earendil.pi.ai.ModelThinkingLevel.LOW -> 2_048
                works.earendil.pi.ai.ModelThinkingLevel.MEDIUM -> 8_192
                else -> 24_576
            }

        "2.5-flash" in model.id ->
            when (level) {
                works.earendil.pi.ai.ModelThinkingLevel.MINIMAL -> 128
                works.earendil.pi.ai.ModelThinkingLevel.LOW -> 2_048
                works.earendil.pi.ai.ModelThinkingLevel.MEDIUM -> 8_192
                else -> 24_576
            }

        else -> -1
    }
}

private fun isGemini3Pro(model: Model): Boolean =
    Regex("gemini-3(?:\\.\\d+)?-pro").containsMatchIn(model.id.lowercase())

private fun isGemini3Flash(model: Model): Boolean {
    val id = model.id.lowercase()
    return Regex("gemini-3(?:\\.\\d+)?-flash").containsMatchIn(id) ||
        id == "gemini-flash-latest" ||
        id == "gemini-flash-lite-latest"
}

private fun isGemma4(model: Model): Boolean =
    Regex("gemma-?4").containsMatchIn(model.id.lowercase())

internal fun mapGoogleStopReason(reason: String): StopReason =
    when (reason) {
        "STOP" -> StopReason.STOP
        "MAX_TOKENS" -> StopReason.LENGTH
        else -> StopReason.ERROR
    }

private val toolCounter = AtomicLong()
