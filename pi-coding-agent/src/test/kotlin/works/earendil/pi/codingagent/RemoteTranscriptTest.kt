package works.earendil.pi.codingagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteTranscriptTest {
    @Test
    fun `projects deltas without mutating authoritative snapshots`() {
        var state = createTranscriptState(snapshot(1, "saved"))
        state =
            applyTranscriptProgress(
                state,
                buildJsonObject {
                    put("type", "assistant_delta")
                    put("messageId", "assistant-1")
                    put("contentIndex", 0)
                    put("kind", "text")
                    put("delta", " response")
                },
            )

        assertEquals(
            "saved",
            state.snapshot
                .getValue("transcript")
                .let { it as JsonArray }[0]
                .jsonObject
                .getValue("content")
                .let { it as JsonArray }[0]
                .jsonObject
                .getValue("text")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "saved response",
            selectTranscript(state)[0]
                .getValue("content")
                .let { it as JsonArray }[0]
                .jsonObject
                .getValue("text")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `buffers partial tool JSON and resets on authoritative snapshots`() {
        var state =
            createTranscriptState(
                snapshot(
                    revision = 1,
                    text = null,
                    content =
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "toolCall")
                                    put("toolCallId", "call-1")
                                    put("toolName", "bash")
                                    put("input", JsonPrimitive(null as String?))
                                },
                            )
                        },
                ),
            )
        listOf("""{"command":""", """"pwd"}""").forEach { delta ->
            state =
                applyTranscriptProgress(
                    state,
                    buildJsonObject {
                        put("type", "assistant_delta")
                        put("messageId", "assistant-1")
                        put("contentIndex", 0)
                        put("kind", "toolCall")
                        put("delta", delta)
                    },
                )
        }

        assertEquals(
            "pwd",
            selectTranscript(state)[0]
                .getValue("content")
                .let { it as JsonArray }[0]
                .jsonObject
                .getValue("input")
                .jsonObject
                .getValue("command")
                .jsonPrimitive
                .content,
        )

        state = applyTranscriptSnapshot(state, snapshot(2, "authoritative"))
        state = applyTranscriptSnapshot(state, snapshot(1, "stale"))
        assertEquals(2, state.snapshot.getValue("revision").jsonPrimitive.content.toInt())
        assertEquals("authoritative", transcriptText(selectTranscript(state)[0]))
    }

    private fun snapshot(
        revision: Int,
        text: String?,
        content: JsonArray =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", requireNotNull(text))
                    },
                )
            },
    ): JsonObject =
        buildJsonObject {
            put("id", "session-1")
            put("cwd", "/workspace")
            put("createdAt", 1)
            put("updatedAt", revision + 1)
            put("phase", "turn")
            put("model", buildJsonObject { put("provider", "faux"); put("id", "model") })
            put("thinkingLevel", "off")
            put("attached", true)
            put("locked", true)
            put("revision", revision)
            put(
                "transcript",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "assistant-1")
                            put("role", "assistant")
                            put("content", content)
                            put("status", "streaming")
                            put("model", buildJsonObject { put("provider", "faux"); put("id", "model") })
                            put("timestamp", 1)
                        },
                    )
                },
            )
            put("queuedSteer", JsonArray(emptyList()))
            put("queuedSteerCount", 0)
        }

    private fun transcriptText(item: JsonObject): String =
        item
            .getValue("content")
            .let { it as JsonArray }[0]
            .jsonObject
            .getValue("text")
            .jsonPrimitive
            .content
}
