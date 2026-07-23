package works.earendil.pi.ai

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
enum class ThinkingLevel {
    @SerialName("minimal")
    MINIMAL,

    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,

    @SerialName("xhigh")
    XHIGH,

    @SerialName("max")
    MAX,
}

@Serializable
enum class ModelThinkingLevel {
    @SerialName("off")
    OFF,

    @SerialName("minimal")
    MINIMAL,

    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,

    @SerialName("xhigh")
    XHIGH,

    @SerialName("max")
    MAX,
}

@Serializable
enum class Transport {
    @SerialName("sse")
    SSE,

    @SerialName("websocket")
    WEBSOCKET,

    @SerialName("websocket-cached")
    WEBSOCKET_CACHED,

    @SerialName("auto")
    AUTO,
}

@Serializable
enum class CacheRetention {
    @SerialName("none")
    NONE,

    @SerialName("short")
    SHORT,

    @SerialName("long")
    LONG,
}

@Serializable
data class ThinkingBudgets(
    val minimal: Int? = null,
    val low: Int? = null,
    val medium: Int? = null,
    val high: Int? = null,
)

@Serializable
data class Cost(
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cacheRead: Double = 0.0,
    val cacheWrite: Double = 0.0,
    val total: Double = 0.0,
)

@Serializable
data class Usage(
    val input: Int = 0,
    val output: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0,
    val cacheWrite1h: Int? = null,
    val reasoning: Int? = null,
    val totalTokens: Int = 0,
    val cost: Cost = Cost(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ContentBlock

@Serializable
@SerialName("text")
data class TextContent(
    val text: String,
    val textSignature: String? = null,
) : ContentBlock

@Serializable
@SerialName("thinking")
data class ThinkingContent(
    val thinking: String,
    val thinkingSignature: String? = null,
    val redacted: Boolean? = null,
) : ContentBlock

@Serializable
@SerialName("image")
data class ImageContent(
    val data: String,
    val mimeType: String,
) : ContentBlock

@Serializable
@SerialName("toolCall")
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val thoughtSignature: String? = null,
) : ContentBlock

@Serializable(with = MessageContentSerializer::class)
sealed interface MessageContent {
    data class Text(
        val text: String,
    ) : MessageContent

    data class Blocks(
        val blocks: List<ContentBlock>,
    ) : MessageContent
}

object MessageContentSerializer : KSerializer<MessageContent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MessageContent")

    override fun serialize(
        encoder: Encoder,
        value: MessageContent,
    ) {
        require(encoder is JsonEncoder) { "MessageContent only supports JSON serialization" }
        val element =
            when (value) {
                is MessageContent.Text -> JsonPrimitive(value.text)
                is MessageContent.Blocks ->
                    JsonArray(
                        value.blocks.map { block ->
                            encoder.json.encodeToJsonElement(ContentBlock.serializer(), block)
                        },
                    )
            }
        encoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): MessageContent {
        require(decoder is JsonDecoder) { "MessageContent only supports JSON deserialization" }
        return when (val element = decoder.decodeJsonElement()) {
            is JsonPrimitive -> MessageContent.Text(element.content)
            is JsonArray ->
                MessageContent.Blocks(
                    element.map { block ->
                        decoder.json.decodeFromJsonElement(ContentBlock.serializer(), block)
                    },
                )

            else -> error("Message content must be a string or an array")
        }
    }
}

@Serializable
enum class StopReason {
    @SerialName("stop")
    STOP,

    @SerialName("length")
    LENGTH,

    @SerialName("toolUse")
    TOOL_USE,

    @SerialName("error")
    ERROR,

    @SerialName("aborted")
    ABORTED,
}

@Serializable
data class AssistantMessageDiagnostic(
    val source: String,
    val message: String,
    val error: DiagnosticErrorInfo? = null,
)

