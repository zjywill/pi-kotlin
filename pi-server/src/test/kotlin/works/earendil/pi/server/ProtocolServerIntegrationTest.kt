package works.earendil.pi.server

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import works.earendil.pi.client.PiClient
import works.earendil.pi.client.PiClientOptions
import works.earendil.pi.client.PiServerException as ClientServerException
import works.earendil.pi.client.UnixTransportOptions
import works.earendil.pi.client.createUnixTransportFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProtocolServerIntegrationTest {
    @Test
    fun `serves authenticated sessions over a real Unix socket`() =
        withServer { server, backend, socket ->
            val client =
                PiClient(
                    PiClientOptions(
                        token = TEST_TOKEN,
                        transportFactory = createUnixTransportFactory(UnixTransportOptions(socket)),
                    ),
                )
            val hello = client.connect()
            assertEquals(PROTOCOL_VERSION_VALUE, hello.long("protocolVersion"))
            assertEquals(emptyList(), client.listSessions())

            val session = client.createSession(cwd = "/work", name = "Created")
            assertTrue(session.attached)
            assertEquals("/work", session.snapshot?.string("cwd"))
            assertEquals("Created", session.snapshot?.string("name"))
            val id = session.id
            assertEquals(id, backend.lastCreatedId)
            assertEquals(1, client.listSessions().size)

            val updated = session.setThinking("high")
            assertEquals("high", updated.string("thinkingLevel"))
            session.detach()
            assertEquals(false, session.attached)
            withTimeout(2_000) {
                while (backend.latestRuntime(id).disposeCount.get() == 0) {
                    yield()
                }
            }
            assertEquals(1, backend.latestRuntime(id).disposeCount.get())

            val reopened = client.attachSession(id)
            assertEquals(id, reopened.id)
            assertEquals(2, backend.runtimes[id]?.size)
            client.disconnect()
            server.close()
        }

    @Test
    fun `rejects invalid authentication with a typed client error`() =
        withServer { server, _, socket ->
            val client =
                PiClient(
                    PiClientOptions(
                        token = "wrong",
                        transportFactory = createUnixTransportFactory(UnixTransportOptions(socket)),
                    ),
                )
            val error = assertFailsWith<ClientServerException> { client.connect() }
            assertEquals("auth", error.code)
            server.close()
        }

    @Test
    fun `shares one runtime and scopes progress to attached clients`() =
        withServer(seed = "shared") { server, backend, socket ->
            val first = client(socket)
            val second = client(socket)
            first.connect()
            second.connect()
            val firstHandle = first.attachSession("shared")
            val secondList = second.listSessions().single()
            assertEquals(false, secondList.boolean("attached"))
            assertEquals(true, secondList.boolean("locked"))
            val secondHandle = second.attachSession("shared")
            assertEquals(1, backend.runtimes["shared"]?.size)

            val firstProgress = CompletableDeferred<JsonObject>()
            val secondProgress = CompletableDeferred<JsonObject>()
            firstHandle.onEvent { event ->
                if (event.string("type") == "session_progress") {
                    firstProgress.complete(event)
                }
            }
            secondHandle.onEvent { event ->
                if (event.string("type") == "session_progress") {
                    secondProgress.complete(event)
                }
            }
            backend.latestRuntime("shared").emitProgress(
                buildJsonObject {
                    put("type", "assistant_delta")
                    put("messageId", "assistant-1")
                    put("contentIndex", 0)
                    put("kind", "text")
                    put("delta", "hello")
                },
            )
            assertEquals("session_progress", withTimeout(2_000) { firstProgress.await() }.string("type"))
            assertEquals("session_progress", withTimeout(2_000) { secondProgress.await() }.string("type"))

            firstHandle.detach()
            val runtime = backend.latestRuntime("shared")
            assertEquals(0, runtime.disposeCount.get())
            val changed = secondHandle.setModel("test", "large")
            assertEquals("large", changed.objectValue("model").string("id"))
            first.disconnect()
            second.disconnect()
            server.close()
        }

    @Test
    fun `assigns durable IDs and rejects backend ID substitution`() =
        runBlocking {
            val directory = Files.createTempDirectory("pi-server-v2-")
            val socket = directory.resolve("server.sock")
            val backend = FakeBackend(wrongCreatedId = true)
            val server =
                createUnixServer(
                    backend,
                    UnixServerOptions(token = TEST_TOKEN, path = socket),
                )
            try {
                server.start()
                val client = client(socket)
                client.connect()
                val error =
                    assertFailsWith<ClientServerException> {
                        client.createSession()
                    }
                assertEquals("invalid_request", error.code)
                assertNotEquals(backend.lastCreatedId, null)
                assertEquals(1, backend.latestRuntime(requireNotNull(backend.lastCreatedId)).disposeCount.get())
                client.disconnect()
            } finally {
                server.close()
                directory.toFile().deleteRecursively()
            }
        }
}

