package works.earendil.pi.agent.harness.session

import works.earendil.pi.ai.Message
import works.earendil.pi.ai.uuidv7

interface DurableSessionStorage<M : DurableSessionMetadata> {
    suspend fun getMetadata(): M

    suspend fun getLanes(): List<LanePointer>

    suspend fun createLane(
        lane: String,
        at: String?,
    )

    suspend fun moveLane(
        lane: String,
        to: String?,
    )

    suspend fun appendEntry(
        entry: ProvisionedEntry,
        lane: String,
    ): DurableEntry

    suspend fun appendRecord(record: NewDurableRecord): DurableRecord

    suspend fun getEntry(id: String): DurableEntry?

    suspend fun findEntries(query: EntryQuery = EntryQuery()): List<DurableEntry>

    suspend fun findEntriesOnBranch(
        query: EntryQuery,
        bounds: BranchBounds,
        start: String,
    ): List<DurableEntry>

    suspend fun findRecords(query: RecordQuery = RecordQuery()): List<DurableRecord>

    suspend fun findOpenOperations(
        lane: String,
        limit: Int? = null,
    ): List<DurableRecord>

    suspend fun getLog(options: LogOptions = LogOptions()): List<DurableLogItem>

    suspend fun getName(): String?

    suspend fun setName(name: String?)

    suspend fun getLabel(id: String): String?

    suspend fun setLabel(
        id: String,
        label: String?,
    )

    suspend fun getStats(): DurableSessionStats

    suspend fun close() = Unit
}

interface DurableSessionTree {
    suspend fun getLeafId(): String?

    suspend fun getEntry(id: String): DurableEntry?

    suspend fun getStats(): DurableSessionStats

    suspend fun getName(): String?

    suspend fun setName(name: String?)

    suspend fun getLabel(targetId: String): String?

    suspend fun setLabel(
        targetId: String,
        label: String?,
    )

    suspend fun findEntries(query: EntryQuery = EntryQuery()): List<DurableEntry>

    suspend fun findEntry(query: EntryQuery = EntryQuery()): DurableEntry?

    suspend fun findEntriesOnBranch(
        query: EntryQuery = EntryQuery(),
        bounds: BranchBounds = BranchBounds(),
    ): List<DurableEntry>

    suspend fun findEntryOnBranch(
        query: EntryQuery = EntryQuery(),
        bounds: BranchBounds = BranchBounds(),
    ): DurableEntry?

    suspend fun appendMessage(message: Message): String

    suspend fun appendCustomEntry(
        customType: String,
        data: kotlinx.serialization.json.JsonElement? = null,
    ): String
}

