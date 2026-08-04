package works.earendil.pi.codingagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.agent.AgentEvent
import works.earendil.pi.agent.AgentToolResult
import works.earendil.pi.ai.AssistantMessageEvent
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.ToolResultMessage

internal val protocolJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

internal val rpcPayloadJson =
    Json(protocolJson) {
        encodeDefaults = true
    }

internal fun encodeRpcMessage(message: Message) =
    rpcPayloadJson.encodeToJsonElement(Message.serializer(), message)

private fun encodeRpcAssistantEvent(event: AssistantMessageEvent): JsonObject {
    val encoded =
        rpcPayloadJson
            .encodeToJsonElement(AssistantMessageEvent.serializer(), event)
            .jsonObject
    val partial = encoded["partial"] as? JsonObject ?: return encoded
    return JsonObject(
        encoded +
            (
                "partial" to
                    JsonObject(
                        mapOf("role" to JsonPrimitive("assistant")) + partial,
                    )
            ),
    )
}

internal fun encodeAgentEvent(
    event: AgentEvent,
    willRetry: Boolean = false,
    linearStreaming: Boolean = false,
): JsonObject =
    buildJsonObject {
        when (event) {
            AgentEvent.AgentStart -> put("type", "agent_start")
            is AgentEvent.AgentEnd -> {
                put("type", "agent_end")
                put(
                    "messages",
                    JsonArray(event.messages.map(::encodeRpcMessage)),
                )
                put("willRetry", willRetry)
            }

            AgentEvent.TurnStart -> put("type", "turn_start")
            is AgentEvent.TurnEnd -> {
                put("type", "turn_end")
                put("message", encodeRpcMessage(event.message))
                put(
                    "toolResults",
                    JsonArray(
                        event.toolResults.map {
                            rpcPayloadJson.encodeToJsonElement(ToolResultMessage.serializer(), it)
                        },
                    ),
                )
            }

            is AgentEvent.MessageStart -> {
                put("type", "message_start")
                put("message", encodeRpcMessage(event.message))
            }

            is AgentEvent.MessageUpdate -> {
                put("type", "message_update")
                if (!linearStreaming) {
                    put("message", encodeRpcMessage(event.message))
                }
                val assistantEvent = encodeRpcAssistantEvent(event.assistantMessageEvent)
                put(
                    "assistantMessageEvent",
                    if (linearStreaming) {
                        JsonObject(assistantEvent - "partial")
                    } else {
                        assistantEvent
                    },
                )
            }

            is AgentEvent.MessageEnd -> {
                put("type", "message_end")
                put("message", encodeRpcMessage(event.message))
            }

            is AgentEvent.ToolExecutionStart -> {
                put("type", "tool_execution_start")
                put("toolCallId", event.toolCallId)
                put("toolName", event.toolName)
                put("args", event.args)
            }

            is AgentEvent.ToolExecutionUpdate -> {
                put("type", "tool_execution_update")
                put("toolCallId", event.toolCallId)
                put("toolName", event.toolName)
                put("args", event.args)
                put("partialResult", encodeToolResult(event.partialResult))
            }

            is AgentEvent.ToolExecutionEnd -> {
                put("type", "tool_execution_end")
                put("toolCallId", event.toolCallId)
                put("toolName", event.toolName)
                put("result", encodeToolResult(event.result))
                put("isError", event.isError)
            }
        }
    }

internal fun encodeToolResult(result: AgentToolResult): JsonObject =
    buildJsonObject {
        put(
            "content",
            JsonArray(result.content.map { protocolJson.encodeToJsonElement(ContentBlock.serializer(), it) }),
        )
        put("details", result.details)
        result.usage?.let { put("usage", protocolJson.encodeToJsonElement(it)) }
        if (result.addedToolNames.isNotEmpty()) {
            put("addedToolNames", JsonArray(result.addedToolNames.map(::JsonPrimitive)))
        }
        if (result.terminate) {
            put("terminate", true)
        }
    }