private fun withServer(
    seed: String? = null,
    block: suspend (PiServer, FakeBackend, Path) -> Unit,
) = runBlocking {
    val directory = Files.createTempDirectory("pi-server-v2-")
    val socket = directory.resolve("server.sock")
    val backend = FakeBackend()
    seed?.let(backend::seed)
    val server =
        createUnixServer(
            backend,
            UnixServerOptions(token = TEST_TOKEN, path = socket),
        )
    try {
        server.start()
        block(server, backend, socket)
    } finally {
        runCatching { server.close() }
        directory.toFile().deleteRecursively()
    }
}

private fun client(socket: Path): PiClient =
    PiClient(
        PiClientOptions(
            token = TEST_TOKEN,
            transportFactory = createUnixTransportFactory(UnixTransportOptions(socket)),
        ),
    )

private data class StoredSession(
    val id: String,
    val cwd: String,
    val name: String?,
    val createdAt: Long,
    var updatedAt: Long,
    var modelProvider: String = "test",
    var modelId: String = "model",
    var thinkingLevel: String = "off",
    var revision: Long = 0,
    var phase: String = "idle",
    val transcript: MutableList<JsonObject> = mutableListOf(),
)

private class FakeBackend(
    private val wrongCreatedId: Boolean = false,
) : PiSessionBackend {
    private val stored = ConcurrentHashMap<String, StoredSession>()
    val runtimes = ConcurrentHashMap<String, MutableList<FakeRuntime>>()
    var lastCreatedId: String? = null

    fun seed(id: String = "session-1") {
        stored[id] = StoredSession(id, "/workspace", null, 1, 1)
    }

    override suspend fun listSessions(): List<JsonObject> =
        stored.values.sortedBy(StoredSession::id).map(::summary)

    override suspend fun listModels(): List<JsonObject> =
        listOf(
            buildJsonObject {
                put("provider", "test")
                put("id", "model")
                put("name", "Test Model")
                put("api", "test")
                put("reasoning", true)
                put("input", buildJsonArray { add(JsonPrimitive("text")) })
                put("contextWindow", 100_000)
                put("maxTokens", 8_192)
                put(
                    "cost",
                    buildJsonObject {
                        put("input", 0)
                        put("output", 0)
                        put("cacheRead", 0)
                        put("cacheWrite", 0)
                    },
                )
                put("supportedThinkingLevels", buildJsonArray { add(JsonPrimitive("off")); add(JsonPrimitive("high")) })
                put("authenticated", true)
            },
        )

    override suspend fun createSession(options: CreateProtocolSessionOptions): PiSessionRuntime {
        val id = if (wrongCreatedId) "wrong-id" else options.id
        lastCreatedId = id
        val storedSession =
            StoredSession(
                id = id,
                cwd = options.cwd ?: "/workspace",
                name = options.name,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                modelProvider = options.model?.string("provider") ?: "test",
                modelId = options.model?.string("id") ?: "model",
                thinkingLevel = options.thinkingLevel ?: "off",
            )
        stored[id] = storedSession
        return runtime(storedSession)
    }

    override suspend fun openSession(sessionId: String): PiSessionRuntime =
        stored[sessionId]?.let(::runtime)
            ?: throw PiServerException("not_found", "Session not found: $sessionId")

    fun latestRuntime(id: String): FakeRuntime = requireNotNull(runtimes[id]?.lastOrNull())

    private fun runtime(session: StoredSession): FakeRuntime =
        FakeRuntime(session).also { runtime ->
            runtimes.computeIfAbsent(session.id) { CopyOnWriteArrayList() }.add(runtime)
        }

    private fun summary(session: StoredSession): JsonObject =
        buildJsonObject {
            put("id", session.id)
            session.name?.let { put("name", it) }
            put("cwd", session.cwd)
            put("createdAt", session.createdAt)
            put("updatedAt", session.updatedAt)
            put("phase", session.phase)
            put(
                "model",
                buildJsonObject {
                    put("provider", session.modelProvider)
                    put("id", session.modelId)
                },
            )
            put("thinkingLevel", session.thinkingLevel)
            put("attached", false)
            put("locked", false)
        }
}

