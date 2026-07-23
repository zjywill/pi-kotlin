package works.earendil.pi.server

import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.providers.builtInModelsCollection
import works.earendil.pi.codingagent.RpcRuntime
import works.earendil.pi.codingagent.RpcRuntimeOptions

fun interface RpcRuntimeFactory {
    fun create(
        cwd: Path,
        provider: String?,
        model: String?,
    ): RpcRuntime
}

class ServerSupervisor(
    private val storage: ServerStorage,
    private val runtimeFactory: RpcRuntimeFactory = defaultRuntimeFactory(),
) {
    private data class LiveInstance(
        var record: InstanceRecord,
        val runtime: RpcRuntime,
        val subscribers: CopyOnWriteArrayList<(JsonObject) -> Unit> = CopyOnWriteArrayList(),
        val unsubscribe: () -> Unit,
    )

    private val liveInstances = ConcurrentHashMap<String, LiveInstance>()

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
        var record =
            InstanceRecord(
                id = id,
                status = InstanceStatus.STARTING,
                cwd = cwd.toAbsolutePath().normalize().toString(),
                createdAt = now,
                lastSeenAt = now,
                label = label,
            )
        storage.upsertInstance(record)
        val runtime =
            try {
                runtimeFactory.create(cwd.toAbsolutePath().normalize(), provider, model)
            } catch (error: Exception) {
                storage.upsertInstance(record.copy(status = InstanceStatus.ERROR, lastSeenAt = Instant.now().toString()))
                throw error
            }
        val liveReference = AtomicReference<LiveInstance?>()
        val unsubscribe =
            runtime.subscribe { event ->
                liveReference.get()?.let { live ->
                    live.subscribers.forEach { subscriber -> subscriber(event) }
                }
            }
        val live = LiveInstance(record, runtime, unsubscribe = unsubscribe)
        liveReference.set(live)
        liveInstances[id] = live
        syncSessionMetadata(live)
        record = live.record.copy(status = InstanceStatus.ONLINE, lastSeenAt = Instant.now().toString())
        live.record = record
        storage.upsertInstance(record)
        return record.copy()
    }

    fun listInstances(): List<InstanceRecord> = storage.loadInstances().map(InstanceRecord::copy)

    fun getInstance(id: String): InstanceRecord? =
        liveInstances[id]?.record?.copy() ?: storage.getInstance(id)?.copy()

    suspend fun stopInstance(id: String): InstanceRecord? {
        val live = liveInstances[id] ?: return null
        update(live, status = InstanceStatus.STOPPING)
        try {
            live.unsubscribe()
            live.runtime.close()
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
        val response = live.runtime.handle(command)
        if (command.string("type") in sessionMetadataCommands) {
            syncSessionMetadata(live)
        } else {
            update(live)
        }
        return response
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
            live.runtime.handle(
                buildJsonObject {
                    put("type", "get_state")
                    put("id", "server_state")
                },
            )
        val (sessionId, sessionFile) = state?.let(::stateSessionFields) ?: (null to null)
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

    companion object {
        private val sessionMetadataCommands =
            setOf("new_session", "switch_session", "fork", "clone", "set_session_name", "prompt")

        private fun defaultRuntimeFactory(): RpcRuntimeFactory =
            RpcRuntimeFactory { cwd, provider, model ->
                RpcRuntime(
                    builtInModelsCollection(),
                    RpcRuntimeOptions(
                        cwd = cwd,
                        provider = provider,
                        model = model,
                    ),
                )
            }
    }
}
