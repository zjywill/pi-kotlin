package works.earendil.pi.agent.session

import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.uuidv7

class InMemorySessionStorage<M : SessionMetadata>(
    private val metadata: M,
    entries: List<SessionTreeEntry> = emptyList(),
) : SessionStorage<M> {
    private val entries = entries.toMutableList()
    private val byId = entries.associateByTo(linkedMapOf(), SessionTreeEntry::id)
    private val labelsById = mutableMapOf<String, String>()
    private var leafId: String? = null

    init {
        entries.forEach { entry ->
            updateLabel(entry)
            leafId = leafIdAfterEntry(entry)
        }
        if (leafId != null && !byId.containsKey(leafId)) {
            throw SessionException(SessionErrorCode.INVALID_SESSION, "Entry $leafId not found")
        }
    }

    override suspend fun getMetadata(): M = metadata

    override suspend fun getLeafId(): String? {
        if (leafId != null && !byId.containsKey(leafId)) {
            throw SessionException(SessionErrorCode.INVALID_SESSION, "Entry $leafId not found")
        }
        return leafId
    }

    override suspend fun setLeafId(leafId: String?) {
        if (leafId != null && !byId.containsKey(leafId)) {
            throw SessionException(SessionErrorCode.NOT_FOUND, "Entry $leafId not found")
        }
        appendEntry(
            LeafEntry(
                id = createEntryId(),
                parentId = this.leafId,
                timestamp = nowTimestamp(),
                targetId = leafId,
            ),
        )
    }

    override suspend fun createEntryId(): String {
        repeat(100) {
            val candidate = uuidv7().takeLast(8)
            if (!byId.containsKey(candidate)) {
                return candidate
            }
        }
        return uuidv7()
    }

    override suspend fun appendEntry(entry: SessionTreeEntry) {
        entries += entry
        byId[entry.id] = entry
        updateLabel(entry)
        leafId = leafIdAfterEntry(entry)
    }

    override suspend fun getEntry(id: String): SessionTreeEntry? = byId[id]

    override suspend fun findEntries(type: String): List<SessionTreeEntry> =
        entries.filter { entryType(it) == type }

    override suspend fun getLabel(id: String): String? = labelsById[id]

    override suspend fun getSessionName(): String? =
        entries
            .filterIsInstance<SessionInfoEntry>()
            .lastOrNull()
            ?.name
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    override suspend fun getSessionStats(): SessionStats = calculateSessionStats(entries)

    override suspend fun getPathToRootOrCompaction(leafId: String?): List<SessionTreeEntry> {
        if (leafId == null) {
            return emptyList()
        }
        var current = byId[leafId]
            ?: throw SessionException(SessionErrorCode.NOT_FOUND, "Entry $leafId not found")
        val path = mutableListOf<SessionTreeEntry>()
        var stopAtEntryId: String? = null
        while (true) {
            path.add(0, current)
            if (stopAtEntryId != null && current.id == stopAtEntryId) {
                break
            }
            if (current is CompactionEntry) {
                if (current.retainedTail != null) {
                    break
                }
                stopAtEntryId = current.firstKeptEntryId
            }
            val parentId = current.parentId ?: break
            current = byId[parentId]
                ?: throw SessionException(SessionErrorCode.INVALID_SESSION, "Entry $parentId not found")
        }
        return path
    }

    override suspend fun getEntries(options: SessionEntryCursorOptions?): List<SessionTreeEntry> {
        if (options?.limit == null) {
            return entries.toList()
        }
        val endExclusive = options.afterEntrySeq?.coerceIn(0, entries.size) ?: entries.size
        val start = (endExclusive - options.limit).coerceAtLeast(0)
        return entries.subList(start, endExclusive).toList()
    }

    private fun updateLabel(entry: SessionTreeEntry) {
        if (entry !is LabelEntry) {
            return
        }
        val label = entry.label?.trim()
        if (label.isNullOrEmpty()) {
            labelsById.remove(entry.targetId)
        } else {
            labelsById[entry.targetId] = label
        }
    }
}

class InMemorySessionRepository : SessionRepository<SessionMetadata, InMemoryCreateOptions, Unit> {
    private val sessions = linkedMapOf<String, Session<SessionMetadata>>()

    override suspend fun create(options: InMemoryCreateOptions): Session<SessionMetadata> {
        val metadata = SessionMetadata(options.id ?: uuidv7(), nowTimestamp())
        val session = Session(InMemorySessionStorage(metadata))
        sessions[metadata.id] = session
        return session
    }

    override suspend fun open(metadata: SessionMetadata): Session<SessionMetadata> =
        sessions[metadata.id]
            ?: throw SessionException(SessionErrorCode.NOT_FOUND, "Session not found: ${metadata.id}")

    override suspend fun list(options: Unit?): List<SessionMetadata> =
        buildList {
            sessions.values.forEach { session -> add(session.getMetadata()) }
        }

    override suspend fun delete(metadata: SessionMetadata) {
        sessions.remove(metadata.id)
    }

    suspend fun fork(
        sourceMetadata: SessionMetadata,
        options: SessionForkOptions = SessionForkOptions(),
    ): Session<SessionMetadata> {
        val source = open(sourceMetadata)
        val forkedEntries = getEntriesToFork(source.getStorage(), options)
        val metadata = SessionMetadata(options.id ?: uuidv7(), nowTimestamp())
        val session = Session(InMemorySessionStorage(metadata, forkedEntries))
        sessions[metadata.id] = session
        return session
    }
}

data class InMemoryCreateOptions(
    val id: String? = null,
)

suspend fun getEntriesToFork(
    storage: SessionStorage<*>,
    options: SessionForkOptions,
): List<SessionTreeEntry> {
    val entryId = options.entryId ?: return storage.getEntries()
    val target =
        storage.getEntry(entryId)
            ?: throw SessionException(SessionErrorCode.INVALID_FORK_TARGET, "Entry $entryId not found")
    val effectiveLeafId =
        when (options.position) {
            SessionForkOptions.Position.AT -> target.id
            SessionForkOptions.Position.BEFORE -> {
                if (target !is MessageEntry || target.message !is works.earendil.pi.ai.UserMessage) {
                    throw SessionException(
                        SessionErrorCode.INVALID_FORK_TARGET,
                        "Entry $entryId is not a user message",
                    )
                }
                target.parentId
            }
        }
    return storage.getPathToRootOrCompaction(effectiveLeafId)
}

fun calculateSessionStats(entries: List<SessionTreeEntry>): SessionStats {
    var messageCount = 0
    var cachedTokens = 0
    var uncachedTokens = 0
    var totalTokens = 0
    var costTotal = 0.0
    entries.forEach { entry ->
        val usage: Usage? =
            when (entry) {
                is MessageEntry -> {
                    messageCount++
                    (entry.message as? AssistantMessage)?.usage
                }

                is CompactionEntry -> entry.usage
                is BranchSummaryEntry -> entry.usage
                else -> null
            }
        if (usage != null) {
            cachedTokens += usage.cacheRead
            uncachedTokens += usage.input + usage.cacheWrite
            totalTokens += usage.input + usage.output + usage.cacheRead + usage.cacheWrite
            costTotal += usage.cost.total
        }
    }
    return SessionStats(messageCount, cachedTokens, uncachedTokens, totalTokens, costTotal)
}