class DurableSession<M : DurableSessionMetadata>(
    private val storage: DurableSessionStorage<M>,
    private val idGenerator: () -> String = ::uuidv7,
) : DurableSessionTree {
    private val mainView = View("main")

    suspend fun getMetadata(): M = storage.getMetadata()

    fun view(lane: String): DurableSessionTree = if (lane == "main") this else View(lane)

    override suspend fun getLeafId(): String? = mainView.getLeafId()

    override suspend fun getEntry(id: String): DurableEntry? = storage.getEntry(id)

    override suspend fun getStats(): DurableSessionStats = storage.getStats()

    override suspend fun getName(): String? = storage.getName()

    override suspend fun setName(name: String?) = storage.setName(name)

    override suspend fun getLabel(targetId: String): String? = storage.getLabel(targetId)

    override suspend fun setLabel(
        targetId: String,
        label: String?,
    ) = storage.setLabel(targetId, label)

    override suspend fun findEntries(query: EntryQuery): List<DurableEntry> {
        validatePublicEntryQuery(query)
        return storage.findEntries(query)
    }

    override suspend fun findEntry(query: EntryQuery): DurableEntry? {
        validatePublicEntryQuery(query)
        return storage.findEntries(query.copy(limit = 1)).firstOrNull()
    }

    override suspend fun findEntriesOnBranch(
        query: EntryQuery,
        bounds: BranchBounds,
    ): List<DurableEntry> = mainView.findEntriesOnBranch(query, bounds)

    override suspend fun findEntryOnBranch(
        query: EntryQuery,
        bounds: BranchBounds,
    ): DurableEntry? = mainView.findEntryOnBranch(query, bounds)

    override suspend fun appendMessage(message: Message): String = mainView.appendMessage(message)

    override suspend fun appendCustomEntry(
        customType: String,
        data: kotlinx.serialization.json.JsonElement?,
    ): String = mainView.appendCustomEntry(customType, data)

    suspend fun getLanes(): List<LanePointer> = storage.getLanes()

    suspend fun createLane(
        lane: String,
        at: String?,
    ) = storage.createLane(lane, at)

    suspend fun moveLane(
        lane: String,
        to: String?,
    ) = storage.moveLane(lane, to)

    suspend fun appendEntry(
        entry: ProvisionedEntry,
        lane: String,
    ): DurableEntry {
        val validated = entry.deepCopy()
        return storage.appendEntry(validated, lane)
    }

    suspend fun appendRecord(record: NewDurableRecord): DurableRecord {
        val validated =
            durableSessionJson.decodeFromString(
                NewDurableRecord.serializer(),
                durableSessionJson.encodeToString(NewDurableRecord.serializer(), record),
            )
        return storage.appendRecord(validated)
    }

    suspend fun findRecords(query: RecordQuery = RecordQuery()): List<DurableRecord> {
        validateLimit(query.limit)
        validateCursor(query.afterSeq)
        if (query.operationKind != null && query.type != "operation_started") {
            throw DurableSessionException(
                DurableSessionErrorCode.INVALID_QUERY,
                "operationKind requires type \"operation_started\"",
            )
        }
        return storage.findRecords(query)
    }

    suspend fun findOpenOperations(
        lane: String,
        limit: Int? = null,
    ): List<DurableRecord> {
        validateLimit(limit)
        return storage.findOpenOperations(lane, limit)
    }

    suspend fun getLog(options: LogOptions = LogOptions()): List<DurableLogItem> {
        validateLimit(options.limit)
        validateCursor(options.afterSeq)
        return storage.getLog(options)
    }

    suspend fun close() = storage.close()

    private inner class View(
        private val lane: String,
    ) : DurableSessionTree {
        override suspend fun getLeafId(): String? =
            storage
                .getLanes()
                .firstOrNull { it.lane == lane }
                ?.leafId
                ?: if (storage.getLanes().none { it.lane == lane }) {
                    throw DurableSessionException(
                        DurableSessionErrorCode.INVALID_LANE,
                        "Lane not found: $lane",
                    )
                } else {
                    null
                }

        override suspend fun getEntry(id: String): DurableEntry? = storage.getEntry(id)

        override suspend fun getStats(): DurableSessionStats = storage.getStats()

        override suspend fun getName(): String? = storage.getName()

        override suspend fun setName(name: String?) = storage.setName(name)

        override suspend fun getLabel(targetId: String): String? = storage.getLabel(targetId)

        override suspend fun setLabel(
            targetId: String,
            label: String?,
        ) = storage.setLabel(targetId, label)

        override suspend fun findEntries(query: EntryQuery): List<DurableEntry> =
            this@DurableSession.findEntries(query)

        override suspend fun findEntry(query: EntryQuery): DurableEntry? =
            this@DurableSession.findEntry(query)

        override suspend fun findEntriesOnBranch(
            query: EntryQuery,
            bounds: BranchBounds,
        ): List<DurableEntry> {
            validatePublicEntryQuery(query)
            val start = bounds.start ?: getLeafId() ?: return emptyList()
            return storage.findEntriesOnBranch(query, bounds.copy(start = start), start)
        }

        override suspend fun findEntryOnBranch(
            query: EntryQuery,
            bounds: BranchBounds,
        ): DurableEntry? {
            validatePublicEntryQuery(query)
            return findEntriesOnBranch(query.copy(limit = 1), bounds).firstOrNull()
        }

        override suspend fun appendMessage(message: Message): String {
            val id = idGenerator()
            appendEntry(
                ProvisionedEntry(
                    id = id,
                    payload = EntryPayload.MessageValue(message),
                ),
                lane,
            )
            return id
        }

        override suspend fun appendCustomEntry(
            customType: String,
            data: kotlinx.serialization.json.JsonElement?,
        ): String {
            val id = idGenerator()
            appendEntry(
                ProvisionedEntry(
                    id = id,
                    payload = EntryPayload.Custom(customType, data),
                ),
                lane,
            )
            return id
        }
    }
}

interface DurableSessionCreateOptions {
    val id: String?
    val parentSessionId: String?
}

data class BasicDurableSessionCreateOptions(
    override val id: String? = null,
    override val parentSessionId: String? = null,
) : DurableSessionCreateOptions

sealed interface DurableForkOptions {
    val id: String?
    val parentSessionId: String?

    data class Branch(
        override val id: String? = null,
        override val parentSessionId: String? = null,
        val entryId: String? = null,
        val position: Position? = null,
    ) : DurableForkOptions {
        enum class Position {
            BEFORE,
            AT,
        }
    }

    data class Tree(
        override val id: String? = null,
        override val parentSessionId: String? = null,
    ) : DurableForkOptions
}

interface DurableSessionRepository<
    M : DurableSessionMetadata,
    C : DurableSessionCreateOptions,
    L,
> {
    suspend fun create(options: C): DurableSession<M>

    suspend fun open(metadata: M): DurableSession<M>

    suspend fun list(options: L? = null): List<M>

    suspend fun delete(metadata: M)

    suspend fun fork(
        source: M,
        options: DurableForkOptions,
    ): DurableSession<M>
}

private fun validatePublicEntryQuery(query: EntryQuery) {
    validateLimit(query.limit)
    validateCursor(query.cursor?.afterSeq)
}
