package works.earendil.pi.storage.sqlite

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import works.earendil.pi.agent.session.Session
import works.earendil.pi.agent.session.SessionErrorCode
import works.earendil.pi.agent.session.SessionException
import works.earendil.pi.agent.session.SessionForkOptions
import works.earendil.pi.agent.session.SessionRepository
import works.earendil.pi.agent.session.getEntriesToFork
import works.earendil.pi.ai.uuidv7

class SqliteSessionRepository(
    databasePath: Path,
) : SessionRepository<SqliteSessionMetadata, SqliteSessionCreateOptions, SqliteSessionListOptions> {
    private val databasePath = databasePath.toAbsolutePath().normalize()

    override suspend fun create(options: SqliteSessionCreateOptions): Session<SqliteSessionMetadata> {
        val connection = openDatabase()
        try {
            val metadata =
                SqliteSessionMetadata(
                    id = options.id ?: uuidv7(),
                    createdAt = works.earendil.pi.agent.session.nowTimestamp(),
                    cwd = options.cwd.toAbsolutePath().normalize(),
                    path = databasePath,
                    parentSessionId = options.parentSessionId,
                    metadata = options.metadata,
                )
            return Session(SqliteSessionStorage.create(connection, metadata))
        } catch (error: Exception) {
            connection.close()
            throw error
        }
    }

    override suspend fun open(metadata: SqliteSessionMetadata): Session<SqliteSessionMetadata> {
        if (!Files.exists(metadata.path)) {
            throw SessionException(SessionErrorCode.NOT_FOUND, "Session not found: ${metadata.id}")
        }
        val connection = openDatabase()
        try {
            return Session(SqliteSessionStorage.open(connection, metadata))
        } catch (error: Exception) {
            connection.close()
            throw error
        }
    }

    override suspend fun list(options: SqliteSessionListOptions?): List<SqliteSessionMetadata> {
        if (!Files.exists(databasePath)) {
            return emptyList()
        }
        openDatabase().use { connection ->
            val sql =
                if (options?.cwd == null) {
                    """
                    SELECT id, created_at, metadata, cwd, parent_session_id
                    FROM sessions ORDER BY created_at DESC
                    """.trimIndent()
                } else {
                    """
                    SELECT id, created_at, metadata, cwd, parent_session_id
                    FROM sessions WHERE cwd = ? ORDER BY created_at DESC
                    """.trimIndent()
                }
            return connection.prepareStatement(sql).use { statement ->
                options?.cwd?.let { statement.setString(1, it.toAbsolutePath().normalize().toString()) }
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                SqliteSessionMetadata(
                                    id = rows.getString("id"),
                                    createdAt = rows.getString("created_at"),
                                    cwd = Path.of(rows.getString("cwd")).toAbsolutePath().normalize(),
                                    path = databasePath,
                                    parentSessionId = rows.getString("parent_session_id"),
                                    metadata = decodeMetadata(rows.getString("metadata"), rows.getString("id")),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    override suspend fun delete(metadata: SqliteSessionMetadata) {
        openDatabase().use { connection ->
            connection.transaction {
                listOf(
                    "branch_entries",
                    "session_entries",
                    "entry_materialized",
                    "session_materialized",
                    "session_sequences",
                ).forEach { table ->
                    prepareStatement("DELETE FROM $table WHERE session_id = ?").use { statement ->
                        statement.setString(1, metadata.id)
                        statement.executeUpdate()
                    }
                }
                val deleted =
                    prepareStatement("DELETE FROM sessions WHERE id = ?").use { statement ->
                        statement.setString(1, metadata.id)
                        statement.executeUpdate()
                    }
                if (deleted == 0) {
                    throw SessionException(SessionErrorCode.NOT_FOUND, "Session not found: ${metadata.id}")
                }
            }
        }
    }

    suspend fun fork(
        sourceMetadata: SqliteSessionMetadata,
        createOptions: SqliteSessionCreateOptions,
        forkOptions: SessionForkOptions = SessionForkOptions(),
    ): Session<SqliteSessionMetadata> {
        val source = open(sourceMetadata)
        val entries =
            try {
                getEntriesToFork(source.getStorage(), forkOptions)
            } finally {
                source.close()
            }
        val target =
            create(
                createOptions.copy(
                    id = forkOptions.id ?: createOptions.id,
                    parentSessionId = createOptions.parentSessionId ?: sourceMetadata.id,
                    metadata = createOptions.metadata ?: sourceMetadata.metadata,
                ),
            )
        entries.forEach { target.getStorage().appendEntry(it) }
        return target
    }

    private fun openDatabase(): Connection {
        databasePath.parent?.let(Files::createDirectories)
        val connection = DriverManager.getConnection("jdbc:sqlite:$databasePath")
        try {
            configureDatabase(connection)
            applyMigrations(connection)
            return connection
        } catch (error: Exception) {
            connection.close()
            throw error
        }
    }
}
