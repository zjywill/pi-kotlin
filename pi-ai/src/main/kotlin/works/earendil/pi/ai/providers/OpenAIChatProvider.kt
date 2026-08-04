package works.earendil.pi.ai.providers

import java.net.http.HttpClient
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.AssistantError
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.AssistantStart
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.GrammarToolInputJsonBuffer
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
import works.earendil.pi.ai.createGrammarToolInputProperties
import works.earendil.pi.ai.createAssistantMessageEventStream
import works.earendil.pi.ai.http.postSse

class OpenAIChatProvider(
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
                val message =
                    AssistantMessage(
                        content = emptyList(),
                        api = model.api,
                        provider = model.provider,
                        model = model.id,
                        stopReason = StopReason.ERROR,
                        errorMessage = error.message ?: error::class.simpleName.orEmpty(),
                    )
                stream.push(AssistantError(StopReason.ERROR, message))
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
        val compat = openAIChatCompat(model)
        val grammarToolInputProperties =
            createGrammarToolInputProperties(context.tools, compat.supportsOpenAIGrammarTools)
        val body = buildOpenAIChatRequestBody(model, context, options)

        val blocks = mutableListOf<works.earendil.pi.ai.ContentBlock>()
        var usage = Usage()
        var stopReason = StopReason.PENDING
        var rawStopReason: String? = null
        var stopError: String? = null
        var hasFinishReason = false
        var textIndex: Int? = null
        var thinkingIndex: Int? = null
        val tools = linkedMapOf<Int, StreamingTool>()

        fun snapshot(): AssistantMessage =
            AssistantMessage(
                content = copyBlocks(blocks),
                api = model.api,
                provider = model.provider,
                model = model.id,
                usage = usage,
                stopReason = stopReason,
                errorMessage = stopError,
                rawStopReason = rawStopReason,
            )

        stream.push(AssistantStart(snapshot()))
        postSse(
            client = client,
            url = "${model.baseUrl.trimEnd('/')}/chat/completions",
            body = providerJson.encodeToString(JsonObject.serializer(), body),
            headers =
                mergedHeaders(
                    openAIChatBaseHeaders(model, options, apiKey) +
                        if (model.provider == "github-copilot") {
                            githubCopilotDynamicHeaders(context)
                        } else {
                            emptyMap()
                        },
                    model.headers,
                    options.headers,
                ),
            timeoutMs = options.timeoutMs,
            maxRetries = options.maxRetries,
            maxRetryDelayMs = options.maxRetryDelayMs,
            fetch = options.fetch,
        ) { event ->
            if (event.data == "[DONE]" || event.data.isBlank()) {
                return@postSse
            }
            val root = providerJson.parseToJsonElement(event.data).jsonObject
            root.obj("usage")?.let { rawUsage ->
                val promptTokens = rawUsage.int("prompt_tokens") ?: 0
                val promptDetails = rawUsage.obj("prompt_tokens_details")
                val cacheRead =
                    promptDetails?.int("cached_tokens")
                        ?: rawUsage.int("prompt_cache_hit_tokens")
                        ?: 0
                val cacheWrite = promptDetails?.int("cache_write_tokens") ?: 0
                usage =
                    calculateUsageCost(
                        model = model,
                        input = (promptTokens - cacheRead - cacheWrite).coerceAtLeast(0),
                        output = rawUsage.int("completion_tokens") ?: 0,
                        cacheRead = cacheRead,
                        cacheWrite = cacheWrite,
                        reasoning = rawUsage.obj("completion_tokens_details")?.int("reasoning_tokens") ?: 0,
                    )
            }
            val choice = root.array("choices")?.firstOrNull()?.jsonObject ?: return@postSse
            val delta = choice.obj("delta") ?: JsonObject(emptyMap())
            delta.string("content")?.let { text ->
                val index =
                    textIndex ?: blocks.size.also {
                        textIndex = it
                        blocks += TextContent("")
                        stream.push(TextStart(it, snapshot()))
                    }
                val current = blocks[index] as TextContent
                blocks[index] = current.copy(text = current.text + text)
                stream.push(TextDelta(index, text, snapshot()))
            }
            (delta.string("reasoning_content") ?: delta.string("reasoning"))?.let { thinking ->
                val index =
                    thinkingIndex ?: blocks.size.also {
                        thinkingIndex = it
                        blocks += ThinkingContent("")
                        stream.push(ThinkingStart(it, snapshot()))
                    }
                val current = blocks[index] as ThinkingContent
                blocks[index] = current.copy(thinking = current.thinking + thinking)
                stream.push(ThinkingDelta(index, thinking, snapshot()))
            }
            delta.array("tool_calls")?.forEach { raw ->
                val toolDelta = raw.jsonObject
                val streamIndex = toolDelta.int("index") ?: 0
                val function = toolDelta.obj("function")
                val custom = toolDelta.obj("custom")
                val name = function?.string("name") ?: custom?.string("name").orEmpty()
                val existing =
                    tools.getOrPut(streamIndex) {
                        val contentIndex = blocks.size
                        val customInputProperty =
                            custom
                                ?.takeIf { function == null }
                                ?.let { grammarToolInputProperties[name] ?: "input" }
                        val created =
                            StreamingTool(
                                contentIndex = contentIndex,
                                id = toolDelta.string("id").orEmpty(),
                                name = name,
                                customInputProperty = customInputProperty,
                                grammarBuffer =
                                    customInputProperty?.let {
                                        GrammarToolInputJsonBuffer()
                                    },
                            )
                        blocks += ToolCall(created.id, created.name, created.argumentsJson())
                        stream.push(ToolCallStart(contentIndex, snapshot()))
                        created
                }
                toolDelta.string("id")?.let { existing.id = it }
                name.takeIf { existing.name.isEmpty() }?.let { existing.name = it }
                if (custom != null && function == null && existing.customInputProperty == null) {
                    existing.customInputProperty = grammarToolInputProperties[existing.name] ?: "input"
                    existing.grammarBuffer = GrammarToolInputJsonBuffer()
                    existing.arguments = ""
                }
                function?.string("arguments")?.let { arguments ->
                    existing.arguments += arguments
                    blocks[existing.contentIndex] =
                        ToolCall(
                            existing.id,
                            existing.name,
                            existing.argumentsJson(),
                        )
                    stream.push(ToolCallDelta(existing.contentIndex, arguments, snapshot()))
                } ?: custom?.string("input")?.let { inputDelta ->
                    val nextInput = existing.customInput + inputDelta
                    val delta =
                        appendGrammarToolInputJsonDelta(
                            requireNotNull(existing.grammarBuffer),
                            requireNotNull(existing.customInputProperty),
                            nextInput,
                            close = false,
                        )
                    existing.customInput = nextInput
                    blocks[existing.contentIndex] =
                        ToolCall(existing.id, existing.name, existing.argumentsJson())
                    delta?.let {
                        stream.push(ToolCallDelta(existing.contentIndex, it, snapshot()))
                    }
                }
            }
            choice.string("finish_reason")?.let { reason ->
                hasFinishReason = true
                rawStopReason = reason
                val mapped = mapOpenAIChatStopReason(reason)
                stopReason = mapped.first
                stopError = mapped.second
            }
        }

        textIndex?.let { index ->
            stream.push(TextEnd(index, (blocks[index] as TextContent).text, snapshot()))
        }
        thinkingIndex?.let { index ->
            stream.push(ThinkingEnd(index, (blocks[index] as ThinkingContent).thinking, snapshot()))
        }
        tools.values.forEach { tool ->
            if (tool.customInputProperty != null) {
                appendGrammarToolInputJsonDelta(
                    requireNotNull(tool.grammarBuffer),
                    requireNotNull(tool.customInputProperty),
                    tool.customInput,
                    close = true,
                )?.let { delta ->
                    blocks[tool.contentIndex] =
                        ToolCall(tool.id, tool.name, tool.argumentsJson())
                    stream.push(ToolCallDelta(tool.contentIndex, delta, snapshot()))
                }
            }
            val call =
                ToolCall(
                    tool.id,
                    tool.name,
                    tool.argumentsJson(),
                )
            blocks[tool.contentIndex] = call
            stream.push(ToolCallEnd(tool.contentIndex, call, snapshot()))
        }
        if (!hasFinishReason && !compat.supportsFinishReason) {
            stopReason = if (blocks.any { it is ToolCall }) StopReason.TOOL_USE else StopReason.STOP
        }
        val final = snapshot()
        if (stopReason == StopReason.PENDING) {
            error("Stream ended without finish_reason")
        } else if (stopReason == StopReason.ERROR) {
            stream.push(
                AssistantError(
                    StopReason.ERROR,
                    final.copy(errorMessage = stopError ?: "Provider returned an error stop reason"),
                ),
            )
        } else {
            stream.push(AssistantDone(stopReason, final))
        }
    }

    private data class StreamingTool(
        val contentIndex: Int,
        var id: String,
        var name: String,
        var arguments: String = "",
        var customInputProperty: String? = null,
        var customInput: String = "",
        var grammarBuffer: GrammarToolInputJsonBuffer? = null,
    ) {
        fun argumentsJson(): JsonObject =
            customInputProperty?.let { property ->
                buildJsonObject { put(property, customInput) }
            } ?: parseJsonObjectOrEmpty(arguments)
    }
}

