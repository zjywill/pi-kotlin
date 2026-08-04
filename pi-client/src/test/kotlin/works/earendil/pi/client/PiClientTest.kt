package works.earendil.pi.client

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.protocol.ClientMessageDecoder
import works.earendil.pi.protocol.PROTOCOL_VERSION
import works.earendil.pi.protocol.ProtocolValidationException
import works.earendil.pi.protocol.encodeServerMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class PiClientTest {
    @Test
    fun `connects with framed hello and reports listener failures`() =
        runBlocking {
            val server = MemoryByteServer()
            val listenerErrors = mutableListOf<Throwable>()
            server.onMessage = { message ->
                if (message.string("type") == "hello") {
                    assertEquals(PROTOCOL_VERSION.toString(), message.string("version"))
                    server.send(serverHello())
                }
            }
            val client =
                PiClient(
                    PiClientOptions(
                        transportFactory = server.factory,
                        onListenerError = listenerErrors::add,
                    ),
                )
            client.subscribe { error("listener failed") }

            assertEquals(baseServerSnapshot(), client.connect())
            assertEquals(ConnectionState.CONNECTED, client.connectionState)
            assertEquals("listener failed", listenerErrors.single().message)
        }

    @Test
    fun `correlates out of order responses and surfaces typed errors`() =
        runBlocking {
            val server = MemoryByteServer()
            val requests = mutableListOf<JsonObject>()
            server.onMessage = { message ->
                when (message.string("type")) {
                    "hello" -> server.send(serverHello())
                    "request" -> requests += message
                }
            }
            val client = PiClient(PiClientOptions(server.factory))
            client.connect()
            val list = async { client.listSessions() }
            val attach = async { client.attachSession("session-1") }
            while (requests.size < 2) {
                kotlinx.coroutines.yield()
            }
            val listRequest = requests.single { it.objectValue("request").string("command") == "list" }
            val attachRequest = requests.single { it.objectValue("request").string("command") == "attach" }
            server.send(
                buildJsonObject {
                    put("type", "response")
                    put("id", attachRequest.string("id"))
                    put("ok", true)
                    put(
                        "result",
                        buildJsonObject {
                            put("command", "attach")
                            put("session", sessionSnapshot("session-1"))
                        },
                    )
                },
            )
            server.send(
                buildJsonObject {
                    put("type", "response")
                    put("id", listRequest.string("id"))
                    put("ok", true)
                    put(
                        "result",
                        buildJsonObject {
                            put("command", "list")
                            put("sessions", JsonArray(emptyList()))
                        },
                    )
                },
            )

            assertEquals(emptyList(), list.await())
            assertEquals("session-1", attach.await().id)

            val locked = async { runCatching { client.attachSession("locked") } }
            while (
                requests.none {
                    val request = it.objectValue("request")
                    request.string("command") == "attach" && request.optionalString("sessionId") == "locked"
                }
            ) {
                kotlinx.coroutines.yield()
            }
            val lockedRequest = requests.last()
            server.send(
                buildJsonObject {
                    put("type", "response")
                    put("id", lockedRequest.string("id"))
                    put("ok", false)
                    put(
                        "error",
                        buildJsonObject {
                            put("code", "session_locked")
                            put("message", "Already attached")
                        },
                    )
                },
            )
            val error = locked.await().exceptionOrNull() as PiServerException
            assertEquals("session_locked", error.code)
        }

    @Test
    fun `keeps lease snapshots current and accepts a lower revision after reacquire`() =
        runBlocking {
            val server = MemoryByteServer()
            var attachRevision = 10
            server.onMessage = { message ->
                when (message.string("type")) {
                    "hello" -> server.send(serverHello())
                    "request" -> {
                        val request = message.objectValue("request")
                        val result =
                            when (request.string("command")) {
                                "attach" ->
                                    buildJsonObject {
                                        put("command", "attach")
                                        put(
                                            "session",
                                            sessionSnapshot(
                                                request.string("sessionId"),
                                                revision = attachRevision,
                                            ),
                                        )
                                    }

                                "detach" ->
                                    buildJsonObject {
                                        put("command", "detach")
                                        put("sessionId", request.string("sessionId"))
                                    }

                                else -> error("Unexpected command")
                            }
                        server.send(
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
            val client = PiClient(PiClientOptions(server.factory))
            client.connect()
            val first = client.attachSession("session-1")
            assertEquals(10, first.snapshot?.long("revision"))
            server.send(sessionSnapshotEvent(sessionSnapshot("session-1", revision = 12, thinking = "high")))
            server.send(sessionSnapshotEvent(sessionSnapshot("session-1", revision = 11, thinking = "medium")))
            assertEquals(12, first.snapshot?.long("revision"))
            assertEquals("high", first.snapshot?.string("thinkingLevel"))

            first.detach()
            assertEquals(false, first.attached)
            assertFailsWith<PiSessionDetachedException> { first.abort() }
            attachRevision = 0
            val reopened = client.attachSession("session-1")
            assertNotSame(first, reopened)
            assertEquals(0, reopened.snapshot?.long("revision"))
        }

    @Test
    fun `detaches a shared session only after its final lease is released`() =
        runBlocking {
            val server = MemoryByteServer()
            val commands = mutableListOf<String>()
            server.onMessage = { message ->
                when (message.string("type")) {
                    "hello" -> server.send(serverHello())
                    "request" -> {
                        val request = message.objectValue("request")
                        val command = request.string("command")
                        commands += command
                        val result =
                            when (command) {
                                "attach" ->
                                    buildJsonObject {
                                        put("command", "attach")
                                        put("session", sessionSnapshot(request.string("sessionId")))
                                    }

                                "detach" ->
                                    buildJsonObject {
                                        put("command", "detach")
                                        put("sessionId", request.string("sessionId"))
                                    }

                                else -> error("Unexpected command")
                            }
                        server.send(
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
            val client = PiClient(PiClientOptions(server.factory))
            client.connect()

            val first = client.attachSession("session-1")
            val second = client.attachSession("session-1")
            assertNotSame(first, second)
            assertEquals(listOf("attach"), commands)

            first.detach()
            assertEquals(false, first.active)
            assertEquals(true, second.active)
            assertEquals(listOf("attach"), commands)

            second.detach()
            assertEquals(false, second.active)
            assertEquals(listOf("attach", "detach"), commands)
        }

    @Test
    fun `enforces lease ownership and invalidates handles on disposal`() =
        runBlocking {
            val server = MemoryByteServer()
            server.onMessage = { message ->
                when (message.string("type")) {
                    "hello" -> server.send(serverHello())
                    "request" -> {
                        val request = message.objectValue("request")
                        when (request.string("command")) {
                            "attach" ->
                                server.send(
                                    buildJsonObject {
                                        put("type", "response")
                                        put("id", message.string("id"))
                                        put("ok", true)
                                        put(
                                            "result",
                                            buildJsonObject {
                                                put("command", "attach")
                                                put("session", sessionSnapshot(request.string("sessionId")))
                                            },
                                        )
                                    },
                                )

                            "detach" ->
                                server.send(
                                    buildJsonObject {
                                        put("type", "response")
                                        put("id", message.string("id"))
                                        put("ok", true)
                                        put(
                                            "result",
                                            buildJsonObject {
                                                put("command", "detach")
                                                put("sessionId", request.string("sessionId"))
                                            },
                                        )
                                    },
                                )
                        }
                    }
                }
            }
            val client = PiClient(PiClientOptions(server.factory))
            client.connect()
            val shared = client.acquireSession("session-1", SessionLeaseMode.SHARED)
            assertFailsWith<PiSessionOwnershipException> {
                client.acquireSession("session-1", SessionLeaseMode.EXCLUSIVE)
            }
            shared.dispose()
            val exclusive = client.acquireSession("session-1", SessionLeaseMode.EXCLUSIVE)
            assertFailsWith<PiSessionOwnershipException> {
                client.acquireSession("session-1", SessionLeaseMode.SHARED)
            }

            client.dispose()

            assertTrue(client.isDisposed)
            assertEquals(false, exclusive.active)
            assertFailsWith<PiClientDisposedException> { exclusive.prompt("after disposal") }
        }

    @Test
    fun `rejects protocol data before transport creation finishes`() =
        runBlocking {
            var closeCount = 0
            val client =
                PiClient(
                    PiClientOptions(
                        transportFactory =
                            ByteTransportFactory { handlers ->
                                handlers.onData(encodeServerMessage(serverHello()))
                                object : ByteTransport {
                                    override suspend fun send(chunk: ByteArray) = Unit

                                    override fun close() {
                                        closeCount++
                                    }
                                }
                            },
                    ),
                )

            assertFailsWith<ProtocolValidationException> { client.connect() }
            assertEquals(ConnectionState.DISCONNECTED, client.connectionState)
            assertTrue(closeCount <= 1)
        }
}

private class MemoryByteServer {
    private lateinit var handlers: ByteTransportHandlers
    private val decoder = ClientMessageDecoder()
    var onMessage: (JsonObject) -> Unit = {}
    val factory =
        ByteTransportFactory { callbacks ->
            handlers = callbacks
            object : ByteTransport {
                override suspend fun send(chunk: ByteArray) {
                    decoder.push(chunk).forEach(onMessage)
                }

                override fun close() = Unit
            }
        }

    fun send(
        message: JsonObject,
        fragmentSize: Int = Int.MAX_VALUE,
    ) {
        val frame = encodeServerMessage(message)
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(frame.size, offset + fragmentSize)
            handlers.onData(frame.copyOfRange(offset, end))
            offset = end
        }
    }
}

private fun baseServerSnapshot(): JsonObject =
    buildJsonObject {
        put("serverId", "server-1")
        put("protocolVersion", PROTOCOL_VERSION)
        put("revision", 0)
        put("sessions", JsonArray(emptyList()))
        put("models", JsonArray(emptyList()))
    }

private fun serverHello(): JsonObject =
    buildJsonObject {
        put("type", "hello")
        put("version", PROTOCOL_VERSION)
        put("connectionId", "connection-1")
        put("snapshot", baseServerSnapshot())
    }

private fun sessionSnapshot(
    id: String,
    revision: Int = 0,
    thinking: String = "off",
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("cwd", "/workspace")
        put("createdAt", 1)
        put("updatedAt", 1)
        put("phase", "idle")
        put(
            "model",
            buildJsonObject {
                put("provider", "test")
                put("id", "model")
            },
        )
        put("thinkingLevel", thinking)
        put("attached", true)
        put("locked", true)
        put("revision", revision)
        put("transcript", buildJsonArray {})
        put("queuedSteer", buildJsonArray {})
        put("queuedSteerCount", 0)
    }

private fun sessionSnapshotEvent(snapshot: JsonObject): JsonObject =
    buildJsonObject {
        put("type", "event")
        put(
            "event",
            buildJsonObject {
                put("type", "session_snapshot")
                put("snapshot", snapshot)
            },
        )
    }

private fun JsonObject.string(name: String): String =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?: error("$name is required")

private fun JsonObject.optionalString(name: String): String? =
    (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.content

private fun JsonObject.long(name: String): Long =
    string(name).toLong()

private fun JsonObject.objectValue(name: String): JsonObject =
    this[name] as? JsonObject ?: error("$name is required")
