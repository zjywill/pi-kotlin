package works.earendil.pi.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.AssistantError
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.AssistantStart
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.validateToolArguments

suspend fun runAgentLoop(
    prompts: List<Message>,
    context: AgentContext,
    config: AgentLoopConfig,
    emit: AgentEventSink = AgentEventSink {},
): List<Message> {
    val newMessages = prompts.toMutableList()
    var currentContext =
        context.copy(
            messages = (context.messages + prompts).toMutableList(),
        )

    emit.emit(AgentEvent.AgentStart)
    emit.emit(AgentEvent.TurnStart)
    prompts.forEach { prompt ->
        emit.emit(AgentEvent.MessageStart(prompt))
        emit.emit(AgentEvent.MessageEnd(prompt))
    }

    var currentConfig = config
    var firstTurn = true
    var pendingMessages = currentConfig.getSteeringMessages?.invoke().orEmpty()

    while (true) {
        var hasMoreToolCalls = true
        while (hasMoreToolCalls || pendingMessages.isNotEmpty()) {
            if (!firstTurn) {
                emit.emit(AgentEvent.TurnStart)
            } else {
                firstTurn = false
            }

            if (pendingMessages.isNotEmpty()) {
                pendingMessages.forEach { message ->
                    emit.emit(AgentEvent.MessageStart(message))
                    emit.emit(AgentEvent.MessageEnd(message))
                    currentContext.messages += message
                    newMessages += message
                }
                pendingMessages = emptyList()
            }

            val message =
                try {
                    streamAssistantResponse(currentContext, currentConfig, emit)
                } catch (error: CancellationException) {
                    emitAbortedStreamResponse(currentContext, currentConfig, emit, error)
                }
            newMessages += message

            if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) {
                withContext(NonCancellable) {
                    emit.emit(AgentEvent.TurnEnd(message, emptyList()))
                    emit.emit(AgentEvent.AgentEnd(newMessages))
                }
                return newMessages
            }

            val toolCalls = message.content.filterIsInstance<ToolCall>()
            val toolResults =
                when {
                    toolCalls.isEmpty() -> emptyList()
                    message.stopReason == StopReason.LENGTH ->
                        failTruncatedToolCalls(toolCalls, emit)

                    else ->
                        executeToolCalls(
                            currentContext,
                            message,
                            toolCalls,
                            currentConfig,
                            emit,
                        )
                }
            hasMoreToolCalls = toolResults.isNotEmpty() && !toolResults.all { it.terminate }
            toolResults.forEach { finalized ->
                currentContext.messages += finalized.message
                newMessages += finalized.message
            }

            emit.emit(AgentEvent.TurnEnd(message, toolResults.map(FinalizedToolCall::message)))

            val turnContext =
                ShouldStopAfterTurnContext(
                    message = message,
                    toolResults = toolResults.map(FinalizedToolCall::message),
                    context = currentContext,
                    newMessages = newMessages,
                )
            currentConfig.prepareNextTurn?.invoke(turnContext)?.let { update ->
                currentContext = update.context ?: currentContext
                currentConfig =
                    currentConfig.copy(
                        model = update.model ?: currentConfig.model,
                    )
            }
            if (currentConfig.shouldStopAfterTurn?.invoke(turnContext) == true) {
                emit.emit(AgentEvent.AgentEnd(newMessages))
                return newMessages
            }

            pendingMessages = currentConfig.getSteeringMessages?.invoke().orEmpty()
        }

        val followUps = currentConfig.getFollowUpMessages?.invoke().orEmpty()
        if (followUps.isEmpty()) {
            break
        }
        pendingMessages = followUps
    }

    emit.emit(AgentEvent.AgentEnd(newMessages))
    return newMessages
}