internal fun buildOpenAIChatRequestBody(
    model: Model,
    context: Context,
    options: StreamOptions,
): JsonObject {
    val compat = openAIChatCompat(model)
    val grammarToolInputProperties =
        createGrammarToolInputProperties(context.tools, compat.supportsOpenAIGrammarTools)
    return buildJsonObject {
        put("model", model.id)
        put(
            "messages",
            buildJsonArray {
                context.systemPrompt?.let { system ->
                    add(
                        buildJsonObject {
                            put(
                                "role",
                                if (model.reasoning && compat.supportsDeveloperRole) {
                                    "developer"
                                } else {
                                    "system"
                                },
                            )
                            put("content", system)
                        },
                    )
                }
                context.messages.forEach { add(openAIMessage(it, grammarToolInputProperties)) }
            },
        )
        put("stream", true)
        if (compat.supportsUsageInStreaming) {
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }
        if (compat.supportsStore) {
            put("store", false)
        }
        options.maxTokens?.let { maxTokens ->
            put(compat.maxTokensField, maxTokens)
        }
        options.temperature?.let { put("temperature", it) }
        if (context.tools.isNotEmpty()) {
            put(
                "tools",
                JsonArray(
                    context.tools.map {
                        openAITool(
                            it,
                            supportsStrictMode = compat.supportsStrictMode,
                            supportsOpenAIGrammarTools = compat.supportsOpenAIGrammarTools,
                        )
                    },
                ),
            )
        }
        if (model.reasoning) {
            val effort = options.reasoning?.let(model::mappedThinkingLevel)
            when (compat.thinkingFormat) {
                "zai" -> {
                    put(
                        "thinking",
                        buildJsonObject {
                            put("type", if (effort == null || effort == "off") "disabled" else "enabled")
                            if (effort != null && effort != "off") {
                                put("clear_thinking", false)
                            }
                        },
                    )
                    if (effort != null && effort != "off" && compat.supportsReasoningEffort) {
                        put("reasoning_effort", effort)
                    }
                }

                "qwen" -> {
                    put("enable_thinking", effort != null && effort != "off")
                    if (effort != null && effort != "off" && compat.supportsReasoningEffort) {
                        put("reasoning_effort", effort)
                    }
                }
                "qwen-chat-template" ->
                    put(
                        "chat_template_kwargs",
                        buildJsonObject {
                            put("enable_thinking", effort != null && effort != "off")
                            put("preserve_thinking", true)
                        },
                    )

                "deepseek" -> {
                    if (effort != null && effort != "off") {
                        put("thinking", buildJsonObject { put("type", "enabled") })
                        if (compat.supportsReasoningEffort) {
                            put("reasoning_effort", effort)
                        }
                    } else if (model.supportsThinkingOff()) {
                        put("thinking", buildJsonObject { put("type", "disabled") })
                    }
                }

                "openrouter" -> {
                    val value = effort?.takeUnless { it == "off" } ?: model.mappedThinkingOff("none")
                    value?.let {
                        put("reasoning", buildJsonObject { put("effort", it) })
                    }
                }

                "ant-ling" -> {
                    if (effort != null && effort != "off") {
                        put("reasoning", buildJsonObject { put("effort", effort) })
                    }
                }

                "together" -> {
                    put("reasoning", buildJsonObject { put("enabled", effort != null && effort != "off") })
                    if (effort != null && effort != "off" && compat.supportsReasoningEffort) {
                        put("reasoning_effort", effort)
                    }
                }

                "baseten" -> {
                    resolveChatTemplateValues(
                        values = compat.chatTemplateArgs,
                        enabled = effort != null && effort != "off",
                        effort = effort,
                    )?.let { put("chat_template_args", it) }
                    if (compat.supportsReasoningEffort) {
                        val resolvedEffort =
                            effort
                                ?: options.reasoningEffort
                                ?: model.mappedThinkingOff("")
                        resolvedEffort
                            ?.takeIf(String::isNotEmpty)
                            ?.let { put("reasoning_effort", it) }
                    }
                }

                "string-thinking" -> {
                    val value = effort?.takeUnless { it == "off" } ?: model.mappedThinkingOff("none")
                    value?.let { put("thinking", it) }
                }

                else -> {
                    if (effort != null && effort != "off" && compat.supportsReasoningEffort) {
                        put("reasoning_effort", effort)
                    } else if (effort == null && compat.supportsReasoningEffort) {
                        model.mappedThinkingOff("")?.takeIf(String::isNotEmpty)?.let {
                            put("reasoning_effort", it)
                        }
                    }
                }
            }
        }
        options.samplingParams?.forEach { (name, value) -> put(name, value) }
    }
}

