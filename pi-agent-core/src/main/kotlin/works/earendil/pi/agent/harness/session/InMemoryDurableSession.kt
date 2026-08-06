package works.earendil.pi.agent.harness.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import works.earendil.pi.ai.uuidv7

class InMemoryDurableSessionStorage(
    private val metadata: DurableSessionMetadata,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : DurableSessionStorage<DurableSessionMetadata> {
    private val mutex = Mutex()
    private val state = DurableSessionState()

    override suspend fun getMetadata(): DurableSessionMetadata =
        DurableSessionMetadata(
            id = metadata.id,
            createdAt = metadata.createdAt,
            parentSessionId = metadata.parentSessionId,
        )

    override suspend fun getLanes(): List<LanePointer> = mutex.withLock { state.getLanes() }

    override suspend fun createLane(
        lane: String,
        at: String?,
    ) {
        mutex.withLock {
            state.validateNewLane(lane)
            state.validateTarget(at)
            state.applyMutation(DurableMutation.Lane(state.nextSequence, lane, at))
        }
    }

    override suspend fun moveLane(
        lane: String,
        to: String?,
    ) {
        mutex.withLock {
            state.requireLane(lane)
            state.validateTarget(to)
            state.applyMutation(DurableMutation.Lane(state.nextSequence, lane, to))
        }
    }

    override suspend fun appendEntry(
        entry: ProvisionedEntry,
        lane: String,
    ): DurableEntry =
        mutex.withLock {
            val validated = entry.deepCopy()
            val parentId = state.requireLane(lane)
            state.validateUnusedId(validated.id)
            val materialized =
                DurableEntry(
                    id = validated.id,
                    seq = state.nextSequence,
                    parentId = parentId,
                    timestamp = currentTimeMillis(),
                    payload = validated.payload,
                )
            state.applyMutation(DurableMutation.Entry(lane, materialized))
            materialized.deepCopy()
        }

    override suspend fun appendRecord(record: NewDurableRecord): DurableRecord =
        mutex.withLock {
            val validated =
                durableSessionJson.decodeFromString(
                    NewDurableRecord.serializer(),
                    durableSessionJson.encodeToString(NewDurableRecord.serializer(), record),
                )
            state.requireLane(validated.lane)
            state.validateUnusedId(validated.id)
            if (
                validated.payload is RecordPayload.OperationStarted &&
                state.findOpenOperations(validated.lane, limit = 1).isNotEmpty()
            ) {
                val operation = state.findOpenOperations(validated.lane, limit = 1).single()
                throw DurableSessionException(
                    DurableSessionErrorCode.STORAGE,
                    "Lane ${validated.lane} already has an open operation ${operation.id}",
                )
            }
            val materialized =
                DurableRecord(
                    id = validated.id,
                    seq = state.nextSequence,
                    lane = validated.lane,
                    timestamp = currentTimeMillis(),
                    payload = validated.payload,
                )
            state.applyMutation(DurableMutation.Record(materialized))
            materialized.deepCopy()
        }

    override suspend fun getEntry(id: String): DurableEntry? = mutex.withLock { state.getEntry(id) }

    override suspend fun findEntries(query: EntryQuery): List<DurableEntry> =
        mutex.withLock { state.findEntries(query) }

    override suspend fun findEntriesOnBranch(
        query: EntryQuery,
        bounds: BranchBounds,
        start: String,
    ): List<DurableEntry> =
        mutex.withLock {
            state.findEntriesOnBranch(query, bounds, start)
        }

    override suspend fun findRecords(query: RecordQuery): List<DurableRecord> =
        mutex.withLock { state.findRecords(query) }

    override suspend fun findOpenOperations(
        lane: String,
        limit: Int?,
    ): List<DurableRecord> =
        mutex.withLock {
            state.findOpenOperations(lane, limit)
        }

    override suspend fun getLog(options: LogOptions): List<DurableLogItem> =
        mutex.withLock { state.getLog(options) }

    override suspend fun getName(): String? = mutex.withLock { state.getName() }

    override suspend fun setName(name: String) {
        mutex.withLock {
            state.applyMutation(DurableMutation.Name(state.nextSequence, name))
        }
    }

    override suspend fun getLabel(id: String): String? = mutex.withLock { state.getLabel(id) }

    override suspend fun setLabel(
        id: String,
        label: String?,
    ) {
        mutex.withLock {
            state.validateTarget(id)
            state.applyMutation(DurableMutation.Label(state.nextSequence, id, label))
        }
    }

    override suspend fun getStats(): DurableSessionStats = mutex.withLock { state.getStats() }

    suspend fun fork(
        targetMetadata: DurableSessionMetadata,
        options: DurableForkOptions,
    ): InMemoryDurableSessionStorage {
        val snapshot =
            mutex.withLock {
                val copiedEntries: List<DurableEntry>
                val forkLanes: List<LanePointer>
                when (options) {
                    is DurableForkOptions.Tree -> {
                        copiedEntries = state.findEntries(EntryQuery(order = EntryOrder.OLDEST_FIRST))
                        forkLanes = state.getLanes()
                    }

                    is DurableForkOptions.Branch -> {
                        val mainLeaf = state.getLanes().single { it.lane == "main" }.leafId
                        val selectedEntryId = options.entryId ?: mainLeaf
                        val targetId =
                            if (selectedEntryId == null) {
                                null
                            } else {
                                val target =
                                    state.getEntry(selectedEntryId)
                                        ?: throw DurableSessionException(
                                            DurableSessionErrorCode.INVALID_FORK_TARGET,
                                            "Fork target is not a message entry: $selectedEntryId",
                                        )
                                if (target.payload !is EntryPayload.MessageValue) {
                                    throw DurableSessionException(
                                        DurableSessionErrorCode.INVALID_FORK_TARGET,
                                        "Fork target is not a message entry: $selectedEntryId",
                                    )
                                }
                                val position =
                                    options.position
                                        ?: if (options.entryId == null) {
                                            DurableForkOptions.Branch.Position.AT
                                        } else {
                                            DurableForkOptions.Branch.Position.BEFORE
                                        }
                                if (position == DurableForkOptions.Branch.Position.AT) {
                                    target.id
                                } else {
                                    target.parentId
                                }
                            }
                        copiedEntries =
                            if (targetId == null) {
                                emptyList()
                            } else {
                                state.findEntriesOnBranch(
                                    query = EntryQuery(order = EntryOrder.OLDEST_FIRST),
                                    bounds = BranchBounds(start = targetId),
                                    start = targetId,
                                )
                            }
                        forkLanes = listOf(LanePointer("main", targetId))
                    }
                }
                InMemoryForkSnapshot(
                    entries = copiedEntries,
                    lanes = forkLanes,
                    name = state.getName(),
                    labels =
                        copiedEntries
                            .mapNotNull { entry ->
                                state.getLabel(entry.id)?.let { label -> entry.id to label }
                            }.toMap(),
                )
            }

        val target = InMemoryDurableSessionStorage(targetMetadata, currentTimeMillis)
        target.mutex.withLock {
            snapshot.entries.forEach { source ->
                val copied = source.copy(seq = target.state.nextSequence)
                target.state.applyMutation(DurableMutation.Entry(null, copied))
            }
            snapshot.lanes.forEach { pointer ->
                target.state.applyMutation(
                    DurableMutation.Lane(
                        target.state.nextSequence,
                        pointer.lane,
                        pointer.leafId,
                    ),
                )
            }
            snapshot.name?.let { name ->
                target.state.applyMutation(DurableMutation.Name(target.state.nextSequence, name))
            }
            snapshot.labels.forEach { (id, label) ->
                target.state.applyMutation(
                    DurableMutation.Label(target.state.nextSequence, id, label),
                )
            }
        }
        return target
    }
}

class InMemoryDurableSessionRepository(
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = ::uuidv7,
) : DurableSessionRepository<
        DurableSessionMetadata,
        BasicDurableSessionCreateOptions,
        Unit,
    > {
    private val mutex = Mutex()
    private val sessions = linkedMapOf<String, InMemoryDurableSessionStorage>()

    override suspend fun create(options: BasicDurableSessionCreateOptions): DurableSession<DurableSessionMetadata> =
        mutex.withLock {
            val id = options.id ?: idGenerator()
            if (id in sessions) {
                throw DurableSessionException(
                    DurableSessionErrorCode.ALREADY_EXISTS,
                    "Session already exists: $id",
                )
            }
            val storage =
                InMemoryDurableSessionStorage(
                    DurableSessionMetadata(
                        id = id,
                        createdAt = currentTimeMillis(),
                        parentSessionId = options.parentSessionId,
                    ),
                    currentTimeMillis,
                )
            sessions[id] = storage
            DurableSession(storage, idGenerator)
        }

    override suspend fun open(metadata: DurableSessionMetadata): DurableSession<DurableSessionMetadata> =
        mutex.withLock {
            DurableSession(requireStorage(metadata.id), idGenerator)
        }

    override suspend fun list(options: Unit?): List<DurableSessionMetadata> =
        mutex.withLock {
            sessions.values.map { it.getMetadata() }
        }

    override suspend fun delete(metadata: DurableSessionMetadata) {
        mutex.withLock {
            sessions.remove(metadata.id)
        }
    }

    override suspend fun fork(
        source: DurableSessionMetadata,
        options: DurableForkOptions,
    ): DurableSession<DurableSessionMetadata> {
        val sourceStorage = mutex.withLock { requireStorage(source.id) }
        val id = options.id ?: idGenerator()
        mutex.withLock {
            if (id in sessions) {
                throw DurableSessionException(
                    DurableSessionErrorCode.ALREADY_EXISTS,
                    "Session already exists: $id",
                )
            }
        }
        val storage =
            sourceStorage.fork(
                targetMetadata =
                    DurableSessionMetadata(
                        id = id,
                        createdAt = currentTimeMillis(),
                        parentSessionId = options.parentSessionId ?: source.id,
                    ),
                options = options,
            )
        mutex.withLock {
            if (id in sessions) {
                throw DurableSessionException(
                    DurableSessionErrorCode.ALREADY_EXISTS,
                    "Session already exists: $id",
                )
            }
            sessions[id] = storage
        }
        return DurableSession(storage, idGenerator)
    }

    private fun requireStorage(id: String): InMemoryDurableSessionStorage =
        sessions[id]
            ?: throw DurableSessionException(
                DurableSessionErrorCode.NOT_FOUND,
                "Session not found: $id",
            )
}

private data class InMemoryForkSnapshot(
    val entries: List<DurableEntry>,
    val lanes: List<LanePointer>,
    val name: String?,
    val labels: Map<String, String>,
)