private suspend fun streamAssistantResponse(
    context: AgentContext,
    config: AgentLoopConfig,
    emit: AgentEventSink,
): AssistantMessage {
    val transformed = config.transformContext?.invoke(context.messages) ?: context.messages
    val llmMessages = config.convertToLlm(transformed)
    val llmContext =
        Context(
            systemPrompt = context.systemPrompt,
            messages = llmMessages.toMutableList(),
            tools =
                context.tools.map { tool ->
                    ToolDefinition(tool.name, tool.description, tool.parameters)
                },
        )
    val stream =
        try {
            config.streamFunction.stream(config.model, llmContext, config.streamOptions)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return emitThrownStreamError(config.model, error, context, emit)
        }

    var addedPartial = false
    var finalMessage: AssistantMessage? = null
    stream.events.collect { event ->
        when (event) {
            is AssistantStart -> {
                context.messages += event.partial
                addedPartial = true
                emit.emit(AgentEvent.MessageStart(event.partial))
            }

            is AssistantDone -> finalMessage = event.message
            is AssistantError -> finalMessage = event.error
            else -> {
                val partial =
                    when (event) {
                        is works.earendil.pi.ai.TextStart -> event.partial
                        is works.earendil.pi.ai.TextDelta -> event.partial
                        is works.earendil.pi.ai.TextEnd -> event.partial
                        is works.earendil.pi.ai.ThinkingStart -> event.partial
                        is works.earendil.pi.ai.ThinkingDelta -> event.partial
                        is works.earendil.pi.ai.ThinkingEnd -> event.partial
                        is works.earendil.pi.ai.ToolCallStart -> event.partial
                        is works.earendil.pi.ai.ToolCallDelta -> event.partial
                        is works.earendil.pi.ai.ToolCallEnd -> event.partial
                        is AssistantStart,
                        is AssistantDone,
                        is AssistantError,
                        -> error("Handled above")
                    }
                if (addedPartial) {
                    context.messages[context.messages.lastIndex] = partial
                }
                emit.emit(AgentEvent.MessageUpdate(partial, event))
            }
        }
    }

    val message = finalMessage ?: stream.result()
    if (addedPartial) {
        context.messages[context.messages.lastIndex] = message
    } else {
        context.messages += message
        emit.emit(AgentEvent.MessageStart(message))
    }
    emit.emit(AgentEvent.MessageEnd(message))
    return message
}

private suspend fun emitAbortedStreamResponse(
    context: AgentContext,
    config: AgentLoopConfig,
    emit: AgentEventSink,
    error: CancellationException,
): AssistantMessage =
    withContext(NonCancellable) {
        val partialIndex =
            context.messages.lastIndex.takeIf { index ->
                index >= 0 &&
                    (context.messages[index] as? AssistantMessage)?.stopReason == StopReason.PENDING
            }
        val partial = partialIndex?.let(context.messages::get) as? AssistantMessage
        val message =
            partial?.copy(
                stopReason = StopReason.ABORTED,
                errorMessage = error.message ?: "Operation aborted",
            )
                ?: AssistantMessage(
                    content = emptyList(),
                    api = config.model.api,
                    provider = config.model.provider,
                    model = config.model.id,
                    stopReason = StopReason.ABORTED,
                    errorMessage = error.message ?: "Operation aborted",
                )
        if (partialIndex == null) {
            context.messages += message
            emit.emit(AgentEvent.MessageStart(message))
        } else {
            context.messages[partialIndex] = message
        }
        emit.emit(AgentEvent.MessageEnd(message))
        message
    }

private suspend fun emitThrownStreamError(
    model: Model,
    error: Throwable,
    context: AgentContext,
    emit: AgentEventSink,
): AssistantMessage {
    val message =
        AssistantMessage(
            content = emptyList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            stopReason = StopReason.ERROR,
            errorMessage = error.message ?: error::class.simpleName.orEmpty(),
        )
    context.messages += message
    emit.emit(AgentEvent.MessageStart(message))
    emit.emit(AgentEvent.MessageEnd(message))
    return message
}

private data class FinalizedToolCall(
    val toolCall: ToolCall,
    val result: AgentToolResult,
    val isError: Boolean,
    val message: ToolResultMessage,
) {
    val terminate: Boolean
        get() = result.terminate
}

private suspend fun failTruncatedToolCalls(
    toolCalls: List<ToolCall>,
    emit: AgentEventSink,
): List<FinalizedToolCall> =
    toolCalls.map { toolCall ->
        emit.emit(AgentEvent.ToolExecutionStart(toolCall.id, toolCall.name, toolCall.arguments))
        val result =
            errorToolResult(
                "Tool call \"${toolCall.name}\" was not executed: the response hit the output token limit, " +
                    "so its arguments may be truncated. Re-issue the tool call with complete arguments.",
            )
        finalizeAndEmit(toolCall, result, isError = true, emit = emit)
    }

private suspend fun executeToolCalls(
    context: AgentContext,
    assistantMessage: AssistantMessage,
    toolCalls: List<ToolCall>,
    config: AgentLoopConfig,
    emit: AgentEventSink,
): List<FinalizedToolCall> {
    val sequential =
        config.toolExecution == ToolExecutionMode.SEQUENTIAL ||
            toolCalls.any { call ->
                context.tools.firstOrNull { it.name == call.name }?.executionMode == ToolExecutionMode.SEQUENTIAL
            }
    return if (sequential) {
        toolCalls.map { toolCall ->
            executeOneToolCall(context, assistantMessage, toolCall, config, emit)
        }
    } else {
        coroutineScope {
            toolCalls
                .map { toolCall ->
                    emit.emit(AgentEvent.ToolExecutionStart(toolCall.id, toolCall.name, toolCall.arguments))
                    async {
                        executeOneToolCall(
                            context,
                            assistantMessage,
                            toolCall,
                            config,
                            emit,
                            startAlreadyEmitted = true,
                        )
                    }
                }.awaitAll()
        }
    }
}

