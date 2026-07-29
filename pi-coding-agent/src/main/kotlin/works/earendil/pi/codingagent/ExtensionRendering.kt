package works.earendil.pi.codingagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.CustomMessage
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.TextContent
import works.earendil.pi.codingagent.session.CustomEntry
import works.earendil.pi.codingagent.session.CustomMessageEntry
import works.earendil.pi.codingagent.session.SessionEntry
import works.earendil.pi.codingagent.session.SessionMessageEntry
import works.earendil.pi.tui.wrapTextWithAnsi

data class ExtensionRenderOptions(
    val width: Int,
    val expanded: Boolean = false,
    val outputPad: Int = 1,
)

data class ExtensionRenderedBlock(
    val entryId: String,
    val kind: String,
    val customType: String,
    val lines: List<String>,
)

internal fun findExtensionRenderer(
    registrations: List<ExtensionRegistration>,
    kind: String,
    customType: String,
): ExtensionRendererRegistration? =
    registrations.firstNotNullOfOrNull { extension ->
        val renderers =
            if (kind == "message") {
                extension.messageRenderers
            } else {
                extension.entryRenderers
            }
        renderers.firstOrNull { it.customType == customType }
    }

internal fun rendererValue(entry: SessionEntry): JsonObject? =
    when (entry) {
        is CustomEntry ->
            buildJsonObject {
                put("type", "custom")
                put("id", entry.id)
                entry.parentId?.let { put("parentId", it) }
                put("timestamp", entry.timestamp)
                put("customType", entry.customType)
                entry.data?.let { put("data", it) }
            }

        is CustomMessageEntry ->
            customMessageValue(
                CustomMessage(
                    customType = entry.customType,
                    content = entry.content,
                    display = entry.display,
                    details = entry.details,
                    timestamp = java.time.Instant.parse(entry.timestamp).toEpochMilli(),
                ),
            )

        is SessionMessageEntry ->
            (entry.message as? CustomMessage)?.let(::customMessageValue)

        else -> null
    }

internal fun customMessage(entry: SessionEntry): CustomMessage? =
    when (entry) {
        is CustomMessageEntry ->
            CustomMessage(
                customType = entry.customType,
                content = entry.content,
                display = entry.display,
                details = entry.details,
                timestamp = java.time.Instant.parse(entry.timestamp).toEpochMilli(),
            )

        is SessionMessageEntry -> entry.message as? CustomMessage
        else -> null
    }

internal fun parseRendererLines(invocation: ExtensionInvocation): List<String>? {
    val result = invocation.result as? JsonObject ?: return null
    if (result["rendered"]?.jsonPrimitive?.content != "true") {
        return null
    }
    return result["lines"]
        ?.jsonArray
        .orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.content }
}

internal fun defaultCustomMessageLines(
    message: CustomMessage,
    width: Int,
): List<String> {
    val content =
        when (val value = message.content) {
            is MessageContent.Text -> value.text
            is MessageContent.Blocks ->
                value.blocks
                    .filterIsInstance<TextContent>()
                    .joinToString("\n", transform = TextContent::text)
        }
    val availableWidth = (width - 2).coerceAtLeast(1)
    return buildList {
        add("")
        add("[${message.customType}]")
        if (content.isNotEmpty()) {
            addAll(wrapTextWithAnsi(content, availableWidth))
        }
    }
}

internal fun rendererErrorLines(
    customType: String,
    message: String,
): List<String> = listOf("", "[$customType] renderer failed: $message")

internal fun extensionRenderedBlockEvent(block: ExtensionRenderedBlock): JsonObject =
    buildJsonObject {
        put("type", "extension_render")
        put("entryId", block.entryId)
        put("kind", block.kind)
        put("customType", block.customType)
        put("lines", JsonArray(block.lines.map(::JsonPrimitive)))
    }

private fun customMessageValue(message: CustomMessage): JsonObject =
    buildJsonObject {
        put("role", "custom")
        put("customType", message.customType)
        put("content", encodeMessageContent(message.content))
        put("display", message.display)
        message.details?.let { put("details", it) }
        put("timestamp", message.timestamp)
    }

private fun encodeMessageContent(content: MessageContent): JsonElement =
    when (content) {
        is MessageContent.Text -> JsonPrimitive(content.text)
        is MessageContent.Blocks ->
            JsonArray(
                content.blocks.map { block ->
                    protocolJson.encodeToJsonElement(ContentBlock.serializer(), block)
                },
            )
    }
