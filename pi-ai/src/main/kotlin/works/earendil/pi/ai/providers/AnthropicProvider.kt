package works.earendil.pi.ai.providers

import java.net.http.HttpClient
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
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
import works.earendil.pi.ai.AuthResult
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.OAuthAuth
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
import works.earendil.pi.ai.resolveJsonSchemaStrictSampling

class AnthropicProvider(
    override val id: String,
    override val name: String,
    override val baseUrl: String,
    private val models: List<Model>,
    private val apiKeyEnvNames: List<String>,
    private val client: HttpClient = HttpClient.newHttpClient(),
    override val oauth: OAuthAuth? = null,
) : Provider {
    override fun getModels(): List<Model> = models

    override fun resolveAmbientAuth(environment: (String) -> String?): AuthResult? {
        environment(ANTHROPIC_AUTH_TOKEN_ENV)
            ?.takeIf(String::isNotBlank)
            ?.let { token ->
                return AuthResult(
                    auth =
                        ModelAuth(
                            headers = mapOf("Authorization" to "Bearer $token"),
                        ),
                    source = ANTHROPIC_AUTH_TOKEN_ENV,
                )
            }
        apiKeyEnvNames.forEach { name ->
            environment(name)
                ?.takeIf(String::isNotBlank)
                ?.let { apiKey ->
                    return AuthResult(
                        auth = ModelAuth(apiKey = apiKey),
                        source = name,
                    )
                }
        }
        return null
    }

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
        val bearerToken =
            if (options.apiKey.isNullOrBlank()) {
                resolveApiKeyOrNull(
                    explicit = null,
                    env = options.env,
                    names = listOf(ANTHROPIC_AUTH_TOKEN_ENV),
                )
            } else {
                null
            }
        val apiKey =
            if (bearerToken == null) {
                resolveApiKeyOrNull(options.apiKey, options.env, apiKeyEnvNames)
            } else {
                null
            }
        val bearerHeaders =
            bearerToken
                ?.let { mapOf("Authorization" to "Bearer $it") }
                .orEmpty()
        val requestHeaders =
            mergedHeaders(
                bearerHeaders,
                model.headers,
                options.headers,
            )
        requireAnthropicRequestAuth(id, apiKey, requestHeaders)
        val isOAuthToken =
            model.provider != "github-copilot" &&
                apiKey?.let(::isAnthropicOAuthToken) == true
        val body = requestBody(model, context, options, isOAuthToken)
        val blocks = mutableListOf<works.earendil.pi.ai.ContentBlock>()
        val providerIndexes = mutableMapOf<Int, Int>()
        val toolArguments = mutableMapOf<Int, String>()
        var responseId: String? = null
        var stopReason = StopReason.PENDING
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
            )

        stream.push(AssistantStart(snapshot()))
        postSse(
            client = client,
            url = "${model.baseUrl.trimEnd('/')}/v1/messages",
            body = providerJson.encodeToString(JsonObject.serializer(), body),
            headers =
                mergedHeaders(
                    anthropicRequestHeaders(model, context, options, apiKey, isOAuthToken) +
                        bearerHeaders,
                    model.headers,
                    options.headers,
                ),
            timeoutMs = options.timeoutMs,
            maxRetries = options.maxRetries,
            maxRetryDelayMs = options.maxRetryDelayMs,
            fetch = options.fetch,
        ) { sse ->
            if (sse.data.isBlank()) {
                return@postSse
            }
            val event = providerJson.parseToJsonElement(sse.data).jsonObject
            when (event.string("type")) {
                "message_start" -> {
                    val message = event.obj("message")
                    responseId = message?.string("id")
                    val rawUsage = message?.obj("usage")
                    usage =
                        calculateUsageCost(
                            model,
                            rawUsage?.int("input_tokens") ?: 0,
                            rawUsage?.int("output_tokens") ?: 0,
                            rawUsage?.int("cache_read_input_tokens") ?: 0,
                            rawUsage?.int("cache_creation_input_tokens") ?: 0,
                        ).copy(
                            cacheWrite1h =
                                rawUsage
                                    ?.obj("cache_creation")
                                    ?.int("ephemeral_1h_input_tokens")
                                    ?: 0,
                        )
                }

                "content_block_start" -> {
                    val providerIndex = event.int("index") ?: return@postSse
                    val block = event.obj("content_block") ?: return@postSse
                    val contentIndex = blocks.size
                    providerIndexes[providerIndex] = contentIndex
                    when (block.string("type")) {
                        "text" -> {
                            blocks += TextContent(block.string("text").orEmpty())
                            stream.push(TextStart(contentIndex, snapshot()))
                        }

                        "thinking" -> {
                            blocks +=
                                ThinkingContent(
                                    thinking = block.string("thinking").orEmpty(),
                                    thinkingSignature = block.string("signature"),
                                )
                            stream.push(ThinkingStart(contentIndex, snapshot()))
                        }

                        "redacted_thinking" -> {
                            blocks +=
                                ThinkingContent(
                                    thinking = "[Reasoning redacted]",
                                    thinkingSignature = block.string("data"),
                                    redacted = true,
                                )
                            stream.push(ThinkingStart(contentIndex, snapshot()))
                        }

                        "tool_use" -> {
                            toolArguments[providerIndex] = ""
                            blocks +=
                                ToolCall(
                                    id = block.string("id").orEmpty(),
                                    name =
                                        if (isOAuthToken) {
                                            fromClaudeCodeToolName(
                                                block.string("name").orEmpty(),
                                                context,
                                            )
                                        } else {
                                            block.string("name").orEmpty()
                                        },
                                    arguments = block.obj("input") ?: JsonObject(emptyMap()),
                                )
                            stream.push(ToolCallStart(contentIndex, snapshot()))
                        }
                    }
                }

                "content_block_delta" -> {
                    val providerIndex = event.int("index") ?: return@postSse
                    val contentIndex = providerIndexes[providerIndex] ?: return@postSse
                    val delta = event.obj("delta") ?: return@postSse
                    when (delta.string("type")) {
                        "text_delta" -> {
                            val text = delta.string("text").orEmpty()
                            val current = blocks[contentIndex] as TextContent
                            blocks[contentIndex] = current.copy(text = current.text + text)
                            stream.push(TextDelta(contentIndex, text, snapshot()))
                        }

                        "thinking_delta" -> {
                            val thinking = delta.string("thinking").orEmpty()
                            val current = blocks[contentIndex] as ThinkingContent
                            blocks[contentIndex] = current.copy(thinking = current.thinking + thinking)
                            stream.push(ThinkingDelta(contentIndex, thinking, snapshot()))
                        }

                        "signature_delta" -> {
                            val signature = delta.string("signature").orEmpty()
                            val current = blocks[contentIndex] as ThinkingContent
                            blocks[contentIndex] =
                                current.copy(
                                    thinkingSignature = current.thinkingSignature.orEmpty() + signature,
                                )
                        }

                        "input_json_delta" -> {
                            val partial = delta.string("partial_json").orEmpty()
                            val combined = toolArguments.getValue(providerIndex) + partial
                            toolArguments[providerIndex] = combined
                            val current = blocks[contentIndex] as ToolCall
                            blocks[contentIndex] =
                                current.copy(arguments = parseJsonObjectOrEmpty(combined))
                            stream.push(ToolCallDelta(contentIndex, partial, snapshot()))
                        }
                    }
                }

                "content_block_stop" -> {
                    val providerIndex = event.int("index") ?: return@postSse
                    val contentIndex = providerIndexes[providerIndex] ?: return@postSse
                    when (val block = blocks[contentIndex]) {
                        is TextContent -> stream.push(TextEnd(contentIndex, block.text, snapshot()))
                        is ThinkingContent -> stream.push(ThinkingEnd(contentIndex, block.thinking, snapshot()))
                        is ToolCall -> {
                            val final =
                                block.copy(
                                    arguments = parseJsonObjectOrEmpty(toolArguments[providerIndex].orEmpty()),
                                )
                            blocks[contentIndex] = final
                            stream.push(ToolCallEnd(contentIndex, final, snapshot()))
                        }

                        else -> Unit
                    }
                }

                "message_delta" -> {
                    val delta = event.obj("delta")
                    stopReason =
                        when (delta?.string("stop_reason")) {
                            "max_tokens" -> StopReason.LENGTH
                            "tool_use" -> StopReason.TOOL_USE
                            "refusal" -> StopReason.ERROR
                            else -> StopReason.STOP
                        }
                    event.obj("usage")?.let { rawUsage ->
                        usage =
                            calculateUsageCost(
                                model,
                                rawUsage.int("input_tokens") ?: usage.input,
                                rawUsage.int("output_tokens") ?: usage.output,
                                rawUsage.int("cache_read_input_tokens") ?: usage.cacheRead,
                                rawUsage.int("cache_creation_input_tokens") ?: usage.cacheWrite,
                                rawUsage.obj("output_tokens_details")?.int("thinking_tokens"),
                            ).copy(cacheWrite1h = usage.cacheWrite1h)
                    }
                }
            }
        }
        val final = snapshot()
        if (stopReason == StopReason.PENDING) {
            error("Anthropic stream ended without a stop reason")
        } else if (stopReason == StopReason.ERROR) {
            stream.push(AssistantError(StopReason.ERROR, final.copy(errorMessage = "Anthropic refused the request")))
        } else {
            stream.push(AssistantDone(stopReason, final))
        }
    }

    internal fun requestBody(
        model: Model,
        context: Context,
        options: StreamOptions,
        isOAuthToken: Boolean = false,
    ): JsonObject =
        buildAnthropicRequestBodyFromMessages(
            model,
            context,
            options,
            anthropicMessages(context, isOAuthToken),
            isOAuthToken,
        )

    private fun anthropicMessages(
        context: Context,
        isOAuthToken: Boolean,
    ): JsonArray =
        buildJsonArray {
            context.messages.forEach { message ->
                when (message) {
                    is works.earendil.pi.ai.UserMessage ->
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", anthropicContent(message.content))
                            },
                        )

                    is AssistantMessage ->
                        add(
                            buildJsonObject {
                                put("role", "assistant")
                                put(
                                    "content",
                                    buildJsonArray {
                                        message.content.forEach { block ->
                                            when (block) {
                                                is TextContent ->
                                                    add(
                                                        buildJsonObject {
                                                            put("type", "text")
                                                            put("text", block.text)
                                                        },
                                                    )

                                                is ThinkingContent ->
                                                    add(
                                                        buildJsonObject {
                                                            put("type", "thinking")
                                                            put("thinking", block.thinking)
                                                            block.thinkingSignature?.let { put("signature", it) }
                                                        },
                                                    )

                                                is ToolCall ->
                                                    add(
                                                        buildJsonObject {
                                                            put("type", "tool_use")
                                                            put("id", block.id)
                                                            put(
                                                                "name",
                                                                anthropicToolName(block.name, isOAuthToken),
                                                            )
                                                            put("input", block.arguments)
                                                        },
                                                    )

                                                else -> Unit
                                            }
                                        }
                                    },
                                )
                            },
                        )

                    is works.earendil.pi.ai.ToolResultMessage ->
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", "tool_result")
                                                put("tool_use_id", message.toolCallId)
                                                put("content", contentText(message.content))
                                                put("is_error", message.isError)
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
                                    "content",
                                    when (message) {
                                        is works.earendil.pi.ai.CustomMessage -> contentText(message.content)
                                        is works.earendil.pi.ai.CompactionSummaryMessage -> message.summary
                                        is works.earendil.pi.ai.BranchSummaryMessage -> message.summary
                                        is works.earendil.pi.ai.BashExecutionMessage ->
                                            "${message.command}\n${message.output}"
                                    },
                                )
                            },
                        )
                }
            }
        }

    private fun anthropicContent(content: MessageContent): kotlinx.serialization.json.JsonElement =
        when (content) {
            is MessageContent.Text -> kotlinx.serialization.json.JsonPrimitive(content.text)
            is MessageContent.Blocks ->
                buildJsonArray {
                    content.blocks.forEach { block ->
                        when (block) {
                            is TextContent ->
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", block.text)
                                    },
                                )

                            is ImageContent ->
                                add(
                                    buildJsonObject {
                                        put("type", "image")
                                        put(
                                            "source",
                                            buildJsonObject {
                                                put("type", "base64")
                                                put("media_type", block.mimeType)
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

private fun anthropicRequestHeaders(
    model: Model,
    context: Context,
    options: StreamOptions,
    apiKey: String?,
    isOAuthToken: Boolean,
): Map<String, String> {
    val betaFeatures = anthropicBetaFeatures(model, context, options)
    if (model.provider == "github-copilot") {
        return buildMap {
            apiKey?.let { put("authorization", "Bearer $it") }
            put("anthropic-version", "2023-06-01")
            put("accept", "application/json")
            put("anthropic-dangerous-direct-browser-access", "true")
            if (betaFeatures.isNotEmpty()) {
                put("anthropic-beta", betaFeatures.joinToString(","))
            }
            putAll(githubCopilotDynamicHeaders(context))
        }
    }
    if (isOAuthToken) {
        return buildMap {
            put("authorization", "Bearer ${requireNotNull(apiKey)}")
            put("anthropic-version", "2023-06-01")
            put("accept", "application/json")
            put("anthropic-dangerous-direct-browser-access", "true")
            put(
                "anthropic-beta",
                (
                    listOf("claude-code-20250219", "oauth-2025-04-20") +
                        betaFeatures
                ).joinToString(","),
            )
            put("user-agent", "claude-cli/$ANTHROPIC_CLAUDE_CODE_VERSION")
            put("x-app", "cli")
        }
    }
    return buildMap {
        apiKey?.let { put("x-api-key", it) }
        put("anthropic-version", "2023-06-01")
        put("accept", "application/json")
        put("anthropic-dangerous-direct-browser-access", "true")
        if (betaFeatures.isNotEmpty()) {
            put("anthropic-beta", betaFeatures.joinToString(","))
        }
    }
}

private fun requireAnthropicRequestAuth(
    provider: String,
    apiKey: String?,
    headers: Map<String, String?>,
) {
    if (!apiKey.isNullOrBlank()) {
        return
    }
    if (
        listOf("authorization", "x-api-key", "cf-aig-authorization")
            .any { expected ->
                headers.entries.any { (name, value) ->
                    name.equals(expected, ignoreCase = true) && !value.isNullOrBlank()
                }
            }
    ) {
        return
    }
    error("No API key for provider: $provider")
}

private fun anthropicBetaFeatures(
    model: Model,
    context: Context,
    options: StreamOptions,
): List<String> =
    buildList {
        val supportsEagerToolInputStreaming =
            model.compat?.get("supportsEagerToolInputStreaming")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.booleanOrNull
                ?: true
        if (context.tools.isNotEmpty() && !supportsEagerToolInputStreaming) {
            add("fine-grained-tool-streaming-2025-05-14")
        }
        val forceAdaptiveThinking =
            model.compat?.get("forceAdaptiveThinking")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.booleanOrNull == true
        if (options.interleavedThinking != false && !forceAdaptiveThinking) {
            add("interleaved-thinking-2025-05-14")
        }
    }

private fun isAnthropicOAuthToken(apiKey: String): Boolean = "sk-ant-oat" in apiKey

private const val ANTHROPIC_AUTH_TOKEN_ENV = "ANTHROPIC_AUTH_TOKEN"

private fun anthropicToolName(
    name: String,
    isOAuthToken: Boolean,
): String =
    if (isOAuthToken) {
        CLAUDE_CODE_TOOL_NAMES[name.lowercase()] ?: name
    } else {
        name
    }

private fun fromClaudeCodeToolName(
    name: String,
    context: Context,
): String =
    context.tools
        .firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?.name
        ?: name

private val CLAUDE_CODE_TOOL_NAMES =
    listOf(
        "Read",
        "Write",
        "Edit",
        "Bash",
        "Grep",
        "Glob",
        "AskUserQuestion",
        "EnterPlanMode",
        "ExitPlanMode",
        "KillShell",
        "NotebookEdit",
        "Skill",
        "Task",
        "TaskOutput",
        "TodoWrite",
        "WebFetch",
        "WebSearch",
    ).associateBy(String::lowercase)

private const val ANTHROPIC_CLAUDE_CODE_VERSION = "2.1.75"

internal fun buildAnthropicRequestBody(
    model: Model,
    context: Context,
    options: StreamOptions,
): JsonObject {
    val provider =
        AnthropicProvider(
            id = model.provider,
            name = model.provider,
            baseUrl = model.baseUrl,
            models = listOf(model),
            apiKeyEnvNames = emptyList(),
        )
    return provider.requestBody(model, context, options)
}

private fun buildAnthropicRequestBodyFromMessages(
    model: Model,
    context: Context,
    options: StreamOptions,
    messages: JsonArray,
    isOAuthToken: Boolean,
): JsonObject {
    val forceAdaptiveThinking =
        model.compat?.get("forceAdaptiveThinking")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.booleanOrNull == true
    val requestedThinking = options.reasoning?.let(model::clampThinkingLevel)
    val rawThinkingBudget =
        requestedThinking
            ?.takeUnless { forceAdaptiveThinking }
            ?.let { thinkingBudget(it, options.thinkingBudgets) }
    val maxTokens =
        if (rawThinkingBudget != null) {
            if (options.maxTokens == null) {
                model.maxTokens
            } else {
                (options.maxTokens + rawThinkingBudget).coerceAtMost(model.maxTokens)
            }
        } else {
            options.maxTokens ?: model.maxTokens
        }
    val adjustedThinkingBudget =
        rawThinkingBudget?.coerceAtMost((maxTokens - 1_024).coerceAtLeast(0))
    val cacheControl =
        if (options.cacheRetention == CacheRetention.NONE) {
            null
        } else {
            buildJsonObject {
                put("type", "ephemeral")
                if (
                    options.cacheRetention == CacheRetention.LONG &&
                    model.compat?.get("supportsLongCacheRetention")
                        ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                        ?.booleanOrNull != false
                ) {
                    put("ttl", "1h")
                }
            }
        }
    return buildJsonObject {
        put("model", model.id)
        put("messages", messages)
        put("max_tokens", maxTokens)
        put("stream", true)
        if (isOAuthToken) {
            put(
                "system",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "You are Claude Code, Anthropic's official CLI for Claude.")
                            cacheControl?.let { put("cache_control", it) }
                        },
                    )
                    context.systemPrompt?.let { system ->
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", system)
                                cacheControl?.let { put("cache_control", it) }
                            },
                        )
                    }
                },
            )
        } else {
            context.systemPrompt?.let { system ->
                put(
                    "system",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", system)
                                cacheControl?.let { put("cache_control", it) }
                            },
                        )
                    },
                )
            }
        }
        val supportsTemperature =
            model.compat?.get("supportsTemperature")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.booleanOrNull
                ?: true
        if (supportsTemperature) {
            options.temperature?.let { put("temperature", it) }
        }
        if (context.tools.isNotEmpty()) {
            val supportsStrictTools =
                model.compat?.get("supportsStrictTools")
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.booleanOrNull
                    ?: false
            put(
                "tools",
                buildJsonArray {
                    context.tools.forEachIndexed { index, tool ->
                        val strict = resolveJsonSchemaStrictSampling(tool, supportsStrictTools)
                        val legacyInputSchema =
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "properties",
                                    tool.parameters["properties"] ?: JsonObject(emptyMap()),
                                )
                                put(
                                    "required",
                                    tool.parameters["required"] ?: JsonArray(emptyList()),
                                )
                            }
                        add(
                            buildJsonObject {
                                put("name", anthropicToolName(tool.name, isOAuthToken))
                                put("description", tool.description)
                                put("eager_input_streaming", true)
                                if (strict == true) {
                                    put("strict", true)
                                }
                                put(
                                    "input_schema",
                                    if (strict == true) {
                                        JsonObject(tool.parameters + legacyInputSchema)
                                    } else {
                                        legacyInputSchema
                                    },
                                )
                                if (index == context.tools.lastIndex) {
                                    cacheControl?.let { put("cache_control", it) }
                                }
                            },
                        )
                    }
                },
            )
        }
        if (model.reasoning) {
            when {
                requestedThinking != null && requestedThinking != works.earendil.pi.ai.ModelThinkingLevel.OFF &&
                    forceAdaptiveThinking -> {
                    put(
                        "thinking",
                        buildJsonObject {
                            put("type", "adaptive")
                            put("display", "summarized")
                        },
                    )
                    val effort =
                        model.thinkingLevelMap[requestedThinking] ?: requestedThinking.name.lowercase()
                    put("output_config", buildJsonObject { put("effort", effort) })
                }

                requestedThinking != null && requestedThinking != works.earendil.pi.ai.ModelThinkingLevel.OFF -> {
                    put(
                        "thinking",
                        buildJsonObject {
                            put("type", "enabled")
                            put("budget_tokens", requireNotNull(adjustedThinkingBudget))
                            put("display", "summarized")
                        },
                    )
                }

                model.supportsThinkingOff() ->
                    put("thinking", buildJsonObject { put("type", "disabled") })
            }
        }
    }
}
