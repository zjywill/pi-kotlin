package works.earendil.pi.ai

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface AssistantMessageEvent {
    val finalMessage: AssistantMessage?
        get() = null
}

@Serializable
@SerialName("start")
data class AssistantStart(
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
@SerialName("text_start")
data class TextStart(
    val contentIndex: Int,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
@SerialName("text_delta")
data class TextDelta(
    val contentIndex: Int,
    val delta: String,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
@SerialName("text_end")
data class TextEnd(
    val contentIndex: Int,
    val content: String,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
@SerialName("thinking_start")
data class ThinkingStart(
    val contentIndex: Int,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
@SerialName("thinking_delta")
data class ThinkingDelta(
    val contentIndex: Int,
    val delta: String,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
@SerialName("thinking_end")
data class ThinkingEnd(
    val contentIndex: Int,
    val content: String,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
@SerialName("toolcall_start")
data class ToolCallStart(
    val contentIndex: Int,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
@SerialName("toolcall_delta")
data class ToolCallDelta(
    val contentIndex: Int,
    val delta: String,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
@SerialName("toolcall_end")
data class ToolCallEnd(
    val contentIndex: Int,
    val toolCall: ToolCall,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
@SerialName("done")
data class AssistantDone(
    val reason: StopReason,
    val message: AssistantMessage,
) : AssistantMessageEvent {
    override val finalMessage: AssistantMessage = message
}

@Serializable
@SerialName("error")
data class AssistantError(
    val reason: StopReason,
    val error: AssistantMessage,
) : AssistantMessageEvent {
    override val finalMessage: AssistantMessage = error
}
