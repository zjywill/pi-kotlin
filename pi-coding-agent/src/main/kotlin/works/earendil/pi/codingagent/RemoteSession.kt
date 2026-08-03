package works.earendil.pi.codingagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import works.earendil.pi.client.ConnectionState
import works.earendil.pi.client.ConnectionStateChange
import works.earendil.pi.client.PiClient
import works.earendil.pi.client.PiSessionHandle
import works.earendil.pi.client.SessionLeaseMode
import works.earendil.pi.client.Unsubscribe

enum class RemoteSessionOperation {
    OPEN,
    CREATE,
    SUBMIT,
    ABORT,
    SET_MODEL,
    SET_THINKING,
    RECONNECT,
}

sealed interface RemoteSessionLifecycle {
    data object Unbound : RemoteSessionLifecycle

    data object Ready : RemoteSessionLifecycle

    data class Busy(
        val operation: RemoteSessionOperation,
    ) : RemoteSessionLifecycle

    data object Disposed : RemoteSessionLifecycle
}

data class RemoteSessionState(
    val lifecycle: RemoteSessionLifecycle,
    val snapshot: JsonObject? = null,
    val transcript: List<JsonObject> = emptyList(),
)

data class CreateRemoteSessionOptions(
    val cwd: String,
    val provider: String? = null,
    val modelId: String? = null,
    val thinkingLevel: String? = null,
)

data class RemoteSessionOptions(
    val onListenerError: ((Throwable) -> Unit)? = null,
)

private class RemoteSessionDisposedException : IllegalStateException("Remote session is disposed")

