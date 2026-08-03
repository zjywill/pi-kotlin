package works.earendil.pi.client

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.protocol.DEFAULT_MAX_FRAME_LENGTH
import works.earendil.pi.protocol.PROTOCOL_VERSION
import works.earendil.pi.protocol.ProtocolValidationException
import works.earendil.pi.protocol.ServerMessageDecoder
import works.earendil.pi.protocol.encodeClientMessage

enum class ConnectionState(
    val wireValue: String,
) {
    DISCONNECTED("disconnected"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
}

data class ConnectionStateChange(
    val state: ConnectionState,
    val error: Throwable? = null,
)

fun interface Unsubscribe {
    fun unsubscribe()
}

data class ByteTransportHandlers(
    val onData: (ByteArray) -> Unit,
    val onClose: () -> Unit,
    val onError: (Throwable) -> Unit,
)

interface ByteTransport {
    suspend fun send(chunk: ByteArray)

    fun close()
}

fun interface ByteTransportFactory {
    suspend fun connect(handlers: ByteTransportHandlers): ByteTransport
}

data class PiClientOptions(
    val token: String,
    val transportFactory: ByteTransportFactory,
    val maxFrameLength: Int = DEFAULT_MAX_FRAME_LENGTH,
    val onListenerError: ((Throwable) -> Unit)? = null,
) {
    init {
        require(token.isNotEmpty()) { "PiClient token must not be empty" }
        require(maxFrameLength > 0) { "PiClient maxFrameLength must be positive" }
    }
}

open class PiClientException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class PiDisconnectedException(
    message: String = "PiClient is disconnected",
    cause: Throwable? = null,
) : PiClientException(message, cause)

class PiClientDisposedException : PiClientException("PiClient is disposed")

class PiSessionDetachedException(
    val sessionId: String,
) : PiClientException("Pi session is detached: $sessionId")

class PiSessionOwnershipException(
    val sessionId: String,
    message: String,
) : PiClientException(message)

class PiServerException(
    val code: String,
    message: String,
    val details: kotlinx.serialization.json.JsonElement? = null,
) : PiClientException(message)

enum class SessionLeaseMode {
    SHARED,
    EXCLUSIVE,
}

internal class SessionLeaseToken(
    val mode: SessionLeaseMode,
)

internal enum class SessionLeaseState {
    ACTIVE,
    RELEASING,
    RELEASED,
    INVALIDATED,
}

class PiSessionHandle internal constructor(
    val id: String,
    private val client: PiClient,
    internal val token: SessionLeaseToken,
    internal val generation: Long,
) {
    internal val releaseMutex = Mutex()
    internal var state = SessionLeaseState.ACTIVE

    val attached: Boolean
        get() = client.isHandleActive(this)

    val active: Boolean
        get() = attached

    val snapshot: JsonObject?
        get() = client.getHandleSnapshot(this)

    fun subscribe(listener: (JsonObject) -> Unit): Unsubscribe =
        client.subscribeHandle(this, listener)

    fun onEvent(listener: (JsonObject) -> Unit): Unsubscribe =
        client.onHandleEvent(this, listener)

    suspend fun detach() {
        client.releaseHandle(this, relinquishOnFailure = false)
    }

    suspend fun dispose() {
        client.releaseHandle(this, relinquishOnFailure = true)
    }

    suspend fun prompt(text: String): JsonObject =
        requestSnapshot(
            buildJsonObject {
                put("command", "prompt")
                put("sessionId", id)
                put("text", text)
            },
        )

    suspend fun steer(text: String): JsonObject =
        requestSnapshot(
            buildJsonObject {
                put("command", "steer")
                put("sessionId", id)
                put("text", text)
            },
        )

    suspend fun abort(): JsonObject =
        requestSnapshot(
            buildJsonObject {
                put("command", "abort")
                put("sessionId", id)
            },
        )

    suspend fun setModel(
        provider: String,
        modelId: String,
    ): JsonObject =
        requestSnapshot(
            buildJsonObject {
                put("command", "set_model")
                put("sessionId", id)
                put(
                    "model",
                    buildJsonObject {
                        put("provider", provider)
                        put("id", modelId)
                    },
                )
            },
        )

    suspend fun setThinking(thinkingLevel: String): JsonObject =
        requestSnapshot(
            buildJsonObject {
                put("command", "set_thinking")
                put("sessionId", id)
                put("thinkingLevel", thinkingLevel)
            },
        )

    private suspend fun requestSnapshot(command: JsonObject): JsonObject =
        client.handleRequest(this, command).objectValue("session")
}

class PiClient(
    private val options: PiClientOptions,
) {
    private data class PendingRequest(
        val command: String,
        val result: CompletableDeferred<JsonObject>,
    )

    private data class ActiveConnection(
        val id: Long,
        val decoder: ServerMessageDecoder,
        val handshake: CompletableDeferred<JsonObject>,
        var transport: ByteTransport? = null,
        var helloSent: Boolean = false,
    )

    private val lock = Any()
    private val connectionSequence = AtomicLong()
    private val requestSequence = AtomicLong()
    private var activeConnection: ActiveConnection? = null
    private var state = ConnectionState.DISCONNECTED
    private var disposed = false
    private var disposeResult: CompletableDeferred<Unit>? = null
    private val pendingRequests = linkedMapOf<String, PendingRequest>()
    private val attachedSessionIds = linkedSetOf<String>()
    private val sessionSnapshots = linkedMapOf<String, JsonObject>()
    private val sessionLeaseCounts = linkedMapOf<String, Int>()
    private val exclusiveSessionLeases = linkedMapOf<String, SessionLeaseToken>()
    private val sessionLeaseGenerations = linkedMapOf<String, Long>()
    private val sessionAttachments = linkedMapOf<String, CompletableDeferred<Unit>>()
    private val sessionDetachments = linkedMapOf<String, CompletableDeferred<Unit>>()
    private val sessionCleanupRequired = linkedSetOf<String>()
    private val sessionReconciliations = linkedMapOf<String, CompletableDeferred<Unit>>()
    private var serverSnapshot: JsonObject? = null
    private val snapshotListeners = linkedSetOf<(JsonObject) -> Unit>()
    private val eventListeners = linkedSetOf<(JsonObject) -> Unit>()
    private val connectionListeners = linkedSetOf<(ConnectionStateChange) -> Unit>()
    private val sessionSnapshotListeners = linkedMapOf<String, LinkedHashSet<(JsonObject) -> Unit>>()
    private val sessionEventListeners = linkedMapOf<String, LinkedHashSet<(JsonObject) -> Unit>>()

    val connectionState: ConnectionState
        get() = synchronized(lock) { state }

    val connected: Boolean
        get() = connectionState == ConnectionState.CONNECTED

    val isDisposed: Boolean
        get() = synchronized(lock) { disposed }

    val snapshot: JsonObject?
        get() = synchronized(lock) { serverSnapshot }

    suspend fun connect(): JsonObject {
        val connection =
            synchronized(lock) {
                assertNotDisposedLocked()
                if (state != ConnectionState.DISCONNECTED) {
                    throw PiDisconnectedException("PiClient is already ${state.wireValue}")
                }
                resetStateLocked()
                ActiveConnection(
                    id = connectionSequence.incrementAndGet(),
                    decoder = ServerMessageDecoder(options.maxFrameLength),
                    handshake = CompletableDeferred(),
                ).also {
                    activeConnection = it
                    state = ConnectionState.CONNECTING
                }
            }
        notifyConnectionState(ConnectionStateChange(ConnectionState.CONNECTING))
        val handlers =
            ByteTransportHandlers(
                onData = { chunk -> handleData(connection.id, chunk) },
                onClose = { handleClose(connection.id) },
                onError = { error -> failConnection(connection.id, disconnectedError(error), close = true) },
            )
        val transport =
            try {
                options.transportFactory.connect(handlers)
            } catch (error: Throwable) {
                failConnection(connection.id, disconnectedError(error), close = false)
                return connection.handshake.await()
            }
        val current = synchronized(lock) { activeConnection }
        if (current !== connection) {
            transport.close()
            return connection.handshake.await()
        }
        connection.transport = transport
        try {
            connection.helloSent = true
            transport.send(
                encodeClientMessage(
                    buildJsonObject {
                        put("type", "hello")
                        put("version", PROTOCOL_VERSION)
                        put("token", options.token)
                    },
                    options.maxFrameLength,
                ),
            )
        } catch (error: Throwable) {
            failConnection(connection.id, disconnectedError(error), close = true)
        }
        return connection.handshake.await()
    }

    suspend fun reconnect(): JsonObject = connect()

    fun disconnect(reason: String = "Client disconnected") {
        val connection = synchronized(lock) { activeConnection } ?: return
        failConnection(connection.id, PiDisconnectedException(reason), close = true)
    }

    fun subscribe(listener: (JsonObject) -> Unit): Unsubscribe =
        addListener(snapshotListeners, listener)

    fun onEvent(listener: (JsonObject) -> Unit): Unsubscribe =
        addListener(eventListeners, listener)

    fun onConnectionStateChange(listener: (ConnectionStateChange) -> Unit): Unsubscribe =
        addListener(connectionListeners, listener)

    suspend fun listSessions(): List<JsonObject> =
        request(buildJsonObject { put("command", "list") })
            .array("sessions")
            .map { it.jsonObject }

    suspend fun createSession(
        cwd: String? = null,
        name: String? = null,
        provider: String? = null,
        modelId: String? = null,
        thinkingLevel: String? = null,
    ): PiSessionHandle {
        val result =
            request(
                buildJsonObject {
                    put("command", "create")
                    cwd?.let { put("cwd", it) }
                    name?.let { put("name", it) }
                    if (provider != null && modelId != null) {
                        put(
                            "model",
                            buildJsonObject {
                                put("provider", provider)
                                put("id", modelId)
                            },
                        )
                    }
                    thinkingLevel?.let { put("thinkingLevel", it) }
                },
            )
        val sessionId = result.objectValue("session").string("id")
        val token = reserveSessionLease(sessionId, SessionLeaseMode.EXCLUSIVE)
        return createSessionHandle(sessionId, token)
    }

    suspend fun attachSession(sessionId: String): PiSessionHandle =
        acquireSession(sessionId, SessionLeaseMode.SHARED)

    suspend fun acquireSession(
        sessionId: String,
        mode: SessionLeaseMode,
    ): PiSessionHandle {
        synchronized(lock) {
            assertNotDisposedLocked()
        }
        val token = reserveSessionLease(sessionId, mode)
        try {
            synchronized(lock) { sessionDetachments[sessionId] }
                ?.let { detachment -> runCatching { detachment.await() } }
            val reconciled = reconcileSessionCleanup(sessionId)
            if (reconciled || !isSessionAttached(sessionId)) {
                attachSessionOnce(sessionId)
            }
            return createSessionHandle(sessionId, token)
        } catch (error: Throwable) {
            releaseSessionLease(sessionId, token)
            throw error
        }
    }

    suspend fun dispose() {
        val (result, owner) =
            synchronized(lock) {
                disposeResult?.let { return@synchronized it to false }
                CompletableDeferred<Unit>().also { disposeResult = it } to true
            }
        if (!owner) {
            result.await()
            return
        }
        val error = PiClientDisposedException()
        val connection =
            synchronized(lock) {
                disposed = true
                activeConnection
            }
        if (connection != null) {
            failConnection(connection.id, error, close = true)
        } else {
            val pending =
                synchronized(lock) {
                    state = ConnectionState.DISCONNECTED
                    invalidateAllSessionLeasesLocked()
                    pendingRequests.values.toList().also { pendingRequests.clear() }
                }
            pending.forEach { request -> request.result.completeExceptionally(error) }
        }
        synchronized(lock) {
            snapshotListeners.clear()
            eventListeners.clear()
            connectionListeners.clear()
            sessionSnapshotListeners.clear()
            sessionEventListeners.clear()
        }
        result.complete(Unit)
        result.await()
    }

    internal fun isHandleActive(handle: PiSessionHandle): Boolean =
        synchronized(lock) {
            refreshHandleStateLocked(handle)
            handle.state == SessionLeaseState.ACTIVE && handle.id in attachedSessionIds
        }

    internal fun getHandleSnapshot(handle: PiSessionHandle): JsonObject? =
        synchronized(lock) {
            refreshHandleStateLocked(handle)
            if (handle.state == SessionLeaseState.ACTIVE && handle.id in attachedSessionIds) {
                sessionSnapshots[handle.id]
            } else {
                null
            }
        }

    internal fun subscribeHandle(
        handle: PiSessionHandle,
        listener: (JsonObject) -> Unit,
    ): Unsubscribe {
        assertHandleActive(handle)
        return addMappedListener(sessionSnapshotListeners, handle.id) { snapshot ->
            if (isHandleActive(handle)) {
                listener(snapshot)
            }
        }
    }

    internal fun onHandleEvent(
        handle: PiSessionHandle,
        listener: (JsonObject) -> Unit,
    ): Unsubscribe {
        assertHandleActive(handle)
        return addMappedListener(sessionEventListeners, handle.id) { event ->
            if (isHandleActive(handle) || event.string("type") == "session_removed") {
                listener(event)
            }
        }
    }

    internal suspend fun handleRequest(
        handle: PiSessionHandle,
        command: JsonObject,
    ): JsonObject {
        assertHandleActive(handle)
        return request(command)
    }

    internal suspend fun releaseHandle(
        handle: PiSessionHandle,
        relinquishOnFailure: Boolean,
    ) {
        handle.releaseMutex.withLock {
            synchronized(lock) {
                refreshHandleStateLocked(handle)
                if (
                    handle.state == SessionLeaseState.RELEASED ||
                    handle.state == SessionLeaseState.INVALIDATED
                ) {
                    return
                }
                assertHandleActiveLocked(handle)
                handle.state = SessionLeaseState.RELEASING
            }
            try {
                val count = synchronized(lock) { sessionLeaseCounts[handle.id] ?: 0 }
                if (count <= 1) {
                    val detachment = CompletableDeferred<Unit>()
                    synchronized(lock) {
                        sessionDetachments[handle.id] = detachment
                    }
                    try {
                        request(
                            buildJsonObject {
                                put("command", "detach")
                                put("sessionId", handle.id)
                            },
                        )
                        releaseSessionLease(handle.id, handle.token)
                        detachment.complete(Unit)
                    } catch (error: Throwable) {
                        detachment.completeExceptionally(error)
                        throw error
                    } finally {
                        synchronized(lock) {
                            sessionDetachments.remove(handle.id, detachment)
                        }
                    }
                } else {
                    releaseSessionLease(handle.id, handle.token)
                }
                synchronized(lock) {
                    handle.state = SessionLeaseState.RELEASED
                }
            } catch (error: Throwable) {
                val invalidated =
                    synchronized(lock) {
                        refreshHandleStateLocked(handle)
                        handle.state == SessionLeaseState.INVALIDATED
                    }
                if (invalidated) {
                    return
                }
                synchronized(lock) {
                    if (relinquishOnFailure) {
                        releaseSessionLeaseLocked(handle.id, handle.token)
                        sessionCleanupRequired += handle.id
                        handle.state = SessionLeaseState.RELEASED
                    } else {
                        handle.state = SessionLeaseState.ACTIVE
                    }
                }
                throw error
            }
        }
    }

    private suspend fun attachSessionOnce(sessionId: String) {
        val (attachment, owner) =
            synchronized(lock) {
                sessionAttachments[sessionId]?.let { return@synchronized it to false }
                CompletableDeferred<Unit>().also { sessionAttachments[sessionId] = it } to true
            }
        if (owner) {
            val previous =
                synchronized(lock) {
                    sessionSnapshots.remove(sessionId)
                }
            try {
                request(
                    buildJsonObject {
                        put("command", "attach")
                        put("sessionId", sessionId)
                    },
                )
                attachment.complete(Unit)
            } catch (error: Throwable) {
                if (previous != null) {
                    synchronized(lock) {
                        sessionSnapshots.putIfAbsent(sessionId, previous)
                    }
                }
                attachment.completeExceptionally(error)
            } finally {
                synchronized(lock) {
                    sessionAttachments.remove(sessionId, attachment)
                }
            }
        }
        attachment.await()
    }

    private suspend fun reconcileSessionCleanup(sessionId: String): Boolean {
        val required = synchronized(lock) { sessionId in sessionCleanupRequired }
        if (!required) {
            return false
        }
        val (reconciliation, owner) =
            synchronized(lock) {
                sessionReconciliations[sessionId]?.let { return@synchronized it to false }
                CompletableDeferred<Unit>().also { sessionReconciliations[sessionId] = it } to true
            }
        if (owner) {
            try {
                request(
                    buildJsonObject {
                        put("command", "detach")
                        put("sessionId", sessionId)
                    },
                )
                synchronized(lock) {
                    sessionCleanupRequired -= sessionId
                }
                reconciliation.complete(Unit)
            } catch (error: Throwable) {
                reconciliation.completeExceptionally(error)
            } finally {
                synchronized(lock) {
                    sessionReconciliations.remove(sessionId, reconciliation)
                }
            }
        }
        reconciliation.await()
        return true
    }

    private suspend fun request(command: JsonObject): JsonObject {
        val connection =
            synchronized(lock) {
                assertNotDisposedLocked()
                activeConnection?.takeIf { state == ConnectionState.CONNECTED }
            } ?: throw PiDisconnectedException()
        val commandName = command.string("command")
        val id = "request-${requestSequence.incrementAndGet()}"
        val result = CompletableDeferred<JsonObject>()
        synchronized(lock) {
            pendingRequests[id] = PendingRequest(commandName, result)
        }
        val frame =
            try {
                encodeClientMessage(
                    buildJsonObject {
                        put("type", "request")
                        put("id", id)
                        put("request", command)
                    },
                    options.maxFrameLength,
                )
            } catch (error: Throwable) {
                takePending(id)?.result?.completeExceptionally(error)
                return result.await()
            }
        try {
            requireNotNull(connection.transport).send(frame)
        } catch (error: Throwable) {
            failConnection(connection.id, disconnectedError(error), close = true)
        }
        return result.await()
    }

    private fun handleData(
        connectionId: Long,
        chunk: ByteArray,
    ) {
        val connection =
            synchronized(lock) {
                activeConnection?.takeIf { it.id == connectionId }
            } ?: return
        if (!connection.helloSent) {
            failConnection(
                connectionId,
                ProtocolValidationException("Received server data before the client hello was sent"),
                close = true,
            )
            return
        }
        val messages =
            try {
                connection.decoder.push(chunk)
            } catch (error: Throwable) {
                failConnection(connectionId, error, close = true)
                return
            }
        messages.forEach { message ->
            if (synchronized(lock) { activeConnection?.id } != connectionId) {
                return
            }
            handleMessage(connection, message)
        }
    }

    private fun handleMessage(
        connection: ActiveConnection,
        message: JsonObject,
    ) {
        val type = message.string("type")
        if (connectionState == ConnectionState.CONNECTING) {
            if (type == "hello_error") {
                failConnection(connection.id, serverError(message.objectValue("error")), close = true)
                return
            }
            if (type != "hello") {
                failConnection(
                    connection.id,
                    ProtocolValidationException("Expected server hello as first message"),
                    close = true,
                )
                return
            }
            val handshakeSnapshot = message.objectValue("snapshot")
            applyServerSnapshot(handshakeSnapshot)
            synchronized(lock) {
                if (activeConnection !== connection || state != ConnectionState.CONNECTING) {
                    return
                }
                state = ConnectionState.CONNECTED
            }
            notifyConnectionState(ConnectionStateChange(ConnectionState.CONNECTED))
            synchronized(lock) {
                if (activeConnection === connection && state == ConnectionState.CONNECTED) {
                    connection.handshake.complete(handshakeSnapshot)
                }
            }
            return
        }
        if (connectionState != ConnectionState.CONNECTED) {
            return
        }
        if (type == "hello" || type == "hello_error") {
            failConnection(
                connection.id,
                ProtocolValidationException("Unexpected handshake message"),
                close = true,
            )
            return
        }
        if (type == "event") {
            applyEvent(message.objectValue("event"))
            return
        }
        val id = message.string("id")
        val pending = takePending(id)
        if (pending == null) {
            failConnection(
                connection.id,
                ProtocolValidationException("Response has no matching request"),
                close = true,
            )
            return
        }
        if (!message.boolean("ok")) {
            pending.result.completeExceptionally(serverError(message.objectValue("error")))
            return
        }
        val result = message.objectValue("result")
        val responseCommand = result.string("command")
        if (responseCommand != pending.command) {
            val error =
                ProtocolValidationException(
                    "Response command $responseCommand does not match ${pending.command}",
                )
            pending.result.completeExceptionally(error)
            failConnection(connection.id, error, close = true)
            return
        }
        applyResult(result)
        pending.result.complete(result)
    }

    private fun applyResult(result: JsonObject) {
        when (result.string("command")) {
            "list" -> Unit
            "detach" -> {
                val sessionId = result.string("sessionId")
                val current =
                    synchronized(lock) {
                        attachedSessionIds.remove(sessionId)
                        sessionSnapshots[sessionId]
                    }
                if (current != null) {
                    applySessionSnapshot(current.copyWith("attached", JsonPrimitive(false)), force = true)
                }
            }

            else -> applySessionSnapshot(result.objectValue("session"))
        }
    }

    private fun applyEvent(event: JsonObject) {
        when (event.string("type")) {
            "server_snapshot" -> applyServerSnapshot(event.objectValue("snapshot"))
            "session_snapshot" -> applySessionSnapshot(event.objectValue("snapshot"))
            "session_removed" -> {
                val sessionId = event.string("sessionId")
                synchronized(lock) {
                    sessionSnapshots.remove(sessionId)
                    attachedSessionIds.remove(sessionId)
                    invalidateSessionLeasesLocked(sessionId)
                }
            }
        }
        notifyListeners(eventListeners, event)
        eventSessionId(event)?.let { sessionId ->
            notifyListeners(synchronized(lock) { sessionEventListeners[sessionId]?.toList().orEmpty() }, event)
        }
    }

    private fun applyServerSnapshot(value: JsonObject) {
        val shouldApply =
            synchronized(lock) {
                val currentRevision = serverSnapshot?.long("revision")
                val revision = value.long("revision")
                if (currentRevision != null && revision < currentRevision) {
                    false
                } else {
                    serverSnapshot = value
                    attachedSessionIds.clear()
                    value.array("sessions").forEach { summary ->
                        val objectValue = summary.jsonObject
                        if (objectValue.boolean("attached")) {
                            attachedSessionIds += objectValue.string("id")
                        }
                    }
                    true
                }
            }
        if (shouldApply) {
            notifyListeners(snapshotListeners, value)
        }
    }

    private fun applySessionSnapshot(
        value: JsonObject,
        force: Boolean = false,
    ) {
        val sessionId = value.string("id")
        val shouldApply =
            synchronized(lock) {
                val currentRevision = sessionSnapshots[sessionId]?.long("revision")
                val revision = value.long("revision")
                if (!force && currentRevision != null && revision < currentRevision) {
                    false
                } else {
                    sessionSnapshots[sessionId] = value
                    if (value.boolean("attached")) {
                        attachedSessionIds += sessionId
                    } else {
                        attachedSessionIds -= sessionId
                    }
                    true
                }
            }
        if (shouldApply) {
            notifyListeners(
                synchronized(lock) { sessionSnapshotListeners[sessionId]?.toList().orEmpty() },
                value,
            )
        }
    }

    private fun handleClose(connectionId: Long) {
        val connection =
            synchronized(lock) {
                activeConnection?.takeIf { it.id == connectionId }
            } ?: return
        val error =
            try {
                connection.decoder.end()
                PiDisconnectedException("Byte transport closed")
            } catch (decoderError: Throwable) {
                decoderError
            }
        failConnection(connectionId, error, close = false)
    }

    private fun failConnection(
        connectionId: Long,
        error: Throwable,
        close: Boolean,
    ) {
        val connection =
            synchronized(lock) {
                val current = activeConnection?.takeIf { it.id == connectionId } ?: return
                activeConnection = null
                state = ConnectionState.DISCONNECTED
                attachedSessionIds.clear()
                invalidateAllSessionLeasesLocked()
                current
            }
        if (close) {
            runCatching { connection.transport?.close() }
        }
        connection.handshake.completeExceptionally(error)
        val pending =
            synchronized(lock) {
                pendingRequests.values.toList().also { pendingRequests.clear() }
            }
        pending.forEach { request -> request.result.completeExceptionally(error) }
        notifyConnectionState(ConnectionStateChange(ConnectionState.DISCONNECTED, error))
    }

    private fun reserveSessionLease(
        sessionId: String,
        mode: SessionLeaseMode,
    ): SessionLeaseToken =
        synchronized(lock) {
            assertNotDisposedLocked()
            val count = sessionLeaseCounts[sessionId] ?: 0
            if (mode == SessionLeaseMode.EXCLUSIVE && count > 0) {
                throw PiSessionOwnershipException(
                    sessionId,
                    "Session $sessionId already has an active lease",
                )
            }
            if (mode == SessionLeaseMode.SHARED && sessionId in exclusiveSessionLeases) {
                throw PiSessionOwnershipException(
                    sessionId,
                    "Session $sessionId has an exclusive lease",
                )
            }
            SessionLeaseToken(mode).also { token ->
                sessionLeaseCounts[sessionId] = count + 1
                if (mode == SessionLeaseMode.EXCLUSIVE) {
                    exclusiveSessionLeases[sessionId] = token
                }
            }
        }

    private fun releaseSessionLease(
        sessionId: String,
        token: SessionLeaseToken,
    ) {
        synchronized(lock) {
            releaseSessionLeaseLocked(sessionId, token)
        }
    }

    private fun releaseSessionLeaseLocked(
        sessionId: String,
        token: SessionLeaseToken,
    ) {
        val count = sessionLeaseCounts[sessionId] ?: 0
        if (count <= 1) {
            sessionLeaseCounts.remove(sessionId)
        } else {
            sessionLeaseCounts[sessionId] = count - 1
        }
        if (exclusiveSessionLeases[sessionId] === token) {
            exclusiveSessionLeases.remove(sessionId)
        }
    }

    private fun createSessionHandle(
        sessionId: String,
        token: SessionLeaseToken,
    ): PiSessionHandle =
        synchronized(lock) {
            PiSessionHandle(
                id = sessionId,
                client = this,
                token = token,
                generation = sessionLeaseGenerations[sessionId] ?: 0,
            )
        }

    private fun isSessionAttached(sessionId: String): Boolean =
        synchronized(lock) {
            sessionId in attachedSessionIds
        }

    private fun assertHandleActive(handle: PiSessionHandle) {
        synchronized(lock) {
            assertHandleActiveLocked(handle)
        }
    }

    private fun assertHandleActiveLocked(handle: PiSessionHandle) {
        assertNotDisposedLocked()
        if (state != ConnectionState.CONNECTED) {
            throw PiDisconnectedException()
        }
        refreshHandleStateLocked(handle)
        if (handle.state != SessionLeaseState.ACTIVE || handle.id !in attachedSessionIds) {
            throw PiSessionDetachedException(handle.id)
        }
    }

    private fun refreshHandleStateLocked(handle: PiSessionHandle) {
        if (
            (
                handle.state == SessionLeaseState.ACTIVE ||
                    handle.state == SessionLeaseState.RELEASING
            ) &&
            (sessionLeaseGenerations[handle.id] ?: 0) != handle.generation
        ) {
            handle.state = SessionLeaseState.INVALIDATED
        }
    }

    private fun invalidateSessionLeasesLocked(sessionId: String) {
        sessionLeaseCounts.remove(sessionId)
        exclusiveSessionLeases.remove(sessionId)
        sessionCleanupRequired.remove(sessionId)
        sessionLeaseGenerations[sessionId] = (sessionLeaseGenerations[sessionId] ?: 0) + 1
    }

    private fun invalidateAllSessionLeasesLocked() {
        sessionLeaseCounts.keys.toList().forEach(::invalidateSessionLeasesLocked)
        sessionCleanupRequired.clear()
    }

    private fun resetStateLocked() {
        serverSnapshot = null
        sessionSnapshots.clear()
        attachedSessionIds.clear()
    }

    private fun assertNotDisposedLocked() {
        if (disposed) {
            throw PiClientDisposedException()
        }
    }

    private fun takePending(id: String): PendingRequest? =
        synchronized(lock) {
            pendingRequests.remove(id)
        }

    private fun notifyConnectionState(change: ConnectionStateChange) {
        notifyListeners(connectionListeners, change)
    }

    private fun <T> addListener(
        listeners: MutableSet<(T) -> Unit>,
        listener: (T) -> Unit,
    ): Unsubscribe {
        synchronized(lock) {
            assertNotDisposedLocked()
            listeners += listener
        }
        return Unsubscribe {
            synchronized(lock) {
                listeners -= listener
            }
        }
    }

    private fun <T> addMappedListener(
        listeners: MutableMap<String, LinkedHashSet<(T) -> Unit>>,
        id: String,
        listener: (T) -> Unit,
    ): Unsubscribe {
        synchronized(lock) {
            assertNotDisposedLocked()
            listeners.getOrPut(id, ::linkedSetOf) += listener
        }
        return Unsubscribe {
            synchronized(lock) {
                listeners[id]?.let { mapped ->
                    mapped -= listener
                    if (mapped.isEmpty()) {
                        listeners.remove(id)
                    }
                }
            }
        }
    }

    private fun <T> notifyListeners(
        listeners: Iterable<(T) -> Unit>,
        value: T,
    ) {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { listener ->
            try {
                listener(value)
            } catch (error: Throwable) {
                reportListenerError(error)
            }
        }
    }

    private fun reportListenerError(error: Throwable) {
        try {
            options.onListenerError?.invoke(error)
        } catch (_: Throwable) {
            // Diagnostics must not affect protocol state.
        }
    }

    companion object {
        suspend fun connect(options: PiClientOptions): PiClient {
            val client = PiClient(options)
            try {
                client.connect()
                return client
            } catch (error: Throwable) {
                client.dispose()
                throw error
            }
        }
    }
}

private fun disconnectedError(error: Throwable): PiDisconnectedException =
    if (error is PiDisconnectedException) {
        error
    } else {
        PiDisconnectedException(error.message ?: "Byte transport failed", error)
    }

private fun serverError(error: JsonObject): PiServerException =
    PiServerException(
        code = error.string("code"),
        message = error.string("message"),
        details = error["details"],
    )

private fun eventSessionId(event: JsonObject): String? =
    when (event.string("type")) {
        "session_snapshot" -> event.objectValue("snapshot").string("id")
        "session_progress",
        "session_removed",
        -> event.string("sessionId")

        else -> null
    }

private fun JsonObject.copyWith(
    key: String,
    value: kotlinx.serialization.json.JsonElement,
): JsonObject =
    JsonObject(toMutableMap().also { it[key] = value })

private fun JsonObject.string(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull ?: error("$name is required")

private fun JsonObject.boolean(name: String): Boolean =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
        ?: error("$name is required")

private fun JsonObject.long(name: String): Long =
    (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        ?: error("$name is required")

private fun JsonObject.array(name: String): JsonArray =
    this[name] as? JsonArray ?: error("$name is required")

private fun JsonObject.objectValue(name: String): JsonObject =
    this[name]?.jsonObject ?: error("$name is required")