private class FakeRuntime(
    private val stored: StoredSession,
) : PiSessionRuntime {
    private val listeners = CopyOnWriteArrayList<(PiSessionRuntimeEvent) -> Unit>()
    val disposeCount = AtomicInteger()

    override fun getPhase(): String = stored.phase

    override suspend fun snapshot(): JsonObject =
        buildJsonObject {
            put("id", stored.id)
            stored.name?.let { put("name", it) }
            put("cwd", stored.cwd)
            put("createdAt", stored.createdAt)
            put("updatedAt", stored.updatedAt)
            put("phase", stored.phase)
            put(
                "model",
                buildJsonObject {
                    put("provider", stored.modelProvider)
                    put("id", stored.modelId)
                },
            )
            put("thinkingLevel", stored.thinkingLevel)
            put("attached", false)
            put("locked", false)
            put("revision", stored.revision)
            put("transcript", JsonArray(stored.transcript.toList()))
            put("queuedSteer", JsonArray(emptyList()))
            put("queuedSteerCount", 0)
        }

    override fun subscribe(listener: (PiSessionRuntimeEvent) -> Unit): ServerUnsubscribe {
        listeners += listener
        return ServerUnsubscribe { listeners -= listener }
    }

    override suspend fun prompt(text: String) {
        if (stored.phase != "idle") {
            throw PiServerException("busy", "Session is busy")
        }
        stored.phase = "turn"
        update()
        stored.transcript += userItem(text)
        stored.transcript += assistantItem("reply:$text")
        stored.phase = "idle"
        update()
    }

    override suspend fun steer(text: String) {
        stored.transcript += userItem(text)
        update()
    }

    override suspend fun abort() {
        stored.phase = "idle"
        update()
    }

    override suspend fun setModel(model: JsonObject) {
        stored.modelProvider = model.string("provider")
        stored.modelId = model.string("id")
        update()
    }

    override suspend fun setThinking(thinkingLevel: String) {
        stored.thinkingLevel = thinkingLevel
        update()
    }

    override suspend fun dispose() {
        disposeCount.incrementAndGet()
    }

    fun emitProgress(progress: JsonObject) {
        listeners.forEach { it(PiSessionRuntimeEvent.Progress(progress)) }
    }

    private fun update() {
        stored.revision += 1
        stored.updatedAt = System.currentTimeMillis()
        listeners.forEach { it(PiSessionRuntimeEvent.Snapshot) }
    }
}

private fun userItem(text: String): JsonObject =
    buildJsonObject {
        put("id", "user-${System.nanoTime()}")
        put("role", "user")
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            },
        )
        put("timestamp", System.currentTimeMillis())
    }

private fun assistantItem(text: String): JsonObject =
    buildJsonObject {
        put("id", "assistant-${System.nanoTime()}")
        put("role", "assistant")
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
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
        put("status", "complete")
        put("stopReason", "stop")
        put("timestamp", System.currentTimeMillis())
    }

private fun JsonObject.string(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull ?: error("$name is required")

private fun JsonObject.long(name: String): Long = string(name).toLong()

private fun JsonObject.boolean(name: String): Boolean = string(name).toBooleanStrict()

private fun JsonObject.objectValue(name: String): JsonObject =
    this[name] as? JsonObject ?: error("$name is required")

private const val TEST_TOKEN = "server-token"
private const val PROTOCOL_VERSION_VALUE = 2L