private suspend fun executeOneToolCall(
    context: AgentContext,
    assistantMessage: AssistantMessage,
    toolCall: ToolCall,
    config: AgentLoopConfig,
    emit: AgentEventSink,
    startAlreadyEmitted: Boolean = false,
): FinalizedToolCall {
    if (!startAlreadyEmitted) {
        emit.emit(AgentEvent.ToolExecutionStart(toolCall.id, toolCall.name, toolCall.arguments))
    }
    val tool =
        context.tools.firstOrNull { it.name == toolCall.name }
            ?: return finalizeAndEmit(
                toolCall,
                errorToolResult("Tool ${toolCall.name} not found"),
                isError = true,
                emit = emit,
            )

    val preparedCall =
        try {
            toolCall.copy(arguments = tool.prepareArguments(toolCall.arguments))
        } catch (error: Throwable) {
            return finalizeAndEmit(
                toolCall,
                errorToolResult(error.message ?: error::class.simpleName.orEmpty()),
                isError = true,
                emit = emit,
            )
        }
    val args =
        try {
            validateToolArguments(
                ToolDefinition(tool.name, tool.description, tool.parameters),
                preparedCall,
            )
        } catch (error: Throwable) {
            return finalizeAndEmit(
                toolCall,
                errorToolResult(error.message ?: error::class.simpleName.orEmpty()),
                isError = true,
                emit = emit,
            )
        }

    val beforeResult =
        config.beforeToolCall?.invoke(
            BeforeToolCallContext(
                assistantMessage = assistantMessage,
                toolCall = toolCall,
                args = args,
                context = context,
            ),
        )
    if (beforeResult?.block == true) {
        return finalizeAndEmit(
            toolCall,
            errorToolResult(beforeResult.reason ?: "Tool execution was blocked"),
            isError = true,
            emit = emit,
        )
    }

    var isError = false
    var result =
        try {
            tool.execute(
                toolCallId = toolCall.id,
                params = args,
                onUpdate =
                    AgentToolUpdateCallback { partial ->
                        emit.emit(
                            AgentEvent.ToolExecutionUpdate(
                                toolCallId = toolCall.id,
                                toolName = toolCall.name,
                                args = toolCall.arguments,
                                partialResult = partial,
                            ),
                        )
                    },
            )
        } catch (error: Throwable) {
            isError = true
            errorToolResult(error.message ?: error::class.simpleName.orEmpty())
        }

    try {
        config.afterToolCall?.invoke(
            AfterToolCallContext(
                assistantMessage = assistantMessage,
                toolCall = toolCall,
                args = args,
                result = result,
                isError = isError,
                context = context,
            ),
        )?.let { patch ->
            result =
                result.copy(
                    content = patch.content ?: result.content,
                    details = patch.details ?: result.details,
                    usage = patch.usage ?: result.usage,
                    terminate = patch.terminate ?: result.terminate,
                )
            isError = patch.isError ?: isError
        }
    } catch (error: Throwable) {
        result = errorToolResult(error.message ?: error::class.simpleName.orEmpty())
        isError = true
    }

    return finalizeAndEmit(toolCall, result, isError, emit)
}

private suspend fun finalizeAndEmit(
    toolCall: ToolCall,
    result: AgentToolResult,
    isError: Boolean,
    emit: AgentEventSink,
): FinalizedToolCall {
    emit.emit(
        AgentEvent.ToolExecutionEnd(
            toolCallId = toolCall.id,
            toolName = toolCall.name,
            result = result,
            isError = isError,
        ),
    )
    val message =
        ToolResultMessage(
            toolCallId = toolCall.id,
            toolName = toolCall.name,
            content = result.content,
            details = result.details,
            usage = result.usage,
            addedToolNames = result.addedToolNames.takeIf(List<String>::isNotEmpty),
            isError = isError,
        )
    emit.emit(AgentEvent.MessageStart(message))
    emit.emit(AgentEvent.MessageEnd(message))
    return FinalizedToolCall(toolCall, result, isError, message)
}

private fun errorToolResult(message: String): AgentToolResult =
    AgentToolResult(
        content = listOf(TextContent(message)),
        details = buildJsonObject {},
    )
