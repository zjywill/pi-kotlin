package works.earendil.pi.server

import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ServerSupervisor internal constructor(
    private val storage: ServerStorage,
    private val processFactory: RpcProcessFactory,
) {
    private data class LiveInstance(
        var record: InstanceRecord,
        val process: RpcProcess,
        val subscribers: CopyOnWriteArrayList<(JsonObject) -> Unit> = CopyOnWriteArrayList(),
        var unsubscribeEvents: (() -> Unit)? = null,
        var unsubscribeExit: (() -> Unit)? = null,
    )

    private val liveInstances = ConcurrentHashMap<String, LiveInstance>()

    constructor(storage: ServerStorage) : this(
        storage,
        RpcProcessFactory { cwd, provider, model ->
            ChildRpcProcess(
                cwd = cwd,
                provider = provider,
                model = model,
            )
        },
    )

    constructor(
        storage: ServerStorage,
        runtimeFactory: RpcRuntimeFactory,
    ) : this(
        storage,
        RpcProcessFactory { cwd, provider, model ->
            InProcessRpcProcess(runtimeFactory.create(cwd, provider, model))
        },
    )

    suspend fun recoverAfterRestart() {
        val now = Instant.now().toString()
        storage.saveInstances(
            storage.loadInstances().map { instance ->
                instance.copy(
                    status =
                        if (instance.status == InstanceStatus.ONLINE || instance.status == InstanceStatus.STARTING) {
                            InstanceStatus.STOPPED
                        } else {
                            instance.status
                        },
                    lastSeenAt = now,
                )
            },
        )
    }

    suspend fun spawnInstance(
        cwd: Path,
        label: String? = null,
        provider: String? = null,
        model: String? = null,
    ): InstanceRecord {
        val now = Instant.now().toString()
        val id = UUID.randomUUID().toString()
        val record =
            InstanceRecord(
                id = id,
                status = InstanceStatus.STARTING,
                cwd = cwd.toAbsolutePath().normalize().toString(),
                createdAt = now,
                lastSeenAt = now,
                label = label,
            )
        storage.upsertInstance(record)
        val process =
            try {
                processFactory.create(cwd.toAbsolutePath().normalize(), provider, model)
            } catch (error: Throwable) {
                val failedAt = Instant.now().toString()
                storage.upsertInstance(
                    record.copy(
                        status = InstanceStatus.ERROR,
                        lastSeenAt = failedAt,
                    ),
                )
                storage.upsertInstance(
                    record.copy(
                        status = InstanceStatus.STOPPED,
                        lastSeenAt = Instant.now().toString(),
                    ),
                )
                throw error
            }
        val live = LiveInstance(record, process)
        liveInstances[id] = live
        bindProcess(live)
        return try {
            syncSessionMetadata(live)
            update(live, status = InstanceStatus.ONLINE)
            live.record.copy()
        } catch (error: Exception) {
            failSpawn(live, error)
        }
    }

    fun listInstances(): List<InstanceRecord> = storage.loadInstances().map(InstanceRecord::copy)

    fun getInstance(id: String): InstanceRecord? =
        liveInstances[id]?.record?.copy() ?: storage.getInstance(id)?.copy()

    suspend fun stopInstance(id: String): InstanceRecord? {
        val live = liveInstances[id] ?: return null
        update(live, status = InstanceStatus.STOPPING)
        try {
            clearBindings(live)
            live.process.close()
        } finally {
            val stopped =
                live.record.copy(
                    status = InstanceStatus.STOPPED,
                    lastSeenAt = Instant.now().toString(),
                )
            liveInstances.remove(id)
            storage.removeInstance(id)
            return stopped
        }
    }

    suspend fun handleRpc(
        id: String,
        command: JsonObject,
    ): JsonObject? {
        val live = liveInstances[id] ?: return null
        val response = live.process.send(command)
        if (command.string("type") in sessionMetadataCommands) {
            syncSessionMetadata(live)
        }
        return response
    }

    suspend fun handleUiResponse(
        id: String,
        response: JsonObject,
    ): Boolean {
        val live = liveInstances[id] ?: return false
        live.process.sendUiResponse(response)
        return true
    }

    fun subscribe(
        id: String,
        listener: (JsonObject) -> Unit,
    ): (() -> Unit)? {
        val live = liveInstances[id] ?: return null
        live.subscribers += listener
        return { live.subscribers -= listener }
    }

    suspend fun shutdown() {
        liveInstances.keys.toList().forEach { id -> stopInstance(id) }
    }

    private suspend fun syncSessionMetadata(live: LiveInstance) {
        val state =
            live.process.send(
                buildJsonObject {
                    put("type", "get_state")
                    put("id", "server_state")
                },
            )
        val (sessionId, sessionFile) = stateSessionFields(state)
        update(live, sessionId = sessionId, sessionFile = sessionFile)
    }

    private fun update(
        live: LiveInstance,
        status: InstanceStatus? = null,
        sessionId: String? = live.record.sessionId,
        sessionFile: String? = live.record.sessionFile,
    ) {
        live.record =
            live.record.copy(
                status = status ?: live.record.status,
                lastSeenAt = Instant.now().toString(),
                sessionId = sessionId,
                sessionFile = sessionFile,
            )
        storage.upsertInstance(live.record)
    }

    private fun bindProcess(live: LiveInstance) {
        clearBindings(live)
        live.unsubscribeEvents =
            live.process.subscribe { event ->
                live.subscribers.forEach { subscriber -> subscriber(event) }
            }
        live.unsubscribeExit =
            live.process.onExit { error ->
                handleUnexpectedExit(live, error)
            }
    }

    private fun clearBindings(live: LiveInstance) {
        live.unsubscribeEvents?.invoke()
        live.unsubscribeExit?.invoke()
        live.unsubscribeEvents = null
        live.unsubscribeExit = null
    }

    private fun handleUnexpectedExit(
        live: LiveInstance,
        error: Throwable,
    ) {
        if (liveInstances[live.record.id] !== live) {
            return
        }
        if (
            live.record.status == InstanceStatus.STOPPING ||
            live.record.status == InstanceStatus.STOPPED
        ) {
            return
        }
        update(live, status = InstanceStatus.ERROR)
        clearBindings(live)
        liveInstances.remove(live.record.id, live)
        System.err.println(
            "RPC process for instance ${live.record.id} exited unexpectedly: " +
                (error.message ?: error::class.simpleName.orEmpty()),
        )
    }

    private suspend fun failSpawn(
        live: LiveInstance,
        error: Throwable,
    ): Nothing {
        update(live, status = InstanceStatus.ERROR)
        clearBindings(live)
        runCatching { live.process.close() }
        update(live, status = InstanceStatus.STOPPED)
        liveInstances.remove(live.record.id, live)
        throw error
    }

    companion object {
        private val sessionMetadataCommands =
            setOf("new_session", "switch_session", "fork", "clone", "set_session_name", "prompt")
    }
}
