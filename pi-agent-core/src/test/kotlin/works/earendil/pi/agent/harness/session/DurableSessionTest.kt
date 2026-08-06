package works.earendil.pi.agent.harness.session

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import works.earendil.pi.ai.Cost
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.UserMessage

class DurableSessionTest {
    @Test
    fun `one sequence covers entries records lanes and facts`() =
        runTest {
            val repository = repository()
            val session = repository.create(BasicDurableSessionCreateOptions(id = "session"))
            val root =
                session.appendEntry(
                    ProvisionedEntry("root", EntryPayload.MessageValue(UserMessage("root"))),
                    "main",
                )
            session.createLane("thread", root.id)
            val child =
                session.appendEntry(
                    ProvisionedEntry(
                        "child",
                        EntryPayload.Custom("note", JsonPrimitive(1)),
                    ),
                    "thread",
                )
            val record =
                session.appendRecord(
                    NewDurableRecord(
                        "run",
                        "thread",
                        RecordPayload.OperationStarted(
                            sourceLeafId = root.id,
                            intent = OperationIntent.Run(emptyList(), emptyList()),
                        ),
                    ),
                )
            session.setName("Example")
            session.setLabel(root.id, "checkpoint")
            session.moveLane("main", child.id)

            assertEquals(null, root.parentId)
            assertEquals(1, root.seq)
            assertEquals("root", child.parentId)
            assertEquals(3, child.seq)
            assertEquals(4, record.seq)
            assertEquals(
                listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L),
                session.getLog().map(DurableLogItem::seq),
            )
            assertEquals(
                listOf(
                    LanePointer("main", "child"),
                    LanePointer("thread", "child"),
                ),
                session.getLanes(),
            )
            assertEquals("Example", session.getName())
            assertEquals("checkpoint", session.getLabel(root.id))
        }

    @Test
    fun `queries validate early and isolate lane branches`() =
        runTest {
            val session =
                repository()
                    .create(BasicDurableSessionCreateOptions(id = "session"))
            session.createLane("thread", null)
            assertCode(DurableSessionErrorCode.INVALID_QUERY) {
                session.findEntries(EntryQuery(limit = 0))
            }
            assertCode(DurableSessionErrorCode.INVALID_QUERY) {
                session.view("thread").findEntriesOnBranch(
                    EntryQuery(cursor = EntryCursor(-1)),
                )
            }
            assertCode(DurableSessionErrorCode.INVALID_QUERY) {
                session.findRecords(RecordQuery(operationKind = "run"))
            }

            session.appendEntry(
                ProvisionedEntry("root", EntryPayload.MessageValue(UserMessage("root"))),
                "main",
            )
            session.moveLane("thread", "root")
            session.appendEntry(
                ProvisionedEntry("main-child", EntryPayload.MessageValue(UserMessage("main"))),
                "main",
            )
            session.appendEntry(
                ProvisionedEntry("thread-child", EntryPayload.MessageValue(UserMessage("thread"))),
                "thread",
            )

            assertEquals(
                listOf("root", "main-child"),
                session
                    .findEntriesOnBranch(EntryQuery(order = EntryOrder.OLDEST_FIRST))
                    .map(DurableEntry::id),
            )
            assertEquals(
                listOf("root", "thread-child"),
                session
                    .view("thread")
                    .findEntriesOnBranch(EntryQuery(order = EntryOrder.OLDEST_FIRST))
                    .map(DurableEntry::id),
            )
            assertEquals(
                listOf("thread-child"),
                session
                    .findEntries(
                        EntryQuery(
                            order = EntryOrder.OLDEST_FIRST,
                            cursor = EntryCursor(afterSeq = 4),
                        ),
                    ).map(DurableEntry::id),
            )
        }

    @Test
    fun `duplicate ids and simultaneous operations are rejected without consuming sequence`() =
        runTest {
            val session =
                repository()
                    .create(BasicDurableSessionCreateOptions(id = "session"))
            session.appendEntry(
                ProvisionedEntry("shared", EntryPayload.MessageValue(UserMessage("root"))),
                "main",
            )
            assertCode(DurableSessionErrorCode.ALREADY_EXISTS) {
                session.appendRecord(
                    NewDurableRecord(
                        "shared",
                        "main",
                        RecordPayload.OperationStarted(
                            null,
                            OperationIntent.Run(emptyList(), emptyList()),
                        ),
                    ),
                )
            }
            session.appendRecord(
                NewDurableRecord(
                    "run",
                    "main",
                    RecordPayload.OperationStarted(
                        null,
                        OperationIntent.Run(emptyList(), emptyList()),
                    ),
                ),
            )
            assertCode(DurableSessionErrorCode.STORAGE) {
                session.appendRecord(
                    NewDurableRecord(
                        "other-run",
                        "main",
                        RecordPayload.OperationStarted(
                            null,
                            OperationIntent.Run(emptyList(), emptyList()),
                        ),
                    ),
                )
            }
            assertEquals(listOf(1L, 2L), session.getLog().map(DurableLogItem::seq))
            assertEquals(listOf("run"), session.findOpenOperations("main").map(DurableRecord::id))

            session.appendRecord(
                NewDurableRecord(
                    "finish",
                    "main",
                    RecordPayload.OperationFinished("run", "completed"),
                ),
            )
            assertEquals(emptyList(), session.findOpenOperations("main"))
        }

    @Test
    fun `usage records update ledger stats and returned values are defensive copies`() =
        runTest {
            val session =
                repository()
                    .create(BasicDurableSessionCreateOptions(id = "session"))
            val mutableTools = mutableListOf("one")
            session.appendEntry(
                ProvisionedEntry(
                    "tools",
                    EntryPayload.ActiveToolsChange(mutableTools),
                ),
                "main",
            )
            mutableTools += "mutated"
            val first = session.getEntry("tools")
            val payload = assertIs<EntryPayload.ActiveToolsChange>(first?.payload)
            assertEquals(listOf("one"), payload.activeToolNames)

            session.appendRecord(
                NewDurableRecord(
                    "usage",
                    "main",
                    RecordPayload.UsageValue(
                        usage =
                            Usage(
                                input = 2,
                                output = 3,
                                cacheRead = 5,
                                cacheWrite = 7,
                                totalTokens = 17,
                                cost = Cost(total = 1.25),
                            ),
                        cause = "adjustment",
                    ),
                ),
            )
            assertEquals(
                DurableSessionStats(
                    messageCount = 0,
                    cachedTokens = 5,
                    uncachedTokens = 9,
                    totalTokens = 17,
                    costTotal = 1.25,
                ),
                session.getStats(),
            )
        }

    @Test
    fun `branch and tree forks preserve the selected scope facts and parent`() =
        runTest {
            var now = 10L
            var next = 0
            val repository =
                InMemoryDurableSessionRepository(
                    currentTimeMillis = { now++ },
                    idGenerator = { "generated-${next++}" },
                )
            val source =
                repository.create(BasicDurableSessionCreateOptions(id = "source"))
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
                ProvisionedEntry("thread-two", EntryPayload.MessageValue(UserMessage("thread"))),
                "thread",
            )
            source.setName("Source")
            source.setLabel("one", "checkpoint")

            val sourceMetadata = source.getMetadata()
            val branch =
                repository.fork(
                    sourceMetadata,
                    DurableForkOptions.Branch(id = "branch", entryId = "two"),
                )
            val tree =
                repository.fork(
                    sourceMetadata,
                    DurableForkOptions.Tree(id = "tree"),
                )

            assertEquals(listOf("one"), branch.findEntries().map(DurableEntry::id))
            assertEquals(listOf(LanePointer("main", "one")), branch.getLanes())
            assertEquals("Source", branch.getName())
            assertEquals("checkpoint", branch.getLabel("one"))
            assertEquals("source", branch.getMetadata().parentSessionId)

            assertEquals(
                setOf("one", "two", "thread-two"),
                tree.findEntries().map(DurableEntry::id).toSet(),
            )
            assertEquals(
                listOf(
                    LanePointer("main", "two"),
                    LanePointer("thread", "thread-two"),
                ),
                tree.getLanes(),
            )
            assertNull(tree.getLabel("two"))
        }

    private fun repository(): InMemoryDurableSessionRepository {
        var time = 1L
        var id = 0
        return InMemoryDurableSessionRepository(
            currentTimeMillis = { time++ },
            idGenerator = { "id-${id++}" },
        )
    }

    private suspend fun assertCode(
        expected: DurableSessionErrorCode,
        operation: suspend () -> Unit,
    ) {
        val error = assertFailsWith<DurableSessionException> { operation() }
        assertEquals(expected, error.code)
    }
}
