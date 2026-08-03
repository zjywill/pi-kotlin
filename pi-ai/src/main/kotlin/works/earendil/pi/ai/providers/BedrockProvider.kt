package works.earendil.pi.ai.providers

import java.net.URI
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.auth.token.credentials.StaticTokenProvider
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.exception.SdkServiceException
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.document.Document
import software.amazon.awssdk.http.Protocol
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.ProxyConfiguration
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient
import software.amazon.awssdk.services.bedrockruntime.auth.scheme.BedrockRuntimeAuthSchemeProvider
import software.amazon.awssdk.services.bedrockruntime.model.AnyToolChoice
import software.amazon.awssdk.services.bedrockruntime.model.AutoToolChoice
import software.amazon.awssdk.services.bedrockruntime.model.CachePointBlock
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler
import software.amazon.awssdk.services.bedrockruntime.model.ImageBlock
import software.amazon.awssdk.services.bedrockruntime.model.ImageFormat
import software.amazon.awssdk.services.bedrockruntime.model.ImageSource
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration
import software.amazon.awssdk.services.bedrockruntime.model.Message
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ReasoningTextBlock
import software.amazon.awssdk.services.bedrockruntime.model.SpecificToolChoice
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.Tool
import software.amazon.awssdk.services.bedrockruntime.model.ToolChoice
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.AssistantError
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.AssistantMessageDiagnostic
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.AssistantStart
import works.earendil.pi.ai.BashExecutionMessage
import works.earendil.pi.ai.BedrockThinkingDisplay
import works.earendil.pi.ai.BranchSummaryMessage
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.CompactionSummaryMessage
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.ContentBlock as PiContentBlock
import works.earendil.pi.ai.CustomMessage
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.Message as PiMessage
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.ModelThinkingLevel
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
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.ai.ThinkingStart
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolCallDelta
import works.earendil.pi.ai.ToolCallEnd
import works.earendil.pi.ai.ToolCallStart
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.ai.createAssistantMessageEventStream
import works.earendil.pi.ai.resolveJsonSchemaStrictSampling
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal enum class BedrockAuthMode {
    DEFAULT,
    PROFILE,
    ACCESS_KEY,
    BEARER,
    SKIP,
}

internal data class BedrockClientConfiguration(
    val region: String?,
    val endpoint: String?,
    val profile: String?,
    val authMode: BedrockAuthMode,
    val bearerToken: String? = null,
    val accessKeyId: String? = null,
    val secretAccessKey: String? = null,
    val sessionToken: String? = null,
    val forceHttp1: Boolean = false,
    val proxyUrl: String? = null,
)

internal sealed interface BedrockStreamEvent {
    data class MessageStart(
        val role: String,
    ) : BedrockStreamEvent

    data class ContentStart(
        val index: Int,
        val toolUseId: String?,
        val toolName: String?,
    ) : BedrockStreamEvent

    data class ContentDelta(
        val index: Int,
        val text: String? = null,
        val toolInput: String? = null,
        val reasoningText: String? = null,
        val reasoningSignature: String? = null,
    ) : BedrockStreamEvent

    data class ContentStop(
        val index: Int,
    ) : BedrockStreamEvent

    data class MessageStop(
        val reason: String?,
    ) : BedrockStreamEvent

    data class Metadata(
        val input: Int,
        val output: Int,
        val cacheRead: Int,
        val cacheWrite: Int,
        val total: Int,
    ) : BedrockStreamEvent
}

internal fun interface BedrockRuntimeTransport {
    suspend fun converseStream(
        invocation: BedrockInvocation,
        onEvent: (BedrockStreamEvent) -> Unit,
    ): BedrockResponseMetadata?
}

internal data class BedrockResponseMetadata(
    val requestId: String? = null,
)

internal data class BedrockInvocation(
    val client: BedrockClientConfiguration,
    val request: JsonObject,
    val headers: Map<String, String>,
    val timeoutMs: Long?,
)

internal class BedrockProvider(
    override val id: String,
    override val name: String,
    private val models: List<Model>,
    private val environment: (String) -> String? = System::getenv,
    private val transport: BedrockRuntimeTransport = AwsBedrockRuntimeTransport(),
) : Provider {
    override val baseUrl: String? = models.firstOrNull()?.baseUrl

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
                            errorMessage = formatBedrockError(error),
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
    ): AssistantMessageEventStream =
        stream(
            model,
            context,
            bedrockSimpleStreamOptions(model, context, options),
        )

    private suspend fun execute(
        model: Model,
        context: Context,
        options: StreamOptions,
        stream: AssistantMessageEventStream,
    ) {
        val state = BedrockStreamState(model, stream)
        runCatching {
            val invocation =
                BedrockInvocation(
                    client = resolveBedrockClientConfiguration(model, options, environment),
                    request = buildBedrockRequestBody(model, context, options, environment),
                    headers = bedrockCustomHeaders(model.headers, options.headers),
                    timeoutMs = options.timeoutMs,
                )
            transport.converseStream(invocation, state::handle)
            state.finish()
        }.onFailure { error ->
            state.fail(
                formatBedrockError(error),
                bedrockFailureDiagnostic(error),
            )
        }
    }
}

