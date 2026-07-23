package works.earendil.pi.agent.session

import kotlinx.coroutines.test.runTest
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionTest {
    @Test
    fun `session builds branch context and tracks state`() =
        runTest {
            val metadata = SessionMetadata("session-1", "2026-01-01T00:00:00Z")
            val session = Session(InMemorySessionStorage(metadata))
            val root = session.appendMessage(UserMessage("root", 1))
            session.appendThinkingLevelChange("high")
            session.appendModelChange("test", "model-1")
            session.appendActiveToolsChange(listOf("read", "bash"))
            session.appendMessage(assistant("first", 2))
            session.moveTo(root)
            session.appendMessage(assistant("second", 3))

            val context = session.buildContext()

            assertEquals(listOf("root", "second"), context.messages.map(::messageText))
            assertEquals("off", context.thinkingLevel)
            assertEquals(SessionModel("test", "model-1"), context.model)
            assertEquals(null, context.activeToolNames)
        }

    @Test
    fun `compaction keeps summary retained tail and later messages`() =
        runTest {
            val session =
                Session(
                    InMemorySessionStorage(
                        SessionMetadata("session-1", "2026-01-01T00:00:00Z"),
                    ),
                )
            session.appendMessage(UserMessage("old", 1))
            session.appendMessage(assistant("old reply", 2))
            session.appendCompaction(
                summary = "summary",
                firstKeptEntryId = null,
                tokensBefore = 100,
                retainedTail = listOf(UserMessage("tail", 3)),
            )
            session.appendMessage(assistant("new", 4))

            assertEquals(
                listOf("summary", "tail", "new"),
                session.buildContext().messages.map(::messageText),
            )
        }

    @Test
    fun `fork before requires a user message`() =
        runTest {
            val repository = InMemorySessionRepository()
            val source = repository.create(InMemoryCreateOptions("source"))
            val assistantId = source.appendMessage(assistant("answer", 1))

            val error =
                assertFailsWith<SessionException> {
                    repository.fork(
                        source.getMetadata(),
                        SessionForkOptions(entryId = assistantId),
                    )
                }

            assertEquals(SessionErrorCode.INVALID_FORK_TARGET, error.code)
        }

    @Test
    fun `stats include assistant and summary usage`() =
        runTest {
            val session =
                Session(
                    InMemorySessionStorage(
                        SessionMetadata("session-1", "2026-01-01T00:00:00Z"),
                    ),
                )
            session.appendMessage(
                assistant("used", 1).copy(
                    usage =
                        works.earendil.pi.ai.Usage(
                            input = 10,
                            output = 2,
                            cacheRead = 3,
                            cacheWrite = 4,
                            cost = works.earendil.pi.ai.Cost(total = 0.5),
                        ),
                ),
            )

            assertEquals(
                SessionStats(
                    messageCount = 1,
                    cachedTokens = 3,
                    uncachedTokens = 14,
                    totalTokens = 19,
                    costTotal = 0.5,
                ),
                session.getSessionStats(),
            )
        }

    private fun assistant(
        text: String,
        timestamp: Long,
    ): AssistantMessage =
        AssistantMessage(
            content = listOf(TextContent(text)),
            api = "test",
            provider = "test",
            model = "model-1",
            stopReason = StopReason.STOP,
            timestamp = timestamp,
        )

    private fun messageText(message: works.earendil.pi.ai.Message): String =
        when (message) {
            is UserMessage -> works.earendil.pi.ai.contentText(message.content)
            is AssistantMessage -> works.earendil.pi.ai.contentText(message.content)
            is works.earendil.pi.ai.CompactionSummaryMessage -> message.summary
            else -> error("Unexpected message: $message")
        }
}
