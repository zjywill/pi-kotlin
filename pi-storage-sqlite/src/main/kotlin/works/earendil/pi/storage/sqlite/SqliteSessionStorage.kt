package works.earendil.pi.storage.sqlite

import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.agent.session.BranchSummaryEntry
import works.earendil.pi.agent.session.CompactionEntry
import works.earendil.pi.agent.session.CustomEntry
import works.earendil.pi.agent.session.LabelEntry
import works.earendil.pi.agent.session.LeafEntry
import works.earendil.pi.agent.session.MessageEntry
import works.earendil.pi.agent.session.ModelChangeEntry
import works.earendil.pi.agent.session.SessionBranchOrder
import works.earendil.pi.agent.session.SessionBranchQuery
import works.earendil.pi.agent.session.SessionEntryCursorOptions
import works.earendil.pi.agent.session.SessionErrorCode
import works.earendil.pi.agent.session.SessionException
import works.earendil.pi.agent.session.SessionHead
import works.earendil.pi.agent.session.SessionInfoEntry
import works.earendil.pi.agent.session.SessionStats
import works.earendil.pi.agent.session.SessionStorage
import works.earendil.pi.agent.session.SessionTreeEntry
import works.earendil.pi.agent.session.ThinkingLevelChangeEntry
import works.earendil.pi.agent.session.calculateSessionStats
import works.earendil.pi.agent.session.entryType
import works.earendil.pi.agent.session.leafIdAfterEntry

