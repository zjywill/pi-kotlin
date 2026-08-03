package works.earendil.pi.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val PROTOCOL_VERSION: Int = 2

class ProtocolValidationException(
    message: String,
) : IllegalArgumentException(message)

fun isSupportedProtocolVersion(version: Number): Boolean =
    version.toDouble().let { it.isFinite() && it % 1.0 == 0.0 && it.toInt() == PROTOCOL_VERSION }

fun parseClientMessage(value: JsonElement): JsonObject =
    validateProtocol("client", value, ::validateClientMessage)

fun parseServerMessage(value: JsonElement): JsonObject =
    validateProtocol("server", value, ::validateServerMessage)

fun encodeClientMessage(
    message: JsonObject,
    maxFrameLength: Int = DEFAULT_MAX_FRAME_LENGTH,
): ByteArray = encodeProtocolMessage(message, maxFrameLength, ::parseClientMessage, "client")

fun encodeServerMessage(
    message: JsonObject,
    maxFrameLength: Int = DEFAULT_MAX_FRAME_LENGTH,
): ByteArray = encodeProtocolMessage(message, maxFrameLength, ::parseServerMessage, "server")

class ClientMessageDecoder(
    maxFrameLength: Int = DEFAULT_MAX_FRAME_LENGTH,
) : ProtocolMessageDecoder(maxFrameLength, "client", ::parseClientMessage)

class ServerMessageDecoder(
    maxFrameLength: Int = DEFAULT_MAX_FRAME_LENGTH,
) : ProtocolMessageDecoder(maxFrameLength, "server", ::parseServerMessage)

open class ProtocolMessageDecoder internal constructor(
    private val maxFrameLength: Int,
    private val kind: String,
    private val parser: (JsonElement) -> JsonObject,
) {
    private val frames = FrameDecoder(maxFrameLength)
    private var failed = false

    fun push(chunk: ByteArray): List<JsonObject> {
        if (failed) {
            throw ProtocolValidationException("$kind message decoder has failed")
        }
        return try {
            frames.push(chunk).map { frame ->
                val decoded = decodeCbor(frame, CborOptions(maxByteLength = maxFrameLength))
                parser(cborToJson(decoded))
            }
        } catch (error: Throwable) {
            failed = true
            if (error is ProtocolValidationException) {
                throw error
            }
            throw ProtocolValidationException("Invalid $kind protocol frame: ${boundedErrorMessage(error)}")
        }
    }

    fun end() {
        if (failed) {
            throw ProtocolValidationException("$kind message decoder has failed")
        }
        try {
            frames.end()
        } catch (error: Throwable) {
            failed = true
            throw ProtocolValidationException("Invalid $kind protocol framing: ${boundedErrorMessage(error)}")
        }
    }
}

private fun encodeProtocolMessage(
    message: JsonObject,
    maxFrameLength: Int,
    parser: (JsonElement) -> JsonObject,
    kind: String,
): ByteArray {
    val validated = parser(message)
    return try {
        val payload =
            encodeCbor(
                jsonToCbor(validated),
                CborOptions(maxByteLength = maxFrameLength),
            )
        encodeFrame(payload).also { assertCompleteFrame(it, maxFrameLength) }
    } catch (error: Throwable) {
        if (error is ProtocolValidationException) {
            throw error
        }
        throw ProtocolValidationException("Unable to encode $kind protocol message: ${boundedErrorMessage(error)}")
    }
}

private fun validateProtocol(
    kind: String,
    value: JsonElement,
    validator: (JsonObject) -> Unit,
): JsonObject {
    val message = value as? JsonObject
        ?: throw ProtocolValidationException("Invalid $kind protocol message")
    try {
        validator(message)
    } catch (_: ProtocolSchemaException) {
        throw ProtocolValidationException("Invalid $kind protocol message")
    }
    return message
}

