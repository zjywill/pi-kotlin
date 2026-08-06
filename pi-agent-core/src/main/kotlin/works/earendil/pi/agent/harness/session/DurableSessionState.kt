package works.earendil.pi.agent.harness.session

import kotlinx.serialization.json.Json

internal val durableSessionJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }

internal fun DurableEntry.deepCopy(): DurableEntry =
    durableSessionJson.decodeFromString(
        DurableEntry.serializer(),
        durableSessionJson.encodeToString(DurableEntry.serializer(), this),
    )

internal fun DurableRecord.deepCopy(): DurableRecord =
    durableSessionJson.decodeFromString(
        DurableRecord.serializer(),
        durableSessionJson.encodeToString(DurableRecord.serializer(), this),
    )

internal fun ProvisionedEntry.deepCopy(): ProvisionedEntry =
    durableSessionJson.decodeFromString(
        ProvisionedEntry.serializer(),
        durableSessionJson.encodeToString(ProvisionedEntry.serializer(), this),
    )

class DurableSessionState {
    private var sequence = 0L
    private val usedIds = mutableSetOf<String>()
    private val entries = mutableListOf<DurableEntry>()
    private val entriesById = linkedMapOf<String, DurableEntry>()
    private val records = mutableListOf<DurableRecord>()
    private val openOperationsByLane =
        linkedMapOf<String, LinkedHashMap<String, DurableRecord>>()
    private val lanes = linkedMapOf("main" to null as String?)
    private val log = mutableListOf<DurableLogItem>()
    private var stats = DurableSessionStats()
    private var name: String? = null
    private val labels = mutableMapOf<String, String>()

    val nextSequence: Long
        get() = sequence + 1

    fun getLanes(): List<LanePointer> = lanes.map { (lane, leafId) -> LanePointer(lane, leafId) }

    fun requireLane(lane: String): String? {
        if (!lanes.containsKey(lane)) {
            throw DurableSessionException(
                DurableSessionErrorCode.INVALID_LANE,
                "Lane not found: $lane",
            )
        }
        return lanes[lane]
    }

    fun validateNewLane(lane: String) {
        if (lanes.containsKey(lane)) {
            throw DurableSessionException(
                DurableSessionErrorCode.ALREADY_EXISTS,
                "Lane already exists: $lane",
            )
        }
    }

    fun validateTarget(targetId: String?) {
        if (targetId != null && targetId !in entriesById) {
            throw DurableSessionException(
                DurableSessionErrorCode.NOT_FOUND,
                "Entry not found: $targetId",
            )
        }
    }

    fun validateUnusedId(id: String) {
        if (id in usedIds) {
            throw DurableSessionException(
                DurableSessionErrorCode.ALREADY_EXISTS,
                "Session id already exists: $id",
            )
        }
    }

