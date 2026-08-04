package works.earendil.pi.agent

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StreamFunction
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.UserMessage

data class AgentInitialState(
    val systemPrompt: String = "",
    val model: Model = UNKNOWN_MODEL,
    val thinkingLevel: AgentThinkingLevel = AgentThinkingLevel.OFF,
    val tools: List<AgentTool> = emptyList(),
    val messages: List<Message> = emptyList(),
)

class AgentState internal constructor(initial: AgentInitialState) {
    var systemPrompt: String = initial.systemPrompt
    var model: Model = initial.model
    var thinkingLevel: AgentThinkingLevel = initial.thinkingLevel
    var tools: List<AgentTool> = initial.tools.toList()
        set(value) {
            field = value.toList()
        }
    var messages: List<Message> = initial.messages.toList()
        set(value) {
            field = value.toList()
        }
    var isStreaming: Boolean = false
        internal set
    var streamingMessage: Message? = null
        internal set
    var pendingToolCalls: Set<String> = emptySet()
        internal set
    var errorMessage: String? = null
        internal set
}

data class AgentOptions(
    val streamFunction: StreamFunction,
    val initialState: AgentInitialState = AgentInitialState(),
    val convertToLlm: suspend (List<Message>) -> List<Message> = { it },
    val transformContext: (suspend (List<Message>) -> List<Message>)? = null,
    val beforeToolCall: (suspend (BeforeToolCallContext) -> BeforeToolCallResult?)? = null,
    val afterToolCall: (suspend (AfterToolCallContext) -> AfterToolCallResult?)? = null,
    val shouldStopAfterTurn: (suspend (ShouldStopAfterTurnContext) -> Boolean)? = null,
    val prepareNextTurn: (suspend (ShouldStopAfterTurnContext) -> AgentLoopTurnUpdate?)? = null,
    val steeringMode: QueueMode = QueueMode.ONE_AT_A_TIME,
    val followUpMode: QueueMode = QueueMode.ONE_AT_A_TIME,
    val toolExecution: ToolExecutionMode = ToolExecutionMode.PARALLEL,
    val streamOptions: SimpleStreamOptions = SimpleStreamOptions(),
)

class Agent(
    private val options: AgentOptions,
) {
    private val listeners = CopyOnWriteArrayList<suspend (AgentEvent) -> Unit>()
    private val steeringQueue = PendingMessageQueue(options.steeringMode)
    private val followUpQueue = PendingMessageQueue(options.followUpMode)
    private val running = AtomicBoolean(false)
    private var idle = CompletableDeferred(Unit)

    val state = AgentState(options.initialState)

    fun subscribe(listener: suspend (AgentEvent) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun steer(message: Message) {
        steeringQueue.enqueue(message)
    }

    fun followUp(message: Message) {
        followUpQueue.enqueue(message)
    }

    fun clearSteeringQueue() = steeringQueue.clear()

    fun clearFollowUpQueue() = followUpQueue.clear()

    fun clearAllQueues() {
        clearSteeringQueue()
        clearFollowUpQueue()
    }

    val steeringMode: QueueMode
        get() = steeringQueue.mode

    val followUpMode: QueueMode
        get() = followUpQueue.mode

    fun setSteeringMode(mode: QueueMode) {
        steeringQueue.mode = mode
    }

    fun setFollowUpMode(mode: QueueMode) {
        followUpQueue.mode = mode
    }

    fun hasQueuedMessages(): Boolean = steeringQueue.hasItems() || followUpQueue.hasItems()

    suspend fun waitForIdle() {
        idle.await()
    }

    fun reset() {
        check(!running.get()) { "Cannot reset while the agent is processing" }
        state.messages = emptyList()
        state.streamingMessage = null
        state.pendingToolCalls = emptySet()
        state.errorMessage = null
        clearAllQueues()
    }

    suspend fun prompt(
        text: String,
        images: List<ImageContent> = emptyList(),
    ) {
        val content = listOf(TextContent(text)) + images
        prompt(UserMessage(content))
    }

    suspend fun prompt(messages: List<Message>) {
        check(running.compareAndSet(false, true)) {
            "Agent is already processing a prompt. Use steer() or followUp() to queue messages, or wait for completion."
        }
        idle = CompletableDeferred()
        state.isStreaming = true
        state.errorMessage = null
        try {
            val context =
                AgentContext(
                    systemPrompt = state.systemPrompt,
                    messages = state.messages.toMutableList(),
                    tools = state.tools,
                )
            runAgentLoop(
                prompts = messages,
                context = context,
                config =
                    AgentLoopConfig(
                        model = state.model,
                        streamFunction = options.streamFunction,
                        convertToLlm = options.convertToLlm,
                        transformContext = options.transformContext,
                        beforeToolCall = options.beforeToolCall,
                        afterToolCall = options.afterToolCall,
                        shouldStopAfterTurn = options.shouldStopAfterTurn,
                        prepareNextTurn = options.prepareNextTurn,
                        getSteeringMessages = steeringQueue::drain,
                        getFollowUpMessages = followUpQueue::drain,
                        toolExecution = options.toolExecution,
                        streamOptions = options.streamOptions,
                    ),
                emit = AgentEventSink(::handleEvent),
            )
        } finally {
            state.isStreaming = false
            state.streamingMessage = null
            state.pendingToolCalls = emptySet()
            running.set(false)
            idle.complete(Unit)
        }
    }

    suspend fun prompt(message: Message) = prompt(listOf(message))

    private suspend fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.MessageStart -> {
                if (event.message is works.earendil.pi.ai.AssistantMessage) {
                    state.streamingMessage = event.message
                }
            }

            is AgentEvent.MessageUpdate -> state.streamingMessage = event.message
            is AgentEvent.MessageEnd -> {
                if (event.message is works.earendil.pi.ai.AssistantMessage) {
                    state.streamingMessage = null
                    state.errorMessage = event.message.errorMessage
                }
            }

            is AgentEvent.ToolExecutionStart ->
                state.pendingToolCalls = state.pendingToolCalls + event.toolCallId

            is AgentEvent.ToolExecutionEnd ->
                state.pendingToolCalls = state.pendingToolCalls - event.toolCallId

            is AgentEvent.AgentEnd -> state.messages = state.messages + event.messages
            AgentEvent.AgentStart,
            AgentEvent.TurnStart,
            is AgentEvent.TurnEnd,
            is AgentEvent.ToolExecutionUpdate,
            -> Unit
        }
        listeners.forEach { listener -> listener(event) }
    }
}

private class PendingMessageQueue(
    initialMode: QueueMode,
) {
    private val messages = ArrayDeque<Message>()
    @Volatile
    var mode: QueueMode = initialMode

    @Synchronized
    fun enqueue(message: Message) {
        messages.addLast(message)
    }

    @Synchronized
    fun hasItems(): Boolean = messages.isNotEmpty()

    @Synchronized
    fun drain(): List<Message> =
        when (mode) {
            QueueMode.ALL -> buildList {
                while (messages.isNotEmpty()) {
                    add(messages.removeFirst())
                }
            }

            QueueMode.ONE_AT_A_TIME ->
                if (messages.isEmpty()) {
                    emptyList()
                } else {
                    listOf(messages.removeFirst())
                }
        }

    @Synchronized
    fun clear() {
        messages.clear()
    }
}

private val UNKNOWN_MODEL =
    Model(
        id = "unknown",
        name = "unknown",
        api = "unknown",
        provider = "unknown",
        baseUrl = "",
        reasoning = false,
        input = emptyList(),
        cost = works.earendil.pi.ai.ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 0,
        maxTokens = 0,
    )
