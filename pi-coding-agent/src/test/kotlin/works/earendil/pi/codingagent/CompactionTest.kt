package works.earendil.pi.codingagent

import kotlinx.serialization.json.buildJsonObject
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.codingagent.session.SessionMessageEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompactionTest {
    @Test
    fun `calculates and estimates context tokens`() {
        assertEquals(18, calculateContextTokens(Usage(input = 10, output = 5, cacheRead = 2, cacheWrite = 1)))
        val estimate =
            estimateContextTokens(
                listOf(
                    UserMessage("hello"),
                    assistant("ok", Usage(input = 100, output = 50, totalTokens = 150)),
                    UserMessage("trailing"),
                ),
            )
        assertEquals(150, estimate.usageTokens)
        assertEquals(1, estimate.lastUsageIndex)
        assertTrue(estimate.trailingTokens > 0)
    }

    @Test
    fun `finds a safe cut point and split turn`() {
        val entries =
            listOf(
                entry("u1", null, UserMessage("turn one")),
                entry("a1", "u1", assistant("a".repeat(100))),
                entry("u2", "a1", UserMessage("turn two")),
                entry("a2", "u2", assistant("b".repeat(100))),
                entry("a3", "a2", assistant("c".repeat(100))),
            )
        val cut = findCutPoint(entries, 0, entries.size, keepRecentTokens = 30)
        assertTrue(cut.firstKeptEntryIndex >= 2)
        if (entries[cut.firstKeptEntryIndex].message is AssistantMessage) {
            assertTrue(cut.isSplitTurn)
            assertEquals(2, cut.turnStartIndex)
        }
    }

    @Test
    fun `serializes and truncates tool results`() {
        val result =
            serializeConversation(
                listOf(
                    ToolResultMessage(
                        toolCallId = "call",
                        toolName = "read",
                        content = listOf(TextContent("x".repeat(5_000))),
                        isError = false,
                    ),
                ),
            )
        assertTrue(result.contains("[Tool result]:"))
        assertTrue(result.contains("[... 3000 more characters truncated]"))
        assertFalse(result.contains("x".repeat(3_000)))
    }

    @Test
    fun `tool results are never selected as cut points`() {
        val toolResult =
            ToolResultMessage(
                toolCallId = "call",
                toolName = "read",
                content = listOf(TextContent("result")),
                isError = false,
            )
        val entries =
            listOf(
                entry("u", null, UserMessage("request")),
                entry(
                    "a",
                    "u",
                    assistant(
                        "working",
                        content =
                            listOf(
                                TextContent("working"),
                                ToolCall("call", "read", buildJsonObject {}),
                            ),
                    ),
                ),
                entry("t", "a", toolResult),
            )
        val cut = findCutPoint(entries, 0, entries.size, 1)
        assertFalse(entries[cut.firstKeptEntryIndex].message is ToolResultMessage)
    }

    @Test
    fun `length stops below the desired output limit are recoverable`() {
        val truncated =
            assistant(
                "partial",
                usage = Usage(input = 10, output = 16, totalTokens = 26),
            ).copy(stopReason = StopReason.LENGTH)

        assertTrue(isRecoverableLength(truncated, desiredMaxOutput = 100))
        assertFalse(isRecoverableLength(truncated, desiredMaxOutput = 16))
    }

    @Test
    fun `context overflow includes cache write input`() {
        val overflow =
            assistant(
                "",
                usage = Usage(input = 58, output = 0, cacheRead = 900, cacheWrite = 42, totalTokens = 1_000),
            ).copy(stopReason = StopReason.LENGTH)

        assertTrue(isContextOverflow(overflow, contextWindow = 1_000))
    }

    private fun entry(
        id: String,
        parent: String?,
        message: works.earendil.pi.ai.Message,
    ) = SessionMessageEntry(id, parent, "2026-07-23T00:00:00Z", message)

    private fun assistant(
        text: String,
        usage: Usage = Usage(),
        content: List<works.earendil.pi.ai.ContentBlock> = listOf(TextContent(text)),
    ) = AssistantMessage(
        content = content,
        api = "faux",
        provider = "faux",
        model = "faux-1",
        usage = usage,
        stopReason = StopReason.STOP,
    )
}