internal fun mapOpenAIChatStopReason(reason: String): Pair<StopReason, String?> =
    when (reason) {
        "stop", "end" -> StopReason.STOP to null
        "length" -> StopReason.LENGTH to null
        "function_call", "tool_calls" -> StopReason.TOOL_USE to null
        else -> StopReason.ERROR to "Provider finish_reason: $reason"
    }

private data class OpenAIChatCompat(
    val supportsStore: Boolean,
    val supportsDeveloperRole: Boolean,
    val supportsUsageInStreaming: Boolean,
    val supportsFinishReason: Boolean,
    val maxTokensField: String,
    val supportsStrictMode: Boolean,
    val supportsOpenAIGrammarTools: Boolean,
    val supportsReasoningEffort: Boolean,
    val thinkingFormat: String,
    val chatTemplateArgs: JsonObject,
    val sendSessionAffinityHeaders: Boolean,
    val sessionAffinityFormat: String,
)

private fun openAIChatCompat(model: Model): OpenAIChatCompat {
    val provider = model.provider
    val baseUrl = model.baseUrl
    val isZai =
        provider == "zai" ||
            provider == "zai-coding-cn" ||
            "api.z.ai" in baseUrl ||
            "open.bigmodel.cn" in baseUrl
    val isTogether = provider == "together" || "api.together.ai" in baseUrl || "api.together.xyz" in baseUrl
    val isMoonshot = provider == "moonshotai" || provider == "moonshotai-cn" || "api.moonshot." in baseUrl
    val isOpenRouter = provider == "openrouter" || "openrouter.ai" in baseUrl
    val isCloudflareWorkers = provider == "cloudflare-workers-ai" || "api.cloudflare.com" in baseUrl
    val isCloudflareGateway = provider == "cloudflare-ai-gateway" || "gateway.ai.cloudflare.com" in baseUrl
    val isNvidia = provider == "nvidia" || "integrate.api.nvidia.com" in baseUrl
    val isAntLing = provider == "ant-ling" || "api.ant-ling.com" in baseUrl
    val isGrok = provider == "xai" || "api.x.ai" in baseUrl
    val isDeepSeek = provider == "deepseek" || "deepseek.com" in baseUrl
    val isNonStandard =
        isNvidia ||
            provider == "cerebras" ||
            "cerebras.ai" in baseUrl ||
            provider == "xai" ||
            "api.x.ai" in baseUrl ||
            isTogether ||
            "chutes.ai" in baseUrl ||
            "deepseek.com" in baseUrl ||
            isZai ||
            isMoonshot ||
            provider == "opencode" ||
            "opencode.ai" in baseUrl ||
            isCloudflareWorkers ||
            isCloudflareGateway ||
            isAntLing
    val useMaxTokens =
        "chutes.ai" in baseUrl ||
            isMoonshot ||
            isCloudflareGateway ||
            isTogether ||
            isNvidia ||
            isAntLing ||
            isZai
    val detected =
        OpenAIChatCompat(
            supportsStore = !isNonStandard,
            supportsDeveloperRole =
                (isOpenRouter && (model.id.startsWith("anthropic/") || model.id.startsWith("openai/"))) ||
                    (!isNonStandard && !isOpenRouter),
            supportsUsageInStreaming = true,
            supportsFinishReason = true,
            maxTokensField = if (useMaxTokens) "max_tokens" else "max_completion_tokens",
            supportsStrictMode = !isMoonshot && !isTogether && !isCloudflareGateway && !isNvidia,
            supportsOpenAIGrammarTools = false,
            supportsReasoningEffort =
                !isGrok &&
                    !isZai &&
                    !isMoonshot &&
                    !isTogether &&
                    !isCloudflareGateway &&
                    !isNvidia &&
                    !isAntLing,
            thinkingFormat =
                when {
                    isDeepSeek -> "deepseek"
                    isZai -> "zai"
                    isTogether -> "together"
                    isAntLing -> "ant-ling"
                    isOpenRouter -> "openrouter"
                    else -> "openai"
                },
            chatTemplateArgs = JsonObject(emptyMap()),
            sendSessionAffinityHeaders = false,
            sessionAffinityFormat = if (isOpenRouter) "openrouter" else "openai",
        )
    val raw = model.compat ?: return detected
    return detected.copy(
        supportsStore = raw.boolean("supportsStore") ?: detected.supportsStore,
        supportsDeveloperRole = raw.boolean("supportsDeveloperRole") ?: detected.supportsDeveloperRole,
        supportsUsageInStreaming =
            raw.boolean("supportsUsageInStreaming") ?: detected.supportsUsageInStreaming,
        supportsFinishReason =
            raw.boolean("supportsFinishReason") ?: detected.supportsFinishReason,
        maxTokensField = raw.string("maxTokensField") ?: detected.maxTokensField,
        supportsStrictMode = raw.boolean("supportsStrictMode") ?: detected.supportsStrictMode,
        supportsOpenAIGrammarTools =
            raw.boolean("supportsOpenAIGrammarTools") ?: detected.supportsOpenAIGrammarTools,
        supportsReasoningEffort =
            raw.boolean("supportsReasoningEffort") ?: detected.supportsReasoningEffort,
        thinkingFormat = raw.string("thinkingFormat") ?: detected.thinkingFormat,
        chatTemplateArgs = raw["chatTemplateArgs"] as? JsonObject ?: detected.chatTemplateArgs,
        sendSessionAffinityHeaders =
            raw.boolean("sendSessionAffinityHeaders") ?: detected.sendSessionAffinityHeaders,
        sessionAffinityFormat = raw.string("sessionAffinityFormat") ?: detected.sessionAffinityFormat,
    )
}

