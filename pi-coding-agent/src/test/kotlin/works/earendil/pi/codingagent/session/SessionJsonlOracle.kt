package works.earendil.pi.codingagent.session

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import works.earendil.pi.ai.Message

private val oracleJson =
    Json {
        explicitNulls = false
        encodeDefaults = true
    }

fun main(args: Array<String>) {
    val fixtureDir = Path.of(requireNotNull(args.firstOrNull()) { "Fixture directory is required" })
    val currentEntries = readEntries(fixtureDir.resolve("current.jsonl"))
    val decoded = currentEntries.mapNotNull(::decodeEntry)
    val sessionEntries = decoded.filterIsInstance<SessionEntry>()
    val v1Entries = readEntries(fixtureDir.resolve("v1.jsonl"))
    val v2Entries = readEntries(fixtureDir.resolve("v2.jsonl"))
    migrateSessionEntries(v1Entries)
    migrateSessionEntries(v2Entries)

    val output =
        buildJsonObject {
            put(
                "parsedTypes",
                JsonArray(currentEntries.map { it["type"] ?: JsonNull }),
            )
            put(
                "roundTrip",
                JsonArray(decoded.map(::encodeEntry)),
            )
            put(
                "contexts",
                buildJsonObject {
                    put("defaultLeaf", encodeContext(buildSessionContext(sessionEntries)))
                    put("mainLeaf", encodeContext(buildSessionContext(sessionEntries, "info")))
                    put("beforeCompaction", encodeContext(buildSessionContext(sessionEntries, "a2")))
                    put("explicitEmptyLeaf", encodeContext(buildSessionContext(sessionEntries, null)))
                    put("missingLeafFallsBack", encodeContext(buildSessionContext(sessionEntries, "missing")))
                },
            )
            put(
                "migrations",
                buildJsonObject {
                    put("v1", normalizeGeneratedIds(v1Entries))
                    put("v2", JsonArray(v2Entries))
                },
            )
        }
    println(oracleJson.encodeToString(JsonObject.serializer(), output))
}

private fun readEntries(path: Path): MutableList<JsonObject> =
    parseSessionEntries(Files.readString(path))

private fun encodeContext(context: SessionContext): JsonObject =
    buildJsonObject {
        put(
            "messages",
            JsonArray(
                context.messages.map { message ->
                    oracleJson.encodeToJsonElement(Message.serializer(), message)
                },
            ),
        )
        put("thinkingLevel", context.thinkingLevel)
        val model = context.model
        if (model == null) {
            put("model", JsonNull)
        } else {
            put(
                "model",
                buildJsonObject {
                    put("provider", model.provider)
                    put("modelId", model.modelId)
                },
            )
        }
    }

private fun normalizeGeneratedIds(entries: List<JsonObject>): JsonObject {
    val generated = entries.filter { it["type"] != JsonPrimitive("session") }
    val idMap =
        generated
            .mapIndexed { index, entry ->
                requireNotNull(entry["id"]?.let(JsonElement::toString)?.trim('"')) to "entry-${index + 1}"
            }.toMap()
    return buildJsonObject {
        put(
            "idLengths",
            JsonArray(
                generated.map { entry ->
                    JsonPrimitive(requireNotNull(entry.stringValue("id")).length)
                },
            ),
        )
        put(
            "entries",
            JsonArray(
                entries.map { entry ->
                    JsonObject(
                        entry.mapValues { (key, value) ->
                            when {
                                key == "id" && value is JsonPrimitive ->
                                    idMap[value.content]?.let(::JsonPrimitive) ?: value

                                key == "parentId" && value is JsonPrimitive ->
                                    idMap[value.content]?.let(::JsonPrimitive) ?: value

                                key == "firstKeptEntryId" && value is JsonPrimitive ->
                                    idMap[value.content]?.let(::JsonPrimitive) ?: value

                                else -> value
                            }
                        },
                    )
                },
            ),
        )
    }
}

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.content
