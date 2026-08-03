package works.earendil.pi.storage.sqlite

import java.sql.Connection
import java.time.Instant

private val MIGRATIONS =
    listOf(
        "001_initial.sql",
        "002_branch_tips.sql",
    )

internal fun configureDatabase(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.execute("PRAGMA journal_mode=WAL")
        statement.execute("PRAGMA synchronous=FULL")
        statement.execute("PRAGMA busy_timeout=5000")
    }
}

internal fun applyMigrations(connection: Connection) {
    connection.createStatement().use { statement ->
        statement.execute(
            """
            CREATE TABLE IF NOT EXISTS migrations (
                id TEXT PRIMARY KEY,
                applied_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }
    val applied =
        connection
            .prepareStatement("SELECT id FROM migrations ORDER BY applied_at, id")
            .use { statement ->
                statement.executeQuery().use { rows ->
                    buildSet {
                        while (rows.next()) {
                            add(rows.getString("id"))
                        }
                    }
                }
            }
    MIGRATIONS.forEach { migration ->
        if (migration in applied) {
            return@forEach
        }
        val sql =
            requireNotNull(
                SqliteSessionRepository::class.java.getResourceAsStream(
                    "/works/earendil/pi/storage/sqlite/migrations/$migration",
                ),
            ) {
                "Missing SQLite migration resource: $migration"
            }.bufferedReader().use { it.readText() }
        connection.transaction {
            sql
                .split(';')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach { migrationStatement ->
                    createStatement().use { statement -> statement.execute(migrationStatement) }
                }
            prepareStatement("INSERT INTO migrations (id, applied_at) VALUES (?, ?)").use { statement ->
                statement.setString(1, migration)
                statement.setString(2, Instant.now().toString())
                statement.executeUpdate()
            }
        }
    }
}

internal inline fun <T> Connection.transaction(block: Connection.() -> T): T {
    val previousAutoCommit = autoCommit
    autoCommit = false
    return try {
        val result = block()
        commit()
        result
    } catch (error: Throwable) {
        rollback()
        throw error
    } finally {
        autoCommit = previousAutoCommit
    }
}
