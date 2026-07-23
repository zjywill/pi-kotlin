package works.earendil.pi.agent

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.AssistantMessageEvent
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StreamFunction
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.Usage

enum class AgentThinkingLevel {
    OFF,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX,
}

enum class ToolExecutionMode {
    SEQUENTIAL,
    PARALLEL,
}

enum class QueueMode {
    ALL,
    ONE_AT_A_TIME,
}

data class AgentToolResult(
    val content: List<ContentBlock>,
    val details: JsonElement = JsonObject(emptyMap()),
    val usage: Usage? = null,
    val addedToolNames: List<String> = emptyList(),
    val terminate: Boolean = false,
)

fun interface AgentToolUpdateCallback {
    suspend fun update(partialResult: AgentToolResult)
}

interface AgentTool {
    val name: String
    val label: String
    val description: String
    val parameters: JsonObject
    val executionMode: ToolExecutionMode?
        get() = null

    fun prepareArguments(arguments: JsonObject): JsonObject = arguments

    suspend fun execute(
        toolCallId: String,
        params: JsonObject,
        onUpdate: AgentToolUpdateCallback? = null,
    ): AgentToolResult
}

data class AgentContext(
    val systemPrompt: String,
    val messages: MutableList<Message> = mutableListOf(),
    val tools: List<AgentTool> = emptyList(),
) {
    fun toLlmContext(messages: List<Message> = this.messages): Context =
        Context(
            systemPrompt = systemPrompt,
            messages = messages.toMutableList(),
            tools =
                tools.map { tool ->
                    works.earendil.pi.ai.ToolDefinition(
                        name = tool.name,
                        description = tool.description,
                        parameters = tool.parameters,
                    )
                },
        )
}

data class BeforeToolCallContext(
    val assistantMessage: AssistantMessage,
    val toolCall: ToolCall,
    val args: JsonObject,
    val context: AgentContext,
)

data class BeforeToolCallResult(
    val block: Boolean = false,
    val reason: String? = null,
)

data class AfterToolCallContext(
    val assistantMessage: AssistantMessage,
    val toolCall: ToolCall,
    val args: JsonObject,
    val result: AgentToolResult,
    val isError: Boolean,
    val context: AgentContext,
)

data class AfterToolCallResult(
    val content: List<ContentBlock>? = null,
    val details: JsonElement? = null,
    val usage: Usage? = null,
    val isError: Boolean? = null,
    val terminate: Boolean? = null,
)

data class ShouldStopAfterTurnContext(
    val message: AssistantMessage,
    val toolResults: List<ToolResultMessage>,
    val context: AgentContext,
    val newMessages: List<Message>,
)

data class AgentLoopTurnUpdate(
    val context: AgentContext? = null,
    val model: Model? = null,
    val thinkingLevel: AgentThinkingLevel? = null,
)

data class AgentLoopConfig(
    val model: Model,
    val streamFunction: StreamFunction,
    val convertToLlm: suspend (List<Message>) -> List<Message> = { messages -> messages },
    val transformContext: (suspend (List<Message>) -> List<Message>)? = null,
    val shouldStopAfterTurn: (suspend (ShouldStopAfterTurnContext) -> Boolean)? = null,
    val prepareNextTurn: (suspend (ShouldStopAfterTurnContext) -> AgentLoopTurnUpdate?)? = null,
    val getSteeringMessages: (suspend () -> List<Message>)? = null,
    val getFollowUpMessages: (suspend () -> List<Message>)? = null,
    val beforeToolCall: (suspend (BeforeToolCallContext) -> BeforeToolCallResult?)? = null,
    val afterToolCall: (suspend (AfterToolCallContext) -> AfterToolCallResult?)? = null,
    val toolExecution: ToolExecutionMode = ToolExecutionMode.PARALLEL,
    val streamOptions: SimpleStreamOptions = SimpleStreamOptions(),
)

sealed interface AgentEvent {
    data object AgentStart : AgentEvent

    data class AgentEnd(
        val messages: List<Message>,
    ) : AgentEvent

    data object TurnStart : AgentEvent

    data class TurnEnd(
        val message: AssistantMessage,
        val toolResults: List<ToolResultMessage>,
    ) : AgentEvent

    data class MessageStart(
        val message: Message,
    ) : AgentEvent

    data class MessageUpdate(
        val message: AssistantMessage,
        val assistantMessageEvent: AssistantMessageEvent,
    ) : AgentEvent

    data class MessageEnd(
        val message: Message,
    ) : AgentEvent

    data class ToolExecutionStart(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
    ) : AgentEvent

    data class ToolExecutionUpdate(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
        val partialResult: AgentToolResult,
    ) : AgentEvent

    data class ToolExecutionEnd(
        val toolCallId: String,
        val toolName: String,
        val result: AgentToolResult,
        val isError: Boolean,
    ) : AgentEvent
}

fun interface AgentEventSink {
    suspend fun emit(event: AgentEvent)
}
