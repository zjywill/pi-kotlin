package works.earendil.pi.agent.harness.session

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import works.earendil.pi.ai.UserMessage

class JsonlDurableSessionTest {
    @Test
    fun `writes source compatible lines and restores shared sequence`() =
        runTest {
            val fixture = fixture()
            val session =
                fixture.repository.create(
                    JsonlDurableSessionCreateOptions(
                        id = "session",
                        cwd = fixture.cwd,
                        metadata = JsonObject(mapOf("owner" to kotlinx.serialization.json.JsonPrimitive("test"))),
                    ),
                )
            val root =
                session.appendEntry(
                    ProvisionedEntry("root", EntryPayload.MessageValue(UserMessage("root"))),
                    "main",
                )
            session.createLane("thread", root.id)
            val record =
                session.appendRecord(
                    NewDurableRecord(
                        "run",
                        "thread",
                        RecordPayload.OperationStarted(
                            sourceLeafId = root.id,
                            intent =
                                OperationIntent.Run(
                                    originalPrompt = listOf(UserMessage("prompt")),
                                    initialMessages =
                                        listOf(
                                            ProvisionedEntry(
                                                "queued",
                                                EntryPayload.MessageValue(UserMessage("queued")),
                                            ),
                                        ),
                                ),
                        ),
                    ),
                )
            session.setName("Example")
            session.setLabel(root.id, "checkpoint")
            session.close()

            val metadata = fixture.repository.list().single()
            val lines = Files.readAllLines(metadata.path)
            assertEquals("header", parse(lines[0])["kind"]?.jsonPrimitive?.content)
            assertEquals(4, parse(lines[0])["version"]?.jsonPrimitive?.content?.toInt())
            assertEquals("entry", parse(lines[1])["kind"]?.jsonPrimitive?.content)
            assertEquals("message", parse(lines[1])["type"]?.jsonPrimitive?.content)
            assertEquals("record", parse(lines[3])["kind"]?.jsonPrimitive?.content)
            val intent = parse(lines[3])["intent"]?.jsonObject
            assertEquals("run", intent?.get("kind")?.jsonPrimitive?.content)
            assertEquals(
                "message",
                intent
                    ?.get("initialMessages")
                    ?.let { it as kotlinx.serialization.json.JsonArray }
                    ?.single()
                    ?.jsonObject
                    ?.get("type")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertFalse(lines.any { "_kind" in it })
            assertEquals(1, root.seq)
            assertEquals(3, record.seq)

            val reopened = fixture.repository.open(metadata)
            val child =
                reopened.appendEntry(
                    ProvisionedEntry("child", EntryPayload.MessageValue(UserMessage("child"))),
                    "main",
                )
            assertEquals(6, child.seq)
            assertEquals("Example", reopened.getName())
            assertEquals("checkpoint", reopened.getLabel("root"))
            assertEquals("test", reopened.getMetadata().metadata?.get("owner")?.jsonPrimitive?.content)
        }

    @Test
    fun `lists by cwd and rejects invalid explicit ids`() =
        runTest {
            val fixture = fixture()
            val other = Files.createDirectories(fixture.root.resolve("other"))
            fixture.repository.create(
                JsonlDurableSessionCreateOptions(id = "one", cwd = fixture.cwd),
            )
            fixture.repository.create(
                JsonlDurableSessionCreateOptions(id = "two", cwd = other),
            )

            assertEquals(
                listOf("one"),
                fixture.repository
                    .list(JsonlDurableSessionListOptions(fixture.cwd))
                    .map(JsonlDurableSessionMetadata::id),
            )
            val error =
                assertFailsWith<DurableSessionException> {
                    fixture.repository.create(
                        JsonlDurableSessionCreateOptions(id = "_invalid", cwd = fixture.cwd),
                    )
                }
            assertEquals(DurableSessionErrorCode.INVALID_PAYLOAD, error.code)
        }

    @Test
    fun `repairs missing newline and truncates only malformed final JSON`() =
        runTest {
            val fixture = fixture()
            val session =
                fixture.repository.create(
                    JsonlDurableSessionCreateOptions(id = "session", cwd = fixture.cwd),
                )
            session.appendMessage(UserMessage("root"))
            val metadata = session.getMetadata()
            val complete = Files.readString(metadata.path)
            Files.writeString(
                metadata.path,
                complete.removeSuffix("\n"),
                StandardOpenOption.TRUNCATE_EXISTING,
            )

            fixture.repository.open(metadata)
            assertTrue(Files.readString(metadata.path).endsWith("\n"))

            Files.writeString(
                metadata.path,
                """{"kind":""",
                StandardOpenOption.APPEND,
            )
            fixture.repository.open(metadata)
            assertEquals(complete, Files.readString(metadata.path))
        }

    @Test
    fun `middle and semantic corruption are rejected without modifying the file`() =
        runTest {
            val fixture = fixture()
            val session =
                fixture.repository.create(
                    JsonlDurableSessionCreateOptions(id = "session", cwd = fixture.cwd),
                )
            session.appendMessage(UserMessage("root"))
            val metadata = session.getMetadata()
            val valid = Files.readString(metadata.path)
            val lines = valid.lines().filter(String::isNotEmpty)

            val middle = lines.first() + "\n" + """{"kind":""" + "\n" + lines[1] + "\n"
            Files.writeString(
                metadata.path,
                middle,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            assertFailsWith<JsonlInvalidFileException> {
                fixture.repository.open(metadata)
            }
            assertEquals(middle, Files.readString(metadata.path))

            val semantic =
                valid +
                    """{"kind":"lane","seq":99,"lane":"main","leafId":null}""" +
                    "\n"
            Files.writeString(
                metadata.path,
                semantic,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            assertFailsWith<JsonlInvalidFileException> {
                fixture.repository.open(metadata)
            }
            assertEquals(semantic, Files.readString(metadata.path))
        }

    @Test
    fun `tree fork reopens with lanes facts and recomputed stats`() =
        runTest {
            val fixture = fixture()
            val source =
                fixture.repository.create(
                    JsonlDurableSessionCreateOptions(id = "source", cwd = fixture.cwd),
                )
            source.appendEntry(
                ProvisionedEntry("root", EntryPayload.MessageValue(UserMessage("root"))),
                "main",
            )
            source.createLane("thread", "root")
            source.appendEntry(
                ProvisionedEntry("thread", EntryPayload.MessageValue(UserMessage("thread"))),
                "thread",
            )
            source.setName("Source")
            source.setLabel("root", "checkpoint")

            val fork =
                fixture.repository.fork(
                    source.getMetadata(),
                    DurableForkOptions.Tree(id = "fork"),
                )
            val reopened = fixture.repository.open(fork.getMetadata())

            assertEquals(
                listOf(
                    LanePointer("main", "root"),
                    LanePointer("thread", "thread"),
                ),
                reopened.getLanes(),
            )
            assertEquals("Source", reopened.getName())
            assertEquals("checkpoint", reopened.getLabel("root"))
            assertEquals(2, reopened.getStats().messageCount)
            assertEquals("source", reopened.getMetadata().parentSessionId)
        }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("pi-kotlin-jsonl-v4")
        val cwd = Files.createDirectories(root.resolve("workspace"))
        var now = 1_000L
        var id = 0
        return Fixture(
            root = root,
            cwd = cwd,
            repository =
                JsonlDurableSessionRepository(
                    sessionsRoot = root.resolve("sessions"),
                    currentTimeMillis = { now++ },
                    idGenerator = { "generated-${id++}" },
                ),
        )
    }

    private fun parse(line: String): JsonObject =
        durableSessionJson.parseToJsonElement(line).jsonObject

    private data class Fixture(
        val root: java.nio.file.Path,
        val cwd: java.nio.file.Path,
        val repository: JsonlDurableSessionRepository,
    )
}
