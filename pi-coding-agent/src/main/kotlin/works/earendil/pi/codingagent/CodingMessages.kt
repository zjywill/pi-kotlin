package works.earendil.pi.codingagent

import works.earendil.pi.ai.BashExecutionMessage
import works.earendil.pi.ai.BranchSummaryMessage
import works.earendil.pi.ai.CompactionSummaryMessage
import works.earendil.pi.ai.CustomMessage
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.UserMessage

internal const val COMPACTION_SUMMARY_PREFIX =
    "The conversation history before this point was compacted into the following summary:\n\n<summary>\n"
internal const val COMPACTION_SUMMARY_SUFFIX = "\n</summary>"
internal const val BRANCH_SUMMARY_PREFIX =
    "The following is a summary of a branch that this conversation came back from:\n\n<summary>\n"
internal const val BRANCH_SUMMARY_SUFFIX = "</summary>"

internal fun convertCodingMessagesToLlm(messages: List<Message>): List<Message> =
    messages.mapNotNull { message ->
        when (message) {
            is BashExecutionMessage ->
                if (message.excludeFromContext == true) {
                    null
                } else {
                    UserMessage(
                        content = listOf(TextContent(formatBashExecution(message))),
                        timestamp = message.timestamp,
                    )
                }

            is CustomMessage ->
                UserMessage(
                    content =
                        when (val content = message.content) {
                            is MessageContent.Text -> listOf(TextContent(content.text))
                            is MessageContent.Blocks -> content.blocks
                        },
                    timestamp = message.timestamp,
                )

            is BranchSummaryMessage ->
                UserMessage(
                    content =
                        listOf(
                            TextContent(BRANCH_SUMMARY_PREFIX + message.summary + BRANCH_SUMMARY_SUFFIX),
                        ),
                    timestamp = message.timestamp,
                )

            is CompactionSummaryMessage ->
                UserMessage(
                    content =
                        listOf(
                            TextContent(COMPACTION_SUMMARY_PREFIX + message.summary + COMPACTION_SUMMARY_SUFFIX),
                        ),
                    timestamp = message.timestamp,
                )

            else -> message
        }
    }

private fun formatBashExecution(message: BashExecutionMessage): String =
    buildString {
        append("Ran `")
        append(message.command)
        append("`\n")
        if (message.output.isNotEmpty()) {
            append("```\n")
            append(message.output)
            append("\n```")
        } else {
            append("(no output)")
        }
        when {
            message.cancelled -> append("\n\n(command cancelled)")
            message.exitCode != null && message.exitCode != 0 ->
                append("\n\nCommand exited with code ${message.exitCode}")
        }
        if (message.truncated && message.fullOutputPath != null) {
            append("\n\n[Output truncated. Full output: ${message.fullOutputPath}]")
        }
    }
