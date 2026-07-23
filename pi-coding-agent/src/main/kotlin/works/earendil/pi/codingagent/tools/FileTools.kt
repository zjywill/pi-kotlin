package works.earendil.pi.codingagent.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.agent.AgentTool
import works.earendil.pi.agent.AgentToolResult
import works.earendil.pi.agent.AgentToolUpdateCallback
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.TextContent

class ReadTool(
    private val cwd: Path,
) : AgentTool {
    override val name = "read"
    override val label = "read"
    override val description =
        "Read a text file or image. Text output is truncated to 2000 lines or 50KB. " +
            "Use offset and limit to continue large files."
    override val parameters =
        objectSchema(
            properties =
                buildJsonObject {
                    put("path", stringSchema("Path to the file to read"))
                    put("offset", numberSchema("Line number to start reading from, 1-indexed"))
                    put("limit", numberSchema("Maximum number of lines to read"))
                },
            required = listOf("path"),
        )

    override suspend fun execute(
        toolCallId: String,
        params: JsonObject,
        onUpdate: AgentToolUpdateCallback?,
    ): AgentToolResult {
        val rawPath = params.requireString("path")
        val path = resolvePath(cwd, rawPath)
        require(Files.isReadable(path)) { "File not found or not readable: $path" }
        val bytes = Files.readAllBytes(path)
        val mimeType = imageMimeType(path)
        if (mimeType != null) {
            return AgentToolResult(
                content =
                    listOf(
                        TextContent("Read image file [$mimeType]"),
                        ImageContent(Base64.getEncoder().encodeToString(bytes), mimeType),
                    ),
            )
        }

        val allLines = String(bytes, StandardCharsets.UTF_8).split('\n')
        val offset = (params.optionalInt("offset") ?: 1).coerceAtLeast(1)
        require(offset <= allLines.size) {
            "Offset $offset is beyond end of file (${allLines.size} lines total)"
        }
        val start = offset - 1
        val limit = params.optionalInt("limit")
        val selected =
            if (limit == null) {
                allLines.drop(start)
            } else {
                allLines.drop(start).take(limit.coerceAtLeast(0))
            }
        val truncated = truncateHead(selected.joinToString("\n"))
        var output = truncated.content
        if (truncated.truncated) {
            val nextOffset = offset + truncated.outputLines
            output +=
                "\n\n[Showing lines $offset-${nextOffset - 1} of ${allLines.size}. " +
                "Use offset=$nextOffset to continue.]"
        } else if (limit != null && start + selected.size < allLines.size) {
            val remaining = allLines.size - start - selected.size
            output += "\n\n[$remaining more lines in file. Use offset=${offset + selected.size} to continue.]"
        }
        val details =
            if (truncated.truncated) {
                buildJsonObject { put("truncation", truncated.toJson()) }
            } else {
                JsonObject(emptyMap())
            }
        return AgentToolResult(listOf(TextContent(output)), details)
    }
}

class WriteTool(
    private val cwd: Path,
) : AgentTool {
    override val name = "write"
    override val label = "write"
    override val description = "Write content to a file, creating parent directories when needed."
    override val parameters =
        objectSchema(
            properties =
                buildJsonObject {
                    put("path", stringSchema("Path to the file to write"))
                    put("content", stringSchema("Content to write"))
                },
            required = listOf("path", "content"),
        )

    override suspend fun execute(
        toolCallId: String,
        params: JsonObject,
        onUpdate: AgentToolUpdateCallback?,
    ): AgentToolResult {
        val rawPath = params.requireString("path")
        val path = resolvePath(cwd, rawPath)
        path.parent?.let(Files::createDirectories)
        Files.writeString(
            path,
            params.requireString("content"),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return AgentToolResult(listOf(TextContent("Successfully wrote to $rawPath")))
    }
}

class EditTool(
    private val cwd: Path,
) : AgentTool {
    override val name = "edit"
    override val label = "edit"
    override val description =
        "Apply one or more exact replacements to a file. Every oldText must be unique in the original file."
    private val editItemSchema =
        objectSchema(
            properties =
                buildJsonObject {
                    put("oldText", stringSchema("Exact text to replace"))
                    put("newText", stringSchema("Replacement text"))
                },
            required = listOf("oldText", "newText"),
        )
    override val parameters =
        objectSchema(
            properties =
                buildJsonObject {
                    put("path", stringSchema("Path to the file to edit"))
                    put("edits", arraySchema(editItemSchema, "Targeted replacements"))
                },
            required = listOf("path", "edits"),
        )

    override fun prepareArguments(arguments: JsonObject): JsonObject {
        if (arguments["edits"] is JsonArray) {
            return arguments
        }
        val oldText = arguments.optionalString("oldText")
        val newText = arguments.optionalString("newText")
        if (oldText == null || newText == null) {
            return arguments
        }
        return JsonObject(
            arguments
                .filterKeys { it != "oldText" && it != "newText" } +
                (
                    "edits" to
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("oldText", oldText)
                                    put("newText", newText)
                                },
                            ),
                        )
                ),
        )
    }

    override suspend fun execute(
        toolCallId: String,
        params: JsonObject,
        onUpdate: AgentToolUpdateCallback?,
    ): AgentToolResult {
        val rawPath = params.requireString("path")
        val path = resolvePath(cwd, rawPath)
        require(Files.isReadable(path) && Files.isWritable(path)) {
            "Could not edit file: $rawPath"
        }
        val raw = Files.readString(path, StandardCharsets.UTF_8)
        val hasBom = raw.startsWith('\uFEFF')
        val withoutBom = raw.removePrefix("\uFEFF")
        val lineEnding = if ("\r\n" in withoutBom) "\r\n" else "\n"
        val normalized = withoutBom.replace("\r\n", "\n")
        val edits =
            params.requireArray("edits").map { element ->
                val edit = element.jsonObject
                edit.requireString("oldText").replace("\r\n", "\n") to
                    edit.requireString("newText").replace("\r\n", "\n")
            }
        require(edits.isNotEmpty()) {
            "Edit tool input is invalid. edits must contain at least one replacement."
        }

        val ranges =
            edits.map { (oldText, _) ->
                val first = normalized.indexOf(oldText)
                require(first >= 0) { "Could not find exact text in $rawPath" }
                require(normalized.indexOf(oldText, first + 1) < 0) {
                    "Found multiple matches for oldText in $rawPath. Provide more context to make it unique."
                }
                first until (first + oldText.length)
            }
        for (left in ranges.indices) {
            for (right in left + 1 until ranges.size) {
                require(ranges[left].last < ranges[right].first || ranges[right].last < ranges[left].first) {
                    "Edits overlap in $rawPath"
                }
            }
        }

        var updated = normalized
        edits
            .zip(ranges)
            .sortedByDescending { (_, range) -> range.first }
            .forEach { (edit, range) ->
                updated = updated.replaceRange(range, edit.second)
            }
        val restored = (if (hasBom) "\uFEFF" else "") + updated.replace("\n", lineEnding)
        Files.writeString(
            path,
            restored,
            StandardCharsets.UTF_8,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        return AgentToolResult(
            content = listOf(TextContent("Successfully replaced ${edits.size} block(s) in $rawPath.")),
            details =
                buildJsonObject {
                    put("firstChangedLine", normalized.take(ranges.minOf { it.first }).count { it == '\n' } + 1)
                },
        )
    }
}

private fun imageMimeType(path: Path): String? =
    when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> null
    }
