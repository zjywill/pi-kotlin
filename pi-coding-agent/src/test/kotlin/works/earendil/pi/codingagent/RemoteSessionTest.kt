package works.earendil.pi.codingagent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.client.ByteTransport
import works.earendil.pi.client.ByteTransportFactory
import works.earendil.pi.client.ByteTransportHandlers
import works.earendil.pi.client.PiClient
import works.earendil.pi.client.PiClientOptions
import works.earendil.pi.client.PiSessionOwnershipException
import works.earendil.pi.protocol.ClientMessageDecoder
import works.earendil.pi.protocol.PROTOCOL_VERSION
import works.earendil.pi.protocol.encodeServerMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemoteSessionTest {
    @Test
    fun `replaces sessions after attaching the next exclusive lease`() =
        runBlocking {
            val server = RemoteMemoryServer()
            val client = connectClient(server)
            val remote = RemoteSession.open(client, "session-1")

            remote.open("session-2")

            assertEquals(
                listOf(
                    "attach:session-1",
                    "attach:session-2",
                    "detach:session-1",
                ),
                server.commands,
            )
            assertEquals("session-2", remote.id)
            remote.dispose()
            assertTrue(client.connected)
        }

    @Test
    fun `projects progress and submits according to the authoritative phase`() =
        runBlocking {
            val server = RemoteMemoryServer()
            val client = connectClient(server)
            val remote = RemoteSession.open(client, "session-1")
            val views = mutableListOf<String>()
            remote.subscribe { state ->
                state.transcript.firstOrNull()?.let(::transcriptText)?.let(views::add)
            }
            server.send(
                buildJsonObject {
                    put("type", "event")
                    put(
                        "event",
                        buildJsonObject {
                            put("type", "session_snapshot")
                            put("snapshot", sessionSnapshot("session-1", revision = 2, phase = "turn"))
                        },
                    )
                },
            )
            server.send(
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
                                    put("type", "assistant_delta")
                                    put("messageId", "assistant-1")
                                    put("contentIndex", 0)
                                    put("kind", "text")
                                    put("delta", " world")
                                },
                            )
                        },
                    )
                },
            )

            remote.submit(" adjust ")

            assertTrue(views.contains("hello world"))
            assertEquals("steer:session-1", server.commands.last())
            remote.dispose()
        }

    @Test
    fun `requires exclusive ownership and releases it on disposal`() =
        runBlocking {
            val server = RemoteMemoryServer()
            val client = connectClient(server)
            val shared = client.attachSession("session-1")

            assertFailsWith<PiSessionOwnershipException> {
                RemoteSession.open(client, "session-1")
            }

            shared.dispose()
            val remote = RemoteSession.open(client, "session-1")
            remote.dispose()
            assertTrue(client.connected)
        }

    private suspend fun connectClient(server: RemoteMemoryServer): PiClient =
        PiClient(PiClientOptions(server.factory)).also { client ->
            client.connect()
        }
}

private class RemoteMemoryServer {
    private lateinit var handlers: ByteTransportHandlers
    private val decoder = ClientMessageDecoder()
    val commands = mutableListOf<String>()
    val factory =
        ByteTransportFactory { callbacks ->
            handlers = callbacks
            object : ByteTransport {
                override suspend fun send(chunk: ByteArray) {
                    decoder.push(chunk).forEach(::handle)
                }

                override fun close() = Unit
            }
        }

    fun send(message: JsonObject) {
        handlers.onData(encodeServerMessage(message))
    }

    private fun handle(message: JsonObject) {
        when (message.string("type")) {
            "hello" ->
                send(
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
                    },
                )

            "request" -> {
                val request = message.objectValue("request")
                val command = request.string("command")
                val sessionId = request.optionalString("sessionId") ?: "created-1"
                commands += "$command:$sessionId"
                val result =
                    when (command) {
                        "attach" ->
                            buildJsonObject {
                                put("command", "attach")
                                put("session", sessionSnapshot(sessionId))
                            }

                        "detach" ->
                            buildJsonObject {
                                put("command", "detach")
                                put("sessionId", sessionId)
                            }

                        "create" ->
                            buildJsonObject {
                                put("command", "create")
                                put("session", sessionSnapshot(sessionId))
                            }

                        "prompt",
                        "steer",
                        "abort",
                        "set_model",
                        "set_thinking",
                        ->
                            buildJsonObject {
                                put("command", command)
                                put(
                                    "session",
                                    sessionSnapshot(
                                        sessionId,
                                        revision = commands.size,
                                        phase = if (command == "prompt" || command == "steer") "turn" else "idle",
                                    ),
                                )
                            }

                        else -> error("Unexpected command: $command")
                    }
                send(
                    buildJsonObject {
                        put("type", "response")
                        put("id", message.string("id"))
                        put("ok", true)
                        put("result", result)
                    },
                )
            }
        }
    }
}

private fun sessionSnapshot(
    id: String,
    revision: Int = 1,
    phase: String = "idle",
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("cwd", "/workspace")
        put("createdAt", 1)
        put("updatedAt", revision + 1)
        put("phase", phase)
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
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject { put("type", "text"); put("text", "hello") })
                            },
                        )
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

private fun transcriptText(item: JsonObject): String? =
    item
        .getValue("content")
        .let { it as JsonArray }
        .firstOrNull()
        ?.jsonObject
        ?.get("text")
        ?.let { it as? JsonPrimitive }
        ?.contentOrNull

private fun JsonObject.string(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull ?: error("$name is required")

private fun JsonObject.optionalString(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.objectValue(name: String): JsonObject =
    this[name]?.jsonObject ?: error("$name is required")