internal class BedrockStreamState(
    private val model: Model,
    private val stream: AssistantMessageEventStream,
) {
    private val blocks = mutableListOf<PiContentBlock>()
    private val providerIndexes = mutableMapOf<Int, Int>()
    private val toolArguments = mutableMapOf<Int, String>()
    private var stopReason = StopReason.PENDING
    private var rawStopReason: String? = null
    private var stopError: String? = null
    private var usage = Usage()

    fun handle(event: BedrockStreamEvent) {
        when (event) {
            is BedrockStreamEvent.MessageStart -> {
                require(event.role == "assistant") {
                    "Unexpected assistant message start but got ${event.role} message start instead"
                }
                stream.push(AssistantStart(snapshot()))
            }

            is BedrockStreamEvent.ContentStart -> {
                if (event.toolUseId != null || event.toolName != null) {
                    val contentIndex = blocks.size
                    providerIndexes[event.index] = contentIndex
                    toolArguments[event.index] = ""
                    blocks +=
                        ToolCall(
                            id = event.toolUseId.orEmpty(),
                            name = event.toolName.orEmpty(),
                            arguments = JsonObject(emptyMap()),
                        )
                    stream.push(ToolCallStart(contentIndex, snapshot()))
                }
            }

            is BedrockStreamEvent.ContentDelta -> {
                event.text?.let { appendText(event.index, it) }
                event.toolInput?.let { appendToolInput(event.index, it) }
                if (event.reasoningText != null || event.reasoningSignature != null) {
                    appendThinking(event.index, event.reasoningText, event.reasoningSignature)
                }
            }

            is BedrockStreamEvent.ContentStop -> finishBlock(event.index)
            is BedrockStreamEvent.MessageStop -> {
                rawStopReason = event.reason
                val mapped = mapBedrockStopReason(event.reason)
                stopReason = mapped.first
                stopError = mapped.second
            }

            is BedrockStreamEvent.Metadata -> {
                usage =
                    calculateUsageCost(
                        model = model,
                        input = event.input,
                        output = event.output,
                        cacheRead = event.cacheRead,
                        cacheWrite = event.cacheWrite,
                    ).copy(totalTokens = event.total)
            }
        }
    }

    fun finish() {
        check(stopReason != StopReason.PENDING) {
            "Bedrock stream ended without a stop reason"
        }
        val final = snapshot()
        if (stopReason == StopReason.ERROR || stopReason == StopReason.ABORTED) {
            error(stopError ?: "An unknown error occurred")
        }
        stream.push(AssistantDone(stopReason, final))
    }

    fun fail(
        message: String,
        diagnostic: AssistantMessageDiagnostic? = null,
    ) {
        stopReason = StopReason.ERROR
        stream.push(
            AssistantError(
                StopReason.ERROR,
                snapshot().copy(
                    errorMessage = message,
                    diagnostics = diagnostic?.let(::listOf),
                ),
            ),
        )
    }

    private fun appendText(
        providerIndex: Int,
        delta: String,
    ) {
        val contentIndex =
            providerIndexes.getOrPut(providerIndex) {
                blocks += TextContent("")
                val index = blocks.lastIndex
                stream.push(TextStart(index, snapshot()))
                index
            }
        val current = blocks[contentIndex] as? TextContent ?: return
        blocks[contentIndex] = current.copy(text = current.text + delta)
        stream.push(TextDelta(contentIndex, delta, snapshot()))
    }

    private fun appendToolInput(
        providerIndex: Int,
        delta: String,
    ) {
        val contentIndex = providerIndexes[providerIndex] ?: return
        val current = blocks[contentIndex] as? ToolCall ?: return
        val combined = toolArguments.getValue(providerIndex) + delta
        toolArguments[providerIndex] = combined
        blocks[contentIndex] = current.copy(arguments = parseJsonObjectOrEmpty(combined))
        stream.push(ToolCallDelta(contentIndex, delta, snapshot()))
    }

    private fun appendThinking(
        providerIndex: Int,
        text: String?,
        signature: String?,
    ) {
        val contentIndex =
            providerIndexes.getOrPut(providerIndex) {
                blocks += ThinkingContent("", "")
                val index = blocks.lastIndex
                stream.push(ThinkingStart(index, snapshot()))
                index
            }
        val current = blocks[contentIndex] as? ThinkingContent ?: return
        var updated = current
        text?.takeIf(String::isNotEmpty)?.let {
            updated = updated.copy(thinking = updated.thinking + it)
            blocks[contentIndex] = updated
            stream.push(ThinkingDelta(contentIndex, it, snapshot()))
        }
        signature?.let {
            updated = updated.copy(thinkingSignature = updated.thinkingSignature.orEmpty() + it)
            blocks[contentIndex] = updated
        }
    }

    private fun finishBlock(providerIndex: Int) {
        val contentIndex = providerIndexes[providerIndex] ?: return
        when (val block = blocks[contentIndex]) {
            is TextContent -> stream.push(TextEnd(contentIndex, block.text, snapshot()))
            is ThinkingContent -> stream.push(ThinkingEnd(contentIndex, block.thinking, snapshot()))
            is ToolCall -> {
                val final = block.copy(arguments = parseJsonObjectOrEmpty(toolArguments[providerIndex].orEmpty()))
                blocks[contentIndex] = final
                stream.push(ToolCallEnd(contentIndex, final, snapshot()))
            }

            else -> Unit
        }
    }

    private fun snapshot(): AssistantMessage =
        AssistantMessage(
            content = copyBlocks(blocks),
            api = model.api,
            provider = model.provider,
            model = model.id,
            usage = usage,
            stopReason = stopReason,
            rawStopReason = rawStopReason,
        )
}

internal fun buildBedrockRequestBody(
    model: Model,
    context: Context,
    options: StreamOptions,
    environment: (String) -> String? = System::getenv,
): JsonObject {
    val cacheRetention = resolveBedrockCacheRetention(options, environment)
    val messages = convertBedrockMessages(model, context.messages, cacheRetention, options, environment)
    return buildJsonObject {
        put("modelId", model.id)
        put("messages", messages)
        buildBedrockSystemPrompt(model, context.systemPrompt, cacheRetention, options, environment)?.let {
            put("system", it)
        }
        put(
            "inferenceConfig",
            buildJsonObject {
                val maxTokens =
                    options.maxTokens
                        ?: model.maxTokens.takeIf { isAnthropicClaudeModel(model) }
                maxTokens?.let { put("maxTokens", it) }
                options.temperature?.let { put("temperature", it) }
            },
        )
        buildBedrockToolConfig(model, context, options)?.let { put("toolConfig", it) }
        buildBedrockAdditionalModelRequestFields(model, options, environment)?.let {
            put("additionalModelRequestFields", it)
        }
        options.requestMetadata?.let { metadata ->
            put(
                "requestMetadata",
                buildJsonObject {
                    metadata.forEach { (key, value) -> put(key, value) }
                },
            )
        }
    }
}

internal fun bedrockSimpleStreamOptions(
    model: Model,
    context: Context,
    options: SimpleStreamOptions,
): StreamOptions {
    val requested = options.reasoning
    val baseMaxTokens =
        clampBedrockMaxTokensToContext(
            model,
            context,
            options.stream.maxTokens ?: model.maxTokens,
        )
    val base =
        options.stream.copy(
            maxTokens = baseMaxTokens,
            reasoning = requested,
            thinkingBudgets = options.thinkingBudgets,
        )
    if (requested == null || !isAnthropicClaudeModel(model) || supportsAdaptiveBedrockThinking(model)) {
        return base
    }

    val level = ModelThinkingLevel.valueOf(requested.name)
    val budget = thinkingBudget(level, options.thinkingBudgets)
    val adjustedMaxTokens = min(baseMaxTokens + budget, model.maxTokens)
    val adjustedBudget =
        if (adjustedMaxTokens <= budget) {
            max(0, adjustedMaxTokens - MIN_BEDROCK_OUTPUT_TOKENS)
        } else {
            budget
        }
    val maxTokens = clampBedrockMaxTokensToContext(model, context, adjustedMaxTokens)
    val finalBudget = min(adjustedBudget, max(0, maxTokens - MIN_BEDROCK_OUTPUT_TOKENS))
    val budgets = options.thinkingBudgets ?: works.earendil.pi.ai.ThinkingBudgets()
    val adjustedBudgets =
        when (requested) {
            ThinkingLevel.MINIMAL -> budgets.copy(minimal = finalBudget)
            ThinkingLevel.LOW -> budgets.copy(low = finalBudget)
            ThinkingLevel.MEDIUM -> budgets.copy(medium = finalBudget)
            ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX -> budgets.copy(high = finalBudget)
        }
    return base.copy(
        maxTokens = maxTokens,
        thinkingBudgets = adjustedBudgets,
    )
}