private fun validateClientMessage(message: JsonObject) {
    when (message.string("type")) {
        "hello" -> {
            message.strict(required = setOf("type", "version", "token"))
            message.integer("version", minimum = 0)
            message.nonEmptyString("token")
        }

        "request" -> {
            message.strict(required = setOf("type", "id", "request"))
            message.nonEmptyString("id")
            validateCommand(message.objectValue("request"))
        }

        else -> schemaError()
    }
}

private fun validateServerMessage(message: JsonObject) {
    when (message.string("type")) {
        "hello" -> {
            message.strict(required = setOf("type", "version", "connectionId", "snapshot"))
            if (message.integer("version", minimum = 0) != PROTOCOL_VERSION.toLong()) {
                schemaError()
            }
            message.nonEmptyString("connectionId")
            validateServerSnapshot(message.objectValue("snapshot"))
        }

        "hello_error" -> {
            message.strict(required = setOf("type", "error"))
            validateProtocolError(message.objectValue("error"))
        }

        "response" -> validateResponse(message)
        "event" -> {
            message.strict(required = setOf("type", "event"))
            validateServerEvent(message.objectValue("event"))
        }

        else -> schemaError()
    }
}

private fun validateCommand(command: JsonObject) {
    when (command.string("command")) {
        "list" -> command.strict(required = setOf("command"))
        "create" -> {
            command.strict(
                required = setOf("command"),
                optional = setOf("cwd", "name", "model", "thinkingLevel"),
            )
            command.optionalNonEmptyString("cwd")
            command.optionalString("name")
            command["model"]?.let(::validateModelRef)
            command.optionalThinkingLevel("thinkingLevel")
        }

        "attach",
        "detach",
        "abort",
        -> {
            command.strict(required = setOf("command", "sessionId"))
            command.nonEmptyString("sessionId")
        }

        "prompt",
        "steer",
        -> {
            command.strict(required = setOf("command", "sessionId", "text"))
            command.nonEmptyString("sessionId")
            command.string("text")
        }

        "set_model" -> {
            command.strict(required = setOf("command", "sessionId", "model"))
            command.nonEmptyString("sessionId")
            validateModelRef(command.required("model"))
        }

        "set_thinking" -> {
            command.strict(required = setOf("command", "sessionId", "thinkingLevel"))
            command.nonEmptyString("sessionId")
            command.thinkingLevel("thinkingLevel")
        }

        else -> schemaError()
    }
}

private fun validateResponse(message: JsonObject) {
    val ok = message.boolean("ok")
    if (ok) {
        message.strict(required = setOf("type", "id", "ok", "result"))
        message.nonEmptyString("id")
        validateCommandResult(message.objectValue("result"))
    } else {
        message.strict(required = setOf("type", "id", "ok", "error"))
        message.nonEmptyString("id")
        validateProtocolError(message.objectValue("error"))
    }
}

private fun validateCommandResult(result: JsonObject) {
    when (result.string("command")) {
        "list" -> {
            result.strict(required = setOf("command", "sessions"))
            result.array("sessions").forEach { validateSessionSummary(it.objectValue()) }
        }

        "detach" -> {
            result.strict(required = setOf("command", "sessionId"))
            result.nonEmptyString("sessionId")
        }

        "create",
        "attach",
        "prompt",
        "steer",
        "abort",
        "set_model",
        "set_thinking",
        -> {
            result.strict(required = setOf("command", "session"))
            validateSessionSnapshot(result.objectValue("session"))
        }

        else -> schemaError()
    }
}

private fun validateServerEvent(event: JsonObject) {
    when (event.string("type")) {
        "server_snapshot" -> {
            event.strict(required = setOf("type", "snapshot"))
            validateServerSnapshot(event.objectValue("snapshot"))
        }

        "session_snapshot" -> {
            event.strict(required = setOf("type", "snapshot"))
            validateSessionSnapshot(event.objectValue("snapshot"))
        }

        "session_progress" -> {
            event.strict(required = setOf("type", "sessionId", "progress"))
            event.nonEmptyString("sessionId")
            validateTranscriptProgress(event.objectValue("progress"))
        }

        "session_removed" -> {
            event.strict(required = setOf("type", "sessionId"))
            event.nonEmptyString("sessionId")
        }

        else -> schemaError()
    }
}

