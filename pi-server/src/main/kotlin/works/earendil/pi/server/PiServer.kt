package works.earendil.pi.server

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.protocol.ClientMessageDecoder
import works.earendil.pi.protocol.PROTOCOL_VERSION
import works.earendil.pi.protocol.ProtocolValidationException
import works.earendil.pi.protocol.encodeServerMessage
import works.earendil.pi.protocol.isSupportedProtocolVersion

class PiServer(
    private val backend: PiSessionBackend,
    private val options: PiServerOptions,
) {
    private enum class ConnectionStage {
        AWAITING_HELLO,
        HANDSHAKING,
        READY,
        CLOSING,
        CLOSED,
    }

    private class ProtocolConnectionState(
        val id: String,
        val connection: ByteConnection,
        val decoder: ClientMessageDecoder,
        val sessionIds: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val queuedRequests: MutableList<JsonObject> = mutableListOf(),
        var stage: ConnectionStage = ConnectionStage.AWAITING_HELLO,
        var disconnected: Boolean = false,
        var handshakeComplete: Boolean = false,
        var handshakeJob: Job? = null,
        var timeoutJob: Job? = null,
    )

    private data class LiveSession(
        val id: String,
        val runtime: PiSessionRuntime,
        val connections: MutableSet<ProtocolConnectionState> = ConcurrentHashMap.newKeySet(),
        val operationCount: AtomicInteger = AtomicInteger(),
        val terminal: AtomicBoolean = AtomicBoolean(),
        var unsubscribe: ServerUnsubscribe = ServerUnsubscribe {},
        var disposing: CompletableDeferred<Unit>? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap.newKeySet<ProtocolConnectionState>()
    private val liveSessions = ConcurrentHashMap<String, LiveSession>()
    private val openingSessions = ConcurrentHashMap<String, CompletableDeferred<LiveSession>>()
    private val acquireMutex = Mutex()
    private val snapshotMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private var revision = 0L
    private var started = false
    private var starting = false
    private var closing = false

    val id: String
        get() = options.serverId

    val addresses: List<String>
        get() = options.listeners.mapNotNull(PiServerListener::address)

    suspend fun start(): PiServer {
        lifecycleMutex.withLock {
            check(!started) { "PiServer is already started" }
            check(!starting) { "PiServer is already starting" }
            check(!closing) { "PiServer is closing or closed" }
            starting = true
        }
        val startedListeners = mutableListOf<PiServerListener>()
        try {
            options.listeners.forEach { listener ->
                listener.start(::accept)
                startedListeners += listener
            }
            lifecycleMutex.withLock {
                started = true
            }
            return this
        } catch (error: Throwable) {
            lifecycleMutex.withLock {
                closing = true
            }
            startedListeners.forEach { listener -> runCatching { listener.close() }.onFailure(::reportError) }
            closeServerState()
            throw error
        } finally {
            lifecycleMutex.withLock {
                starting = false
            }
        }
    }

    fun accept(connection: ByteConnection): ByteConnectionHandler {
        if (closing) {
            scope.launch { closeConnection(connection) }
            return ByteConnectionHandler(
                onData = {},
                onClose = {},
                onError = ::reportError,
            )
        }
        val state =
            ProtocolConnectionState(
                id = UUID.randomUUID().toString(),
                connection = connection,
                decoder = ClientMessageDecoder(options.maxFrameLength),
            )
        state.timeoutJob =
            scope.launch {
                delay(options.handshakeTimeoutMs)
                failProtocol(
                    state,
                    protocolError("invalid_request", "Handshake timeout"),
                )
            }
        connections += state
        return ByteConnectionHandler(
            onData = { chunk -> receive(state, chunk) },
            onClose = { transportClosed(state) },
            onError = { error ->
                reportError(error)
                scope.launch {
                    closeConnection(connection)
                    disconnect(state)
                }
            },
        )
    }

    suspend fun close() {
        lifecycleMutex.withLock {
            if (closing) {
                return
            }
            closing = true
        }
        options.listeners.forEach { listener ->
            runCatching { listener.close() }.onFailure(::reportError)
        }
        closeServerState()
        lifecycleMutex.withLock {
            started = false
        }
        scope.cancel()
    }

    private fun receive(
        state: ProtocolConnectionState,
        chunk: ByteArray,
    ) {
        if (state.disconnected || state.stage == ConnectionStage.CLOSED) {
            return
        }
        val messages =
            try {
                state.decoder.push(chunk)
            } catch (error: Throwable) {
                scope.launch { failProtocol(state, toProtocolError(error)) }
                return
            }
        messages.forEach { message -> dispatchMessage(state, message) }
    }

    private fun dispatchMessage(
        state: ProtocolConnectionState,
        message: JsonObject,
    ) {
        if (state.stage == ConnectionStage.AWAITING_HELLO) {
            if (message.protocolString("type") != "hello") {
                scope.launch {
                    failProtocol(
                        state,
                        protocolError("invalid_request", "The first client message must be hello"),
                    )
                }
                return
            }
            state.stage = ConnectionStage.HANDSHAKING
            state.handshakeJob =
                scope.launch {
                    try {
                        finishHandshake(state, message)
                    } catch (error: Throwable) {
                        failProtocol(state, toProtocolError(error))
                    }
                }
            return
        }
        if (message.protocolString("type") == "hello") {
            scope.launch {
                failProtocol(
                    state,
                    protocolError("invalid_request", "hello may only be sent as the first message"),
                )
            }
            return
        }
        when (state.stage) {
            ConnectionStage.READY -> scope.launch { handleRequest(state, message) }
            ConnectionStage.HANDSHAKING ->
                synchronized(state.queuedRequests) {
                    state.queuedRequests += message
                }

            else -> Unit
        }
    }

    private suspend fun finishHandshake(
        state: ProtocolConnectionState,
        hello: JsonObject,
    ) {
        val version = hello.protocolLong("version")
        if (!isSupportedProtocolVersion(version)) {
            failProtocol(
                state,
                protocolError(
                    "version",
                    "Unsupported protocol version $version; expected $PROTOCOL_VERSION",
                ),
            )
            return
        }
        val snapshotRevision = revision
        val snapshot = serverSnapshot(state)
        if (closing || state.disconnected || state.stage != ConnectionStage.HANDSHAKING || state.connection.closed) {
            return
        }
        val sent =
            sendMessage(
                state,
                buildJsonObject {
                    put("type", "hello")
                    put("version", PROTOCOL_VERSION)
                    put("connectionId", state.id)
                    put("snapshot", snapshot)
                },
            )
        if (!sent || state.disconnected || state.stage != ConnectionStage.HANDSHAKING) {
            return
        }
        state.handshakeComplete = true
        state.stage = ConnectionStage.READY
        state.timeoutJob?.cancel()
        val queued =
            synchronized(state.queuedRequests) {
                state.queuedRequests.toList().also {
                    state.queuedRequests.clear()
                }
            }
        queued.forEach { request -> scope.launch { handleRequest(state, request) } }
        if (snapshotRevision != revision) {
            sendMessage(state, serverSnapshotEvent(serverSnapshot(state)))
        }
    }

    private suspend fun handleRequest(
        state: ProtocolConnectionState,
        envelope: JsonObject,
    ) {
        val requestId = envelope.protocolString("id")
        try {
            val result = executeCommand(state, envelope.protocolObject("request"))
            sendMessage(
                state,
                buildJsonObject {
                    put("type", "response")
                    put("id", requestId)
                    put("ok", true)
                    put("result", result)
                },
            )
        } catch (error: Throwable) {
            sendMessage(
                state,
                buildJsonObject {
                    put("type", "response")
                    put("id", requestId)
                    put("ok", false)
                    put("error", toProtocolError(error))
                },
            )
        }
    }

    private suspend fun executeCommand(
        connection: ProtocolConnectionState,
        command: JsonObject,
    ): JsonObject =
        when (command.protocolString("command")) {
            "list" ->
                buildJsonObject {
                    put("command", "list")
                    put("sessions", JsonArray(listSessionSummaries(connection)))
                }

            "create" -> {
                val sessionId = UUID.randomUUID().toString()
                val live =
                    acquire(sessionId) {
                        backend.createSession(
                            CreateProtocolSessionOptions(
                                id = sessionId,
                                cwd = command.optionalProtocolString("cwd"),
                                name = command.optionalProtocolString("name"),
                                model = command["model"] as? JsonObject,
                                thinkingLevel = command.optionalProtocolString("thinkingLevel"),
                            ),
                        )
                    }
                attach(connection, live)
                val snapshot = forConnection(broadcastSessionSnapshot(live), connection)
                broadcastServerSnapshot()
                buildJsonObject {
                    put("command", "create")
                    put("session", snapshot)
                }
            }

            "attach" -> {
                val sessionId = command.protocolString("sessionId")
                val live = acquire(sessionId) { backend.openSession(sessionId) }
                attach(connection, live)
                val snapshot = forConnection(broadcastSessionSnapshot(live), connection)
                broadcastServerSnapshot()
                buildJsonObject {
                    put("command", "attach")
                    put("session", snapshot)
                }
            }

            "detach" -> {
                val sessionId = command.protocolString("sessionId")
                val live = requireAttached(connection, sessionId)
                detach(connection, live)
                if (live.connections.isNotEmpty()) {
                    broadcastSessionSnapshot(live)
                }
                maybeDispose(live)
                broadcastServerSnapshot()
                buildJsonObject {
                    put("command", "detach")
                    put("sessionId", sessionId)
                }
            }

            "prompt" ->
                sessionOperation(connection, command) { runtime ->
                    runtime.prompt(command.protocolString("text"))
                }

            "steer" ->
                sessionOperation(connection, command) { runtime ->
                    runtime.steer(command.protocolString("text"))
                }

            "abort" ->
                sessionOperation(connection, command) { runtime ->
                    runtime.abort()
                }

            "set_model" ->
                sessionOperation(connection, command) { runtime ->
                    runtime.setModel(command.protocolObject("model"))
                }

            "set_thinking" ->
                sessionOperation(connection, command) { runtime ->
                    runtime.setThinking(command.protocolString("thinkingLevel"))
                }

            else -> throw PiServerException("invalid_request", "Unknown command")
        }

    private suspend fun sessionOperation(
        connection: ProtocolConnectionState,
        command: JsonObject,
        operation: suspend (PiSessionRuntime) -> Unit,
    ): JsonObject {
        val commandName = command.protocolString("command")
        val live = requireAttached(connection, command.protocolString("sessionId"))
        live.operationCount.incrementAndGet()
        try {
            operation(live.runtime)
            val snapshot = forConnection(broadcastSessionSnapshot(live), connection)
            return buildJsonObject {
                put("command", commandName)
                put("session", snapshot)
            }
        } finally {
            live.operationCount.decrementAndGet()
            scope.launch { maybeDispose(live) }
        }
    }

    private suspend fun acquire(
        id: String,
        createRuntime: suspend () -> PiSessionRuntime,
    ): LiveSession {
        while (true) {
            liveSessions[id]?.let { live ->
                if (live.terminal.get()) {
                    throw PiServerException("session_locked", "Session runtime is terminating: $id")
                }
                live.disposing?.await()
                if (liveSessions[id] === live) {
                    return live
                }
            }
            val (pending, owner) =
                acquireMutex.withLock {
                    openingSessions[id]?.let { return@withLock it to false }
                    CompletableDeferred<LiveSession>()
                        .also { openingSessions[id] = it }
                        .let { it to true }
                }
            if (owner) {
                try {
                    val runtime = createRuntime()
                    if (closing) {
                        runtime.dispose()
                        throw IllegalStateException("PiServer closed while acquiring a session runtime")
                    }
                    val snapshot = runtime.snapshot()
                    if (snapshot.protocolString("id") != id) {
                        runtime.dispose()
                        throw PiServerException(
                            "invalid_request",
                            "Backend returned session ${snapshot.protocolString("id")} for server-assigned session $id",
                        )
                    }
                    val live = LiveSession(id, runtime)
                    live.unsubscribe =
                        runtime.subscribe { event ->
                            handleRuntimeEvent(live, event)
                        }
                    liveSessions[id] = live
                    pending.complete(live)
                } catch (error: Throwable) {
                    pending.completeExceptionally(error)
                } finally {
                    acquireMutex.withLock {
                        openingSessions.remove(id, pending)
                    }
                }
            }
            return pending.await()
        }
    }

    private fun handleRuntimeEvent(
        live: LiveSession,
        event: PiSessionRuntimeEvent,
    ) {
        when (event) {
            is PiSessionRuntimeEvent.Error ->
                scope.launch {
                    terminate(live, event.error)
                }

            is PiSessionRuntimeEvent.Progress -> {
                val envelope =
                    buildJsonObject {
                        put("type", "event")
                        put(
                            "event",
                            buildJsonObject {
                                put("type", "session_progress")
                                put("sessionId", live.id)
                                put("progress", event.progress)
                            },
                        )
                    }
                live.connections.forEach { connection ->
                    scope.launch { sendMessage(connection, envelope) }
                }
            }

            PiSessionRuntimeEvent.Snapshot ->
                scope.launch {
                    runCatching { broadcastSessionSnapshot(live) }.onFailure(::reportError)
                }
        }
        scope.launch { maybeDispose(live) }
    }

    private suspend fun terminate(
        live: LiveSession,
        error: PiServerException,
    ) {
        if (!live.terminal.compareAndSet(false, true)) {
            return
        }
        reportError(error)
        live.unsubscribe.unsubscribe()
        val attached = live.connections.toList()
        attached.forEach { connection -> closeConnection(connection.connection) }
        attached.forEach { connection -> disconnect(connection) }
        maybeDispose(live)
    }

    private suspend fun normalizedSnapshot(live: LiveSession): JsonObject {
        val snapshot = live.runtime.snapshot()
        if (snapshot.protocolString("id") != live.id) {
            throw PiServerException(
                "invalid_request",
                "Runtime session ID changed from ${live.id} to ${snapshot.protocolString("id")}",
            )
        }
        return snapshot.copyWith(
            mapOf(
                "phase" to JsonPrimitive(live.runtime.getPhase()),
                "attached" to JsonPrimitive(live.connections.isNotEmpty()),
                "locked" to JsonPrimitive(true),
            ),
        )
    }

    private fun forConnection(
        snapshot: JsonObject,
        connection: ProtocolConnectionState,
    ): JsonObject =
        snapshot.copyWith(
            mapOf("attached" to JsonPrimitive(snapshot.protocolString("id") in connection.sessionIds)),
        )

    private suspend fun broadcastSessionSnapshot(live: LiveSession): JsonObject {
        val snapshot = normalizedSnapshot(live)
        val event =
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
        live.connections.forEach { connection ->
            scope.launch { sendMessage(connection, event) }
        }
        return snapshot
    }

    private suspend fun attach(
        connection: ProtocolConnectionState,
        live: LiveSession,
    ) {
        if (connection.disconnected || connection.stage != ConnectionStage.READY || connection.connection.closed) {
            maybeDispose(live)
            throw PiServerException("invalid_request", "Connection closed while attaching to a session")
        }
        connection.sessionIds += live.id
        live.connections += connection
    }

    private fun detach(
        connection: ProtocolConnectionState,
        live: LiveSession,
    ) {
        connection.sessionIds -= live.id
        live.connections -= connection
    }

    private fun requireAttached(
        connection: ProtocolConnectionState,
        sessionId: String,
    ): LiveSession {
        if (sessionId !in connection.sessionIds) {
            throw PiServerException("invalid_request", "Connection is not attached to session $sessionId")
        }
        return liveSessions[sessionId]
            ?.takeUnless { it.terminal.get() || it.disposing != null }
            ?: throw PiServerException("not_found", "Session is not live: $sessionId")
    }

    private suspend fun maybeDispose(live: LiveSession) {
        if (
            live.disposing != null ||
            live.connections.isNotEmpty() ||
            live.operationCount.get() > 0 ||
            (!live.terminal.get() && live.runtime.getPhase() != "idle")
        ) {
            return
        }
        val deferred = CompletableDeferred<Unit>()
        live.disposing = deferred
        live.unsubscribe.unsubscribe()
        try {
            live.runtime.dispose()
            liveSessions.remove(live.id, live)
            deferred.complete(Unit)
        } catch (error: Throwable) {
            live.disposing = null
            deferred.completeExceptionally(error)
            throw error
        }
    }

    private suspend fun listSessionSummaries(connection: ProtocolConnectionState?): List<JsonObject> {
        val stored = backend.listSessions()
        val liveSnapshots = liveSessions.values.associate { live -> live.id to normalizedSnapshot(live) }.toMutableMap()
        val summaries =
            stored.map { storedSummary ->
                val sessionId = storedSummary.protocolString("id")
                val live = liveSnapshots.remove(sessionId)
                if (live == null) {
                    storedSummary.copyWith(mapOf("attached" to JsonPrimitive(false)))
                } else {
                    toSummary(live).copyWith(
                        mapOf("attached" to JsonPrimitive(connection?.sessionIds?.contains(sessionId) == true)),
                    )
                }
            }.toMutableList()
        liveSnapshots.values.forEach { snapshot ->
            summaries +=
                toSummary(snapshot).copyWith(
                    mapOf(
                        "attached" to
                            JsonPrimitive(connection?.sessionIds?.contains(snapshot.protocolString("id")) == true),
                    ),
                )
        }
        return summaries
    }

    private suspend fun serverSnapshot(connection: ProtocolConnectionState?): JsonObject =
        buildJsonObject {
            put("serverId", id)
            put("protocolVersion", PROTOCOL_VERSION)
            put("revision", revision)
            put("sessions", JsonArray(listSessionSummaries(connection)))
            put("models", JsonArray(backend.listModels()))
        }

    private suspend fun broadcastServerSnapshot() {
        snapshotMutex.withLock {
            val ready =
                connections.filter {
                    it.stage == ConnectionStage.READY && !it.disconnected
                }
            if (ready.isEmpty() || closing) {
                return
            }
            revision += 1
            val models = backend.listModels()
            ready.forEach { connection ->
                val snapshot =
                    buildJsonObject {
                        put("serverId", id)
                        put("protocolVersion", PROTOCOL_VERSION)
                        put("revision", revision)
                        put("sessions", JsonArray(listSessionSummaries(connection)))
                        put("models", JsonArray(models))
                    }
                sendMessage(connection, serverSnapshotEvent(snapshot))
            }
        }
    }

    private suspend fun sendMessage(
        state: ProtocolConnectionState,
        message: JsonObject,
    ): Boolean {
        if (state.disconnected || state.connection.closed) {
            return false
        }
        val frame =
            try {
                encodeServerMessage(message, options.maxFrameLength)
            } catch (error: Throwable) {
                reportError(error)
                closeConnection(state.connection)
                disconnect(state)
                return false
            }
        return try {
            state.connection.send(frame)
            true
        } catch (error: Throwable) {
            reportError(error)
            closeConnection(state.connection)
            disconnect(state)
            false
        }
    }

    private fun transportClosed(state: ProtocolConnectionState) {
        if (!state.disconnected && state.stage != ConnectionStage.CLOSING) {
            runCatching(state.decoder::end).onFailure(::reportError)
        }
        scope.launch { disconnect(state) }
    }

    private suspend fun disconnect(state: ProtocolConnectionState) {
        if (state.disconnected) {
            return
        }
        val completed = state.handshakeComplete
        state.disconnected = true
        state.stage = ConnectionStage.CLOSED
        state.timeoutJob?.cancel()
        connections -= state
        val sessions = state.sessionIds.mapNotNull(liveSessions::get)
        state.sessionIds.clear()
        sessions.forEach { live -> live.connections -= state }
        sessions.forEach { live ->
            runCatching { maybeDispose(live) }.onFailure(::reportError)
        }
        if (!closing && completed) {
            runCatching { broadcastServerSnapshot() }.onFailure(::reportError)
        }
    }

    private suspend fun failProtocol(
        state: ProtocolConnectionState,
        error: JsonObject,
    ) {
        if (state.disconnected || state.stage == ConnectionStage.CLOSING || state.stage == ConnectionStage.CLOSED) {
            return
        }
        state.stage = ConnectionStage.CLOSING
        state.timeoutJob?.cancel()
        val finalFrame =
            runCatching {
                encodeServerMessage(
                    buildJsonObject {
                        put("type", "hello_error")
                        put("error", error)
                    },
                    options.maxFrameLength,
                )
            }.onFailure(::reportError).getOrNull()
        closeConnection(state.connection, finalFrame)
        disconnect(state)
    }

    private suspend fun closeServerState() {
        val currentConnections = connections.toList()
        currentConnections.forEach { state ->
            state.stage = ConnectionStage.CLOSING
            state.timeoutJob?.cancel()
        }
        currentConnections.forEach { state -> closeConnection(state.connection) }
        currentConnections.forEach { state -> disconnect(state) }
        openingSessions.values.forEach { pending ->
            runCatching { pending.await() }.onFailure(::reportError)
        }
        val sessions = liveSessions.values.toList()
        liveSessions.clear()
        sessions.forEach { live ->
            live.unsubscribe.unsubscribe()
            runCatching { live.runtime.dispose() }.onFailure(::reportError)
        }
        connections.clear()
    }

    private suspend fun closeConnection(
        connection: ByteConnection,
        finalChunk: ByteArray? = null,
    ) {
        runCatching { connection.close(finalChunk) }.onFailure(::reportError)
    }

    private fun toProtocolError(error: Throwable): JsonObject =
        when (error) {
            is PiServerException -> protocolError(error.code, error.message.orEmpty(), error.details)
            is ProtocolValidationException -> protocolError("invalid_request", error.message.orEmpty())
            else -> {
                reportError(error)
                protocolError("invalid_request", "Internal server error")
            }
        }

    private fun reportError(error: Throwable) {
        try {
            options.onError?.invoke(error)
        } catch (_: Throwable) {
            // Error observers cannot affect server state.
        }
    }
}

private fun protocolError(
    code: String,
    message: String,
    details: JsonElement? = null,
): JsonObject =
    buildJsonObject {
        put("code", code)
        put("message", message)
        details?.let { put("details", it) }
    }

private fun serverSnapshotEvent(snapshot: JsonObject): JsonObject =
    buildJsonObject {
        put("type", "event")
        put(
            "event",
            buildJsonObject {
                put("type", "server_snapshot")
                put("snapshot", snapshot)
            },
        )
    }

private fun toSummary(snapshot: JsonObject): JsonObject =
    JsonObject(
        snapshot.filterKeys {
            it in
                setOf(
                    "id",
                    "name",
                    "cwd",
                    "createdAt",
                    "updatedAt",
                    "phase",
                    "model",
                    "thinkingLevel",
                    "attached",
                    "locked",
                )
        },
    )

private fun JsonObject.copyWith(values: Map<String, JsonElement>): JsonObject =
    JsonObject(toMutableMap().also { it.putAll(values) })

private fun JsonObject.protocolString(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull ?: error("$name is required")

private fun JsonObject.optionalProtocolString(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.protocolLong(name: String): Long =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: error("$name is required")

private fun JsonObject.protocolObject(name: String): JsonObject =
    this[name]?.jsonObject ?: error("$name is required")
