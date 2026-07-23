package works.earendil.pi.codingagent

import works.earendil.pi.ai.BashExecutionMessage
import works.earendil.pi.ai.BranchSummaryMessage
import works.earendil.pi.ai.CompactionSummaryMessage
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.ai.contentText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodingMessagesTest {
    @Test
    fun `excluded bash messages are removed and visible bash matches upstream prompt text`() {
        val visible =
            BashExecutionMessage(
                command = "false",
                output = "failed",
                exitCode = 7,
                truncated = true,
                fullOutputPath = "/tmp/full.log",
                timestamp = 1,
            )
        val excluded =
            BashExecutionMessage(
                command = "printf secret",
                output = "secret",
                excludeFromContext = true,
                timestamp = 2,
            )

        val projected = convertCodingMessagesToLlm(listOf(visible, excluded))

        assertEquals(1, projected.size)
        assertEquals(
            "Ran `false`\n```\nfailed\n```\n\nCommand exited with code 7" +
                "\n\n[Output truncated. Full output: /tmp/full.log]",
            contentText((projected.single() as UserMessage).content),
        )
    }

    @Test
    fun `branch and compaction summaries use upstream context wrappers`() {
        val projected =
            convertCodingMessagesToLlm(
                listOf(
                    BranchSummaryMessage("branch", "from", 1),
                    CompactionSummaryMessage("compact", 100, 2),
                ),
            ).map { message ->
                val content = (message as UserMessage).content
                assertTrue(content is MessageContent.Blocks)
                contentText(content)
            }

        assertEquals(
            listOf(
                "The following is a summary of a branch that this conversation came back from:" +
                    "\n\n<summary>\nbranch</summary>",
                "The conversation history before this point was compacted into the following summary:" +
                    "\n\n<summary>\ncompact\n</summary>",
            ),
            projected,
        )
    }
}
