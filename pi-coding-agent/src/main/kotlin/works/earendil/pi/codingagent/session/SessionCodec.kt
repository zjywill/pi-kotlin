package works.earendil.pi.codingagent.session

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.MessageContentSerializer
import works.earendil.pi.ai.Usage

internal val sessionJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

private val sessionPayloadJson =
    Json(sessionJson) {
        encodeDefaults = true
    }

fun parseSessionEntries(content: String): MutableList<JsonObject> =
    content
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { line ->
            runCatching { sessionJson.decodeFromString<JsonElement>(line) as? JsonObject }.getOrNull()
        }.toMutableList()

fun migrateSessionEntries(entries: MutableList<JsonObject>): Boolean {
    val headerIndex = entries.indexOfFirst { it["type"]?.jsonPrimitive?.contentOrNull == "session" }
    if (headerIndex < 0) {
        return false
    }
    val header = entries[headerIndex]
    val version = header["version"]?.jsonPrimitive?.intOrNull ?: 1
    if (version >= CURRENT_SESSION_VERSION) {
        return false
    }

    if (version < 2) {
        val ids = mutableSetOf<String>()
        var previousId: String? = null
        for (index in entries.indices) {
            val entry = entries[index]
            if (entry["type"]?.jsonPrimitive?.contentOrNull == "session") {
                entries[index] = JsonObject(entry + ("version" to JsonPrimitive(2)))
                continue
            }

            val id = generateShortId(ids)
            var migrated =
                JsonObject(
                    entry +
                        mapOf(
                            "id" to JsonPrimitive(id),
                            "parentId" to (previousId?.let(::JsonPrimitive) ?: JsonNull),
                        ),
                )
            previousId = id
            if (entry["type"]?.jsonPrimitive?.contentOrNull == "compaction") {
                val firstKeptIndex = entry["firstKeptEntryIndex"]?.jsonPrimitive?.intOrNull
                val target = firstKeptIndex?.let(entries::getOrNull)
                val targetId = target?.get("id")?.jsonPrimitive?.contentOrNull
                val withoutOld = migrated.toMutableMap().also { it.remove("firstKeptEntryIndex") }
                if (targetId != null) {
                    withoutOld["firstKeptEntryId"] = JsonPrimitive(targetId)
                }
                migrated = JsonObject(withoutOld)
            }
            entries[index] = migrated
        }
    }

    for (index in entries.indices) {
        val entry = entries[index]
        if (entry["type"]?.jsonPrimitive?.contentOrNull == "session") {
            entries[index] = JsonObject(entry + ("version" to JsonPrimitive(CURRENT_SESSION_VERSION)))
            continue
        }
        if (entry["type"]?.jsonPrimitive?.contentOrNull != "message") {
            continue
        }
        val message = entry["message"] as? JsonObject ?: continue
        if (message["role"]?.jsonPrimitive?.contentOrNull != "hookMessage") {
            continue
        }
        val migratedMessage = JsonObject(message + ("role" to JsonPrimitive("custom")))
        entries[index] = JsonObject(entry + ("message" to migratedMessage))
    }
    return true
}

internal fun encodeEntry(entry: FileEntry): JsonObject =
    when (entry) {
        is SessionHeader ->
            buildJsonObject {
                put("type", "session")
                put("version", entry.version)
                put("id", entry.id)
                put("timestamp", entry.timestamp)
                put("cwd", entry.cwd)
                entry.parentSession?.let { put("parentSession", it) }
            }

        is SessionMessageEntry ->
            baseEntry("message", entry) {
                put("message", sessionPayloadJson.encodeToJsonElement(Message.serializer(), entry.message))
            }

        is ThinkingLevelChangeEntry ->
            baseEntry("thinking_level_change", entry) {
                put("thinkingLevel", entry.thinkingLevel)
            }

        is ModelChangeEntry ->
            baseEntry("model_change", entry) {
                put("provider", entry.provider)
                put("modelId", entry.modelId)
            }

        is CompactionEntry ->
            baseEntry("compaction", entry) {
                put("summary", entry.summary)
                put("firstKeptEntryId", entry.firstKeptEntryId)
                put("tokensBefore", entry.tokensBefore)
                entry.details?.let { put("details", it) }
                entry.usage?.let { put("usage", sessionPayloadJson.encodeToJsonElement(Usage.serializer(), it)) }
                entry.fromHook?.let { put("fromHook", it) }
            }

        is BranchSummaryEntry ->
            baseEntry("branch_summary", entry) {
                put("fromId", entry.fromId)
                put("summary", entry.summary)
                entry.details?.let { put("details", it) }
                entry.usage?.let { put("usage", sessionPayloadJson.encodeToJsonElement(Usage.serializer(), it)) }
                entry.fromHook?.let { put("fromHook", it) }
            }

        is CustomEntry ->
            baseEntry("custom", entry) {
                put("customType", entry.customType)
                entry.data?.let { put("data", it) }
            }

        is CustomMessageEntry ->
            baseEntry("custom_message", entry) {
                put("customType", entry.customType)
                put("content", sessionJson.encodeToJsonElement(MessageContentSerializer, entry.content))
                entry.details?.let { put("details", it) }
                put("display", entry.display)
            }

        is LabelEntry ->
            baseEntry("label", entry) {
                put("targetId", entry.targetId)
                entry.label?.let { put("label", it) }
            }

        is SessionInfoEntry ->
            baseEntry("session_info", entry) {
                entry.name?.let { put("name", it) }
            }
    }