    fun applyMutation(
        mutation: DurableMutation,
        invalid: (String) -> Nothing = ::invalidMutation,
    ) {
        if (mutation.seq != nextSequence) {
            invalid("has non-consecutive seq ${mutation.seq}")
        }

        when (mutation) {
            is DurableMutation.Entry -> {
                val entry = mutation.entry.deepCopy()
                if (entry.id in usedIds) {
                    invalid("contains duplicate id ${entry.id}")
                }
                mutation.lane?.let { lane ->
                    if (!lanes.containsKey(lane)) {
                        invalid("references missing lane $lane")
                    }
                    if (entry.parentId != lanes[lane]) {
                        invalid("does not chain to the lane leaf")
                    }
                }
                if (entry.parentId != null && entry.parentId !in entriesById) {
                    invalid("references missing parent ${entry.parentId}")
                }
                sequence = entry.seq
                usedIds += entry.id
                entries += entry
                entriesById[entry.id] = entry
                mutation.lane?.let { lanes[it] = entry.id }
                log += DurableLogItem.Entry(entry.seq, entry)
                if (entry.payload is EntryPayload.MessageValue) {
                    stats = stats.copy(messageCount = stats.messageCount + 1)
                }
            }

            is DurableMutation.Record -> {
                val record = mutation.record.deepCopy()
                if (!lanes.containsKey(record.lane)) {
                    invalid("references missing lane ${record.lane}")
                }
                if (record.id in usedIds) {
                    invalid("contains duplicate id ${record.id}")
                }
                sequence = record.seq
                usedIds += record.id
                records += record
                when (val payload = record.payload) {
                    is RecordPayload.OperationStarted ->
                        openOperationsByLane
                            .getOrPut(record.lane) { linkedMapOf() }[record.id] = record

                    is RecordPayload.OperationFinished ->
                        openOperationsByLane[record.lane]?.remove(payload.runId)

                    else -> Unit
                }
                log += DurableLogItem.Record(record.seq, record)
                val usage = (record.payload as? RecordPayload.UsageValue)?.usage
                if (usage != null) {
                    stats =
                        stats.copy(
                            cachedTokens = stats.cachedTokens + usage.cacheRead,
                            uncachedTokens = stats.uncachedTokens + usage.input + usage.cacheWrite,
                            totalTokens = stats.totalTokens + usage.totalTokens,
                            costTotal = stats.costTotal + usage.cost.total,
                        )
                }
            }

            is DurableMutation.Lane -> {
                if (mutation.leafId != null && mutation.leafId !in entriesById) {
                    invalid("references missing lane target ${mutation.leafId}")
                }
                sequence = mutation.seq
                lanes[mutation.lane] = mutation.leafId
                log += DurableLogItem.Lane(mutation.seq, mutation.lane, mutation.leafId)
            }

            is DurableMutation.Name -> {
                sequence = mutation.seq
                name = mutation.name
                log += DurableLogItem.Name(mutation.seq, mutation.name)
            }

            is DurableMutation.Label -> {
                if (mutation.targetId !in entriesById) {
                    invalid("references missing label target ${mutation.targetId}")
                }
                sequence = mutation.seq
                if (mutation.label == null) {
                    labels.remove(mutation.targetId)
                } else {
                    labels[mutation.targetId] = mutation.label
                }
                log +=
                    DurableLogItem.Label(
                        mutation.seq,
                        mutation.targetId,
                        mutation.label,
                    )
            }
        }
    }

    fun getEntry(id: String): DurableEntry? = entriesById[id]?.deepCopy()

    fun findEntries(query: EntryQuery = EntryQuery()): List<DurableEntry> {
        validateEntryQuery(query)
        return ordered(entries, query.order)
            .asSequence()
            .filter { matchesEntryQuery(it, query) }
            .let { sequence -> query.limit?.let(sequence::take) ?: sequence }
            .map(DurableEntry::deepCopy)
            .toList()
    }

    fun findEntriesOnBranch(
        query: EntryQuery,
        bounds: BranchBounds,
        start: String,
    ): List<DurableEntry> {
        validateEntryQuery(query)
        val branch = walkToRoot(start)
        val traversal =
            if (query.order == EntryOrder.OLDEST_FIRST) {
                branch.asReversed()
            } else {
                branch
            }
        val result = mutableListOf<DurableEntry>()
        traversal.forEach { entry ->
            val reachedBound = entry.id == bounds.stopAtId || entry.type == bounds.stopAtType
            if (matchesEntryQuery(entry, query)) {
                result += entry.deepCopy()
            }
            if (reachedBound || result.size == query.limit) {
                return result
            }
        }
        return result
    }

    fun findRecords(query: RecordQuery = RecordQuery()): List<DurableRecord> {
        validateRecordQuery(query)
        return ordered(records, query.order)
            .asSequence()
            .filter { matchesRecordQuery(it, query) }
            .let { sequence -> query.limit?.let(sequence::take) ?: sequence }
            .map(DurableRecord::deepCopy)
            .toList()
    }

    fun findOpenOperations(
        lane: String,
        limit: Int? = null,
    ): List<DurableRecord> {
        validateLimit(limit)
        val operations = openOperationsByLane[lane]?.values.orEmpty().toList().asReversed()
        return (limit?.let(operations::take) ?: operations).map(DurableRecord::deepCopy)
    }

