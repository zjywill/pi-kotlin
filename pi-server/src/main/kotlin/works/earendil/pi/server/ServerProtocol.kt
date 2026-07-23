package works.earendil.pi.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal val serverJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        prettyPrint = false
    }

enum class InstanceStatus(
    val wireValue: String,
) {
    STARTING("starting"),
    ONLINE("online"),
    STOPPING("stopping"),
    STOPPED("stopped"),
    ERROR("error"),
}

data class InstanceRecord(
    val id: String,
    val status: InstanceStatus,
    val cwd: String,
    val createdAt: String,
    val lastSeenAt: String? = null,
    val label: String? = null,
    val sessionId: String? = null,
    val sessionFile: String? = null,
)

fun encodeMessage(message: JsonObject): String = message.toString() + "\n"

fun parseMessage(line: String): JsonObject = serverJson.parseToJsonElement(line).jsonObject

fun InstanceRecord.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("status", status.wireValue)
        put("cwd", cwd)
        put("createdAt", createdAt)
        lastSeenAt?.let { put("lastSeenAt", it) }
        label?.let { put("label", it) }
        sessionId?.let { put("sessionId", it) }
        sessionFile?.let { put("sessionFile", it) }
    }

fun instanceSummary(record: InstanceRecord): JsonObject =
    buildJsonObject {
        put("id", record.id)
        put("status", record.status.wireValue)
        put("cwd", record.cwd)
        record.label?.let { put("label", it) }
        record.sessionId?.let { put("sessionId", it) }
        record.sessionFile?.let { put("sessionFile", it) }
    }

fun errorResponse(message: String): JsonObject =
    buildJsonObject {
        put("type", "error")
        put("ok", false)
        put("error", message)
    }

internal fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.instanceId(): String =
    string("instanceId") ?: error("instanceId is required")

internal fun listResponse(instances: List<InstanceRecord>): JsonObject =
    buildJsonObject {
        put("type", "list_result")
        put("ok", true)
        put("instances", JsonArray(instances.map(::instanceSummary)))
    }

internal fun stateSessionFields(state: JsonObject): Pair<String?, String?> {
    val data = state["data"] as? JsonObject ?: return null to null
    val sessionId = data.string("sessionId")
    val sessionFile = data.string("sessionFile")
    return sessionId to sessionFile
}

internal fun nullableString(value: String?): kotlinx.serialization.json.JsonElement =
    value?.let(::JsonPrimitive) ?: JsonNull
