package works.earendil.pi.storage.sqlite

import java.nio.file.Files
import java.sql.DriverManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import works.earendil.pi.agent.harness.session.DurableForkOptions
import works.earendil.pi.agent.harness.session.DurableSessionErrorCode
import works.earendil.pi.agent.harness.session.DurableSessionException
import works.earendil.pi.agent.harness.session.EntryOrder
import works.earendil.pi.agent.harness.session.EntryPayload
import works.earendil.pi.agent.harness.session.EntryQuery
import works.earendil.pi.agent.harness.session.ProvisionedEntry
import works.earendil.pi.ai.UserMessage

class SqliteDurableSessionTest {
    @Test
    fun `persists lanes facts and records across reopen`() =
        runTest {
            val fixture = fixture()
            val repository = fixture.repository()
            val session =
                repository.create(
                    SqliteDurableSessionCreateOptions(
                        id = "session",
                        cwd = fixture.cwd,
                        metadata = JsonObject(mapOf("profile" to JsonPrimitive("test"))),
                    ),
                )
            val root =
                session.appendEntry(
                    ProvisionedEntry("root", EntryPayload.MessageValue(UserMessage("root"))),
                    "main",
                )
            session.createLane("thread", root.id)
            session.setName("Example")
            session.setLabel(root.id, "checkpoint")
            session.close()

            val listed = repository.list().single()
            assertEquals("Example", listed.name)
            assertEquals("test", listed.metadata?.get("profile")?.let { it as JsonPrimitive }?.content)

            val reopened = repository.open(listed)
            assertEquals("checkpoint", reopened.getLabel(root.id))
            assertEquals(1, reopened.getStats().messageCount)
            assertEquals(
                listOf("root"),
                reopened.findEntries(EntryQuery(order = EntryOrder.OLDEST_FIRST)).map { it.id },
            )
            assertEquals("root", reopened.getLanes().single { it.lane == "thread" }.leafId)
            reopened.close()
        }

    @Test
    fun `list does not claim the active writer lease`() =
        runTest {
            val fixture = fixture()
            val writer = fixture.repository()
            val reader = fixture.repository()
            val session =
                writer.create(
                    SqliteDurableSessionCreateOptions(id = "session", cwd = fixture.cwd),
                )
            session.setName("Active")

            val listed = reader.list().single()
            assertEquals("Active", listed.name)
            val error =
                assertFailsWith<DurableSessionException> {
                    reader.open(listed)
                }
            assertEquals(DurableSessionErrorCode.STORAGE, error.code)
            assertTrue(error.message.orEmpty().contains("active writer"))

            session.close()
            val reopened = reader.open(listed)
            reopened.close()
        }

    @Test
    fun `heartbeat retains ownership and expired leases can be reclaimed`() =
        runTest {
            val fixture = fixture()
            val lease = SqliteWriterLeaseOptions(ttlMs = 160, heartbeatIntervalMs = 40)
            val first = fixture.repository(lease)
            val second = fixture.repository(lease)
            val session =
                first.create(
                    SqliteDurableSessionCreateOptions(id = "session", cwd = fixture.cwd),
                )
            delay(240)
            assertFailsWith<DurableSessionException> {
                second.open(second.list().single())
            }
            session.close()

            DriverManager.getConnection("jdbc:sqlite:${fixture.database}").use { connection ->
                connection.prepareStatement(
                    "UPDATE durable_writer_leases SET expires_at_ms = 0 WHERE session_id = ?",
                ).use { statement ->
                    statement.setString(1, "session")
                    statement.executeUpdate()
                }
            }
            val reclaimed = second.open(second.list().single())
            reclaimed.close()
        }

    @Test
    fun `tree and branch forks preserve message statistics`() =
        runTest {
            val fixture = fixture()
            val repository = fixture.repository()
            val source =
                repository.create(
                    SqliteDurableSessionCreateOptions(id = "source", cwd = fixture.cwd),
                )
            source.appendEntry(
                ProvisionedEntry("one", EntryPayload.MessageValue(UserMessage("one"))),
                "main",
            )
            source.appendEntry(
                ProvisionedEntry("two", EntryPayload.MessageValue(UserMessage("two"))),
                "main",
            )
            source.createLane("thread", "one")
            source.appendEntry(
                ProvisionedEntry("thread", EntryPayload.MessageValue(UserMessage("thread"))),
                "thread",
            )

            val tree =
                repository.fork(
                    source.getMetadata(),
                    DurableForkOptions.Tree(id = "tree"),
                )
            val branch =
                repository.fork(
                    source.getMetadata(),
                    DurableForkOptions.Branch(id = "branch", entryId = "two"),
                )

            assertEquals(3, tree.getStats().messageCount)
            assertEquals(1, branch.getStats().messageCount)
            tree.close()
            branch.close()
            source.close()
        }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("pi-kotlin-durable-sqlite")
        return Fixture(
            cwd = Files.createDirectories(root.resolve("workspace")),
            database = root.resolve("sessions.sqlite"),
        )
    }

    private data class Fixture(
        val cwd: java.nio.file.Path,
        val database: java.nio.file.Path,
    ) {
        fun repository(
            lease: SqliteWriterLeaseOptions = SqliteWriterLeaseOptions(),
        ): SqliteDurableSessionRepository =
            SqliteDurableSessionRepository(
                databasePath = database,
                writerLease = lease,
            )
    }
}
