package works.earendil.pi.storage.sqlite

import java.sql.ResultSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.agent.session.SessionErrorCode
import works.earendil.pi.agent.session.SessionException
import works.earendil.pi.agent.session.SessionTreeEntry

internal val sqliteJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

internal data class SessionEntryRow(
    val id: String,
    val sequence: Int,
    val parentId: String?,
    val type: String,
    val timestamp: String,
    val payload: String,
)

internal fun ResultSet.toEntryRow(): SessionEntryRow =
    SessionEntryRow(
        id = getString("id"),
        sequence = getInt("entry_seq"),
        parentId = getString("parent_id"),
        type = getString("type"),
        timestamp = getString("timestamp"),
        payload = getString("payload"),
    )

internal fun encodeEntryPayload(entry: SessionTreeEntry): String {
    val encoded = sqliteJson.encodeToJsonElement(SessionTreeEntry.serializer(), entry).jsonObject.toMutableMap()
    encoded.remove("type")
    encoded.remove("id")
    encoded.remove("parentId")
    encoded.remove("timestamp")
    return JsonObject(encoded).toString()
}

internal fun decodeEntry(row: SessionEntryRow): SessionTreeEntry =
    try {
        val payload = sqliteJson.parseToJsonElement(row.payload).jsonObject
        val encoded =
            buildJsonObject {
                put("type", row.type)
                put("id", row.id)
                row.parentId?.let { put("parentId", it) }
                put("timestamp", row.timestamp)
                payload.forEach(::put)
            }
        sqliteJson.decodeFromJsonElement(SessionTreeEntry.serializer(), encoded)
    } catch (error: Exception) {
        throw SessionException(
            SessionErrorCode.INVALID_ENTRY,
            "Invalid SQLite session entry ${row.id}",
            error,
        )
    }

internal fun encodeMetadata(metadata: JsonObject?): String? = metadata?.toString()

internal fun decodeMetadata(
    value: String?,
    sessionId: String,
): JsonObject? {
    if (value == null) {
        return null
    }
    return try {
        sqliteJson.parseToJsonElement(value).jsonObject
    } catch (error: Exception) {
        throw SessionException(
            SessionErrorCode.INVALID_SESSION,
            "Invalid SQLite session $sessionId: metadata is not valid JSON",
            error,
        )
    }
}

internal fun summaryJson(
    entries: List<SessionTreeEntry>,
    name: String?,
    currentModel: Pair<String, String>?,
    currentThinkingLevel: String?,
): String {
    val stats = works.earendil.pi.agent.session.calculateSessionStats(entries)
    return buildJsonObject {
        name?.let { put("name", it) }
        put("messageCount", stats.messageCount)
        put("cachedTokens", stats.cachedTokens)
        put("uncachedTokens", stats.uncachedTokens)
        put("totalTokens", stats.totalTokens)
        put("costTotal", stats.costTotal)
        currentModel?.let { (provider, modelId) ->
            put(
                "currentModel",
                buildJsonObject {
                    put("provider", provider)
                    put("modelId", modelId)
                },
            )
        }
        currentThinkingLevel?.let { put("currentThinkingLevel", JsonPrimitive(it)) }
    }.toString()
}