private fun resolveChatTemplateValues(
    values: JsonObject,
    enabled: Boolean,
    effort: String?,
): JsonObject? {
    if (values.isEmpty()) {
        return null
    }
    val resolved =
        buildMap<String, JsonElement> {
            values.forEach { (key, value) ->
                val variable = (value as? JsonObject)?.get("\$var")?.jsonPrimitive?.contentOrNull
                val resolvedValue =
                    when (variable) {
                        "thinking.enabled" -> JsonPrimitive(enabled)
                        "thinking.effort" -> effort?.let(::JsonPrimitive)
                        null -> value
                        else -> null
                    }
                if (resolvedValue != null) {
                    put(key, resolvedValue)
                }
            }
        }
    return resolved.takeIf(Map<String, JsonElement>::isNotEmpty)?.let(::JsonObject)
}

private fun openAIChatBaseHeaders(
    model: Model,
    options: StreamOptions,
    apiKey: String,
): Map<String, String> =
    buildMap {
        put("authorization", "Bearer $apiKey")
        val sessionId =
            options.sessionId
                ?.takeIf(String::isNotEmpty)
                ?.takeUnless { options.cacheRetention == CacheRetention.NONE }
        val compat = openAIChatCompat(model)
        if (sessionId != null && compat.sendSessionAffinityHeaders) {
            if (compat.sessionAffinityFormat == "openrouter") {
                put("x-session-id", sessionId)
            } else {
                if (compat.sessionAffinityFormat == "openai") {
                    put("session_id", sessionId)
                }
                put("x-client-request-id", sessionId)
                put("x-session-affinity", sessionId)
            }
        }
    }

private fun JsonObject.boolean(name: String): Boolean? =
    this[name]?.let { element ->
        (element as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
    }