internal fun decodeEntry(element: JsonObject): FileEntry? {
    val type = element["type"]?.jsonPrimitive?.contentOrNull ?: return null
    if (type == "session") {
        return SessionHeader(
            version = element["version"]?.jsonPrimitive?.intOrNull ?: 1,
            id = element.string("id") ?: return null,
            timestamp = element.string("timestamp") ?: return null,
            cwd = element.string("cwd").orEmpty(),
            parentSession = element.string("parentSession"),
        )
    }

    val id = element.string("id") ?: return null
    val parentId = element.string("parentId")
    val timestamp = element.string("timestamp") ?: return null
    return runCatching {
        when (type) {
            "message" ->
                SessionMessageEntry(
                    id,
                    parentId,
                    timestamp,
                    sessionJson.decodeFromJsonElement(
                        Message.serializer(),
                        element["message"] ?: return null,
                    ),
                )

            "thinking_level_change" ->
                ThinkingLevelChangeEntry(
                    id,
                    parentId,
                    timestamp,
                    element.string("thinkingLevel").orEmpty(),
                )

            "model_change" ->
                ModelChangeEntry(
                    id,
                    parentId,
                    timestamp,
                    element.string("provider").orEmpty(),
                    element.string("modelId").orEmpty(),
                )

            "compaction" ->
                CompactionEntry(
                    id,
                    parentId,
                    timestamp,
                    element.string("summary").orEmpty(),
                    element.string("firstKeptEntryId").orEmpty(),
                    element["tokensBefore"]?.jsonPrimitive?.intOrNull ?: 0,
                    element["details"],
                    element["usage"]?.let { sessionJson.decodeFromJsonElement(Usage.serializer(), it) },
                    element["fromHook"]?.jsonPrimitive?.booleanOrNull,
                )

            "branch_summary" ->
                BranchSummaryEntry(
                    id,
                    parentId,
                    timestamp,
                    element.string("fromId").orEmpty(),
                    element.string("summary").orEmpty(),
                    element["details"],
                    element["usage"]?.let { sessionJson.decodeFromJsonElement(Usage.serializer(), it) },
                    element["fromHook"]?.jsonPrimitive?.booleanOrNull,
                )

            "custom" ->
                CustomEntry(
                    id,
                    parentId,
                    timestamp,
                    element.string("customType").orEmpty(),
                    element["data"],
                )

            "custom_message" ->
                CustomMessageEntry(
                    id,
                    parentId,
                    timestamp,
                    element.string("customType").orEmpty(),
                    sessionJson.decodeFromJsonElement(
                        MessageContentSerializer,
                        element["content"] ?: JsonPrimitive(""),
                    ),
                    element["details"],
                    element["display"]?.jsonPrimitive?.booleanOrNull ?: false,
                )

            "label" ->
                LabelEntry(
                    id,
                    parentId,
                    timestamp,
                    element.string("targetId").orEmpty(),
                    element.string("label"),
                )

            "session_info" ->
                SessionInfoEntry(
                    id,
                    parentId,
                    timestamp,
                    element.string("name"),
                )

            else -> null
        }
    }.getOrNull()
}

internal data class LoadedSession(
    val entries: MutableList<FileEntry>,
    val migrated: Boolean,
)

internal fun loadSession(path: Path): LoadedSession {
    if (!Files.exists(path)) {
        return LoadedSession(mutableListOf(), migrated = false)
    }
    val raw = parseSessionEntries(Files.readString(path))
    if (raw.isEmpty()) {
        return LoadedSession(mutableListOf(), migrated = false)
    }
    val first = raw.first()
    if (first["type"]?.jsonPrimitive?.contentOrNull != "session" || first.string("id") == null) {
        return LoadedSession(mutableListOf(), migrated = false)
    }
    val migrated = migrateSessionEntries(raw)
    return LoadedSession(raw.mapNotNull(::decodeEntry).toMutableList(), migrated)
}

internal fun encodeLine(entry: FileEntry): String = sessionJson.encodeToString(encodeEntry(entry)) + "\n"

private fun baseEntry(
    type: String,
    entry: SessionEntry,
    body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
): JsonObject =
    buildJsonObject {
        put("type", type)
        put("id", entry.id)
        if (entry.parentId == null) {
            put("parentId", JsonNull)
        } else {
            put("parentId", JsonPrimitive(entry.parentId))
        }
        put("timestamp", entry.timestamp)
        body()
    }

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun generateShortId(existing: MutableSet<String>): String {
    repeat(100) {
        val candidate = UUID.randomUUID().toString().take(8)
        if (existing.add(candidate)) {
            return candidate
        }
    }
    return UUID.randomUUID().toString()
}
