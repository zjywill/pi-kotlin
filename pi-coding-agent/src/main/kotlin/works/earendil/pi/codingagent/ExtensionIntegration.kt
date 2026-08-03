package works.earendil.pi.codingagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.agent.AfterToolCallContext
import works.earendil.pi.agent.AfterToolCallResult
import works.earendil.pi.agent.Agent
import works.earendil.pi.agent.AgentEvent
import works.earendil.pi.agent.AgentThinkingLevel
import works.earendil.pi.agent.BeforeToolCallContext
import works.earendil.pi.agent.BeforeToolCallResult
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.CustomMessage
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.codingagent.session.SessionManager

internal data class ExtensionBeforeAgentStartResult(
    val systemPrompt: String?,
    val messages: List<JsonObject>,
)

internal suspend fun emitExtensionBeforeToolCall(
    host: ExtensionHost?,
    context: () -> JsonObject,
    onActions: suspend (List<ExtensionAction>) -> Unit,
    call: BeforeToolCallContext,
): BeforeToolCallResult? {
    host ?: return null
    val invocation =
        withContext(Dispatchers.IO) {
            host.emit(
                event =
                    buildJsonObject {
                        put("type", "tool_call")
                        put("toolName", call.toolCall.name)
                        put("toolCallId", call.toolCall.id)
                        put("input", call.args)
                    },
                context = context(),
            )
        }
    onActions(invocation.actions)
    val result = invocation.result as? JsonObject ?: return null
    return BeforeToolCallResult(
        block = result["block"]?.jsonPrimitive?.booleanOrNull == true,
        reason = result.stringValue("reason"),
    )
}

internal suspend fun emitExtensionAfterToolCall(
    host: ExtensionHost?,
    context: () -> JsonObject,
    onActions: suspend (List<ExtensionAction>) -> Unit,
    call: AfterToolCallContext,
): AfterToolCallResult? {
    host ?: return null
    val invocation =
        withContext(Dispatchers.IO) {
            host.emit(
                event =
                    buildJsonObject {
                        put("type", "tool_result")
                        put("toolName", call.toolCall.name)
                        put("toolCallId", call.toolCall.id)
                        put("input", call.args)
                        put(
                            "content",
                            JsonArray(
                                call.result.content.map {
                                    protocolJson.encodeToJsonElement(ContentBlock.serializer(), it)
                                },
                            ),
                        )
                        put("details", call.result.details)
                        call.result.usage?.let {
                            put("usage", protocolJson.encodeToJsonElement(Usage.serializer(), it))
                        }
                        put("isError", call.isError)
                        if (call.result.terminate) {
                            put("terminate", true)
                        }
                    },
                context = context(),
            )
        }
    onActions(invocation.actions)
    val result = invocation.result as? JsonObject ?: return null
    return AfterToolCallResult(
        content =
            result["content"]
                ?.jsonArray
                ?.map { protocolJson.decodeFromJsonElement(ContentBlock.serializer(), it) },
        details = result["details"],
        usage = result["usage"]?.let { protocolJson.decodeFromJsonElement(Usage.serializer(), it) },
        isError = result["isError"]?.jsonPrimitive?.booleanOrNull,
        terminate = result["terminate"]?.jsonPrimitive?.booleanOrNull,
    )
}

internal suspend fun emitExtensionAgentEvent(
    host: ExtensionHost?,
    event: AgentEvent,
    context: () -> JsonObject,
    onActions: suspend (List<ExtensionAction>) -> Unit,
) {
    host ?: return
    val invocation =
        withContext(Dispatchers.IO) {
            host.emit(encodeAgentEvent(event), context())
        }
    onActions(invocation.actions)
}

internal suspend fun emitExtensionEvent(
    host: ExtensionHost?,
    event: JsonObject,
    context: () -> JsonObject,
    onActions: suspend (List<ExtensionAction>) -> Unit,
): JsonElement? {
    host ?: return null
    val invocation =
        withContext(Dispatchers.IO) {
            host.emit(event, context())
        }
    onActions(invocation.actions)
    return invocation.result
}