internal fun resolveBedrockClientConfiguration(
    model: Model,
    options: StreamOptions,
    environment: (String) -> String? = System::getenv,
): BedrockClientConfiguration {
    fun env(name: String): String? =
        options.env[name]?.takeIf(String::isNotBlank)
            ?: environment(name)?.takeIf(String::isNotBlank)

    val configuredRegion = options.region?.takeIf(String::isNotBlank) ?: env("AWS_REGION") ?: env("AWS_DEFAULT_REGION")
    val ambientProfile = environment("AWS_PROFILE")?.takeIf(String::isNotBlank)
    val optionsProfile =
        options.profile?.takeIf(String::isNotBlank)
            ?: options.env["AWS_PROFILE"]?.takeIf(String::isNotBlank)
    val profile = optionsProfile ?: ambientProfile
    val endpointRegion = standardBedrockEndpointRegion(model.baseUrl)
    val explicitEndpoint = endpointRegion == null || (configuredRegion == null && ambientProfile == null)
    val arnRegion =
        Regex("^arn:aws(?:-[a-z0-9-]+)?:bedrock:([a-z0-9-]+):", RegexOption.IGNORE_CASE)
            .find(model.id)
            ?.groupValues
            ?.get(1)
    val region =
        arnRegion
            ?: configuredRegion
            ?: endpointRegion?.takeIf { explicitEndpoint }
            ?: "us-east-1".takeIf { ambientProfile == null }
    val skipAuth = env("AWS_BEDROCK_SKIP_AUTH") == "1"
    val bearer =
        options.bearerToken?.takeIf(String::isNotBlank)
            ?: options.apiKey?.takeIf(String::isNotBlank)
            ?: env("AWS_BEARER_TOKEN_BEDROCK")
    val accessKeyId = env("AWS_ACCESS_KEY_ID")
    val secretAccessKey = env("AWS_SECRET_ACCESS_KEY")
    val authMode =
        when {
            skipAuth -> BedrockAuthMode.SKIP
            bearer != null -> BedrockAuthMode.BEARER
            optionsProfile != null -> BedrockAuthMode.PROFILE
            accessKeyId != null && secretAccessKey != null -> BedrockAuthMode.ACCESS_KEY
            profile != null -> BedrockAuthMode.PROFILE
            else -> BedrockAuthMode.DEFAULT
        }
    return BedrockClientConfiguration(
        region = region,
        endpoint = model.baseUrl.takeIf { explicitEndpoint },
        profile = profile,
        authMode = authMode,
        bearerToken = bearer.takeIf { authMode == BedrockAuthMode.BEARER },
        accessKeyId = accessKeyId.takeIf { authMode == BedrockAuthMode.ACCESS_KEY },
        secretAccessKey = secretAccessKey.takeIf { authMode == BedrockAuthMode.ACCESS_KEY },
        sessionToken = env("AWS_SESSION_TOKEN").takeIf { authMode == BedrockAuthMode.ACCESS_KEY },
        forceHttp1 = env("AWS_BEDROCK_FORCE_HTTP1") == "1",
        proxyUrl = resolveBedrockProxyUrl(model.baseUrl, options, environment),
    )
}

internal fun bedrockCustomHeaders(
    modelHeaders: Map<String, String>,
    optionHeaders: Map<String, String?>,
): Map<String, String> =
    mergedHeaders(emptyMap(), modelHeaders, optionHeaders)
        .filterKeys { name -> !isReservedBedrockHeader(name) }

internal fun isReservedBedrockHeader(name: String): Boolean {
    val lower = name.lowercase()
    return lower == "authorization" || lower == "host" || lower.startsWith("x-amz-")
}

private fun convertBedrockMessages(
    model: Model,
    messages: List<PiMessage>,
    cacheRetention: CacheRetention,
    options: StreamOptions,
    environment: (String) -> String?,
): JsonArray {
    val transformed = transformBedrockMessages(model, messages)
    val result = mutableListOf<JsonObject>()
    var index = 0
    while (index < transformed.size) {
        when (val message = transformed[index]) {
            is UserMessage -> {
                result +=
                    buildJsonObject {
                        put("role", "user")
                        put("content", bedrockUserContent(message.content))
                    }
            }

            is AssistantMessage -> {
                val content = bedrockAssistantContent(model, message)
                if (content.isNotEmpty()) {
                    result +=
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", content)
                        }
                }
            }

            is ToolResultMessage -> {
                val toolResults = mutableListOf<JsonObject>()
                var next = index
                while (next < transformed.size) {
                    val toolResult = transformed[next] as? ToolResultMessage ?: break
                    toolResults += bedrockToolResult(toolResult)
                    next++
                }
                result +=
                    buildJsonObject {
                        put("role", "user")
                        put("content", JsonArray(toolResults))
                    }
                index = next - 1
            }

            is CustomMessage -> {
                result += bedrockSyntheticUser(message.content)
            }

            is CompactionSummaryMessage -> {
                result += bedrockSyntheticUser(MessageContent.Text(message.summary))
            }

            is BranchSummaryMessage -> {
                result += bedrockSyntheticUser(MessageContent.Text(message.summary))
            }

            is BashExecutionMessage -> {
                result += bedrockSyntheticUser(MessageContent.Text("${message.command}\n${message.output}"))
            }
        }
        index++
    }

    if (
        cacheRetention != CacheRetention.NONE &&
        supportsBedrockPromptCaching(model, options, environment) &&
        result.lastOrNull()?.string("role") == "user"
    ) {
        val last = result.removeLast()
        result +=
            JsonObject(
                last +
                    (
                        "content" to
                            JsonArray(
                                last.getValue("content").jsonArray +
                                    bedrockCachePoint(cacheRetention),
                            )
                    ),
            )
    }
    return JsonArray(result)
}

private fun transformBedrockMessages(
    model: Model,
    messages: List<PiMessage>,
): List<PiMessage> {
    val normalizedIds = mutableMapOf<String, String>()
    val firstPass =
        messages.mapNotNull { message ->
            when (message) {
                is UserMessage ->
                    message.copy(content = downgradeBedrockImages(message.content, model, USER_IMAGE_PLACEHOLDER))

                is ToolResultMessage ->
                    message.copy(
                        toolCallId = normalizedIds[message.toolCallId] ?: message.toolCallId,
                        content = downgradeBedrockToolImages(message.content, model),
                    )

                is AssistantMessage -> {
                    if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) {
                        return@mapNotNull null
                    }
                    val sameModel =
                        message.provider == model.provider &&
                            message.api == model.api &&
                            message.model == model.id
                    val content =
                        message.content.mapNotNull { block ->
                            when (block) {
                                is ThinkingContent ->
                                    when {
                                        block.redacted == true && !sameModel -> null
                                        sameModel && !block.thinkingSignature.isNullOrBlank() -> block
                                        block.thinking.isBlank() -> null
                                        sameModel -> block
                                        else -> TextContent(block.thinking)
                                    }

                                is TextContent -> block.copy(textSignature = block.textSignature.takeIf { sameModel })
                                is ToolCall -> {
                                    val id =
                                        if (sameModel) {
                                            block.id
                                        } else {
                                            normalizeBedrockToolCallId(block.id)
                                        }
                                    if (id != block.id) {
                                        normalizedIds[block.id] = id
                                    }
                                    block.copy(
                                        id = id,
                                        thoughtSignature = block.thoughtSignature.takeIf { sameModel },
                                    )
                                }

                                is ImageContent -> null
                            }
                        }
                    message.copy(content = content)
                }

                else -> message
            }
        }

    val result = mutableListOf<PiMessage>()
    var pending = emptyList<ToolCall>()
    var existingResults = mutableSetOf<String>()
    fun insertSyntheticResults() {
        pending
            .filterNot { it.id in existingResults }
            .forEach { call ->
                result +=
                    ToolResultMessage(
                        toolCallId = call.id,
                        toolName = call.name,
                        content = listOf(TextContent("No result provided")),
                        isError = true,
                    )
            }
        pending = emptyList()
        existingResults = mutableSetOf()
    }
    firstPass.forEach { message ->
        when (message) {
            is AssistantMessage -> {
                insertSyntheticResults()
                pending = message.content.filterIsInstance<ToolCall>()
                result += message
            }

            is ToolResultMessage -> {
                existingResults += message.toolCallId
                result += message
            }

            is UserMessage -> {
                insertSyntheticResults()
                result += message
            }

            else -> result += message
        }
    }
    insertSyntheticResults()
    return result
}

