package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.UserMessage

internal fun buildInitialPrompts(
    args: Args,
    cwd: Path,
    stdinContent: String? = null,
): List<UserMessage> {
    val fileText = StringBuilder()
    val images = mutableListOf<ImageContent>()
    args.fileArgs.forEach { rawPath ->
        val path = resolveInitialPromptPath(cwd, rawPath)
        require(Files.exists(path)) { "File not found: $path" }
        if (Files.size(path) == 0L) {
            return@forEach
        }
        val mimeType = imageMimeType(path)
        if (mimeType != null) {
            images +=
                ImageContent(
                    Base64.getEncoder().encodeToString(Files.readAllBytes(path)),
                    mimeType,
                )
            fileText.append("<file name=\"$path\"></file>\n")
        } else {
            fileText.append("<file name=\"$path\">\n${Files.readString(path)}\n</file>\n")
        }
    }
    val firstMessage = args.messages.firstOrNull()
    val initialText =
        buildString {
            stdinContent?.trim()?.takeIf(String::isNotEmpty)?.let(::append)
            append(fileText)
            firstMessage?.let(::append)
        }
    return buildList {
        if (initialText.isNotEmpty() || images.isNotEmpty()) {
            if (images.isEmpty()) {
                add(UserMessage(initialText))
            } else {
                val blocks = buildList<ContentBlock> {
                    if (initialText.isNotEmpty()) {
                        add(TextContent(initialText))
                    }
                    addAll(images)
                }
                add(UserMessage(blocks))
            }
        }
        args.messages.drop(1).forEach { add(UserMessage(it)) }
    }
}

internal fun encodePromptCommand(message: UserMessage): kotlinx.serialization.json.JsonObject =
    buildJsonObject {
        when (val content = message.content) {
            is MessageContent.Text -> put("message", content.text)
            is MessageContent.Blocks -> {
                put(
                    "message",
                    content.blocks.filterIsInstance<TextContent>().joinToString("") { it.text },
                )
                val images = content.blocks.filterIsInstance<ImageContent>()
                if (images.isNotEmpty()) {
                    put(
                        "images",
                        JsonArray(
                            images.map {
                                protocolJson.encodeToJsonElement(ImageContent.serializer(), it)
                            },
                        ),
                    )
                }
            }
        }
    }

private fun resolveInitialPromptPath(
    cwd: Path,
    value: String,
): Path {
    val expanded =
        if (value == "~" || value.startsWith("~/")) {
            Path.of(System.getProperty("user.home")).resolve(value.removePrefix("~/"))
        } else {
            Path.of(value)
        }
    return (if (expanded.isAbsolute) expanded else cwd.resolve(expanded)).toAbsolutePath().normalize()
}

private fun imageMimeType(path: Path): String? =
    when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> null
    }
