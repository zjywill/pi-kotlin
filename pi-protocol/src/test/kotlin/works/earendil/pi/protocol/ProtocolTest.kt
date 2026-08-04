package works.earendil.pi.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtocolTest {
    @Test
    fun `uses protocol version one and validates strict hello`() {
        assertEquals(1, PROTOCOL_VERSION)
        assertEquals(true, isSupportedProtocolVersion(1))
        assertEquals(false, isSupportedProtocolVersion(2))
        assertEquals(false, isSupportedProtocolVersion(2.5))
        parseClientMessage(clientHello())
        listOf(
            buildJsonObject {
                put("type", "hello")
                put("version", "1")
            },
            buildJsonObject {
                clientHello().forEach(::put)
                put("token", "secret")
            },
            buildJsonObject {
                clientHello().forEach(::put)
                put("extra", true)
            },
        ).forEach { invalid ->
            assertFailsWith<ProtocolValidationException> { parseClientMessage(invalid) }
        }
    }

    @Test
    fun `encodes and incrementally decodes client and server messages`() {
        val request =
            buildJsonObject {
                put("type", "request")
                put("id", "request-1")
                put("request", buildJsonObject { put("command", "list") })
            }
        val wire = encodeClientMessage(clientHello()) + encodeClientMessage(request)
        for (split in 0..wire.size) {
            val decoder = ClientMessageDecoder()
            val messages =
                decoder.push(wire.copyOfRange(0, split)) +
                    decoder.push(wire.copyOfRange(split, wire.size))
            decoder.end()
            assertEquals(listOf(clientHello(), request), messages)
        }

        val server = ServerMessageDecoder()
        assertEquals(listOf(serverHello()), server.push(encodeServerMessage(serverHello())))
        server.end()
    }

    @Test
    fun `rejects inconsistent transcript states and unknown fields`() {
        val streaming =
            assistantItem(
                status = "streaming",
                extra = mapOf("stopReason" to JsonPrimitive("stop")),
            )
        val completeWithoutReason = assistantItem(status = "complete")
        val invalidTool =
            toolItem(
                status = "error",
                isError = false,
            )
        listOf(streaming, completeWithoutReason, invalidTool).forEach { item ->
            assertFailsWith<ProtocolValidationException> {
                parseServerMessage(progressMessage(item))
            }
        }
    }

    @Test
    fun `accepts authoritative snapshots and bounded errors`() {
        parseServerMessage(serverHello())
        val error =
            buildJsonObject {
                put("type", "hello_error")
                put(
                    "error",
                    buildJsonObject {
                        put("code", "version")
                        put("message", "Unsupported version")
                    },
                )
            }
        parseServerMessage(error)
        assertFailsWith<ProtocolValidationException> {
            encodeClientMessage(clientHello(), maxFrameLength = 8)
        }
    }
}

private fun clientHello(): JsonObject =
    buildJsonObject {
        put("type", "hello")
        put("version", PROTOCOL_VERSION)
    }

private fun serverHello(): JsonObject =
    buildJsonObject {
        put("type", "hello")
        put("version", PROTOCOL_VERSION)
        put("connectionId", "connection-1")
        put(
            "snapshot",
            buildJsonObject {
                put("serverId", "server-1")
                put("protocolVersion", PROTOCOL_VERSION)
                put("revision", 0)
                put("sessions", JsonArray(emptyList()))
                put("models", JsonArray(emptyList()))
            },
        )
    }

private fun progressMessage(item: JsonObject): JsonObject =
    buildJsonObject {
        put("type", "event")
        put(
            "event",
            buildJsonObject {
                put("type", "session_progress")
                put("sessionId", "session-1")
                put(
                    "progress",
                    buildJsonObject {
                        put("type", "item_finished")
                        put("item", item)
                    },
                )
            },
        )
    }

private fun assistantItem(
    status: String,
    extra: Map<String, JsonPrimitive> = emptyMap(),
): JsonObject =
    buildJsonObject {
        put("id", "assistant-1")
        put("role", "assistant")
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "hello")
                    },
                )
            },
        )
        put(
            "model",
            buildJsonObject {
                put("provider", "test")
                put("id", "model")
            },
        )
        put("timestamp", 1)
        put("status", status)
        extra.forEach(::put)
    }

private fun toolItem(
    status: String,
    isError: Boolean,
): JsonObject =
    buildJsonObject {
        put("id", "tool-1")
        put("role", "tool")
        put("toolCallId", "call-1")
        put("toolName", "read")
        put("input", JsonObject(emptyMap()))
        put("content", JsonArray(emptyList()))
        put("timestamp", 1)
        put("status", status)
        put("isError", isError)
    }