private fun validateServerSnapshot(snapshot: JsonObject) {
    snapshot.strict(required = setOf("serverId", "protocolVersion", "revision", "sessions", "models"))
    snapshot.nonEmptyString("serverId")
    if (snapshot.integer("protocolVersion", minimum = 0) != PROTOCOL_VERSION.toLong()) {
        schemaError()
    }
    snapshot.integer("revision", minimum = 0)
    snapshot.array("sessions").forEach { validateSessionSummary(it.objectValue()) }
    snapshot.array("models").forEach { validateModelMetadata(it.objectValue()) }
}

private fun validateSessionSummary(summary: JsonObject) {
    validateSessionSummaryFields(summary, allowSnapshotFields = false)
}

private fun validateSessionSnapshot(snapshot: JsonObject) {
    validateSessionSummaryFields(snapshot, allowSnapshotFields = true)
    snapshot.integer("revision", minimum = 0)
    snapshot.array("transcript").forEach(::validateTranscriptItem)
    snapshot.array("queuedSteer").forEach { validateUserTranscriptItem(it.objectValue()) }
    snapshot.integer("queuedSteerCount", minimum = 0)
}

private fun validateSessionSummaryFields(
    value: JsonObject,
    allowSnapshotFields: Boolean,
) {
    val required =
        mutableSetOf(
            "id",
            "cwd",
            "createdAt",
            "updatedAt",
            "phase",
            "model",
            "thinkingLevel",
            "attached",
            "locked",
        )
    if (allowSnapshotFields) {
        required += setOf("revision", "transcript", "queuedSteer", "queuedSteerCount")
    }
    value.strict(required = required, optional = setOf("name"))
    value.nonEmptyString("id")
    value.optionalString("name")
    value.nonEmptyString("cwd")
    value.integer("createdAt", minimum = 0)
    value.integer("updatedAt", minimum = 0)
    if (value.string("phase") !in SESSION_PHASES) schemaError()
    validateModelRef(value.required("model"))
    value.thinkingLevel("thinkingLevel")
    value.boolean("attached")
    value.boolean("locked")
}

private fun validateModelRef(value: JsonElement) {
    val model = value.objectValue()
    model.strict(required = setOf("provider", "id"))
    model.nonEmptyString("provider")
    model.nonEmptyString("id")
}

private fun validateModelMetadata(model: JsonObject) {
    model.strict(
        required =
            setOf(
                "provider",
                "id",
                "name",
                "api",
                "reasoning",
                "input",
                "contextWindow",
                "maxTokens",
                "cost",
                "supportedThinkingLevels",
                "authenticated",
            ),
    )
    model.nonEmptyString("provider")
    model.nonEmptyString("id")
    model.nonEmptyString("name")
    model.nonEmptyString("api")
    model.boolean("reasoning")
    model.array("input").forEach { entry ->
        if (entry.stringValue() !in setOf("text", "image")) schemaError()
    }
    model.integer("contextWindow", minimum = 1)
    model.integer("maxTokens", minimum = 1)
    validateModelCost(model.objectValue("cost"))
    val levels = model.array("supportedThinkingLevels")
    if (levels.isEmpty()) schemaError()
    levels.forEach { if (it.stringValue() !in THINKING_LEVELS) schemaError() }
    model.boolean("authenticated")
}

private fun validateModelCost(cost: JsonObject) {
    cost.strict(required = setOf("input", "output", "cacheRead", "cacheWrite"))
    setOf("input", "output", "cacheRead", "cacheWrite").forEach { cost.number(it, minimum = 0.0) }
}