internal suspend fun emitExtensionBeforeAgentStart(
    host: ExtensionHost?,
    prompt: String,
    systemPrompt: String,
    context: () -> JsonObject,
    onActions: suspend (List<ExtensionAction>) -> Unit,
): ExtensionBeforeAgentStartResult? {
    val result =
        emitExtensionEvent(
            host = host,
            event =
                buildJsonObject {
                    put("type", "before_agent_start")
                    put("prompt", prompt)
                    put("systemPrompt", systemPrompt)
                    put("systemPromptOptions", buildJsonObject { put("cwd", context().stringValue("cwd").orEmpty()) })
                },
            context = context,
            onActions = onActions,
        ) as? JsonObject ?: return null
    return ExtensionBeforeAgentStartResult(
        systemPrompt = result.stringValue("systemPrompt"),
        messages = result["messages"]?.jsonArray.orEmpty().map(JsonElement::jsonObject),
    )
}

internal fun appendExtensionMessage(
    sessionManager: SessionManager,
    value: JsonElement?,
): String? {
    val message = extensionCustomMessage(value) ?: return null
    return sessionManager.appendCustomMessageEntry(
        customType = message.customType,
        content = message.content,
        display = message.display,
        details = message.details,
    )
}

internal fun extensionCustomMessage(value: JsonElement?): CustomMessage? {
    val message = value as? JsonObject ?: return null
    val customType = message.stringValue("customType") ?: return null
    val content =
        when (val raw = message["content"]) {
            is JsonPrimitive -> MessageContent.Text(raw.content)
            is JsonArray ->
                MessageContent.Blocks(
                    raw.map { protocolJson.decodeFromJsonElement(ContentBlock.serializer(), it) },
                )

            else -> MessageContent.Text("")
        }
    return CustomMessage(
        customType = customType,
        content = content,
        display = message["display"]?.jsonPrimitive?.booleanOrNull ?: true,
        details = message["details"],
    )
}

internal fun extensionUserMessage(action: JsonObject): UserMessage =
    when (val content = action["content"]) {
        is JsonPrimitive -> UserMessage(content.content)
        is JsonArray ->
            UserMessage(
                content.map {
                    protocolJson.decodeFromJsonElement(ContentBlock.serializer(), it)
                },
            )

        else -> UserMessage("")
    }

internal fun appendAgentMessage(
    sessionManager: SessionManager,
    message: Message,
): String =
    if (message is CustomMessage) {
        sessionManager.appendCustomMessageEntry(
            customType = message.customType,
            content = message.content,
            display = message.display,
            details = message.details,
        )
    } else {
        sessionManager.appendMessage(message)
    }

internal fun queueExtensionUserMessage(
    agent: Agent?,
    action: JsonObject,
): Boolean {
    if (agent?.state?.isStreaming != true) {
        return false
    }
    val message = extensionUserMessage(action)
    when (action["options"]?.jsonObject?.stringValue("deliverAs")) {
        "followUp" -> agent.followUp(message)
        else -> agent.steer(message)
    }
    return true
}

internal fun AgentThinkingLevel.toProtocolValue(): String = name.lowercase().replace('_', '-')

internal fun AgentThinkingLevel.toProviderThinkingLevel(): ThinkingLevel? =
    when (this) {
        AgentThinkingLevel.OFF -> null
        AgentThinkingLevel.MINIMAL -> ThinkingLevel.MINIMAL
        AgentThinkingLevel.LOW -> ThinkingLevel.LOW
        AgentThinkingLevel.MEDIUM -> ThinkingLevel.MEDIUM
        AgentThinkingLevel.HIGH -> ThinkingLevel.HIGH
        AgentThinkingLevel.XHIGH -> ThinkingLevel.XHIGH
        AgentThinkingLevel.MAX -> ThinkingLevel.MAX
    }

internal fun String.toCoreThinkingLevel(): AgentThinkingLevel? =
    when (this) {
        "off" -> AgentThinkingLevel.OFF
        "minimal" -> AgentThinkingLevel.MINIMAL
        "low" -> AgentThinkingLevel.LOW
        "medium" -> AgentThinkingLevel.MEDIUM
        "high" -> AgentThinkingLevel.HIGH
        "xhigh" -> AgentThinkingLevel.XHIGH
        "max" -> AgentThinkingLevel.MAX
        else -> null
    }

internal fun findExtensionCommand(
    host: ExtensionHost?,
    text: String,
): Pair<String, String>? {
    if (!text.startsWith('/')) {
        return null
    }
    val command = text.removePrefix("/")
    val name = command.substringBefore(' ')
    if (host?.registrations?.commands?.none { it.invocationName == name } != false) {
        return null
    }
    return name to command.substringAfter(' ', "").trimStart()
}

internal fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.content