    fun getLog(options: LogOptions = LogOptions()): List<DurableLogItem> {
        validateLimit(options.limit)
        validateCursor(options.afterSeq)
        return log
            .asSequence()
            .filter { options.afterSeq == null || it.seq > options.afterSeq }
            .let { sequence -> options.limit?.let(sequence::take) ?: sequence }
            .map(::copyLogItem)
            .toList()
    }

    fun getName(): String? = name

    fun getLabel(id: String): String? = labels[id]

    fun getStats(): DurableSessionStats = stats.copy()

    private fun walkToRoot(start: String): List<DurableEntry> {
        val result = mutableListOf<DurableEntry>()
        val visited = mutableSetOf<String>()
        var current =
            entriesById[start]
                ?: throw DurableSessionException(
                    DurableSessionErrorCode.NOT_FOUND,
                    "Entry not found: $start",
                )
        while (true) {
            if (!visited.add(current.id)) {
                throw DurableSessionException(
                    DurableSessionErrorCode.INVALID_ENTRY,
                    "Session branch contains a cycle at ${current.id}",
                )
            }
            result += current
            val parentId = current.parentId ?: break
            current =
                entriesById[parentId]
                    ?: throw DurableSessionException(
                        DurableSessionErrorCode.INVALID_ENTRY,
                        "Entry not found: $parentId",
                    )
        }
        return result
    }

    private fun matchesEntryQuery(
        entry: DurableEntry,
        query: EntryQuery,
    ): Boolean =
        (query.type == null || entry.type == query.type) &&
            (
                query.customType == null ||
                    (entry.payload as? EntryPayload.Custom)?.customType == query.customType
            ) &&
            (
                query.cursor == null ||
                    if (query.order == EntryOrder.OLDEST_FIRST) {
                        entry.seq > query.cursor.afterSeq
                    } else {
                        entry.seq < query.cursor.afterSeq
                    }
            )

    private fun matchesRecordQuery(
        record: DurableRecord,
        query: RecordQuery,
    ): Boolean =
        (query.lane == null || record.lane == query.lane) &&
            (query.type == null || record.type == query.type) &&
            (query.runId == null || record.runId == query.runId) &&
            (
                query.operationKind == null ||
                    (record.payload as? RecordPayload.OperationStarted)?.intent?.kind == query.operationKind
            ) &&
            (query.afterSeq == null || record.seq > query.afterSeq)
}

private fun invalidMutation(message: String): Nothing =
    throw DurableSessionException(
        DurableSessionErrorCode.INVALID_ENTRY,
        "Invalid session mutation: $message",
    )

internal fun validateLimit(limit: Int?) {
    if (limit != null && limit <= 0) {
        throw DurableSessionException(
            DurableSessionErrorCode.INVALID_QUERY,
            "limit must be a positive integer",
        )
    }
}

internal fun validateCursor(afterSeq: Long?) {
    if (afterSeq != null && afterSeq < 0) {
        throw DurableSessionException(
            DurableSessionErrorCode.INVALID_QUERY,
            "cursor sequence must be a non-negative integer",
        )
    }
}

private fun validateEntryQuery(query: EntryQuery) {
    validateLimit(query.limit)
    validateCursor(query.cursor?.afterSeq)
}

private fun validateRecordQuery(query: RecordQuery) {
    validateLimit(query.limit)
    validateCursor(query.afterSeq)
    if (query.operationKind != null && query.type != "operation_started") {
        throw DurableSessionException(
            DurableSessionErrorCode.INVALID_QUERY,
            "operationKind requires type \"operation_started\"",
        )
    }
}

private fun <T> ordered(
    values: List<T>,
    order: EntryOrder,
): List<T> =
    if (order == EntryOrder.OLDEST_FIRST) {
        values
    } else {
        values.asReversed()
    }

private fun copyLogItem(item: DurableLogItem): DurableLogItem =
    when (item) {
        is DurableLogItem.Entry -> item.copy(entry = item.entry.deepCopy())
        is DurableLogItem.Record -> item.copy(record = item.record.deepCopy())
        is DurableLogItem.Lane -> item.copy()
        is DurableLogItem.Name -> item.copy()
        is DurableLogItem.Label -> item.copy()
    }
