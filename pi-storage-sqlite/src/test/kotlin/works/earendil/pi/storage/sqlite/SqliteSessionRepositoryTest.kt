package works.earendil.pi.storage.sqlite

import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.agent.session.SessionErrorCode
import works.earendil.pi.agent.session.SessionException
import works.earendil.pi.agent.session.SessionForkOptions
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.Cost
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.ai.contentText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteSessionRepositoryTest {
    @Test
    fun `applies the pinned schema migration`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-sqlite-schema")
            val database = root.resolve("sessions.sqlite")
            val repository = SqliteSessionRepository(database)
            repository.create(SqliteSessionCreateOptions(root, id = "session-1")).close()

            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                val migrationIds =
                    connection
                        .prepareStatement("SELECT id FROM migrations ORDER BY id")
                        .use { statement ->
                            statement.executeQuery().use { rows ->
                                buildList {
                                    while (rows.next()) add(rows.getString("id"))
                                }
                            }
                        }
                val tables =
                    connection
                        .prepareStatement(
                            "SELECT name, sql FROM sqlite_master WHERE type = 'table' ORDER BY name",
                        ).use { statement ->
                            statement.executeQuery().use { rows ->
                                buildMap {
                                    while (rows.next()) {
                                        put(rows.getString("name"), rows.getString("sql"))
                                    }
                                }
                            }
                        }

                assertEquals(listOf("001_initial.sql"), migrationIds)
                assertTrue(
                    setOf(
                        "migrations",
                        "sessions",
                        "session_entries",
                        "session_sequences",
                        "branch_entries",
                        "session_materialized",
                        "entry_materialized",
                    ).all(tables::containsKey),
                )
                listOf(
                    "sessions",
                    "session_sequences",
                    "branch_entries",
                    "session_materialized",
                    "entry_materialized",
                ).forEach { table ->
                    assertTrue(tables[table].orEmpty().contains("WITHOUT ROWID"))
                }
            }
        }

    @Test
    fun `create list open branch and materialized state survive restart`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-sqlite-session")
            val database = root.resolve("sessions.sqlite")
            val repository = SqliteSessionRepository(database)
            val metadataJson = buildJsonObject { put("profile", "reviewer") }
            val session =
                repository.create(
                    SqliteSessionCreateOptions(
                        cwd = root,
                        id = "session-1",
                        metadata = metadataJson,
                    ),
                )
            val rootId = session.appendMessage(UserMessage("root", 1))
            session.appendMessage(assistant("first child", 2))
            session.appendThinkingLevelChange("high")
            session.appendModelChange("anthropic", "claude-test")
            session.appendSessionName("  Review Session  ")
            session.appendLabel(rootId, "checkpoint")
            session.moveTo(rootId)
            val branchId = session.appendMessage(assistant("second child", 3))
            val metadata = session.getMetadata()
            session.close()

            val listed = repository.list(SqliteSessionListOptions(root))
            val reopened = repository.open(metadata)
            val context = reopened.buildContext()

            assertEquals(listOf("session-1"), listed.map(SqliteSessionMetadata::id))
            assertEquals(metadataJson, listed.single().metadata)
            assertEquals(listOf("root", "second child"), context.messages.map(::messageText))
            assertEquals("Review Session", reopened.getSessionName())
            assertEquals("checkpoint", reopened.getLabel(rootId))
            assertEquals(branchId, reopened.getLeafId())

            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                val sessionRow =
                    connection
                        .prepareStatement("SELECT active_leaf_id FROM sessions WHERE id = ?")
                        .use { statement ->
                            statement.setString(1, "session-1")
                            statement.executeQuery().use { rows ->
                                assertTrue(rows.next())
                                rows.getString("active_leaf_id")
                            }
                        }
                val branchCount =
                    connection
                        .prepareStatement(
                            "SELECT COUNT(DISTINCT branch_id) AS count FROM branch_entries WHERE session_id = ?",
                        ).use { statement ->
                            statement.setString(1, "session-1")
                            statement.executeQuery().use { rows ->
                                assertTrue(rows.next())
                                rows.getInt("count")
                            }
                        }
                val materialized =
                    connection
                        .prepareStatement("SELECT payload FROM session_materialized WHERE session_id = ?")
                        .use { statement ->
                            statement.setString(1, "session-1")
                            statement.executeQuery().use { rows ->
                                assertTrue(rows.next())
                                Json.parseToJsonElement(rows.getString("payload")).jsonObject
                            }
                        }

                assertEquals(branchId, sessionRow)
                assertEquals(3, branchCount)
                assertEquals("Review Session", materialized["name"]?.jsonPrimitive?.content)
                assertEquals(3, materialized["messageCount"]?.jsonPrimitive?.content?.toInt())
                assertEquals("anthropic", materialized["currentModel"]?.jsonObject
                    ?.get("provider")?.jsonPrimitive?.content)
                assertEquals("high", materialized["currentThinkingLevel"]?.jsonPrimitive?.content)
            }
            reopened.close()
        }

    @Test
    fun `stats pagination fork and delete use persisted entries`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-sqlite-fork")
            val database = root.resolve("sessions.sqlite")
            val repository = SqliteSessionRepository(database)
            val source = repository.create(SqliteSessionCreateOptions(root, id = "source"))
            source.appendMessage(UserMessage("one", 1))
            val assistantId =
                source.appendMessage(
                    assistant("two", 2).copy(
                        usage =
                            Usage(
                                input = 10,
                                output = 2,
                                cacheRead = 3,
                                cacheWrite = 4,
                                cost = Cost(total = 0.5),
                            ),
                    ),
                )
            source.appendMessage(UserMessage("three", 3))
            val sourceMetadata = source.getMetadata()

            assertEquals(2, source.getEntries(works.earendil.pi.agent.session.SessionEntryCursorOptions(limit = 2)).size)
            assertEquals(
                works.earendil.pi.agent.session.SessionStats(3, 3, 14, 19, 0.5),
                source.getSessionStats(),
            )
            source.close()

            val fork =
                repository.fork(
                    sourceMetadata,
                    SqliteSessionCreateOptions(root, id = "fork"),
                    SessionForkOptions(
                        entryId = assistantId,
                        position = SessionForkOptions.Position.AT,
                    ),
                )
            assertEquals(listOf("one", "two"), fork.buildContext().messages.map(::messageText))
            assertEquals("source", fork.getMetadata().parentSessionId)
            val forkMetadata = fork.getMetadata()
            fork.close()

            repository.delete(forkMetadata)
            val error =
                assertFailsWith<SessionException> {
                    repository.open(forkMetadata)
                }
            assertEquals(SessionErrorCode.NOT_FOUND, error.code)
        }

    private fun assistant(
        text: String,
        timestamp: Long,
    ): AssistantMessage =
        AssistantMessage(
            content = listOf(TextContent(text)),
            api = "test",
            provider = "anthropic",
            model = "claude-test",
            stopReason = StopReason.STOP,
            timestamp = timestamp,
        )

    private fun messageText(message: works.earendil.pi.ai.Message): String =
        when (message) {
            is UserMessage -> contentText(message.content)
            is AssistantMessage -> contentText(message.content)
            else -> error("Unexpected message: $message")
        }
}
