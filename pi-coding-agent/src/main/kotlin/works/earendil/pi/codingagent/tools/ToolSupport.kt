package works.earendil.pi.codingagent.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val DEFAULT_MAX_LINES = 2000
const val DEFAULT_MAX_BYTES = 50 * 1024

data class TruncationResult(
    val content: String,
    val truncated: Boolean,
    val truncatedBy: String? = null,
    val totalLines: Int,
    val totalBytes: Int,
    val outputLines: Int,
    val outputBytes: Int,
    val firstLineExceedsLimit: Boolean = false,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("truncated", truncated)
            truncatedBy?.let { put("truncatedBy", it) }
            put("totalLines", totalLines)
            put("totalBytes", totalBytes)
            put("outputLines", outputLines)
            put("outputBytes", outputBytes)
            put("firstLineExceedsLimit", firstLineExceedsLimit)
            put("maxLines", DEFAULT_MAX_LINES)
            put("maxBytes", DEFAULT_MAX_BYTES)
        }
}

fun truncateHead(
    content: String,
    maxLines: Int = DEFAULT_MAX_LINES,
    maxBytes: Int = DEFAULT_MAX_BYTES,
): TruncationResult {
    val lines = splitLines(content)
    val totalBytes = content.toByteArray(StandardCharsets.UTF_8).size
    if (lines.size <= maxLines && totalBytes <= maxBytes) {
        return TruncationResult(
            content,
            truncated = false,
            totalLines = lines.size,
            totalBytes = totalBytes,
            outputLines = lines.size,
            outputBytes = totalBytes,
        )
    }
    if (lines.firstOrNull()?.toByteArray(StandardCharsets.UTF_8)?.size?.let { it > maxBytes } == true) {
        return TruncationResult(
            "",
            truncated = true,
            truncatedBy = "bytes",
            totalLines = lines.size,
            totalBytes = totalBytes,
            outputLines = 0,
            outputBytes = 0,
            firstLineExceedsLimit = true,
        )
    }

    val output = mutableListOf<String>()
    var bytes = 0
    var reason = "lines"
    for (line in lines.take(maxLines)) {
        val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size + if (output.isEmpty()) 0 else 1
        if (bytes + lineBytes > maxBytes) {
            reason = "bytes"
            break
        }
        output += line
        bytes += lineBytes
    }
    val text = output.joinToString("\n")
    return TruncationResult(
        text,
        truncated = true,
        truncatedBy = reason,
        totalLines = lines.size,
        totalBytes = totalBytes,
        outputLines = output.size,
        outputBytes = text.toByteArray(StandardCharsets.UTF_8).size,
    )
}

fun truncateTail(
    content: String,
    maxLines: Int = DEFAULT_MAX_LINES,
    maxBytes: Int = DEFAULT_MAX_BYTES,
): TruncationResult {
    val lines = splitLines(content)
    val totalBytes = content.toByteArray(StandardCharsets.UTF_8).size
    if (lines.size <= maxLines && totalBytes <= maxBytes) {
        return TruncationResult(
            content,
            truncated = false,
            totalLines = lines.size,
            totalBytes = totalBytes,
            outputLines = lines.size,
            outputBytes = totalBytes,
        )
    }

    val output = ArrayDeque<String>()
    var bytes = 0
    var reason = "lines"
    for (line in lines.asReversed().take(maxLines)) {
        val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size + if (output.isEmpty()) 0 else 1
        if (bytes + lineBytes > maxBytes) {
            reason = "bytes"
            break
        }
        output.addFirst(line)
        bytes += lineBytes
    }
    val text = output.joinToString("\n")
    return TruncationResult(
        text,
        truncated = true,
        truncatedBy = reason,
        totalLines = lines.size,
        totalBytes = totalBytes,
        outputLines = output.size,
        outputBytes = text.toByteArray(StandardCharsets.UTF_8).size,
    )
}

fun resolvePath(
    cwd: Path,
    rawPath: String,
): Path {
    val path = Path.of(rawPath)
    return (if (path.isAbsolute) path else cwd.resolve(path)).toAbsolutePath().normalize()
}

fun JsonObject.requireString(name: String): String =
    (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?: error("Missing or invalid string argument: $name")

fun JsonObject.optionalString(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content

fun JsonObject.optionalInt(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

fun JsonObject.optionalBoolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull

fun JsonObject.requireArray(name: String): JsonArray =
    this[name] as? JsonArray ?: error("Missing or invalid array argument: $name")

fun objectSchema(
    properties: JsonObject,
    required: List<String> = emptyList(),
): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", properties)
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map(::JsonPrimitive)))
        }
    }

fun stringSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("description", description)
    }

fun numberSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", "number")
        put("description", description)
    }

fun booleanSchema(description: String): JsonObject =
    buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

fun arraySchema(
    items: JsonElement,
    description: String,
): JsonObject =
    buildJsonObject {
        put("type", "array")
        put("items", items)
        put("description", description)
    }

private fun splitLines(content: String): List<String> {
    if (content.isEmpty()) {
        return emptyList()
    }
    val lines = content.split('\n').toMutableList()
    if (content.endsWith('\n')) {
        lines.removeLast()
    }
    return lines
}
