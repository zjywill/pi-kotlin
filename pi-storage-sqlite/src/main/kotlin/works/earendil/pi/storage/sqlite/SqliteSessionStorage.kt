package works.earendil.pi.storage.sqlite

import java.sql.Connection
import java.sql.ResultSet
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.agent.session.BranchSummaryEntry
import works.earendil.pi.agent.session.CompactionEntry
import works.earendil.pi.agent.session.LabelEntry
import works.earendil.pi.agent.session.LeafEntry
import works.earendil.pi.agent.session.MessageEntry
import works.earendil.pi.agent.session.ModelChangeEntry
import works.earendil.pi.agent.session.SessionEntryCursorOptions
import works.earendil.pi.agent.session.SessionErrorCode
import works.earendil.pi.agent.session.SessionException
import works.earendil.pi.agent.session.SessionInfoEntry
import works.earendil.pi.agent.session.SessionStats
import works.earendil.pi.agent.session.SessionStorage
import works.earendil.pi.agent.session.SessionTreeEntry
import works.earendil.pi.agent.session.ThinkingLevelChangeEntry
import works.earendil.pi.agent.session.calculateSessionStats
import works.earendil.pi.agent.session.entryType
import works.earendil.pi.agent.session.leafIdAfterEntry
import works.earendil.pi.ai.uuidv7