private fun validateProtocolError(error: JsonObject) {
    error.strict(required = setOf("code", "message"), optional = setOf("details"))
    if (error.string("code") !in PROTOCOL_ERROR_CODES) schemaError()
    error.string("message")
    error["details"]?.let(::validateJsonValue)
}

private fun validateTranscriptItem(value: JsonElement) {
    val item = value.objectValue()
    when (item.string("role")) {
        "user" -> validateUserTranscriptItem(item)
        "assistant" -> validateAssistantTranscriptItem(item)
        "tool" -> validateToolTranscriptItem(item)
        else -> schemaError()
    }
}

private fun validateUserTranscriptItem(item: JsonObject) {
    item.strict(required = setOf("id", "role", "content", "timestamp"))
    item.nonEmptyString("id")
    item.array("content").forEach(::validateUserContent)
    item.integer("timestamp", minimum = 0)
}

private fun validateAssistantTranscriptItem(item: JsonObject) {
    val common =
        setOf(
            "id",
            "role",
            "content",
            "model",
            "timestamp",
            "status",
        )
    val optional = mutableSetOf("responseModel", "usage")
    when (item.string("status")) {
        "streaming" -> item.strict(required = common, optional = optional)
        "complete" -> {
            item.strict(required = common + "stopReason", optional = optional)
            if (item.string("stopReason") !in setOf("stop", "length", "toolUse")) schemaError()
        }

        "error" -> {
            optional += "errorMessage"
            item.strict(required = common + "stopReason", optional = optional)
            if (item.string("stopReason") != "error") schemaError()
            item.optionalNonEmptyString("errorMessage")
        }

        "aborted" -> {
            optional += "errorMessage"
            item.strict(required = common + "stopReason", optional = optional)
            if (item.string("stopReason") != "aborted") schemaError()
            item.optionalString("errorMessage")
        }

        else -> schemaError()
    }
    item.nonEmptyString("id")
    item.array("content").forEach(::validateAssistantContent)
    validateModelRef(item.required("model"))
    item.optionalNonEmptyString("responseModel")
    item["usage"]?.let { validateUsage(it.objectValue()) }
    item.integer("timestamp", minimum = 0)
}

private fun validateToolTranscriptItem(item: JsonObject) {
    val required =
        setOf(
            "id",
            "role",
            "toolCallId",
            "toolName",
            "input",
            "content",
            "timestamp",
            "status",
            "isError",
        )
    item.strict(required = required, optional = setOf("details", "usage"))
    item.nonEmptyString("id")
    item.nonEmptyString("toolCallId")
    item.nonEmptyString("toolName")
    validateJsonValue(item.required("input"))
    item.array("content").forEach(::validateToolContent)
    item["details"]?.let(::validateJsonValue)
    item["usage"]?.let { validateUsage(it.objectValue()) }
    item.integer("timestamp", minimum = 0)
    when (item.string("status")) {
        "running",
        "complete",
        -> if (item.boolean("isError")) schemaError()

        "error" -> if (!item.boolean("isError")) schemaError()
        else -> schemaError()
    }
}

private fun validateTranscriptProgress(progress: JsonObject) {
    when (progress.string("type")) {
        "item_started" -> {
            progress.strict(required = setOf("type", "item"))
            validateTranscriptItem(progress.required("item"))
        }

        "assistant_delta" -> {
            progress.strict(required = setOf("type", "messageId", "contentIndex", "kind", "delta"))
            progress.nonEmptyString("messageId")
            progress.integer("contentIndex", minimum = 0)
            if (progress.string("kind") !in setOf("text", "thinking", "toolCall")) schemaError()
            progress.string("delta")
        }

        "item_updated" -> {
            progress.strict(required = setOf("type", "item"))
            val item = progress.objectValue("item")
            when (item.string("role")) {
                "assistant" -> validateAssistantTranscriptItem(item)
                "tool" -> validateToolTranscriptItem(item)
                else -> schemaError()
            }
        }

        "item_finished" -> {
            progress.strict(required = setOf("type", "item"))
            val item = progress.objectValue("item")
            when (item.string("role")) {
                "assistant" -> {
                    validateAssistantTranscriptItem(item)
                    if (item.string("status") == "streaming") schemaError()
                }

                "tool" -> {
                    validateToolTranscriptItem(item)
                    if (item.string("status") == "running") schemaError()
                }

                else -> schemaError()
            }
        }

        else -> schemaError()
    }
}

