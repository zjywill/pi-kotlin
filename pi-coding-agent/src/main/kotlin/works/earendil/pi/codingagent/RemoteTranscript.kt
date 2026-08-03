package works.earendil.pi.codingagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class TranscriptState(
    val snapshot: JsonObject,
    val progressItems: Map<String, JsonObject> = emptyMap(),
    val progressOrder: List<String> = emptyList(),
    val toolCallBuffers: Map<String, String> = emptyMap(),
)

fun createTranscriptState(snapshot: JsonObject): TranscriptState = TranscriptState(snapshot)

fun applyTranscriptSnapshot(
    state: TranscriptState,
    snapshot: JsonObject,
): TranscriptState {
    if (
        state.snapshot.string("id") == snapshot.string("id") &&
        snapshot.long("revision") < state.snapshot.long("revision")
    ) {
        return state
    }
    return createTranscriptState(snapshot)
}

fun applyTranscriptProgress(
    state: TranscriptState,
    progress: JsonObject,
): TranscriptState =
    when (progress.string("type")) {
        "item_started",
        "item_updated",
        -> setProgressItem(state, progress.objectValue("item"))

        "item_finished" -> {
            val item = progress.objectValue("item")
            val prefix = "${item.string("id")}:"
            setProgressItem(
                state.copy(
                    toolCallBuffers =
                        state.toolCallBuffers.filterKeys { key -> !key.startsWith(prefix) },
                ),
                item,
            )
        }

        "assistant_delta" -> applyAssistantDelta(state, progress)
        else -> state
    }

fun selectTranscript(state: TranscriptState): List<JsonObject> {
    val transcript =
        state.snapshot
            .array("transcript")
            .map(JsonElement::jsonObject)
            .map { item -> state.progressItems[item.string("id")] ?: item }
            .toMutableList()
    val ids = transcript.mapTo(linkedSetOf(), JsonObject::itemId)
    state.progressOrder.forEach { id ->
        if (id !in ids) {
            state.progressItems[id]?.let { item ->
                transcript += item
                ids += id
            }
        }
    }
    state.snapshot.array("queuedSteer").forEach { element ->
        val item = element.jsonObject
        if (ids.add(item.itemId())) {
            transcript += item
        }
    }
    return transcript
}

private fun applyAssistantDelta(
    state: TranscriptState,
    progress: JsonObject,
): TranscriptState {
    val messageId = progress.string("messageId")
    val item =
        state.progressItems[messageId]
            ?: state.snapshot
                .array("transcript")
                .map(JsonElement::jsonObject)
                .firstOrNull { candidate -> candidate.string("id") == messageId }
            ?: return state
    if (item.string("role") != "assistant") {
        return state
    }
    val contentIndex = progress.primitive("contentIndex").intOrNull ?: return state
    val kind = progress.string("kind")
    val delta = progress.string("delta")
    var toolCallBuffers = state.toolCallBuffers
    val content =
        JsonArray(
            item.array("content").mapIndexed { index, part ->
                if (index != contentIndex) {
                    return@mapIndexed part
                }
                val objectPart = part as? JsonObject ?: return@mapIndexed part
                when {
                    kind == "text" && objectPart.string("type") == "text" ->
                        objectPart.copyWith(
                            "text",
                            JsonPrimitive(objectPart.string("text") + delta),
                        )

                    kind == "thinking" && objectPart.string("type") == "thinking" ->
                        objectPart.copyWith(
                            "thinking",
                            JsonPrimitive(objectPart.string("thinking") + delta),
                        )

                    kind == "toolCall" && objectPart.string("type") == "toolCall" -> {
                        val key = "$messageId:$contentIndex"
                        val existing =
                            state.toolCallBuffers[key]
                                ?: (objectPart["input"] as? JsonPrimitive)
                                    ?.takeIf(JsonPrimitive::isString)
                                    ?.contentOrNull
                                ?: ""
                        val buffer = existing + delta
                        toolCallBuffers = state.toolCallBuffers + (key to buffer)
                        objectPart.copyWith("input", parsePartialToolInput(buffer))
                    }

                    else -> objectPart
                }
            },
        )
    return setProgressItem(
        state.copy(toolCallBuffers = toolCallBuffers),
        item.copyWith("content", content),
    )
}

private fun parsePartialToolInput(value: String): JsonElement =
    runCatching { Json.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }

private fun setProgressItem(
    state: TranscriptState,
    item: JsonObject,
): TranscriptState {
    val id = item.itemId()
    return state.copy(
        progressItems = state.progressItems + (id to item),
        progressOrder =
            if (id in state.progressItems) {
                state.progressOrder
            } else {
                state.progressOrder + id
            },
    )
}

private fun JsonObject.itemId(): String = string("id")

private fun JsonObject.copyWith(
    key: String,
    value: JsonElement,
): JsonObject =
    buildJsonObject {
        this@copyWith.forEach(::put)
        put(key, value)
    }

private fun JsonObject.string(name: String): String =
    primitive(name).contentOrNull ?: error("$name is required")

private fun JsonObject.long(name: String): Long =
    primitive(name).contentOrNull?.toLongOrNull() ?: error("$name is required")

private fun JsonObject.primitive(name: String): JsonPrimitive =
    this[name] as? JsonPrimitive ?: error("$name is required")

private fun JsonObject.array(name: String): JsonArray =
    this[name] as? JsonArray ?: error("$name is required")

private fun JsonObject.objectValue(name: String): JsonObject =
    this[name]?.jsonObject ?: error("$name is required")