@Serializable
data class DiagnosticErrorInfo(
    val name: String? = null,
    val message: String,
    val code: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("role")
sealed interface Message {
    val timestamp: Long
}

@Serializable
@SerialName("user")
data class UserMessage(
    val content: MessageContent,
    override val timestamp: Long = System.currentTimeMillis(),
) : Message {
    constructor(
        content: String,
        timestamp: Long = System.currentTimeMillis(),
    ) : this(MessageContent.Text(content), timestamp)

    constructor(
        content: List<ContentBlock>,
        timestamp: Long = System.currentTimeMillis(),
    ) : this(MessageContent.Blocks(content), timestamp)
}

@Serializable
@SerialName("assistant")
data class AssistantMessage(
    val content: List<ContentBlock>,
    val api: String,
    val provider: String,
    val model: String,
    val responseModel: String? = null,
    val responseId: String? = null,
    val diagnostics: List<AssistantMessageDiagnostic>? = null,
    val usage: Usage = Usage(),
    val stopReason: StopReason = StopReason.STOP,
    val errorMessage: String? = null,
    override val timestamp: Long = System.currentTimeMillis(),
) : Message

@Serializable
@SerialName("toolResult")
data class ToolResultMessage(
    val toolCallId: String,
    val toolName: String,
    val content: List<ContentBlock>,
    val details: JsonElement? = null,
    val usage: Usage? = null,
    val addedToolNames: List<String>? = null,
    val isError: Boolean,
    override val timestamp: Long = System.currentTimeMillis(),
) : Message

@Serializable
@SerialName("custom")
data class CustomMessage(
    val customType: String,
    val content: MessageContent,
    val display: Boolean,
    val details: JsonElement? = null,
    override val timestamp: Long = System.currentTimeMillis(),
) : Message

@Serializable
@SerialName("compactionSummary")
data class CompactionSummaryMessage(
    val summary: String,
    val tokensBefore: Int,
    override val timestamp: Long = System.currentTimeMillis(),
) : Message

@Serializable
@SerialName("branchSummary")
data class BranchSummaryMessage(
    val summary: String,
    val fromId: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : Message

@Serializable
@SerialName("bashExecution")
data class BashExecutionMessage(
    val command: String,
    val output: String,
    val exitCode: Int? = null,
    val cancelled: Boolean = false,
    val truncated: Boolean = false,
    val fullOutputPath: String? = null,
    val excludeFromContext: Boolean? = null,
    override val timestamp: Long = System.currentTimeMillis(),
) : Message

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class Context(
    val systemPrompt: String? = null,
    val messages: MutableList<Message> = mutableListOf(),
    val tools: List<ToolDefinition> = emptyList(),
)

@Serializable
data class ModelCostTier(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
    val inputTokensAbove: Int,
)

@Serializable
data class ModelCost(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
    val tiers: List<ModelCostTier> = emptyList(),
)

@Serializable
enum class ModelInput {
    @SerialName("text")
    TEXT,

    @SerialName("image")
    IMAGE,
}

@Serializable
data class Model(
    val id: String,
    val name: String,
    val api: String,
    val provider: String,
    val baseUrl: String,
    val reasoning: Boolean,
    val thinkingLevelMap: Map<ModelThinkingLevel, String?> = emptyMap(),
    val input: List<ModelInput>,
    val cost: ModelCost,
    val contextWindow: Int,
    val maxTokens: Int,
    val headers: Map<String, String> = emptyMap(),
    val compat: JsonObject? = null,
)

data class ProviderResponse(
    val status: Int,
    val headers: Map<String, String>,
)

enum class BedrockThinkingDisplay {
    SUMMARIZED,
    OMITTED,
}

data class StreamOptions(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val apiKey: String? = null,
    val transport: Transport = Transport.AUTO,
    val cacheRetention: CacheRetention = CacheRetention.SHORT,
    val sessionId: String? = null,
    val headers: Map<String, String?> = emptyMap(),
    val timeoutMs: Long? = null,
    val websocketConnectTimeoutMs: Long? = null,
    val maxRetries: Int? = null,
    val maxRetryDelayMs: Long? = null,
    val metadata: JsonObject? = null,
    val env: Map<String, String> = emptyMap(),
    val reasoning: ThinkingLevel? = null,
    val reasoningEffort: String? = null,
    val reasoningSummary: String? = null,
    val promptMode: String? = null,
    val toolChoice: JsonElement? = null,
    val thinkingBudgets: ThinkingBudgets? = null,
    val azureApiVersion: String? = null,
    val azureResourceName: String? = null,
    val azureBaseUrl: String? = null,
    val azureDeploymentName: String? = null,
    val project: String? = null,
    val location: String? = null,
    val region: String? = null,
    val profile: String? = null,
    val interleavedThinking: Boolean? = null,
    val thinkingDisplay: BedrockThinkingDisplay? = null,
    val requestMetadata: Map<String, String>? = null,
    val bearerToken: String? = null,
)

data class SimpleStreamOptions(
    val stream: StreamOptions = StreamOptions(),
    val reasoning: ThinkingLevel? = null,
    val thinkingBudgets: ThinkingBudgets? = null,
)

fun contentText(
    content: List<ContentBlock>,
    separator: String = "\n",
): String = content.filterIsInstance<TextContent>().joinToString(separator) { it.text }

fun contentText(content: String): String = content

fun contentText(
    content: MessageContent,
    separator: String = "\n",
): String =
    when (content) {
        is MessageContent.Text -> content.text
        is MessageContent.Blocks -> contentText(content.blocks, separator)
    }
