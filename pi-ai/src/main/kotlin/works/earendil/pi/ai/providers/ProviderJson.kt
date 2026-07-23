package works.earendil.pi.ai.providers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.BashExecutionMessage
import works.earendil.pi.ai.BranchSummaryMessage
import works.earendil.pi.ai.CompactionSummaryMessage
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.Cost
import works.earendil.pi.ai.CustomMessage
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ThinkingContent
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.contentText

internal val providerJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

internal val providerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal fun resolveApiKey(
    provider: String,
    explicit: String?,
    env: Map<String, String>,
    names: List<String>,
): String {
    explicit?.takeIf(String::isNotBlank)?.let { return it }
    names.firstNotNullOfOrNull { name ->
        env[name]?.takeIf(String::isNotBlank) ?: System.getenv(name)?.takeIf(String::isNotBlank)
    }?.let { return it }
    error("No API key for provider: $provider")
}

internal fun mergedHeaders(
    base: Map<String, String>,
    model: Map<String, String>,
    override: Map<String, String?>,
): Map<String, String> {
    val result = linkedMapOf<String, String>()
    fun removeCaseInsensitive(name: String) {
        result.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(result::remove)
    }
    (base + model).forEach { (name, value) ->
        removeCaseInsensitive(name)
        result[name] = value
    }
    override.forEach { (name, value) ->
        removeCaseInsensitive(name)
        if (value != null) {
            result[name] = value
        }
    }
    return result
}

internal fun openAIMessage(message: Message): JsonObject =
    when (message) {
        is works.earendil.pi.ai.UserMessage ->
            buildJsonObject {
                put("role", "user")
                put("content", openAIUserContent(message.content))
            }

        is AssistantMessage ->
            buildJsonObject {
                put("role", "assistant")
                val text = contentText(message.content, "")
                if (text.isNotEmpty()) {
                    put("content", text)
                } else {
                    put("content", JsonNull)
                }
                val toolCalls = message.content.filterIsInstance<ToolCall>()
                if (toolCalls.isNotEmpty()) {
                    put(
                        "tool_calls",
                        buildJsonArray {
                            toolCalls.forEach { call ->
                                add(
                                    buildJsonObject {
                                        put("id", call.id)
                                        put("type", "function")
                                        put(
                                            "function",
                                            buildJsonObject {
                                                put("name", call.name)
                                                put("arguments", providerJson.encodeToString(JsonObject.serializer(), call.arguments))
                                            },
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }

        is ToolResultMessage ->
            buildJsonObject {
                put("role", "tool")
                put("tool_call_id", message.toolCallId)
                put("content", contentText(message.content))
            }

        is CustomMessage ->
            buildJsonObject {
                put("role", "user")
                put("content", contentText(message.content))
            }

        is CompactionSummaryMessage ->
            buildJsonObject {
                put("role", "user")
                put("content", message.summary)
            }

        is BranchSummaryMessage ->
            buildJsonObject {
                put("role", "user")
                put("content", message.summary)
            }

        is BashExecutionMessage ->
            buildJsonObject {
                put("role", "user")
                put("content", "${message.command}\n${message.output}")
            }
    }

internal fun openAITool(
    tool: ToolDefinition,
    strict: Boolean = false,
    includeStrict: Boolean = false,
): JsonObject =
    buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parameters)
                if (includeStrict) {
                    put("strict", strict)
                }
            },
        )
    }

internal fun parseJsonObjectOrEmpty(value: String): JsonObject =
    runCatching { providerJson.parseToJsonElement(value).jsonObject }.getOrElse { JsonObject(emptyMap()) }

internal fun calculateUsageCost(
    model: Model,
    input: Int,
    output: Int,
    cacheRead: Int = 0,
    cacheWrite: Int = 0,
    reasoning: Int? = null,
): Usage {
    val totalInput = input + cacheRead
    val tier =
        model.cost.tiers
            .filter { totalInput > it.inputTokensAbove }
            .maxByOrNull { it.inputTokensAbove }
    val inputCost = input * (tier?.input ?: model.cost.input) / 1_000_000.0
    val outputCost = output * (tier?.output ?: model.cost.output) / 1_000_000.0
    val cacheReadCost = cacheRead * (tier?.cacheRead ?: model.cost.cacheRead) / 1_000_000.0
    val cacheWriteCost = cacheWrite * (tier?.cacheWrite ?: model.cost.cacheWrite) / 1_000_000.0
    return Usage(
        input = input,
        output = output,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        reasoning = reasoning,
        totalTokens = input + output + cacheRead + cacheWrite,
        cost =
            Cost(
                inputCost,
                outputCost,
                cacheReadCost,
                cacheWriteCost,
                inputCost + outputCost + cacheReadCost + cacheWriteCost,
            ),
    )
}

internal fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

internal fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

internal fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun openAIUserContent(content: MessageContent): JsonElement =
    when (content) {
        is MessageContent.Text -> JsonPrimitive(content.text)
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
                                    put("type", "image_url")
                                    put(
                                        "image_url",
                                        buildJsonObject {
                                            put("url", "data:${block.mimeType};base64,${block.data}")
                                        },
                                    )
                                },
                            )

                        else -> Unit
                    }
                }
            }
    }

internal fun copyBlocks(blocks: List<ContentBlock>): List<ContentBlock> =
    blocks.map { block ->
        when (block) {
            is TextContent -> block.copy()
            is ThinkingContent -> block.copy()
            is ImageContent -> block.copy()
            is ToolCall -> block.copy(arguments = JsonObject(block.arguments.toMap()))
        }
    }