class SqliteSessionStorage private constructor(
    private val connection: Connection,
    private val metadata: SqliteSessionMetadata,
    entries: List<SessionTreeEntry>,
    private var currentLeafId: String?,
    private var activeBranchId: String?,
) : SessionStorage<SqliteSessionMetadata> {
    private var byId = entries.associateByTo(linkedMapOf(), SessionTreeEntry::id)
    private var labelsById = buildLabels(entries)

    override suspend fun getMetadata(): SqliteSessionMetadata = metadata

    override suspend fun getLeafId(): String? = currentLeafId

    override suspend fun setLeafId(leafId: String?) {
        if (leafId != null && getEntry(leafId) == null) {
            throw SessionException(SessionErrorCode.NOT_FOUND, "Entry $leafId not found")
        }
        appendEntry(
            LeafEntry(
                id = createEntryId(),
                parentId = currentLeafId,
                timestamp = works.earendil.pi.agent.session.nowTimestamp(),
                targetId = leafId,
            ),
        )
    }

    override suspend fun createEntryId(): String {
        repeat(100) {
            val candidate = uuidv7().takeLast(8)
            val exists =
                connection
                    .prepareStatement(
                        "SELECT 1 FROM session_entries WHERE session_id = ? AND id = ? LIMIT 1",
                    ).use { statement ->
                        statement.setString(1, metadata.id)
                        statement.setString(2, candidate)
                        statement.executeQuery().use(ResultSet::next)
                    }
            if (!exists) {
                return candidate
            }
        }
        return uuidv7()
    }

    override suspend fun appendEntry(entry: SessionTreeEntry) {
        val previousById = LinkedHashMap(byId)
        val previousLabels = HashMap(labelsById)
        val previousLeaf = currentLeafId
        val previousBranch = activeBranchId
        try {
            connection.transaction {
                val parentHadExistingChild = hasExistingChild(entry.parentId)
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
                byId[entry.id] = entry
                currentLeafId = leafIdAfterEntry(entry)
                updateLabel(entry)
                prepareStatement("UPDATE sessions SET active_leaf_id = ? WHERE id = ?").use { statement ->
                    statement.setString(1, currentLeafId)
                    statement.setString(2, metadata.id)
                    statement.executeUpdate()
                }
                if (entry is LeafEntry) {
                    activeBranchId = null
                    materializeBranch(entry.targetId)
                    appendToActiveBranch(entry.id)
                } else {
                    if (activeBranchId == null || parentHadExistingChild) {
                        materializeBranch(entry.parentId)
                    }
                    appendToActiveBranch(entry.id)
                }
                writeMaterializedState()
            }
        } catch (error: Exception) {
            byId = previousById
            labelsById = previousLabels
            currentLeafId = previousLeaf
            activeBranchId = previousBranch
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

    override suspend fun getEntry(id: String): SessionTreeEntry? {
        byId[id]?.let { return it }
        val row =
            connection
                .prepareStatement(
                    """
                    SELECT id, entry_seq, parent_id, type, timestamp, payload
                    FROM session_entries WHERE session_id = ? AND id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, metadata.id)
                    statement.setString(2, id)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.toEntryRow() else null
                    }
                } ?: return null
        return runCatching { decodeEntry(row) }.getOrNull()?.also { byId[it.id] = it }
    }

    override suspend fun findEntries(type: String): List<SessionTreeEntry> =
        connection
            .prepareStatement(
                """
                SELECT id, entry_seq, parent_id, type, timestamp, payload
                FROM session_entries WHERE session_id = ? AND type = ? ORDER BY entry_seq
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, metadata.id)
                statement.setString(2, type)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            runCatching { decodeEntry(rows.toEntryRow()) }.getOrNull()?.let {
                                byId[it.id] = it
                                add(it)
                            }
                        }
                    }
                }
            }

    override suspend fun getLabel(id: String): String? = labelsById[id]

    override suspend fun getSessionName(): String? =
        getEntries()
            .filterIsInstance<SessionInfoEntry>()
            .lastOrNull()
            ?.name
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    override suspend fun getSessionStats(): SessionStats = calculateSessionStats(getEntries())

    override suspend fun getPathToRootOrCompaction(leafId: String?): List<SessionTreeEntry> {
        if (leafId == null) {
            return emptyList()
        }
        if (leafId == currentLeafId && activeBranchId != null) {
            return loadMaterializedBranch(requireNotNull(activeBranchId))
        }
        return buildPath(leafId)
    }

    override suspend fun getEntries(options: SessionEntryCursorOptions?): List<SessionTreeEntry> {
        val cursor = options?.afterEntrySeq
        val limit = options?.limit
        val sql =
            if (limit == null) {
                """
                SELECT id, entry_seq, parent_id, type, timestamp, payload
                FROM session_entries WHERE session_id = ? ORDER BY entry_seq
                """.trimIndent()
            } else {
                """
                SELECT id, entry_seq, parent_id, type, timestamp, payload
                FROM session_entries
                WHERE session_id = ? AND entry_seq <= COALESCE(?, entry_seq)
                ORDER BY entry_seq DESC LIMIT ?
                """.trimIndent()
            }
        val entries =
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, metadata.id)
                if (limit != null) {
                    if (cursor == null) {
                        statement.setNull(2, java.sql.Types.INTEGER)
                    } else {
                        statement.setInt(2, cursor)
                    }
                    statement.setInt(3, limit)
                }
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            runCatching { decodeEntry(rows.toEntryRow()) }.getOrNull()?.let(::add)
                        }
                    }
                }
            }
        val ordered = if (limit == null) entries else entries.asReversed()
        ordered.forEach { byId[it.id] = it }
        return ordered
    }

    override suspend fun close() {
        connection.close()
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

    private fun Connection.hasExistingChild(parentId: String?): Boolean {
        val sql =
            if (parentId == null) {
                "SELECT 1 FROM session_entries WHERE session_id = ? AND parent_id IS NULL LIMIT 1"
            } else {
                "SELECT 1 FROM session_entries WHERE session_id = ? AND parent_id = ? LIMIT 1"
            }
        return prepareStatement(sql).use { statement ->
            statement.setString(1, metadata.id)
            if (parentId != null) {
                statement.setString(2, parentId)
            }
            statement.executeQuery().use(ResultSet::next)
        }
    }

    private fun Connection.materializeBranch(leafId: String?) {
        val branchId = uuidv7()
        buildPath(leafId).forEach { entry ->
            val sequence = entrySequence(entry.id)
            prepareStatement(
                "INSERT INTO branch_entries (session_id, branch_id, entry_id, entry_seq) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, metadata.id)
                statement.setString(2, branchId)
                statement.setString(3, entry.id)
                statement.setInt(4, sequence)
                statement.executeUpdate()
            }
        }
        activeBranchId = branchId
    }

    private fun Connection.appendToActiveBranch(entryId: String) {
        val branchId =
            activeBranchId
                ?: throw SessionException(
                    SessionErrorCode.INVALID_SESSION,
                    "Invalid SQLite session: active branch missing for session ${metadata.id}",
                )
        prepareStatement(
            "INSERT INTO branch_entries (session_id, branch_id, entry_id, entry_seq) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, metadata.id)
            statement.setString(2, branchId)
            statement.setString(3, entryId)
            statement.setInt(4, entrySequence(entryId))
            statement.executeUpdate()
        }
    }

    private fun Connection.entrySequence(entryId: String): Int =
        prepareStatement(
            "SELECT entry_seq FROM session_entries WHERE session_id = ? AND id = ?",
        ).use { statement ->
            statement.setString(1, metadata.id)
            statement.setString(2, entryId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    throw SessionException(
                        SessionErrorCode.INVALID_SESSION,
                        "Invalid SQLite session: missing entry row for $entryId",
                    )
                }
                rows.getInt("entry_seq")
            }
        }

    private fun buildPath(leafId: String?): List<SessionTreeEntry> {
        if (leafId == null) {
            return emptyList()
        }
        var current =
            byId[leafId] ?: loadEntry(leafId)
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
            current =
                byId[parentId] ?: loadEntry(parentId)
                    ?: throw SessionException(
                        SessionErrorCode.INVALID_SESSION,
                        "Entry $parentId not found",
                    )
        }
        return path
    }

    private fun loadEntry(id: String): SessionTreeEntry? {
        val row =
            connection
                .prepareStatement(
                    """
                    SELECT id, entry_seq, parent_id, type, timestamp, payload
                    FROM session_entries WHERE session_id = ? AND id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, metadata.id)
                    statement.setString(2, id)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.toEntryRow() else null
                    }
                } ?: return null
        return runCatching { decodeEntry(row) }.getOrNull()?.also { byId[it.id] = it }
    }

    private fun loadMaterializedBranch(branchId: String): List<SessionTreeEntry> =
        connection
            .prepareStatement(
                """
                SELECT e.id, e.entry_seq, e.parent_id, e.type, e.timestamp, e.payload
                FROM branch_entries b
                JOIN session_entries e
                  ON e.session_id = b.session_id AND e.id = b.entry_id
                WHERE b.session_id = ? AND b.branch_id = ?
                ORDER BY b.entry_seq
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, metadata.id)
                statement.setString(2, branchId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val entry = decodeEntry(rows.toEntryRow())
                            byId[entry.id] = entry
                            if (entry !is LeafEntry) {
                                add(entry)
                            }
                        }
                    }
                }
            }

    private fun Connection.writeMaterializedState() {
        val entries = loadAllEntries()
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

    private fun Connection.loadAllEntries(): List<SessionTreeEntry> =
        prepareStatement(
            """
            SELECT id, entry_seq, parent_id, type, timestamp, payload
            FROM session_entries WHERE session_id = ? ORDER BY entry_seq
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

    companion object {
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
            return SqliteSessionStorage(connection, metadata, emptyList(), null, null)
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
                    FROM session_entries WHERE session_id = ? ORDER BY entry_seq
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, metadata.id)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                runCatching { decodeEntry(rows.toEntryRow()) }.getOrNull()?.let(::add)
                            }
                        }
                    }
                }
            val activeBranchId =
                connection.prepareStatement(
                    """
                    SELECT branch_id FROM branch_entries
                    WHERE session_id = ? ORDER BY entry_seq DESC, branch_id DESC LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, metadata.id)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getString("branch_id") else null
                    }
                }
            return SqliteSessionStorage(connection, metadata, entries, sessionState.second, activeBranchId)
        }

        private fun buildLabels(entries: List<SessionTreeEntry>): MutableMap<String, String> =
            buildMap {
                entries.filterIsInstance<LabelEntry>().forEach { entry ->
                    val label = entry.label?.trim()
                    if (label.isNullOrEmpty()) {
                        remove(entry.targetId)
                    } else {
                        put(entry.targetId, label)
                    }
                }
            }.toMutableMap()
    }
}

private fun ResultSet.toEntryRow(): SessionEntryRow =
    SessionEntryRow(
        id = getString("id"),
        sequence = getInt("entry_seq"),
        parentId = getString("parent_id"),
        type = getString("type"),
        timestamp = getString("timestamp"),
        payload = getString("payload"),
    )