private fun validateUserContent(value: JsonElement) {
    val content = value.objectValue()
    when (content.string("type")) {
        "text" -> {
            content.strict(required = setOf("type", "text"))
            content.string("text")
        }

        "image" -> {
            content.strict(required = setOf("type", "data", "mimeType"))
            content.string("data")
            content.nonEmptyString("mimeType")
        }

        else -> schemaError()
    }
}

private fun validateAssistantContent(value: JsonElement) {
    val content = value.objectValue()
    when (content.string("type")) {
        "text" -> {
            content.strict(required = setOf("type", "text"))
            content.string("text")
        }

        "thinking" -> {
            content.strict(required = setOf("type", "thinking"), optional = setOf("redacted"))
            content.string("thinking")
            content["redacted"]?.let { if (it.booleanValue() == null) schemaError() }
        }

        "toolCall" -> {
            content.strict(required = setOf("type", "toolCallId", "toolName", "input"))
            content.nonEmptyString("toolCallId")
            content.nonEmptyString("toolName")
            validateJsonValue(content.required("input"))
        }

        else -> schemaError()
    }
}

private fun validateToolContent(value: JsonElement) {
    val content = value.objectValue()
    when (content.string("type")) {
        "text" -> {
            content.strict(required = setOf("type", "text"))
            content.string("text")
        }

        "image" -> {
            content.strict(required = setOf("type", "data", "mimeType"))
            content.string("data")
            content.nonEmptyString("mimeType")
        }

        else -> schemaError()
    }
}

private fun validateUsage(usage: JsonObject) {
    usage.strict(
        required = setOf("input", "output", "cacheRead", "cacheWrite", "totalTokens", "cost"),
        optional = setOf("reasoning"),
    )
    setOf("input", "output", "cacheRead", "cacheWrite", "totalTokens").forEach {
        usage.integer(it, minimum = 0)
    }
    usage["reasoning"]?.let { if (it.integerValue(minimum = 0) == null) schemaError() }
    val cost = usage.objectValue("cost")
    cost.strict(required = setOf("input", "output", "cacheRead", "cacheWrite", "total"))
    setOf("input", "output", "cacheRead", "cacheWrite", "total").forEach {
        cost.number(it, minimum = 0.0)
    }
}

private fun validateJsonValue(value: JsonElement) {
    when (value) {
        JsonNull -> Unit
        is JsonPrimitive -> {
            if (!value.isString && value.booleanOrNull == null && value.doubleOrNull?.isFinite() != true) {
                schemaError()
            }
        }

        is JsonArray -> value.forEach(::validateJsonValue)
        is JsonObject -> value.values.forEach(::validateJsonValue)
    }
}

private fun jsonToCbor(value: JsonElement): Any? =
    when (value) {
        JsonNull -> null
        is JsonArray -> value.map(::jsonToCbor)
        is JsonObject -> value.mapValuesTo(linkedMapOf()) { (_, entry) -> jsonToCbor(entry) }
        is JsonPrimitive ->
            when {
                value.isString -> value.content
                value.booleanOrNull != null -> value.booleanOrNull
                value.longOrNull != null -> value.longOrNull
                value.doubleOrNull != null -> value.doubleOrNull
                else -> throw ProtocolValidationException("Invalid protocol JSON value")
            }
    }

