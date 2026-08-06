package works.earendil.pi.storage.sqlite

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import works.earendil.pi.agent.harness.session.BranchBounds
import works.earendil.pi.agent.harness.session.DurableEntry
import works.earendil.pi.agent.harness.session.DurableForkOptions
import works.earendil.pi.agent.harness.session.DurableLogItem
import works.earendil.pi.agent.harness.session.DurableMutation
import works.earendil.pi.agent.harness.session.DurableRecord
import works.earendil.pi.agent.harness.session.DurableSession
import works.earendil.pi.agent.harness.session.DurableSessionCreateOptions
import works.earendil.pi.agent.harness.session.DurableSessionErrorCode
import works.earendil.pi.agent.harness.session.DurableSessionException
import works.earendil.pi.agent.harness.session.DurableSessionMetadata
import works.earendil.pi.agent.harness.session.DurableSessionRepository
import works.earendil.pi.agent.harness.session.DurableSessionState
import works.earendil.pi.agent.harness.session.DurableSessionStats
import works.earendil.pi.agent.harness.session.DurableSessionStorage
import works.earendil.pi.agent.harness.session.EntryOrder
import works.earendil.pi.agent.harness.session.EntryPayload
import works.earendil.pi.agent.harness.session.EntryQuery
import works.earendil.pi.agent.harness.session.LanePointer
import works.earendil.pi.agent.harness.session.LogOptions
import works.earendil.pi.agent.harness.session.NewDurableRecord
import works.earendil.pi.agent.harness.session.ProvisionedEntry
import works.earendil.pi.agent.harness.session.RecordPayload
import works.earendil.pi.agent.harness.session.RecordQuery
import works.earendil.pi.agent.harness.session.encodeJsonlMutation
import works.earendil.pi.agent.harness.session.parseJsonlMutation
import works.earendil.pi.ai.uuidv7

data class SqliteDurableSessionMetadata(
    override val id: String,
    override val createdAt: Long,
    override val parentSessionId: String? = null,
    val cwd: Path,
    val path: Path,
    val name: String? = null,
    val metadata: JsonObject? = null,
) : DurableSessionMetadata(id, createdAt, parentSessionId)

data class SqliteDurableSessionCreateOptions(
    override val id: String? = null,
    override val parentSessionId: String? = null,
    val cwd: Path,
    val metadata: JsonObject? = null,
) : DurableSessionCreateOptions

data class SqliteDurableSessionListOptions(
    val cwd: Path? = null,
)

data class SqliteWriterLeaseOptions(
    val ttlMs: Long = 30_000,
    val heartbeatIntervalMs: Long = 10_000,
) {
    init {
        require(ttlMs > 0) { "writerLease.ttlMs must be positive" }
        require(heartbeatIntervalMs > 0 && heartbeatIntervalMs < ttlMs) {
            "writerLease.heartbeatIntervalMs must be positive and less than ttlMs"
        }
    }
}