class SqliteSessionStorage private constructor(
    private val connection: Connection,
    override val metadata: SqliteSessionMetadata,
    entries: List<SessionTreeEntry>,
    private var currentLeafId: String?,
) : SessionStorage<SqliteSessionMetadata> {
    private val mutexKey = metadata.path to metadata.id
    private val operationMutex = operationMutexes.computeIfAbsent(mutexKey) { Mutex() }
    private val byId = entries.associateByTo(linkedMapOf(), SessionTreeEntry::id)
    private var labelsById = buildLabels(entries)

    override suspend fun readHead(): SessionHead =
        operationMutex.withLock {
            if (currentLeafId != null && getEntryLocked(requireNotNull(currentLeafId)) == null) {
                throw SessionException(
                    SessionErrorCode.INVALID_SESSION,
                    "Entry $currentLeafId not found",
                )
            }
            SessionHead(currentLeafId)
        }

    override suspend fun appendEntry(entry: SessionTreeEntry) {
        operationMutex.withLock {
            appendEntryLocked(entry)
        }
    }

    override suspend fun readEntry(id: String): SessionTreeEntry? =
        operationMutex.withLock {
            getEntryLocked(id)
        }

    override suspend fun getLabel(id: String): String? =
        operationMutex.withLock {
            labelsById[id]
        }

    override suspend fun getName(): String? =
        operationMutex.withLock {
            connection
                .loadAllEntries()
                .filterIsInstance<SessionInfoEntry>()
                .lastOrNull()
                ?.name
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        }

    override suspend fun getStats(): SessionStats =
        operationMutex.withLock {
            calculateSessionStats(connection.loadAllEntries())
        }

    override suspend fun readPathToRootOrCompaction(leafId: String?): List<SessionTreeEntry> =
        operationMutex.withLock {
            if (leafId == null) {
                emptyList()
            } else {
                trimPathToRootOrCompaction(loadFullPath(leafId))
            }
        }

    override suspend fun readEntries(options: SessionEntryCursorOptions?): List<SessionTreeEntry> =
        operationMutex.withLock {
            val cursor = options?.afterEntrySeq ?: 0
            val limit = options?.limit
            val sql =
                if (limit == null) {
                    """
                    SELECT id, entry_seq, parent_id, type, timestamp, payload
                    FROM session_entries
                    WHERE session_id = ? AND entry_seq > ?
                    ORDER BY entry_seq
                    """.trimIndent()
                } else {
                    """
                    SELECT id, entry_seq, parent_id, type, timestamp, payload
                    FROM session_entries
                    WHERE session_id = ? AND entry_seq > ?
                    ORDER BY entry_seq
                    LIMIT ?
                    """.trimIndent()
                }
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, metadata.id)
                statement.setInt(2, cursor)
                if (limit != null) {
                    statement.setInt(3, limit)
                }
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            decodeEntry(rows.toEntryRow()).also { entry ->
                                byId[entry.id] = entry
                                add(entry)
                            }
                        }
                    }
                }
            }
        }

    override suspend fun findEntriesOnBranch(query: SessionBranchQuery): List<SessionTreeEntry> =
        operationMutex.withLock {
            val startId = query.start ?: return@withLock emptyList()
            val path =
                loadFullPath(startId).let { entries ->
                    if (query.order == SessionBranchOrder.OLDEST_FIRST) {
                        entries
                    } else {
                        entries.asReversed()
                    }
                }
            val bounded =
                buildList {
                    path.forEach { entry ->
                        add(entry)
                        if (entry.id == query.stopAtId || entryType(entry) == query.stopAtType) {
                            return@buildList
                        }
                    }
                }
            val filtered =
                bounded.filter { entry ->
                    (query.type == null || entryType(entry) == query.type) &&
                        (
                            query.customType == null ||
                                (entry is CustomEntry && entry.customType == query.customType)
                        )
                }
            query.limit?.let(filtered::take) ?: filtered
        }

    override suspend fun close() {
        operationMutex.withLock {
            connection.close()
        }
        operationMutexes.remove(mutexKey, operationMutex)
    }

    private fun appendEntryLocked(entry: SessionTreeEntry) {
        val leafTargetId = (entry as? LeafEntry)?.targetId
        if (leafTargetId != null && getEntryLocked(leafTargetId) == null) {
            throw SessionException(SessionErrorCode.NOT_FOUND, "Entry $leafTargetId not found")
        }
        val nextLeafId = leafIdAfterEntry(entry)
        val nextLabels = HashMap(labelsById).also { labels -> updateLabel(labels, entry) }
        try {
            connection.transaction {
                val nextSequence = nextSequence()
                prepareStatement(
                    """
                    INSERT INTO session_entries
                        (session_id, id, entry_seq, parent_id, type, timestamp, payload)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, metadata.id)
                    statement.setString(2, entry.id)
                    statement.setInt(3, nextSequence)
                    statement.setString(4, entry.parentId)
                    statement.setString(5, entryType(entry))
                    statement.setString(6, entry.timestamp)
                    statement.setString(7, encodeEntryPayload(entry))
                    statement.executeUpdate()
                }
                prepareStatement("UPDATE session_sequences SET next_seq = ? WHERE session_id = ?").use { statement ->
                    statement.setInt(1, nextSequence + 1)
                    statement.setString(2, metadata.id)
                    statement.executeUpdate()
                }
                prepareStatement("UPDATE sessions SET active_leaf_id = ? WHERE id = ?").use { statement ->
                    statement.setString(1, nextLeafId)
                    statement.setString(2, metadata.id)
                    statement.executeUpdate()
                }
                appendEntryToBranchCache(
                    sessionId = metadata.id,
                    entryId = entry.id,
                    entrySequence = nextSequence,
                    parentId = entry.parentId,
                )
                writeMaterializedState()
            }
            byId[entry.id] = entry
            currentLeafId = nextLeafId
            labelsById = nextLabels
        } catch (error: Exception) {
            if (error is SessionException) {
                throw error
            }
            throw SessionException(
                SessionErrorCode.STORAGE,
                "Failed to append SQLite session entry ${entry.id}",
                error,
            )
        }
    }

    private fun getEntryLocked(id: String): SessionTreeEntry? {
        byId[id]?.let { return it }
        return loadEntry(id)
    }

    private fun loadFullPath(leafId: String): List<SessionTreeEntry> {
        var cached = connection.readCachedBranch(metadata.id, leafId)
        if (cached != null) {
            val entries = decodeRows(connection.readCachedBranchRows(metadata.id, cached))
            if (isValidCachedPath(entries, leafId)) {
                return entries
            }
        }
        val canonical = readCanonicalPathToRoot(leafId)
        try {
            connection.transaction {
                rebuildCachedBranch(metadata.id, leafId, cached?.branchId)
            }
        } catch (error: Exception) {
            if (error is SessionException) {
                throw error
            }
            throw SessionException(
                SessionErrorCode.STORAGE,
                "Failed to rebuild SQLite branch cache at entry $leafId",
                error,
            )
        }
        cached = connection.readCachedBranch(metadata.id, leafId)
        if (cached == null) {
            throw SessionException(
                SessionErrorCode.INVALID_SESSION,
                "Branch cache repair did not produce entry $leafId",
            )
        }
        return canonical
    }

    private fun readCanonicalPathToRoot(leafId: String): List<SessionTreeEntry> {
        val path = mutableListOf<SessionTreeEntry>()
        val visited = mutableSetOf<String>()
        var current =
            getEntryLocked(leafId)
                ?: throw SessionException(SessionErrorCode.NOT_FOUND, "Entry $leafId not found")
        while (true) {
            if (!visited.add(current.id)) {
                throw SessionException(
                    SessionErrorCode.INVALID_SESSION,
                    "Cycle in parent chain at entry ${current.id}",
                )
            }
            path += current
            val parentId = current.parentId ?: break
            current =
                getEntryLocked(parentId)
                    ?: throw SessionException(
                        SessionErrorCode.INVALID_SESSION,
                        "Entry $parentId not found",
                    )
        }
        return path.asReversed()
    }

    private fun trimPathToRootOrCompaction(entries: List<SessionTreeEntry>): List<SessionTreeEntry> {
        val path = mutableListOf<SessionTreeEntry>()
        var stopAtEntryId: String? = null
        for (index in entries.indices.reversed()) {
            val entry = entries[index]
            path += entry
            if (stopAtEntryId != null && entry.id == stopAtEntryId) {
                break
            }
            if (entry is CompactionEntry) {
                if (entry.retainedTail != null) {
                    break
                }
                stopAtEntryId = entry.firstKeptEntryId
            }
        }
        return path.asReversed()
    }

    private fun isValidCachedPath(
        entries: List<SessionTreeEntry>,
        leafId: String,
    ): Boolean {
        if (entries.isEmpty() || entries.last().id != leafId || entries.first().parentId != null) {
            return false
        }
        return entries
            .zipWithNext()
            .all { (parent, child) -> child.parentId == parent.id }
    }

    private fun decodeRows(rows: List<SessionEntryRow>): List<SessionTreeEntry> =
        rows.map(::decodeEntry).onEach { entry -> byId[entry.id] = entry }

    private fun loadEntry(id: String): SessionTreeEntry? {
        val row =
            connection
                .prepareStatement(
                    """
                    SELECT id, entry_seq, parent_id, type, timestamp, payload
                    FROM session_entries
                    WHERE session_id = ? AND id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, metadata.id)
                    statement.setString(2, id)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.toEntryRow() else null
                    }
                } ?: return null
        return decodeEntry(row).also { byId[it.id] = it }
    }

    private fun Connection.nextSequence(): Int =
        prepareStatement("SELECT next_seq FROM session_sequences WHERE session_id = ?").use { statement ->
            statement.setString(1, metadata.id)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    throw SessionException(
                        SessionErrorCode.INVALID_SESSION,
                        "Invalid SQLite session: missing sequence row for session ${metadata.id}",
                    )
                }
                rows.getInt("next_seq")
            }
        }

    private fun Connection.writeMaterializedState() {
        val entries = loadAllEntries(cache = false)
        val name =
            entries
                .filterIsInstance<SessionInfoEntry>()
                .lastOrNull()
                ?.name
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        var currentModel: Pair<String, String>? = null
        var currentThinkingLevel: String? = null
        entries.forEach { entry ->
            when (entry) {
                is ModelChangeEntry -> currentModel = entry.provider to entry.modelId
                is MessageEntry -> {
                    val message = entry.message
                    if (message is works.earendil.pi.ai.AssistantMessage) {
                        currentModel = message.provider to message.model
                    }
                }

                is ThinkingLevelChangeEntry -> currentThinkingLevel = entry.thinkingLevel
                else -> Unit
            }
        }
        prepareStatement("UPDATE session_materialized SET payload = ? WHERE session_id = ?").use { statement ->
            statement.setString(1, summaryJson(entries, name, currentModel, currentThinkingLevel))
            statement.setString(2, metadata.id)
            statement.executeUpdate()
        }
        prepareStatement("DELETE FROM entry_materialized WHERE session_id = ?").use { statement ->
            statement.setString(1, metadata.id)
            statement.executeUpdate()
        }
        entries.forEachIndexed { index, entry ->
            if (entry is LabelEntry) {
                prepareStatement(
                    """
                    INSERT INTO entry_materialized (session_id, entry_seq, type, payload)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, metadata.id)
                    statement.setInt(2, index + 1)
                    statement.setString(3, "label")
                    statement.setString(
                        4,
                        buildJsonObject {
                            put("targetId", entry.targetId)
                            entry.label?.let { put("label", it) }
                        }.toString(),
                    )
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun Connection.loadAllEntries(cache: Boolean = true): List<SessionTreeEntry> =
        prepareStatement(
            """
            SELECT id, entry_seq, parent_id, type, timestamp, payload
            FROM session_entries
            WHERE session_id = ?
            ORDER BY entry_seq
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, metadata.id)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        decodeEntry(rows.toEntryRow()).also { entry ->
                            if (cache) {
                                byId[entry.id] = entry
                            }
                            add(entry)
                        }
                    }
                }
            }
        }

    companion object {
        private val operationMutexes = ConcurrentHashMap<Pair<java.nio.file.Path, String>, Mutex>()

        internal fun create(
            connection: Connection,
            metadata: SqliteSessionMetadata,
        ): SqliteSessionStorage {
            connection.prepareStatement(
                """
                INSERT INTO sessions
                    (id, created_at, metadata, cwd, parent_session_id, active_leaf_id)
                VALUES (?, ?, ?, ?, ?, NULL)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, metadata.id)
                statement.setString(2, metadata.createdAt)
                statement.setString(3, encodeMetadata(metadata.metadata))
                statement.setString(4, metadata.cwd.toString())
                statement.setString(5, metadata.parentSessionId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO session_sequences (session_id, next_seq) VALUES (?, 1)",
            ).use { statement ->
                statement.setString(1, metadata.id)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO session_materialized (session_id, payload) VALUES (?, ?)",
            ).use { statement ->
                statement.setString(1, metadata.id)
                statement.setString(2, summaryJson(emptyList(), null, null, null))
                statement.executeUpdate()
            }
            return SqliteSessionStorage(connection, metadata, emptyList(), null)
        }

        internal fun open(
            connection: Connection,
            metadata: SqliteSessionMetadata,
        ): SqliteSessionStorage {
            val sessionState =
                connection.prepareStatement(
                    "SELECT active_leaf_id FROM sessions WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, metadata.id)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) {
                            true to rows.getString("active_leaf_id")
                        } else {
                            false to null
                        }
                    }
                }
            if (!sessionState.first) {
                throw SessionException(
                    SessionErrorCode.NOT_FOUND,
                    "Session not found: ${metadata.id}",
                )
            }
            val entries =
                connection.prepareStatement(
                    """
                    SELECT id, entry_seq, parent_id, type, timestamp, payload
                    FROM session_entries
                    WHERE session_id = ?
                    ORDER BY entry_seq
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, metadata.id)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(decodeEntry(rows.toEntryRow()))
                            }
                        }
                    }
                }
            return SqliteSessionStorage(connection, metadata, entries, sessionState.second)
        }

        private fun buildLabels(entries: List<SessionTreeEntry>): MutableMap<String, String> =
            buildMap {
                entries.forEach { entry -> updateLabel(this, entry) }
            }.toMutableMap()

        private fun updateLabel(
            labels: MutableMap<String, String>,
            entry: SessionTreeEntry,
        ) {
            if (entry !is LabelEntry) {
                return
            }
            val label = entry.label?.trim()
            if (label.isNullOrEmpty()) {
                labels.remove(entry.targetId)
            } else {
                labels[entry.targetId] = label
            }
        }
    }
}