private fun cborToJson(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Byte,
        is Short,
        is Int,
        is Long,
        -> JsonPrimitive((value as Number).toLong())

        is Float,
        is Double,
        -> JsonPrimitive((value as Number).toDouble())

        is String -> JsonPrimitive(value)
        is List<*> -> buildJsonArray { value.forEach { add(cborToJson(it)) } }
        is Map<*, *> ->
            buildJsonObject {
                value.forEach { (key, entry) ->
                    if (key !is String) {
                        throw ProtocolValidationException("Protocol maps require string keys")
                    }
                    put(key, cborToJson(entry))
                }
            }

        is ByteArray -> throw ProtocolValidationException("Protocol messages do not allow byte strings")
        else -> throw ProtocolValidationException("Unsupported decoded protocol value")
    }

private fun boundedErrorMessage(error: Throwable): String {
    val message = error.message ?: "Unknown codec error"
    return if (message.length <= 500) message else message.take(497) + "..."
}

private class ProtocolSchemaException : IllegalArgumentException()

private fun schemaError(): Nothing = throw ProtocolSchemaException()

private fun JsonObject.strict(
    required: Set<String>,
    optional: Set<String> = emptySet(),
) {
    if (!keys.containsAll(required) || keys.any { it !in required && it !in optional }) {
        schemaError()
    }
}

private fun JsonObject.required(name: String): JsonElement =
    this[name]?.takeUnless { it === JsonNull } ?: schemaError()

private fun JsonObject.string(name: String): String = required(name).stringValue()

private fun JsonObject.nonEmptyString(name: String): String =
    string(name).takeIf(String::isNotEmpty) ?: schemaError()

private fun JsonObject.optionalString(name: String): String? =
    this[name]?.let { value ->
        if (value === JsonNull) schemaError()
        value.stringValue()
    }

private fun JsonObject.optionalNonEmptyString(name: String): String? =
    optionalString(name)?.takeIf(String::isNotEmpty) ?: if (containsKey(name)) schemaError() else null

private fun JsonElement.stringValue(): String =
    (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: schemaError()

private fun JsonObject.boolean(name: String): Boolean =
    required(name).booleanValue() ?: schemaError()

private fun JsonElement.booleanValue(): Boolean? =
    (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull

private fun JsonObject.integer(
    name: String,
    minimum: Long? = null,
): Long = required(name).integerValue(minimum) ?: schemaError()

private fun JsonElement.integerValue(minimum: Long? = null): Long? {
    val primitive = (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString) ?: return null
    val value = primitive.longOrNull ?: return null
    return value.takeIf { minimum == null || it >= minimum }
}

private fun JsonObject.number(
    name: String,
    minimum: Double? = null,
): Double {
    val primitive = required(name) as? JsonPrimitive ?: schemaError()
    if (primitive.isString) schemaError()
    val value = primitive.doubleOrNull?.takeIf(Double::isFinite) ?: schemaError()
    if (minimum != null && value < minimum) schemaError()
    return value
}

private fun JsonObject.array(name: String): JsonArray =
    required(name) as? JsonArray ?: schemaError()

private fun JsonObject.objectValue(name: String): JsonObject =
    required(name).objectValue()

private fun JsonElement.objectValue(): JsonObject = this as? JsonObject ?: schemaError()

private fun JsonObject.thinkingLevel(name: String): String =
    string(name).takeIf(THINKING_LEVELS::contains) ?: schemaError()

private fun JsonObject.optionalThinkingLevel(name: String): String? =
    optionalString(name)?.takeIf(THINKING_LEVELS::contains) ?: if (containsKey(name)) schemaError() else null

private val THINKING_LEVELS = setOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
private val SESSION_PHASES = setOf("idle", "turn", "compaction", "branch_summary", "retry")
private val PROTOCOL_ERROR_CODES =
    setOf("auth", "version", "busy", "session_locked", "not_found", "invalid_request")