class SqliteDurableSessionRepository(
    databasePath: Path,
    private val writerLease: SqliteWriterLeaseOptions = SqliteWriterLeaseOptions(),
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = ::uuidv7,
) : DurableSessionRepository<
        SqliteDurableSessionMetadata,
        SqliteDurableSessionCreateOptions,
        SqliteDurableSessionListOptions,
    > {
    private val path = databasePath.toAbsolutePath().normalize()
    private val mutex = Mutex()
    private val openStorages = mutableMapOf<String, SqliteDurableSessionStorage>()

    override suspend fun create(
        options: SqliteDurableSessionCreateOptions,
    ): DurableSession<SqliteDurableSessionMetadata> =
        mutex.withLock {
            val id = options.id ?: idGenerator()
            if (id in openStorages) {
                alreadyExists(id)
            }
            openConnection().use { connection ->
                if (sessionExists(connection, id)) {
                    alreadyExists(id)
                }
                val metadata =
                    SqliteDurableSessionMetadata(
                        id = id,
                        createdAt = currentTimeMillis(),
                        parentSessionId = options.parentSessionId,
                        cwd = options.cwd.toAbsolutePath().normalize(),
                        path = path,
                        metadata = options.metadata,
                    )
                connection.transaction {
                    prepareStatement(
                        """
                        INSERT INTO durable_sessions
                            (id, created_at, cwd, parent_session_id, metadata)
                        VALUES (?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, metadata.id)
                        statement.setLong(2, metadata.createdAt)
                        statement.setString(3, metadata.cwd.toString())
                        statement.setString(4, metadata.parentSessionId)
                        statement.setString(5, metadata.metadata?.toString())
                        statement.executeUpdate()
                    }
                }
                val storage = openStorage(metadata)
                openStorages[id] = storage
                DurableSession(storage, idGenerator)
            }
        }

    override suspend fun open(
        metadata: SqliteDurableSessionMetadata,
    ): DurableSession<SqliteDurableSessionMetadata> =
        mutex.withLock {
            openStorages[metadata.id]?.let { storage ->
                return@withLock DurableSession(storage, idGenerator)
            }
            openConnection().use { connection ->
                val current =
                    readMetadata(connection, metadata.id)
                        ?: throw DurableSessionException(
                            DurableSessionErrorCode.NOT_FOUND,
                            "Session not found: ${metadata.id}",
                        )
                val storage = openStorage(current)
                openStorages[current.id] = storage
                DurableSession(storage, idGenerator)
            }
        }

    override suspend fun list(
        options: SqliteDurableSessionListOptions?,
    ): List<SqliteDurableSessionMetadata> =
        openConnection().use { connection ->
            val cwd = options?.cwd?.toAbsolutePath()?.normalize()?.toString()
            val sql =
                if (cwd == null) {
                    """
                    SELECT id, created_at, cwd, parent_session_id, metadata
                    FROM durable_sessions
                    ORDER BY created_at DESC
                    """.trimIndent()
                } else {
                    """
                    SELECT id, created_at, cwd, parent_session_id, metadata
                    FROM durable_sessions
                    WHERE cwd = ?
                    ORDER BY created_at DESC
                    """.trimIndent()
                }
            connection.prepareStatement(sql).use { statement ->
                if (cwd != null) {
                    statement.setString(1, cwd)
                }
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(rows.toMetadata(connection, path))
                        }
                    }
                }
            }
        }

    override suspend fun delete(metadata: SqliteDurableSessionMetadata) {
        val owned =
            mutex.withLock {
                openStorages.remove(metadata.id)
            }
        owned?.close()
        openConnection().use { connection ->
            val deleted =
                connection.transaction {
                    listOf("durable_log", "durable_facts", "durable_writer_leases").forEach { table ->
                        prepareStatement("DELETE FROM $table WHERE session_id = ?").use { statement ->
                            statement.setString(1, metadata.id)
                            statement.executeUpdate()
                        }
                    }
                    prepareStatement("DELETE FROM durable_sessions WHERE id = ?").use { statement ->
                        statement.setString(1, metadata.id)
                        statement.executeUpdate()
                    }
                }
            if (deleted == 0) {
                throw DurableSessionException(
                    DurableSessionErrorCode.NOT_FOUND,
                    "Session not found: ${metadata.id}",
                )
            }
        }
    }

    override suspend fun fork(
        source: SqliteDurableSessionMetadata,
        options: DurableForkOptions,
    ): DurableSession<SqliteDurableSessionMetadata> {
        val sourceSession = open(source)
        val entries: List<DurableEntry>
        val lanes: List<LanePointer>
        when (options) {
            is DurableForkOptions.Tree -> {
                entries = sourceSession.findEntries(EntryQuery(order = EntryOrder.OLDEST_FIRST))
                lanes = sourceSession.getLanes()
            }

            is DurableForkOptions.Branch -> {
                val selectedEntryId = options.entryId ?: sourceSession.getLeafId()
                val targetId =
                    if (selectedEntryId == null) {
                        null
                    } else {
                        val target =
                            sourceSession.getEntry(selectedEntryId)
                                ?: invalidForkTarget(selectedEntryId)
                        if (target.payload !is EntryPayload.MessageValue) {
                            invalidForkTarget(selectedEntryId)
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
                entries =
                    if (targetId == null) {
                        emptyList()
                    } else {
                        sourceSession.findEntriesOnBranch(
                            EntryQuery(order = EntryOrder.OLDEST_FIRST),
                            BranchBounds(start = targetId),
                        )
                    }
                lanes = listOf(LanePointer("main", targetId))
            }
        }
        val labels =
            entries.mapNotNull { entry ->
                sourceSession.getLabel(entry.id)?.let { label -> entry.id to label }
            }.toMap()
        val name = sourceSession.getName()
        val target =
            create(
                SqliteDurableSessionCreateOptions(
                    id = options.id,
                    parentSessionId = options.parentSessionId ?: source.id,
                    cwd = source.cwd,
                    metadata = source.metadata,
                ),
            )
        val storage =
            mutex.withLock {
                requireNotNull(openStorages[target.getMetadata().id])
            }
        storage.importFork(entries, lanes, name, labels)
        return target
    }

    private fun openStorage(
        metadata: SqliteDurableSessionMetadata,
    ): SqliteDurableSessionStorage {
        val connection = openConnection()
        try {
            val lease = acquireLease(connection, metadata.id)
            val state = loadState(connection, metadata.id)
            return SqliteDurableSessionStorage(
                metadata = metadata,
                connection = connection,
                state = state,
                lease = lease,
                leaseOptions = writerLease,
                currentTimeMillis = currentTimeMillis,
                onClose = { closed ->
                    CoroutineScope(Dispatchers.Default).launch {
                        mutex.withLock {
                            openStorages.remove(metadata.id, closed)
                        }
                    }
                },
            )
        } catch (error: Throwable) {
            connection.close()
            throw error
        }
    }

    private fun openConnection(): Connection {
        path.parent?.let(Files::createDirectories)
        val connection = java.sql.DriverManager.getConnection("jdbc:sqlite:$path")
        try {
            configureDatabase(connection)
            applyMigrations(connection)
            ensureDurableSchema(connection)
            return connection
        } catch (error: Throwable) {
            connection.close()
            throw error
        }
    }

    private fun acquireLease(
        connection: Connection,
        sessionId: String,
    ): SqliteWriterLease =
        connection.transaction {
            val now = currentTimeMillis()
            val current =
                prepareStatement(
                    """
                    SELECT owner_id, fence, expires_at_ms
                    FROM durable_writer_leases
                    WHERE session_id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) {
                            SqliteWriterLease(
                                ownerId = rows.getString("owner_id"),
                                fence = rows.getLong("fence"),
                                expiresAtMs = rows.getLong("expires_at_ms"),
                            )
                        } else {
                            null
                        }
                    }
                }
            if (current != null && current.expiresAtMs > now) {
                activeWriter(sessionId)
            }
            val lease =
                SqliteWriterLease(
                    ownerId = idGenerator(),
                    fence = (current?.fence ?: 0) + 1,
                    expiresAtMs = now + writerLease.ttlMs,
                )
            prepareStatement(
                """
                INSERT INTO durable_writer_leases
                    (session_id, owner_id, fence, expires_at_ms)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    owner_id = excluded.owner_id,
                    fence = excluded.fence,
                    expires_at_ms = excluded.expires_at_ms
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, lease.ownerId)
                statement.setLong(3, lease.fence)
                statement.setLong(4, lease.expiresAtMs)
                statement.executeUpdate()
            }
            lease
        }

    private fun loadState(
        connection: Connection,
        sessionId: String,
    ): DurableSessionState {
        val state = DurableSessionState()
        connection.prepareStatement(
            """
            SELECT seq, payload
            FROM durable_log
            WHERE session_id = ?
            ORDER BY seq
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val seq = rows.getLong("seq")
                    val mutation =
                        try {
                            parseJsonlMutation(
                                rows.getString("payload").trimEnd(),
                                path,
                                seq.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            )
                        } catch (error: Throwable) {
                            throw DurableSessionException(
                                DurableSessionErrorCode.STORAGE,
                                "Invalid SQLite durable log at sequence $seq",
                                error,
                            )
                        }
                    state.applyMutation(mutation)
                }
            }
        }
        return state
    }

    private fun readMetadata(
        connection: Connection,
        id: String,
    ): SqliteDurableSessionMetadata? =
        connection.prepareStatement(
            """
            SELECT id, created_at, cwd, parent_session_id, metadata
            FROM durable_sessions
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.toMetadata(connection, path) else null
            }
        }
}

private class SqliteDurableSessionStorage(
    private val metadata: SqliteDurableSessionMetadata,
    private val connection: Connection,
    private val state: DurableSessionState,
    private var lease: SqliteWriterLease,
    private val leaseOptions: SqliteWriterLeaseOptions,
    private val currentTimeMillis: () -> Long,
    private val onClose: (SqliteDurableSessionStorage) -> Unit,
) : DurableSessionStorage<SqliteDurableSessionMetadata> {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val leaseLost = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val heartbeat: Job =
        scope.launch {
            while (isActive) {
                delay(leaseOptions.heartbeatIntervalMs)
                runCatching {
                    mutex.withLock {
                        if (!closed.get()) {
                            renewLease()
                        }
                    }
                }.onFailure {
                    leaseLost.set(true)
                    cancel()
                }
            }
        }

    override suspend fun getMetadata(): SqliteDurableSessionMetadata =
        mutex.withLock {
            metadata.copy(name = state.getName())
        }

    override suspend fun getLanes(): List<LanePointer> = mutex.withLock { state.getLanes() }

    override suspend fun createLane(
        lane: String,
        at: String?,
    ) {
        mutex.withLock {
            state.validateNewLane(lane)
            state.validateTarget(at)
            persist(DurableMutation.Lane(state.nextSequence, lane, at))
        }
    }

    override suspend fun moveLane(
        lane: String,
        to: String?,
    ) {
        mutex.withLock {
            state.requireLane(lane)
            state.validateTarget(to)
            persist(DurableMutation.Lane(state.nextSequence, lane, to))
        }
    }

    override suspend fun appendEntry(
        entry: ProvisionedEntry,
        lane: String,
    ): DurableEntry =
        mutex.withLock {
            val parentId = state.requireLane(lane)
            state.validateUnusedId(entry.id)
            val materialized =
                DurableEntry(
                    id = entry.id,
                    seq = state.nextSequence,
                    parentId = parentId,
                    timestamp = currentTimeMillis(),
                    payload = entry.payload,
                )
            persist(DurableMutation.Entry(lane, materialized))
            state.getEntry(materialized.id) ?: error("Persisted entry disappeared")
        }

    override suspend fun appendRecord(record: NewDurableRecord): DurableRecord =
        mutex.withLock {
            state.requireLane(record.lane)
            state.validateUnusedId(record.id)
            if (
                record.payload is RecordPayload.OperationStarted &&
                state.findOpenOperations(record.lane, 1).isNotEmpty()
            ) {
                val operation = state.findOpenOperations(record.lane, 1).single()
                throw DurableSessionException(
                    DurableSessionErrorCode.STORAGE,
                    "Lane ${record.lane} already has an open operation ${operation.id}",
                )
            }
            val materialized =
                DurableRecord(
                    id = record.id,
                    seq = state.nextSequence,
                    lane = record.lane,
                    timestamp = currentTimeMillis(),
                    payload = record.payload,
                )
            persist(DurableMutation.Record(materialized))
            state.findRecords(RecordQuery(runId = materialized.runId, limit = 1))
                .firstOrNull { it.id == materialized.id }
                ?: materialized
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
            persist(DurableMutation.Name(state.nextSequence, name))
        }
    }

    override suspend fun getLabel(id: String): String? = mutex.withLock { state.getLabel(id) }

    override suspend fun setLabel(
        id: String,
        label: String?,
    ) {
        mutex.withLock {
            state.validateTarget(id)
            persist(DurableMutation.Label(state.nextSequence, id, label))
        }
    }

    override suspend fun getStats(): DurableSessionStats = mutex.withLock { state.getStats() }

    suspend fun importFork(
        entries: List<DurableEntry>,
        lanes: List<LanePointer>,
        name: String?,
        labels: Map<String, String>,
    ) {
        mutex.withLock {
            entries.forEach { source ->
                persist(
                    DurableMutation.Entry(
                        lane = null,
                        entry = source.copy(seq = state.nextSequence),
                    ),
                )
            }
            lanes.forEach { pointer ->
                persist(
                    DurableMutation.Lane(
                        seq = state.nextSequence,
                        lane = pointer.lane,
                        leafId = pointer.leafId,
                    ),
                )
            }
            name?.let {
                persist(DurableMutation.Name(state.nextSequence, it))
            }
            labels.forEach { (id, label) ->
                persist(DurableMutation.Label(state.nextSequence, id, label))
            }
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        heartbeat.cancel()
        scope.cancel()
        mutex.withLock {
            runCatching {
                connection.prepareStatement(
                    """
                    DELETE FROM durable_writer_leases
                    WHERE session_id = ? AND owner_id = ? AND fence = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, metadata.id)
                    statement.setString(2, lease.ownerId)
                    statement.setLong(3, lease.fence)
                    statement.executeUpdate()
                }
            }
            connection.close()
        }
        onClose(this)
    }

    private fun persist(mutation: DurableMutation) {
        ensureWritable()
        val payload = encodeJsonlMutation(mutation)
        try {
            connection.transaction {
                renewLeaseInTransaction()
                prepareStatement(
                    """
                    INSERT INTO durable_log (session_id, seq, payload)
                    VALUES (?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, metadata.id)
                    statement.setLong(2, mutation.seq)
                    statement.setString(3, payload)
                    statement.executeUpdate()
                }
                when (mutation) {
                    is DurableMutation.Name ->
                        insertFact(
                            mutation.seq,
                            "name",
                            null,
                            Json.encodeToString(kotlinx.serialization.serializer<String>(), mutation.name),
                        )

                    is DurableMutation.Label ->
                        insertFact(
                            mutation.seq,
                            "label",
                            mutation.targetId,
                            mutation.label?.let {
                                Json.encodeToString(kotlinx.serialization.serializer<String>(), it)
                            },
                        )

                    else -> Unit
                }
            }
            state.applyMutation(mutation)
        } catch (error: DurableSessionException) {
            throw error
        } catch (error: SQLException) {
            throw DurableSessionException(
                DurableSessionErrorCode.STORAGE,
                "Failed to append SQLite durable mutation ${mutation.seq}",
                error,
            )
        }
    }

    private fun Connection.insertFact(
        seq: Long,
        kind: String,
        key: String?,
        value: String?,
    ) {
        prepareStatement(
            """
            INSERT INTO durable_facts (session_id, seq, kind, fact_key, value)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, metadata.id)
            statement.setLong(2, seq)
            statement.setString(3, kind)
            statement.setString(4, key)
            statement.setString(5, value)
            statement.executeUpdate()
        }
    }

    private fun renewLease() {
        ensureWritable()
        connection.transaction {
            renewLeaseInTransaction()
        }
    }

    private fun Connection.renewLeaseInTransaction() {
        val now = currentTimeMillis()
        val expiresAt = now + leaseOptions.ttlMs
        val updated =
            prepareStatement(
                """
                UPDATE durable_writer_leases
                SET expires_at_ms = ?
                WHERE session_id = ?
                    AND owner_id = ?
                    AND fence = ?
                    AND expires_at_ms > ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, expiresAt)
                statement.setString(2, metadata.id)
                statement.setString(3, lease.ownerId)
                statement.setLong(4, lease.fence)
                statement.setLong(5, now)
                statement.executeUpdate()
            }
        if (updated != 1) {
            leaseLost.set(true)
            lostWriter(metadata.id)
        }
        lease = lease.copy(expiresAtMs = expiresAt)
    }

    private fun ensureWritable() {
        if (closed.get()) {
            throw DurableSessionException(
                DurableSessionErrorCode.STORAGE,
                "SQLite session ${metadata.id} is closed",
            )
        }
        if (leaseLost.get()) {
            lostWriter(metadata.id)
        }
    }
}

private data class SqliteWriterLease(
    val ownerId: String,
    val fence: Long,
    val expiresAtMs: Long,
)

private fun ensureDurableSchema(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.execute(
            """
            CREATE TABLE IF NOT EXISTS durable_sessions (
                id TEXT PRIMARY KEY,
                created_at INTEGER NOT NULL,
                cwd TEXT NOT NULL,
                parent_session_id TEXT NULL,
                metadata TEXT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        statement.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_durable_sessions_created_at
            ON durable_sessions(created_at DESC)
            """.trimIndent(),
        )
        statement.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_durable_sessions_cwd
            ON durable_sessions(cwd)
            """.trimIndent(),
        )
        statement.execute(
            """
            CREATE TABLE IF NOT EXISTS durable_log (
                session_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                payload TEXT NOT NULL,
                PRIMARY KEY (session_id, seq)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        statement.execute(
            """
            CREATE TABLE IF NOT EXISTS durable_facts (
                session_id TEXT NOT NULL,
                seq INTEGER NOT NULL,
                kind TEXT NOT NULL,
                fact_key TEXT NULL,
                value TEXT NULL,
                PRIMARY KEY (session_id, seq)
            ) WITHOUT ROWID
            """.trimIndent(),
        )
        statement.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_durable_facts_lookup
            ON durable_facts(session_id, kind, fact_key, seq DESC)
            """.trimIndent(),
        )
        statement.execute(
            """
            CREATE TABLE IF NOT EXISTS durable_writer_leases (
                session_id TEXT PRIMARY KEY,
                owner_id TEXT NOT NULL,
                fence INTEGER NOT NULL,
                expires_at_ms INTEGER NOT NULL
            ) WITHOUT ROWID
            """.trimIndent(),
        )
    }
}

private fun sessionExists(
    connection: Connection,
    id: String,
): Boolean =
    connection.prepareStatement("SELECT 1 FROM durable_sessions WHERE id = ?").use { statement ->
        statement.setString(1, id)
        statement.executeQuery().use(ResultSet::next)
    }

private fun ResultSet.toMetadata(
    connection: Connection,
    path: Path,
): SqliteDurableSessionMetadata {
    val id = getString("id")
    val metadata =
        getString("metadata")?.let { raw ->
            try {
                Json.parseToJsonElement(raw) as? JsonObject
                    ?: throw IllegalArgumentException("metadata must be an object")
            } catch (error: Throwable) {
                throw DurableSessionException(
                    DurableSessionErrorCode.STORAGE,
                    "Invalid SQLite session $id: metadata is not valid JSON",
                    error,
                )
            }
        }
    return SqliteDurableSessionMetadata(
        id = id,
        createdAt = getLong("created_at"),
        parentSessionId = getString("parent_session_id"),
        cwd = Path.of(getString("cwd")).toAbsolutePath().normalize(),
        path = path,
        name = readSessionName(connection, id),
        metadata = metadata,
    )
}

private fun readSessionName(
    connection: Connection,
    sessionId: String,
): String? =
    connection.prepareStatement(
        """
        SELECT value
        FROM durable_facts
        WHERE session_id = ? AND kind = 'name' AND fact_key IS NULL
        ORDER BY seq DESC
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, sessionId)
        statement.executeQuery().use { rows ->
            if (!rows.next()) {
                null
            } else {
                val raw =
                    rows.getString("value")
                        ?: throw DurableSessionException(
                            DurableSessionErrorCode.STORAGE,
                            "Invalid SQLite session $sessionId: name must be a string",
                        )
                try {
                    Json.decodeFromString(kotlinx.serialization.serializer<String>(), raw)
                } catch (error: Throwable) {
                    throw DurableSessionException(
                        DurableSessionErrorCode.STORAGE,
                        "Invalid SQLite session $sessionId: name is not valid JSON",
                        error,
                    )
                }
            }
        }
    }

private fun alreadyExists(id: String): Nothing =
    throw DurableSessionException(
        DurableSessionErrorCode.ALREADY_EXISTS,
        "Session already exists: $id",
    )

private fun activeWriter(id: String): Nothing =
    throw DurableSessionException(
        DurableSessionErrorCode.STORAGE,
        "SQLite session $id already has an active writer",
    )

private fun lostWriter(id: String): Nothing =
    throw DurableSessionException(
        DurableSessionErrorCode.STORAGE,
        "SQLite session $id writer lease was lost",
    )

private fun invalidForkTarget(id: String): Nothing =
    throw DurableSessionException(
        DurableSessionErrorCode.INVALID_FORK_TARGET,
        "Fork target is not a message entry: $id",
    )
