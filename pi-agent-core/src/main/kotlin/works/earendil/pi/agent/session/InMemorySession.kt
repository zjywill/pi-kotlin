package works.earendil.pi.agent.session

import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.uuidv7

class InMemorySessionStorage<M : SessionMetadata>(
    override val metadata: M,
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

    override suspend fun readHead(): SessionHead {
        if (leafId != null && !byId.containsKey(leafId)) {
            throw SessionException(SessionErrorCode.INVALID_SESSION, "Entry $leafId not found")
        }
        return SessionHead(leafId)
    }

    override suspend fun appendEntry(entry: SessionTreeEntry) {
        if (byId.containsKey(entry.id)) {
            throw SessionException(SessionErrorCode.INVALID_ENTRY, "Entry ${entry.id} already exists")
        }
        entries += entry
        byId[entry.id] = entry
        updateLabel(entry)
        leafId = leafIdAfterEntry(entry)
    }

    override suspend fun readEntry(id: String): SessionTreeEntry? = byId[id]

    override suspend fun getLabel(id: String): String? = labelsById[id]

    override suspend fun getName(): String? =
        entries
            .filterIsInstance<SessionInfoEntry>()
            .lastOrNull()
            ?.name
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    override suspend fun getStats(): SessionStats = calculateSessionStats(entries)

    override suspend fun readPathToRootOrCompaction(leafId: String?): List<SessionTreeEntry> {
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

    override suspend fun readEntries(options: SessionEntryCursorOptions?): List<SessionTreeEntry> {
        val start = options?.afterEntrySeq?.coerceIn(0, entries.size) ?: 0
        val endExclusive =
            options
                ?.limit
                ?.let { limit -> (start + limit.coerceAtLeast(0)).coerceAtMost(entries.size) }
                ?: entries.size
        return entries.subList(start, endExclusive).toList()
    }

    override suspend fun findEntriesOnBranch(query: SessionBranchQuery): List<SessionTreeEntry> =
        findEntriesOnCanonicalBranch(byId, query)

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

fun findEntriesOnCanonicalBranch(
    entriesById: Map<String, SessionTreeEntry>,
    query: SessionBranchQuery,
): List<SessionTreeEntry> {
    val startId = query.start ?: return emptyList()
    val pathFromStart = mutableListOf<SessionTreeEntry>()
    val visited = mutableSetOf<String>()
    var current =
        entriesById[startId]
            ?: throw SessionException(SessionErrorCode.NOT_FOUND, "Entry $startId not found")
    while (true) {
        if (!visited.add(current.id)) {
            throw SessionException(
                SessionErrorCode.INVALID_SESSION,
                "Session branch contains a cycle at ${current.id}",
            )
        }
        pathFromStart += current
        if (
            query.order == SessionBranchOrder.NEWEST_FIRST &&
            (current.id == query.stopAtId || entryType(current) == query.stopAtType)
        ) {
            break
        }
        val parentId = current.parentId ?: break
        current =
            entriesById[parentId]
                ?: throw SessionException(
                    SessionErrorCode.INVALID_SESSION,
                    "Entry $parentId not found",
                )
    }
    val traversal =
        if (query.order == SessionBranchOrder.OLDEST_FIRST) {
            pathFromStart.asReversed()
        } else {
            pathFromStart
        }
    val stopIndex =
        if (query.order == SessionBranchOrder.OLDEST_FIRST) {
            traversal.indexOfFirst { entry ->
                entry.id == query.stopAtId || entryType(entry) == query.stopAtType
            }
        } else {
            -1
        }
    val bounded = if (stopIndex < 0) traversal else traversal.take(stopIndex + 1)
    val filtered =
        bounded.filter { entry ->
            (query.type == null || entryType(entry) == query.type) &&
                (
                    query.customType == null ||
                        (entry is CustomEntry && entry.customType == query.customType)
                )
        }
    return query.limit?.let(filtered::take) ?: filtered
}

class InMemorySessionRepository : SessionRepository<SessionMetadata, InMemoryCreateOptions, Unit> {
    private val sessions = linkedMapOf<String, InMemorySessionStorage<SessionMetadata>>()

    override suspend fun create(options: InMemoryCreateOptions): Session<SessionMetadata> {
        val metadata = SessionMetadata(options.id ?: uuidv7(), nowTimestamp())
        val storage = InMemorySessionStorage(metadata)
        sessions[metadata.id] = storage
        return createSession(storage)
    }

    override suspend fun open(metadata: SessionMetadata): Session<SessionMetadata> =
        sessions[metadata.id]
            ?.let { createSession(it) }
            ?: throw SessionException(SessionErrorCode.NOT_FOUND, "Session not found: ${metadata.id}")

    override suspend fun list(options: Unit?): List<SessionMetadata> =
        sessions.values.map(InMemorySessionStorage<SessionMetadata>::metadata)

    override suspend fun delete(metadata: SessionMetadata) {
        sessions.remove(metadata.id)
    }

    override suspend fun fork(
        source: SessionMetadata,
        createOptions: InMemoryCreateOptions,
        forkOptions: SessionForkOptions,
    ): Session<SessionMetadata> {
        val sourceStorage =
            sessions[source.id]
                ?: throw SessionException(SessionErrorCode.NOT_FOUND, "Session not found: ${source.id}")
        val forkedEntries = getEntriesToFork(sourceStorage, forkOptions)
        val metadata = SessionMetadata(createOptions.id ?: forkOptions.id ?: uuidv7(), nowTimestamp())
        val storage = InMemorySessionStorage(metadata, forkedEntries)
        sessions[metadata.id] = storage
        return createSession(storage)
    }

    suspend fun fork(
        source: SessionMetadata,
        options: SessionForkOptions = SessionForkOptions(),
    ): Session<SessionMetadata> =
        fork(source, InMemoryCreateOptions(options.id), options)
}

data class InMemoryCreateOptions(
    val id: String? = null,
)

suspend fun getEntriesToFork(
    storage: SessionStorage<*>,
    options: SessionForkOptions,
): List<SessionTreeEntry> {
    val entryId = options.entryId ?: return storage.readEntries()
    val target =
        storage.readEntry(entryId)
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
    return storage.readPathToRootOrCompaction(effectiveLeafId)
}

fun <M : SessionMetadata, C, L> createScanningSessionSearch(
    repository: SessionRepository<M, C, L>,
    cwd: (M) -> String? = { null },
): SessionSearch<M> =
    SessionSearch { options ->
        val query = options.text.trim().lowercase()
        if (query.isEmpty()) {
            return@SessionSearch emptyList()
        }
        buildList {
            repository.list().forEach { metadata ->
                if (options.cwd != null && cwd(metadata) != options.cwd) {
                    return@forEach
                }
                repository.open(metadata).getEntries().forEach { entry ->
                    val snippet = entry.toString()
                    if (query in snippet.lowercase()) {
                        add(
                            SessionSearchHit(
                                metadata = metadata,
                                entryId = entry.id,
                                timestamp = entry.timestamp,
                                snippet = snippet,
                            ),
                        )
                    }
                }
            }
        }
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
