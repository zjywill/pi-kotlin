package works.earendil.pi.storage.sqlite

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import works.earendil.pi.agent.session.SessionSearch
import works.earendil.pi.agent.session.SessionSearchHit
import works.earendil.pi.agent.session.SessionSearchOptions

class SqliteSessionSearch(
    databasePath: Path,
) : SessionSearch<SqliteSessionMetadata> {
    private val databasePath = databasePath.toAbsolutePath().normalize()

    override suspend fun search(options: SessionSearchOptions): List<SessionSearchHit<SqliteSessionMetadata>> {
        val text = options.text.trim()
        if (text.isEmpty()) {
            return emptyList()
        }
        openDatabase().use { connection ->
            ensureSearchSchema(connection)
            val query = "\"${text.replace("\"", "\"\"")}\""
            val cwd = options.cwd?.let(Path::of)?.toAbsolutePath()?.normalize()?.toString()
            return connection
                .prepareStatement(
                    """
                    SELECT
                        s.id,
                        s.created_at,
                        s.metadata,
                        s.cwd,
                        s.parent_session_id,
                        e.id AS entry_id,
                        e.timestamp AS entry_timestamp,
                        bm25(session_search_fts) AS score
                    FROM session_search_fts
                    JOIN session_entries e ON e.rowid = session_search_fts.rowid
                    JOIN sessions s ON s.id = e.session_id
                    WHERE session_search_fts MATCH ?
                      AND (? IS NULL OR s.cwd = ?)
                    ORDER BY score
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, query)
                    statement.setString(2, cwd)
                    statement.setString(3, cwd)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    SessionSearchHit(
                                        metadata =
                                            SqliteSessionMetadata(
                                                id = rows.getString("id"),
                                                createdAt = rows.getString("created_at"),
                                                cwd = Path.of(rows.getString("cwd")).toAbsolutePath().normalize(),
                                                path = databasePath,
                                                parentSessionId = rows.getString("parent_session_id"),
                                                metadata =
                                                    decodeMetadata(
                                                        rows.getString("metadata"),
                                                        rows.getString("id"),
                                                    ),
                                            ),
                                        entryId = rows.getString("entry_id"),
                                        timestamp = rows.getString("entry_timestamp"),
                                        score = rows.getDouble("score"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }
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

    private fun ensureSearchSchema(connection: Connection) {
        val existed =
            connection
                .prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
                ).use { statement ->
                    statement.setString(1, "session_search_fts")
                    statement.executeQuery().use { rows -> rows.next() }
                }
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS session_search_fts USING fts5(
                    payload,
                    content = 'session_entries',
                    content_rowid = 'rowid',
                    tokenize = 'trigram remove_diacritics 1'
                )
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TRIGGER IF NOT EXISTS session_search_fts_ai
                AFTER INSERT ON session_entries BEGIN
                    INSERT INTO session_search_fts(rowid, payload) VALUES (new.rowid, new.payload);
                END
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TRIGGER IF NOT EXISTS session_search_fts_ad
                AFTER DELETE ON session_entries BEGIN
                    INSERT INTO session_search_fts(session_search_fts, rowid, payload)
                    VALUES('delete', old.rowid, old.payload);
                END
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE TRIGGER IF NOT EXISTS session_search_fts_au
                AFTER UPDATE OF payload ON session_entries BEGIN
                    INSERT INTO session_search_fts(session_search_fts, rowid, payload)
                    VALUES('delete', old.rowid, old.payload);
                    INSERT INTO session_search_fts(rowid, payload) VALUES (new.rowid, new.payload);
                END
                """.trimIndent(),
            )
            if (!existed) {
                statement.execute("INSERT INTO session_search_fts(session_search_fts) VALUES('rebuild')")
            }
        }
    }
}

fun createSqliteSessionSearch(databasePath: Path): SessionSearch<SqliteSessionMetadata> =
    SqliteSessionSearch(databasePath)