class RemoteSession private constructor(
    private val client: PiClient,
    private val options: RemoteSessionOptions = RemoteSessionOptions(),
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val disposeSignal = CompletableDeferred<Unit>()
    private var lifecycle: RemoteSessionLifecycle = RemoteSessionLifecycle.Unbound
    private var handle: PiSessionHandle? = null
    private var transcriptState: TranscriptState? = null
    private var unsubscribeSnapshot: Unsubscribe? = null
    private var unsubscribeEvents: Unsubscribe? = null
    private val listeners = linkedSetOf<(RemoteSessionState) -> Unit>()
    private val pendingAttachmentOperations = linkedSetOf<Deferred<Unit>>()
    private val activeOperationStates = linkedSetOf<RemoteSessionLifecycle.Busy>()
    private var disposeResult: CompletableDeferred<Unit>? = null

    val id: String?
        get() = synchronized(lock) { handle?.id }

    val state: RemoteSessionState
        get() =
            synchronized(lock) {
                val transcript = transcriptState
                RemoteSessionState(
                    lifecycle = lifecycle,
                    snapshot = transcript?.snapshot,
                    transcript = transcript?.let(::selectTranscript).orEmpty(),
                )
            }

    val snapshot: JsonObject?
        get() = synchronized(lock) { transcriptState?.snapshot }

    val phase: String?
        get() = snapshot?.optionalString("phase")

    val operation: RemoteSessionOperation?
        get() = (synchronized(lock) { lifecycle } as? RemoteSessionLifecycle.Busy)?.operation

    val models: List<JsonObject>
        get() =
            client.snapshot
                ?.array("models")
                ?.map { element -> element as JsonObject }
                .orEmpty()

    val sessions: List<JsonObject>
        get() =
            client.snapshot
                ?.array("sessions")
                ?.map { element -> element as JsonObject }
                .orEmpty()

    val connectionState: ConnectionState
        get() = client.connectionState

    val disposed: Boolean
        get() = synchronized(lock) { lifecycle == RemoteSessionLifecycle.Disposed }

    fun subscribe(listener: (RemoteSessionState) -> Unit): Unsubscribe {
        val current =
            synchronized(lock) {
                assertNotDisposedLocked()
                listeners += listener
                state
            }
        callListener(listener, current)
        return Unsubscribe {
            synchronized(lock) {
                listeners -= listener
            }
        }
    }

    fun onConnectionStateChange(listener: (ConnectionStateChange) -> Unit): Unsubscribe {
        synchronized(lock) {
            assertNotDisposedLocked()
        }
        return client.onConnectionStateChange(listener)
    }

    suspend fun open(sessionId: String) {
        synchronized(lock) {
            if (handle?.id == sessionId && lifecycle == RemoteSessionLifecycle.Ready) {
                return
            }
        }
        replace(RemoteSessionOperation.OPEN) {
            client.acquireSession(sessionId, SessionLeaseMode.EXCLUSIVE)
        }
    }

    suspend fun create(options: CreateRemoteSessionOptions) {
        replace(RemoteSessionOperation.CREATE) {
            client.createSession(
                cwd = options.cwd,
                provider = options.provider,
                modelId = options.modelId,
                thinkingLevel = options.thinkingLevel,
            )
        }
    }

    suspend fun submit(text: String) {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return
        }
        assertAvailable()
        val currentHandle = requireHandle()
        val currentPhase = phase
        if (currentPhase != "idle" && currentPhase != "turn") {
            error("Session cannot accept input during ${currentPhase ?: "unknown"} phase")
        }
        runOperation(RemoteSessionOperation.SUBMIT) {
            if (phase == "idle") {
                currentHandle.prompt(normalized)
            } else {
                currentHandle.steer(normalized)
            }
        }
    }

    suspend fun abort() {
        val preemptingSubmit =
            synchronized(lock) {
                (lifecycle as? RemoteSessionLifecycle.Busy)?.operation == RemoteSessionOperation.SUBMIT
            }
        if (preemptingSubmit) {
            synchronized(lock) { assertNotDisposedLocked() }
        } else {
            assertAvailable()
        }
        val currentHandle = requireHandle()
        if (phase == "idle" && !preemptingSubmit) {
            return
        }
        runOperation(RemoteSessionOperation.ABORT, preempt = preemptingSubmit) {
            currentHandle.abort()
        }
    }

    suspend fun setModel(
        provider: String,
        modelId: String,
    ) {
        runIdleOperation(RemoteSessionOperation.SET_MODEL, "change model") {
            requireHandle().setModel(provider, modelId)
        }
    }

    suspend fun setThinking(thinkingLevel: String) {
        runIdleOperation(RemoteSessionOperation.SET_THINKING, "change thinking level") {
            requireHandle().setThinking(thinkingLevel)
        }
    }

    suspend fun reconnect() {
        assertAvailable()
        val sessionId = requireHandle().id
        runOperation(RemoteSessionOperation.RECONNECT) {
            trackAttachmentOperation {
                client.reconnect()
                val next = client.acquireSession(sessionId, SessionLeaseMode.EXCLUSIVE)
                assertNotDisposedAfterAwait(next)
                bind(next)
            }
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
        val currentHandle: PiSessionHandle?
        val pending: List<Deferred<Unit>>
        synchronized(lock) {
            lifecycle = RemoteSessionLifecycle.Disposed
            disposeSignal.complete(Unit)
            clearSubscriptionsLocked()
            currentHandle = handle
            handle = null
            transcriptState = null
            pending = pendingAttachmentOperations.toList()
        }
        notifyListeners()
        val cleanup =
            buildList {
                addAll(pending)
                currentHandle?.let { lease ->
                    add(scope.async { lease.dispose() })
                }
            }
        val errors =
            cleanup
                .map { operation -> runCatching { operation.await() }.exceptionOrNull() }
                .filterNotNull()
                .filterNot { it is RemoteSessionDisposedException }
        synchronized(lock) {
            listeners.clear()
        }
        scope.cancel()
        if (errors.isEmpty()) {
            result.complete(Unit)
        } else {
            val first = errors.first()
            errors.drop(1).forEach(first::addSuppressed)
            result.completeExceptionally(first)
        }
        result.await()
    }

    private suspend fun replace(
        operation: RemoteSessionOperation,
        prepare: suspend () -> PiSessionHandle,
    ) {
        assertAvailable()
        if (synchronized(lock) { handle != null } && phase != "idle") {
            error("Cannot ${operation.name.lowercase()} a session while session is ${phase ?: "unavailable"}")
        }
        runOperation(operation) {
            trackAttachmentOperation {
                prepareReplacement(operation, prepare)
            }
        }
    }

    private suspend fun prepareReplacement(
        operation: RemoteSessionOperation,
        prepare: suspend () -> PiSessionHandle,
    ) {
        val previous = synchronized(lock) { handle }
        val next = prepare()
        assertNotDisposedAfterAwait(next)
        val nextSnapshot = next.snapshot
        if (nextSnapshot == null) {
            next.dispose()
            error("Session ${next.id} did not provide a snapshot")
        }
        if (previous != null && previous.id != next.id && previous.attached && phase != "idle") {
            next.dispose()
            error(
                "Cannot ${operation.name.lowercase()} a session while session is ${phase ?: "unavailable"}",
            )
        }
        if (previous != null && previous.id != next.id && previous.attached) {
            try {
                previous.detach()
            } catch (error: Throwable) {
                runCatching { next.dispose() }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
        assertNotDisposedAfterAwait(next)
        bind(next, nextSnapshot)
    }

    private suspend fun runIdleOperation(
        operation: RemoteSessionOperation,
        description: String,
        run: suspend () -> Unit,
    ) {
        assertAvailable()
        requireHandle()
        if (phase != "idle") {
            error("Cannot $description while session is ${phase ?: "unavailable"}")
        }
        runOperation(operation, run = run)
    }

    private suspend fun runOperation(
        operation: RemoteSessionOperation,
        preempt: Boolean = false,
        run: suspend () -> Unit,
    ) {
        val previous =
            synchronized(lock) {
                if (preempt) {
                    assertNotDisposedLocked()
                } else {
                    assertAvailableLocked()
                }
                lifecycle
            }
        val busy = RemoteSessionLifecycle.Busy(operation)
        synchronized(lock) {
            lifecycle = busy
            activeOperationStates += busy
        }
        notifyListeners()
        val running = scope.async { run() }
        try {
            select {
                running.onAwait { }
                disposeSignal.onAwait {
                    throw RemoteSessionDisposedException()
                }
            }
        } finally {
            synchronized(lock) {
                activeOperationStates -= busy
                if (lifecycle === busy && lifecycle != RemoteSessionLifecycle.Disposed) {
                    lifecycle =
                        if (
                            preempt &&
                            previous is RemoteSessionLifecycle.Busy &&
                            previous in activeOperationStates
                        ) {
                            previous
                        } else if (handle != null) {
                            RemoteSessionLifecycle.Ready
                        } else {
                            RemoteSessionLifecycle.Unbound
                        }
                }
            }
            notifyListeners()
        }
    }

    private suspend fun trackAttachmentOperation(run: suspend () -> Unit) {
        val operation = scope.async { run() }
        synchronized(lock) {
            pendingAttachmentOperations += operation
        }
        try {
            operation.await()
        } finally {
            synchronized(lock) {
                pendingAttachmentOperations -= operation
            }
        }
    }

    private fun bind(
        next: PiSessionHandle,
        knownSnapshot: JsonObject? = null,
    ) {
        val nextSnapshot = knownSnapshot ?: next.snapshot ?: error("Session ${next.id} did not provide a snapshot")
        synchronized(lock) {
            clearSubscriptionsLocked()
            handle = next
            transcriptState = createTranscriptState(nextSnapshot)
            unsubscribeSnapshot =
                next.subscribe { snapshot ->
                    synchronized(lock) {
                        transcriptState = transcriptState?.let { state -> applyTranscriptSnapshot(state, snapshot) }
                    }
                    notifyListeners()
                }
            unsubscribeEvents =
                next.onEvent { event ->
                    handleEvent(event)
                }
        }
    }

    private fun handleEvent(event: JsonObject) {
        when (event.string("type")) {
            "session_removed" -> {
                synchronized(lock) {
                    clearSubscriptionsLocked()
                    handle = null
                    transcriptState = null
                    if (lifecycle !is RemoteSessionLifecycle.Busy) {
                        lifecycle = RemoteSessionLifecycle.Unbound
                    }
                }
                notifyListeners()
            }

            "session_progress" -> {
                synchronized(lock) {
                    transcriptState =
                        transcriptState?.let { state ->
                            applyTranscriptProgress(state, event.objectValue("progress"))
                        }
                }
                notifyListeners()
            }
        }
    }

    private fun notifyListeners() {
        val current = state
        val currentListeners = synchronized(lock) { listeners.toList() }
        currentListeners.forEach { listener -> callListener(listener, current) }
    }

    private fun callListener(
        listener: (RemoteSessionState) -> Unit,
        current: RemoteSessionState,
    ) {
        try {
            listener(current)
        } catch (error: Throwable) {
            try {
                options.onListenerError?.invoke(error)
            } catch (_: Throwable) {
                // Diagnostics must not affect client or session state.
            }
        }
    }

    private fun clearSubscriptionsLocked() {
        unsubscribeSnapshot?.unsubscribe()
        unsubscribeEvents?.unsubscribe()
        unsubscribeSnapshot = null
        unsubscribeEvents = null
    }

    private fun requireHandle(): PiSessionHandle =
        synchronized(lock) {
            handle ?: error("No remote session is attached")
        }

    private fun assertAvailable() {
        synchronized(lock) {
            assertAvailableLocked()
        }
    }

    private fun assertAvailableLocked() {
        assertNotDisposedLocked()
        val busy = lifecycle as? RemoteSessionLifecycle.Busy
        if (busy != null) {
            error("Remote session is busy with ${busy.operation.name.lowercase()}")
        }
    }

    private fun assertNotDisposedLocked() {
        if (lifecycle == RemoteSessionLifecycle.Disposed) {
            throw RemoteSessionDisposedException()
        }
    }

    private suspend fun assertNotDisposedAfterAwait(next: PiSessionHandle) {
        if (!disposed) {
            return
        }
        next.dispose()
        throw RemoteSessionDisposedException()
    }

    companion object {
        suspend fun open(
            client: PiClient,
            sessionId: String,
            options: RemoteSessionOptions = RemoteSessionOptions(),
        ): RemoteSession {
            val session = RemoteSession(client, options)
            try {
                session.open(sessionId)
                return session
            } catch (error: Throwable) {
                runCatching { session.dispose() }
                throw error
            }
        }

        suspend fun create(
            client: PiClient,
            createOptions: CreateRemoteSessionOptions,
            options: RemoteSessionOptions = RemoteSessionOptions(),
        ): RemoteSession {
            val session = RemoteSession(client, options)
            try {
                session.create(createOptions)
                return session
            } catch (error: Throwable) {
                runCatching { session.dispose() }
                throw error
            }
        }
    }
}

private fun JsonObject.string(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull ?: error("$name is required")

private fun JsonObject.optionalString(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.array(name: String): JsonArray =
    this[name] as? JsonArray ?: error("$name is required")

private fun JsonObject.objectValue(name: String): JsonObject =
    this[name] as? JsonObject ?: error("$name is required")