private fun bedrockUserContent(content: MessageContent): JsonArray {
    val blocks =
        when (content) {
            is MessageContent.Text ->
                listOfNotNull(bedrockTextBlock(content.text))

            is MessageContent.Blocks ->
                content.blocks.mapNotNull { block ->
                    when (block) {
                        is TextContent -> bedrockTextBlock(block.text)
                        is ImageContent -> bedrockImageBlock(block)
                        else -> null
                    }
                }
        }
    return JsonArray(blocks.ifEmpty { listOf(buildJsonObject { put("text", EMPTY_TEXT_PLACEHOLDER) }) })
}

private fun bedrockAssistantContent(
    model: Model,
    message: AssistantMessage,
): JsonArray =
    buildJsonArray {
        message.content.forEach { block ->
            when (block) {
                is TextContent -> bedrockTextBlock(block.text)?.let(::add)
                is ToolCall ->
                    add(
                        buildJsonObject {
                            put(
                                "toolUse",
                                buildJsonObject {
                                    put("toolUseId", block.id)
                                    put("name", block.name)
                                    put("input", block.arguments)
                                },
                            )
                        },
                    )

                is ThinkingContent -> {
                    val thinking = sanitizeBedrockSurrogates(block.thinking)
                    if (thinking.isNotBlank()) {
                        when {
                            isAnthropicClaudeModel(model) && block.thinkingSignature.isNullOrBlank() ->
                                add(buildJsonObject { put("text", thinking) })

                            else ->
                                add(
                                    buildJsonObject {
                                        put(
                                            "reasoningContent",
                                            buildJsonObject {
                                                put(
                                                    "reasoningText",
                                                    buildJsonObject {
                                                        put("text", thinking)
                                                        block.thinkingSignature
                                                            ?.takeIf(String::isNotBlank)
                                                            ?.takeIf { isAnthropicClaudeModel(model) }
                                                            ?.let { put("signature", it) }
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                        }
                    }
                }

                is ImageContent -> Unit
            }
        }
    }

private fun bedrockToolResult(message: ToolResultMessage): JsonObject =
    buildJsonObject {
        put(
            "toolResult",
            buildJsonObject {
                put("toolUseId", message.toolCallId)
                put(
                    "content",
                    buildJsonArray {
                        var added = false
                        message.content.forEach { block ->
                            when (block) {
                                is TextContent ->
                                    bedrockTextBlock(block.text)?.let {
                                        add(it)
                                        added = true
                                    }

                                is ImageContent -> {
                                    add(bedrockToolResultImageBlock(block))
                                    added = true
                                }

                                else -> Unit
                            }
                        }
                        if (!added) {
                            add(buildJsonObject { put("text", EMPTY_TEXT_PLACEHOLDER) })
                        }
                    },
                )
                put("status", if (message.isError) "error" else "success")
            },
        )
    }

private fun bedrockSyntheticUser(content: MessageContent): JsonObject =
    buildJsonObject {
        put("role", "user")
        put("content", bedrockUserContent(content))
    }

private fun bedrockTextBlock(text: String): JsonObject? {
    val sanitized = sanitizeBedrockSurrogates(text)
    return sanitized.takeIf { it.isNotBlank() }?.let { buildJsonObject { put("text", it) } }
}

private fun bedrockImageBlock(image: ImageContent): JsonObject =
    buildJsonObject {
        put(
            "image",
            buildJsonObject {
                put("format", bedrockImageFormat(image.mimeType))
                put("source", buildJsonObject { put("bytes", image.data) })
            },
        )
    }

private fun bedrockToolResultImageBlock(image: ImageContent): JsonObject = bedrockImageBlock(image)

private fun buildBedrockSystemPrompt(
    model: Model,
    systemPrompt: String?,
    cacheRetention: CacheRetention,
    options: StreamOptions,
    environment: (String) -> String?,
): JsonArray? {
    systemPrompt ?: return null
    return buildJsonArray {
        add(buildJsonObject { put("text", sanitizeBedrockSurrogates(systemPrompt)) })
        if (
            cacheRetention != CacheRetention.NONE &&
            supportsBedrockPromptCaching(model, options, environment)
        ) {
            add(bedrockCachePoint(cacheRetention))
        }
    }
}

private fun buildBedrockToolConfig(
    model: Model,
    context: Context,
    options: StreamOptions,
): JsonObject? {
    val choice = options.toolChoice
    if (context.tools.isEmpty() || (choice as? JsonPrimitive)?.content == "none") {
        return null
    }
    return buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                val supportsStrictMode =
                    model.compat
                        ?.get("supportsStrictMode")
                        ?.jsonPrimitive
                        ?.booleanOrNull
                        ?: false
                context.tools.forEach { tool ->
                    val strict = resolveJsonSchemaStrictSampling(tool, supportsStrictMode)
                    add(
                        buildJsonObject {
                            put(
                                "toolSpec",
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("inputSchema", buildJsonObject { put("json", tool.parameters) })
                                    if (strict == true) {
                                        put("strict", true)
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
        bedrockToolChoice(choice)?.let { put("toolChoice", it) }
    }
}

private fun bedrockToolChoice(choice: JsonElement?): JsonObject? =
    when ((choice as? JsonPrimitive)?.content) {
        "auto" -> buildJsonObject { put("auto", JsonObject(emptyMap())) }
        "any" -> buildJsonObject { put("any", JsonObject(emptyMap())) }
        else -> {
            val objectChoice = choice as? JsonObject
            if (objectChoice?.string("type") == "tool") {
                objectChoice.string("name")?.let { name ->
                    buildJsonObject {
                        put("tool", buildJsonObject { put("name", name) })
                    }
                }
            } else {
                null
            }
        }
    }

private fun buildBedrockAdditionalModelRequestFields(
    model: Model,
    options: StreamOptions,
    environment: (String) -> String?,
): JsonObject? {
    val reasoning = options.reasoning ?: return null
    if (!model.reasoning || !isAnthropicClaudeModel(model)) {
        return null
    }
    val display =
        options.thinkingDisplay
            ?.name
            ?.lowercase()
            ?: BedrockThinkingDisplay.SUMMARIZED.name.lowercase()
    val includeDisplay = !isGovCloudBedrockTarget(model, options, environment)
    return if (supportsAdaptiveBedrockThinking(model)) {
        buildJsonObject {
            put(
                "thinking",
                buildJsonObject {
                    put("type", "adaptive")
                    if (includeDisplay) put("display", display)
                },
            )
            put(
                "output_config",
                buildJsonObject {
                    put("effort", mapBedrockThinkingEffort(model, reasoning))
                },
            )
        }
    } else {
        buildJsonObject {
            put(
                "thinking",
                buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", thinkingBudget(ModelThinkingLevel.valueOf(reasoning.name), options.thinkingBudgets))
                    if (includeDisplay) put("display", display)
                },
            )
            if (options.interleavedThinking != false) {
                put(
                    "anthropic_beta",
                    JsonArray(listOf(JsonPrimitive("interleaved-thinking-2025-05-14"))),
                )
            }
        }
    }
}

private fun resolveBedrockCacheRetention(
    options: StreamOptions,
    environment: (String) -> String?,
): CacheRetention =
    options.cacheRetention
        ?: if ((options.env["PI_CACHE_RETENTION"] ?: environment("PI_CACHE_RETENTION")) == "long") {
            CacheRetention.LONG
        } else {
            CacheRetention.SHORT
        }

private fun supportsBedrockPromptCaching(
    model: Model,
    options: StreamOptions,
    environment: (String) -> String?,
): Boolean {
    val candidates = bedrockModelCandidates(model)
    if (candidates.none { "claude" in it }) {
        return (options.env["AWS_BEDROCK_FORCE_CACHE"] ?: environment("AWS_BEDROCK_FORCE_CACHE")) == "1"
    }
    return candidates.any { candidate ->
        "fable-5" in candidate ||
            "opus-5" in candidate ||
            "sonnet-5" in candidate ||
            "-4-" in candidate ||
            "claude-3-7-sonnet" in candidate ||
            "claude-3-5-haiku" in candidate
    }
}

private fun supportsAdaptiveBedrockThinking(model: Model): Boolean =
    bedrockModelCandidates(model).any { candidate ->
        "opus-4-6" in candidate ||
            "opus-4-7" in candidate ||
            "opus-4-8" in candidate ||
            "opus-5" in candidate ||
            "sonnet-4-6" in candidate ||
            "sonnet-5" in candidate ||
            "fable-5" in candidate
    }

private fun supportsNativeBedrockXhigh(model: Model): Boolean =
    bedrockModelCandidates(model).any { candidate ->
        "opus-4-7" in candidate ||
            "opus-4-8" in candidate ||
            "opus-5" in candidate ||
            "sonnet-5" in candidate ||
            "fable-5" in candidate
    }

private fun mapBedrockThinkingEffort(
    model: Model,
    level: ThinkingLevel,
): String {
    if (level == ThinkingLevel.XHIGH && supportsNativeBedrockXhigh(model)) {
        return "xhigh"
    }
    model.thinkingLevelMap[ModelThinkingLevel.valueOf(level.name)]?.let { return it }
    return when (level) {
        ThinkingLevel.MINIMAL, ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH, ThinkingLevel.XHIGH, ThinkingLevel.MAX -> "high"
    }
}

private fun bedrockModelCandidates(model: Model): List<String> =
    listOf(model.id, model.name).flatMap { value ->
        val lower = value.lowercase()
        listOf(lower, lower.replace(Regex("[\\s_.:]+"), "-"))
    }

private fun isAnthropicClaudeModel(model: Model): Boolean =
    bedrockModelCandidates(model).any { candidate ->
        "anthropic.claude" in candidate ||
            "anthropic/claude" in candidate ||
            "claude" in candidate
    }

private fun isGovCloudBedrockTarget(
    model: Model,
    options: StreamOptions,
    environment: (String) -> String?,
): Boolean =
    (
        options.region
            ?: options.env["AWS_REGION"]
            ?: options.env["AWS_DEFAULT_REGION"]
            ?: environment("AWS_REGION")
            ?: environment("AWS_DEFAULT_REGION")
    )?.lowercase()?.startsWith("us-gov-") == true ||
        model.id.lowercase().startsWith("us-gov.") ||
        model.id.lowercase().startsWith("arn:aws-us-gov:")

private fun bedrockCachePoint(cacheRetention: CacheRetention): JsonObject =
    buildJsonObject {
        put(
            "cachePoint",
            buildJsonObject {
                put("type", "default")
                if (cacheRetention == CacheRetention.LONG) {
                    put("ttl", "1h")
                }
            },
        )
    }

private fun normalizeBedrockToolCallId(id: String): String =
    id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

private fun downgradeBedrockImages(
    content: MessageContent,
    model: Model,
    placeholder: String,
): MessageContent {
    if (ModelInput.IMAGE in model.input || content !is MessageContent.Blocks) {
        return content
    }
    return MessageContent.Blocks(replaceBedrockImages(content.blocks, placeholder))
}

private fun downgradeBedrockToolImages(
    content: List<PiContentBlock>,
    model: Model,
): List<PiContentBlock> =
    if (ModelInput.IMAGE in model.input) {
        content
    } else {
        replaceBedrockImages(content, TOOL_IMAGE_PLACEHOLDER)
    }

private fun replaceBedrockImages(
    content: List<PiContentBlock>,
    placeholder: String,
): List<PiContentBlock> {
    val result = mutableListOf<PiContentBlock>()
    var previousWasPlaceholder = false
    content.forEach { block ->
        if (block is ImageContent) {
            if (!previousWasPlaceholder) {
                result += TextContent(placeholder)
            }
            previousWasPlaceholder = true
        } else {
            result += block
            previousWasPlaceholder = block is TextContent && block.text == placeholder
        }
    }
    return result
}

private fun standardBedrockEndpointRegion(baseUrl: String): String? =
    runCatching {
        Regex(
            "^bedrock-runtime(?:-fips)?\\.([a-z0-9-]+)\\.amazonaws\\.com(?:\\.cn)?$",
            RegexOption.IGNORE_CASE,
        ).find(URI(baseUrl).host.orEmpty())?.groupValues?.get(1)
    }.getOrNull()

private fun bedrockImageFormat(mimeType: String): String =
    when (mimeType) {
        "image/jpeg", "image/jpg" -> "jpeg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> error("Unknown image type: $mimeType")
    }

private fun sanitizeBedrockSurrogates(value: String): String {
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

internal fun mapBedrockStopReason(reason: String?): Pair<StopReason, String?> =
    when (reason) {
        "end_turn", "stop_sequence" -> StopReason.STOP to null
        "max_tokens", "model_context_window_exceeded" -> StopReason.LENGTH to null
        "tool_use" -> StopReason.TOOL_USE to null
        null -> StopReason.ERROR to null
        else -> StopReason.ERROR to "Provider stopped with: $reason"
    }

private fun formatBedrockError(error: Throwable): String {
    val causes = generateSequence(error) { current -> current.cause }.toList()
    val source =
        causes.firstOrNull { it::class.simpleName in BEDROCK_ERROR_PREFIXES }
            ?: causes.lastOrNull { current -> current.message != null }
            ?: error
    val core = source.message ?: source::class.simpleName.orEmpty()
    val hint =
        if (core.contains("data retention mode", ignoreCase = true)) {
            " See https://docs.aws.amazon.com/bedrock/latest/userguide/data-retention.html for supported data retention modes."
        } else {
            ""
        }
    val prefix = BEDROCK_ERROR_PREFIXES[source::class.simpleName].orEmpty()
    return "$prefix$core$hint"
}

private fun bedrockFailureDiagnostic(error: Throwable): AssistantMessageDiagnostic? {
    if (error is CancellationException) {
        return null
    }
    val failure = error as? BedrockTransportFailure
    val sdkError =
        generateSequence(failure?.source ?: error) { current -> current.cause }
            .filterIsInstance<SdkServiceException>()
            .firstOrNull()
    val details =
        buildJsonObject {
            sdkError?.statusCode()?.takeIf { it > 0 }?.let { put("status", it) }
            sdkError
                ?.javaClass
                ?.simpleName
                ?.takeIf { it.endsWith("Exception") }
                ?.let { put("errorCode", it) }
            (
                normalizeBedrockDiagnosticValue(sdkError?.requestId())
                    ?: normalizeBedrockDiagnosticValue(failure?.responseRequestId)
            )?.let { put("requestId", it) }
        }
    if (details.isEmpty()) {
        return null
    }
    return AssistantMessageDiagnostic(
        type = "bedrock_response_failure",
        timestamp = System.currentTimeMillis(),
        details = details,
    )
}

private fun normalizeBedrockDiagnosticValue(value: String?): String? =
    value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_BEDROCK_DIAGNOSTIC_VALUE_CHARS }

private class AwsBedrockRuntimeTransport : BedrockRuntimeTransport {
    override suspend fun converseStream(
        invocation: BedrockInvocation,
        onEvent: (BedrockStreamEvent) -> Unit,
    ): BedrockResponseMetadata? {
        val client = buildClient(invocation)
        val failure = AtomicReference<Throwable>()
        val responseRequestId = AtomicReference<String>()
        try {
            val handler =
                ConverseStreamResponseHandler
                    .builder()
                    .onResponse { response ->
                        responseRequestId.set(response.responseMetadata()?.requestId())
                    }
                    .onError(failure::set)
                    .subscriber(
                        ConverseStreamResponseHandler.Visitor
                            .builder()
                            .onMessageStart { event ->
                                onEvent(BedrockStreamEvent.MessageStart(event.roleAsString()))
                            }.onContentBlockStart { event ->
                                val tool = event.start()?.toolUse()
                                onEvent(
                                    BedrockStreamEvent.ContentStart(
                                        event.contentBlockIndex() ?: 0,
                                        tool?.toolUseId(),
                                        tool?.name(),
                                    ),
                                )
                            }.onContentBlockDelta { event ->
                                val delta = event.delta()
                                val reasoning = delta?.reasoningContent()
                                onEvent(
                                    BedrockStreamEvent.ContentDelta(
                                        index = event.contentBlockIndex() ?: 0,
                                        text = delta?.text(),
                                        toolInput = delta?.toolUse()?.input(),
                                        reasoningText = reasoning?.text(),
                                        reasoningSignature = reasoning?.signature(),
                                    ),
                                )
                            }.onContentBlockStop { event ->
                                onEvent(BedrockStreamEvent.ContentStop(event.contentBlockIndex() ?: 0))
                            }.onMessageStop { event ->
                                onEvent(BedrockStreamEvent.MessageStop(event.stopReasonAsString()))
                            }.onMetadata { event ->
                                val raw = event.usage()
                                onEvent(
                                    BedrockStreamEvent.Metadata(
                                        input = raw?.inputTokens() ?: 0,
                                        output = raw?.outputTokens() ?: 0,
                                        cacheRead = raw?.cacheReadInputTokens() ?: 0,
                                        cacheWrite = raw?.cacheWriteInputTokens() ?: 0,
                                        total =
                                            raw?.totalTokens()?.takeIf { it != 0 }
                                                ?: ((raw?.inputTokens() ?: 0) + (raw?.outputTokens() ?: 0)),
                                    ),
                                )
                            }.build(),
                    ).build()
            try {
                client.converseStream(toSdkRequest(invocation), handler).await()
                failure.get()?.let { throw it }
            } catch (error: Throwable) {
                throw BedrockTransportFailure(error, responseRequestId.get())
            }
            return BedrockResponseMetadata(responseRequestId.get())
        } finally {
            client.close()
        }
    }

    private fun buildClient(invocation: BedrockInvocation): BedrockRuntimeAsyncClient {
        val config = invocation.client
        val builder = BedrockRuntimeAsyncClient.builder()
        config.region?.let { builder.region(Region.of(it)) }
        config.endpoint?.let { builder.endpointOverride(URI(it)) }
        if (config.forceHttp1 || config.proxyUrl != null) {
            builder.httpClientBuilder(
                NettyNioAsyncHttpClient
                    .builder()
                    .protocol(Protocol.HTTP1_1)
                    .apply {
                        config.proxyUrl?.let { proxy ->
                            proxyConfiguration(bedrockProxyConfiguration(proxy))
                        }
                    },
            )
        }
        invocation.timeoutMs?.let { timeout ->
            builder.overrideConfiguration(
                ClientOverrideConfiguration
                    .builder()
                    .apiCallTimeout(Duration.ofMillis(timeout))
                    .build(),
            )
        }
        when (config.authMode) {
            BedrockAuthMode.SKIP ->
                builder.credentialsProvider(
                    StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("dummy-access-key", "dummy-secret-key"),
                    ),
                )

            BedrockAuthMode.BEARER -> {
                builder.authSchemeProvider(
                    BedrockRuntimeAuthSchemeProvider.defaultProvider(listOf("httpBearerAuth")),
                )
                val token = requireNotNull(config.bearerToken)
                builder.tokenProvider(StaticTokenProvider.create { token })
            }

            BedrockAuthMode.ACCESS_KEY -> {
                val accessKey = requireNotNull(config.accessKeyId)
                val secret = requireNotNull(config.secretAccessKey)
                val credentials =
                    config.sessionToken?.let {
                        AwsSessionCredentials.create(accessKey, secret, it)
                    } ?: AwsBasicCredentials.create(accessKey, secret)
                builder.credentialsProvider(StaticCredentialsProvider.create(credentials))
            }

            BedrockAuthMode.PROFILE ->
                builder.credentialsProvider(ProfileCredentialsProvider.create(requireNotNull(config.profile)))

            BedrockAuthMode.DEFAULT ->
                builder.credentialsProvider(DefaultCredentialsProvider.builder().build())
        }
        return builder.build()
    }

    private fun toSdkRequest(invocation: BedrockInvocation): ConverseStreamRequest {
        val body = invocation.request
        val builder =
            ConverseStreamRequest
                .builder()
                .modelId(body.getValue("modelId").jsonPrimitive.content)
                .messages(body.getValue("messages").jsonArray.map(::toSdkMessage))
        body["system"]?.jsonArray?.map(::toSdkSystemBlock)?.let(builder::system)
        body["inferenceConfig"]?.jsonObject?.let { config ->
            builder.inferenceConfig(
                InferenceConfiguration
                    .builder()
                    .apply {
                        config["maxTokens"]?.jsonPrimitive?.intOrNull?.let(::maxTokens)
                        config["temperature"]?.jsonPrimitive?.doubleOrNull?.toFloat()?.let(::temperature)
                    }.build(),
            )
        }
        body["toolConfig"]?.jsonObject?.let { builder.toolConfig(toSdkToolConfiguration(it)) }
        body["additionalModelRequestFields"]?.let { builder.additionalModelRequestFields(it.toDocument()) }
        body["requestMetadata"]?.jsonObject?.let { metadata ->
            builder.requestMetadata(metadata.mapValues { it.value.jsonPrimitive.content })
        }
        if (invocation.headers.isNotEmpty()) {
            val override =
                AwsRequestOverrideConfiguration
                    .builder()
                    .apply {
                        invocation.headers.forEach { (name, value) -> putHeader(name, value) }
                    }.build()
            builder.overrideConfiguration(override)
        }
        return builder.build()
    }

    private fun toSdkMessage(raw: JsonElement): Message {
        val message = raw.jsonObject
        return Message
            .builder()
            .role(ConversationRole.fromValue(message.getValue("role").jsonPrimitive.content))
            .content(message.getValue("content").jsonArray.map(::toSdkContentBlock))
            .build()
    }

    private fun toSdkContentBlock(raw: JsonElement): ContentBlock {
        val block = raw.jsonObject
        block.string("text")?.let { return ContentBlock.fromText(it) }
        block.obj("image")?.let { return ContentBlock.fromImage(toSdkImageBlock(it)) }
        block.obj("cachePoint")?.let { return ContentBlock.fromCachePoint(toSdkCachePoint(it)) }
        block.obj("toolUse")?.let { tool ->
            return ContentBlock.fromToolUse(
                ToolUseBlock
                    .builder()
                    .toolUseId(tool.string("toolUseId"))
                    .name(tool.string("name"))
                    .input(tool.getValue("input").toDocument())
                    .build(),
            )
        }
        block.obj("toolResult")?.let { tool ->
            return ContentBlock.fromToolResult(
                ToolResultBlock
                    .builder()
                    .toolUseId(tool.string("toolUseId"))
                    .content(tool.getValue("content").jsonArray.map(::toSdkToolResultContent))
                    .status(ToolResultStatus.fromValue(tool.string("status")))
                    .build(),
            )
        }
        block.obj("reasoningContent")?.obj("reasoningText")?.let { reasoning ->
            return ContentBlock.fromReasoningContent(
                ReasoningContentBlock.fromReasoningText(
                    ReasoningTextBlock
                        .builder()
                        .text(reasoning.string("text"))
                        .apply { reasoning.string("signature")?.let(::signature) }
                        .build(),
                ),
            )
        }
        error("Unsupported Bedrock content block: $block")
    }

    private fun toSdkToolResultContent(raw: JsonElement): ToolResultContentBlock {
        val block = raw.jsonObject
        block.string("text")?.let { return ToolResultContentBlock.fromText(it) }
        block.obj("image")?.let { return ToolResultContentBlock.fromImage(toSdkImageBlock(it)) }
        block["json"]?.let { return ToolResultContentBlock.fromJson(it.toDocument()) }
        error("Unsupported Bedrock tool result block: $block")
    }

    private fun toSdkImageBlock(raw: JsonObject): ImageBlock =
        ImageBlock
            .builder()
            .format(ImageFormat.fromValue(raw.string("format")))
            .source(
                ImageSource.fromBytes(
                    SdkBytes.fromByteArray(
                        Base64.getDecoder().decode(raw.getValue("source").jsonObject.string("bytes")),
                    ),
                ),
            ).build()

    private fun toSdkCachePoint(raw: JsonObject): CachePointBlock =
        CachePointBlock
            .builder()
            .type(raw.string("type"))
            .apply { raw.string("ttl")?.let(::ttl) }
            .build()

    private fun toSdkSystemBlock(raw: JsonElement): SystemContentBlock {
        val block = raw.jsonObject
        block.string("text")?.let { return SystemContentBlock.fromText(it) }
        block.obj("cachePoint")?.let {
            return SystemContentBlock.fromCachePoint(toSdkCachePoint(it))
        }
        error("Unsupported Bedrock system block: $block")
    }

    private fun toSdkToolConfiguration(raw: JsonObject): ToolConfiguration {
        val tools =
            raw.getValue("tools").jsonArray.map { entry ->
                val spec = entry.jsonObject.getValue("toolSpec").jsonObject
                Tool.fromToolSpec(
                    ToolSpecification
                        .builder()
                        .name(spec.string("name"))
                        .description(spec.string("description"))
                        .inputSchema(
                            ToolInputSchema.fromJson(
                                spec.getValue("inputSchema").jsonObject.getValue("json").toDocument(),
                            ),
                        ).apply {
                            spec["strict"]?.jsonPrimitive?.booleanOrNull?.let(::strict)
                        }.build(),
                )
            }
        return ToolConfiguration
            .builder()
            .tools(tools)
            .apply {
                raw.obj("toolChoice")?.let { toolChoice(toSdkToolChoice(it)) }
            }.build()
    }

    private fun toSdkToolChoice(raw: JsonObject): ToolChoice =
        when {
            "auto" in raw -> ToolChoice.fromAuto(AutoToolChoice.builder().build())
            "any" in raw -> ToolChoice.fromAny(AnyToolChoice.builder().build())
            else ->
                ToolChoice.fromTool(
                    SpecificToolChoice
                        .builder()
                        .name(raw.getValue("tool").jsonObject.string("name"))
                        .build(),
                )
    }
}

private class BedrockTransportFailure(
    val source: Throwable,
    val responseRequestId: String?,
) : RuntimeException(source.message, source)

private fun JsonElement.toDocument(): Document =
    when (this) {
        JsonNull -> Document.fromNull()
        is JsonArray -> Document.fromList(map(JsonElement::toDocument))
        is JsonObject -> Document.fromMap(mapValues { it.value.toDocument() })
        is JsonPrimitive ->
            when {
                isString -> Document.fromString(content)
                booleanOrNull != null -> Document.fromBoolean(requireNotNull(booleanOrNull))
                else -> Document.fromNumber(content)
            }
    }

private fun resolveBedrockProxyUrl(
    targetUrl: String,
    options: StreamOptions,
    environment: (String) -> String?,
): String? {
    fun env(name: String): String? =
        options.env[name]?.takeIf(String::isNotBlank)
            ?: environment(name)?.takeIf(String::isNotBlank)

    val target = runCatching { URI(targetUrl) }.getOrNull() ?: return null
    if (bedrockNoProxyMatches(target, env("NO_PROXY") ?: env("no_proxy"))) {
        return null
    }
    return when (target.scheme?.lowercase()) {
        "https" ->
            env("HTTPS_PROXY")
                ?: env("https_proxy")
                ?: env("HTTP_PROXY")
                ?: env("http_proxy")
                ?: env("ALL_PROXY")
                ?: env("all_proxy")

        "http" ->
            env("HTTP_PROXY")
                ?: env("http_proxy")
                ?: env("ALL_PROXY")
                ?: env("all_proxy")

        else -> null
    }
}

private fun clampBedrockMaxTokensToContext(
    model: Model,
    context: Context,
    maxTokens: Int,
): Int {
    if (model.contextWindow <= 0) {
        return max(1, maxTokens)
    }
    val available = model.contextWindow - estimateBedrockContextTokens(context) - BEDROCK_CONTEXT_SAFETY_TOKENS
    return min(maxTokens, max(1, available))
}

private fun estimateBedrockContextTokens(context: Context): Int {
    val messages = context.messages
    var latestPrefixTimestamp = Long.MIN_VALUE
    var usageIndex: Int? = null
    var usageTokens = 0
    messages.forEachIndexed { index, message ->
        if (message is AssistantMessage) {
            val total =
                message.usage.totalTokens.takeIf { it != 0 }
                    ?: (
                        message.usage.input +
                            message.usage.output +
                            message.usage.cacheRead +
                            message.usage.cacheWrite
                    )
            if (
                message.timestamp >= latestPrefixTimestamp &&
                message.stopReason != StopReason.ABORTED &&
                message.stopReason != StopReason.ERROR &&
                total > 0
            ) {
                usageIndex = index
                usageTokens = total
            }
        }
        latestPrefixTimestamp = max(latestPrefixTimestamp, message.timestamp)
    }

    if (usageIndex != null) {
        val trailing = messages.drop(requireNotNull(usageIndex) + 1)
        val trailingTokens = trailing.sumOf(::estimateBedrockMessageTokens)
        val addedNames =
            trailing.filterIsInstance<ToolResultMessage>().flatMap { it.addedToolNames.orEmpty() }.toSet()
        val addedToolTokens =
            estimateBedrockToolTokens(context.tools.filter { it.name in addedNames })
        return usageTokens + trailingTokens + addedToolTokens
    }

    return messages.sumOf(::estimateBedrockMessageTokens) +
        context.systemPrompt.orEmpty().bedrockEstimatedTokens() +
        estimateBedrockToolTokens(context.tools)
}

private fun estimateBedrockMessageTokens(message: PiMessage): Int =
    when (message) {
        is UserMessage -> estimateBedrockMessageContentTokens(message.content)
        is ToolResultMessage -> estimateBedrockBlocksTokens(message.content)
        is AssistantMessage ->
            ceil(
                message.content.sumOf { block ->
                    when (block) {
                        is TextContent -> block.text.length
                        is ThinkingContent -> block.thinking.length
                        is ToolCall -> block.name.length + block.arguments.toString().length
                        is ImageContent -> ESTIMATED_BEDROCK_IMAGE_CHARS
                    }
                } / BEDROCK_CHARS_PER_TOKEN,
            ).toInt()

        is CustomMessage -> estimateBedrockMessageContentTokens(message.content)
        is CompactionSummaryMessage -> message.summary.bedrockEstimatedTokens()
        is BranchSummaryMessage -> message.summary.bedrockEstimatedTokens()
        is BashExecutionMessage -> "${message.command}\n${message.output}".bedrockEstimatedTokens()
    }

private fun estimateBedrockMessageContentTokens(content: MessageContent): Int =
    when (content) {
        is MessageContent.Text -> content.text.bedrockEstimatedTokens()
        is MessageContent.Blocks -> estimateBedrockBlocksTokens(content.blocks)
    }

private fun estimateBedrockBlocksTokens(blocks: List<PiContentBlock>): Int =
    ceil(
        blocks.sumOf { block ->
            when (block) {
                is TextContent -> block.text.length
                is ThinkingContent -> block.thinking.length
                is ToolCall -> block.name.length + block.arguments.toString().length
                is ImageContent -> ESTIMATED_BEDROCK_IMAGE_CHARS
            }
        } / BEDROCK_CHARS_PER_TOKEN,
    ).toInt()

private fun estimateBedrockToolTokens(tools: List<works.earendil.pi.ai.ToolDefinition>): Int {
    if (tools.isEmpty()) {
        return 0
    }
    val json =
        buildJsonArray {
            tools.forEach { tool ->
                add(
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                    },
                )
            }
        }.toString()
    return json.bedrockEstimatedTokens()
}

private fun String.bedrockEstimatedTokens(): Int = ceil(length / BEDROCK_CHARS_PER_TOKEN).toInt()

private fun bedrockProxyConfiguration(proxyUrl: String): ProxyConfiguration {
    val proxy = URI(proxyUrl)
    val defaultPort = if (proxy.scheme.equals("https", ignoreCase = true)) 443 else 80
    val userInfo = proxy.userInfo?.split(':', limit = 2)
    return ProxyConfiguration
        .builder()
        .scheme(proxy.scheme ?: "http")
        .host(requireNotNull(proxy.host) { "Bedrock proxy URL must include a host" })
        .port(proxy.port.takeIf { it >= 0 } ?: defaultPort)
        .useSystemPropertyValues(false)
        .useEnvironmentVariableValues(false)
        .apply {
            userInfo?.getOrNull(0)?.let(::username)
            userInfo?.getOrNull(1)?.let(::password)
        }.build()
}

private fun bedrockNoProxyMatches(
    target: URI,
    noProxy: String?,
): Boolean {
    val host = target.host?.lowercase() ?: return false
    val port = if (target.port >= 0) target.port else if (target.scheme == "https") 443 else 80
    return noProxy
        ?.split(',')
        ?.asSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.any { raw ->
            if (raw == "*") {
                true
            } else {
                val entry = raw.removePrefix("http://").removePrefix("https://").substringBefore('/')
                val entryHost = entry.substringBeforeLast(':', entry).removePrefix(".").lowercase()
                val entryPort = entry.substringAfterLast(':', "").toIntOrNull()
                (host == entryHost || host.endsWith(".$entryHost")) &&
                    (entryPort == null || entryPort == port)
            }
        } == true
}

private const val EMPTY_TEXT_PLACEHOLDER = "<empty>"
private const val USER_IMAGE_PLACEHOLDER = "(image omitted: model does not support images)"
private const val TOOL_IMAGE_PLACEHOLDER = "(tool image omitted: model does not support images)"
private const val BEDROCK_CONTEXT_SAFETY_TOKENS = 4_096
private const val MIN_BEDROCK_OUTPUT_TOKENS = 1_024
private const val BEDROCK_CHARS_PER_TOKEN = 4.0
private const val ESTIMATED_BEDROCK_IMAGE_CHARS = 4_800
private const val MAX_BEDROCK_DIAGNOSTIC_VALUE_CHARS = 200
private val BEDROCK_ERROR_PREFIXES =
    mapOf(
        "InternalServerException" to "Internal server error: ",
        "ModelStreamErrorException" to "Model stream error: ",
        "ValidationException" to "Validation error: ",
        "ThrottlingException" to "Throttling error: ",
        "ServiceUnavailableException" to "Service unavailable: ",
    )
