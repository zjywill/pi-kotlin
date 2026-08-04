package works.earendil.pi.codingagent

import kotlin.math.ceil
import kotlin.math.min
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.BashExecutionMessage
import works.earendil.pi.ai.BranchSummaryMessage
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.CompactionSummaryMessage
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.CustomMessage
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ThinkingContent
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.ai.contentText
import works.earendil.pi.ai.uuidv7
import works.earendil.pi.codingagent.session.BranchSummaryEntry
import works.earendil.pi.codingagent.session.CompactionEntry
import works.earendil.pi.codingagent.session.CustomMessageEntry
import works.earendil.pi.codingagent.session.SessionEntry
import works.earendil.pi.codingagent.session.SessionMessageEntry
import works.earendil.pi.codingagent.session.buildSessionContext

data class CompactionSettings(
    val enabled: Boolean = true,
    val reserveTokens: Int = 16_384,
    val keepRecentTokens: Int = 20_000,
)

val DEFAULT_COMPACTION_SETTINGS = CompactionSettings()

data class ContextUsageEstimate(
    val tokens: Int,
    val usageTokens: Int,
    val trailingTokens: Int,
    val lastUsageIndex: Int?,
)

data class CutPointResult(
    val firstKeptEntryIndex: Int,
    val turnStartIndex: Int,
    val isSplitTurn: Boolean,
)

data class CompactionPreparation(
    val firstKeptEntryId: String,
    val messagesToSummarize: List<Message>,
    val turnPrefixMessages: List<Message>,
    val isSplitTurn: Boolean,
    val tokensBefore: Int,
    val previousSummary: String?,
    val settings: CompactionSettings,
)

data class CompactionResult(
    val summary: String,
    val firstKeptEntryId: String,
    val tokensBefore: Int,
    val usage: Usage,
    val details: JsonObject,
)

fun calculateContextTokens(usage: Usage): Int =
    usage.totalTokens.takeIf { it > 0 }
        ?: (usage.input + usage.output + usage.cacheRead + usage.cacheWrite)

fun getLastAssistantUsage(entries: List<SessionEntry>): Usage? =
    entries
        .asReversed()
        .filterIsInstance<SessionMessageEntry>()
        .mapNotNull { (it.message as? AssistantMessage)?.validUsage() }
        .firstOrNull()

fun estimateContextTokens(messages: List<Message>): ContextUsageEstimate {
    val usageIndex =
        messages.indices.reversed().firstOrNull { index ->
            (messages[index] as? AssistantMessage)?.validUsage() != null
        }
    if (usageIndex == null) {
        val estimated = messages.sumOf(::estimateTokens)
        return ContextUsageEstimate(estimated, 0, estimated, null)
    }
    val usageTokens = calculateContextTokens(requireNotNull((messages[usageIndex] as AssistantMessage).validUsage()))
    val trailing = messages.drop(usageIndex + 1).sumOf(::estimateTokens)
    return ContextUsageEstimate(usageTokens + trailing, usageTokens, trailing, usageIndex)
}

fun shouldCompact(
    contextTokens: Int,
    contextWindow: Int,
    settings: CompactionSettings,
): Boolean = settings.enabled && contextTokens > contextWindow - settings.reserveTokens

fun isContextOverflow(
    message: AssistantMessage,
    contextWindow: Int,
): Boolean {
    if (
        message.stopReason == StopReason.ERROR &&
        message.errorMessage?.let(CONTEXT_OVERFLOW_PATTERN::containsMatchIn) == true
    ) {
        return true
    }
    if (contextWindow <= 0) {
        return false
    }
    val inputTokens = message.usage.input + message.usage.cacheRead + message.usage.cacheWrite
    if (inputTokens > contextWindow) {
        return true
    }
    return message.stopReason == StopReason.LENGTH &&
        message.usage.output == 0 &&
        inputTokens >= (contextWindow * 0.99).toInt()
}

fun isRecoverableLength(
    message: AssistantMessage,
    desiredMaxOutput: Int,
): Boolean =
    message.stopReason == StopReason.LENGTH &&
        desiredMaxOutput > 0 &&
        message.usage.output < desiredMaxOutput

fun estimateTokens(message: Message): Int {
    val characters =
        when (message) {
            is UserMessage -> contentCharacters(message.content)
            is AssistantMessage ->
                message.content.sumOf { block ->
                    when (block) {
                        is TextContent -> block.text.length
                        is ThinkingContent -> block.thinking.length
                        is ToolCall -> block.name.length + block.arguments.toString().length
                        is ImageContent -> 4_800
                    }
                }

            is ToolResultMessage -> message.content.sumOf(::contentBlockCharacters)
            is CustomMessage -> contentCharacters(message.content)
            is BashExecutionMessage -> message.command.length + message.output.length
            is BranchSummaryMessage -> message.summary.length
            is CompactionSummaryMessage -> message.summary.length
        }
    return ceil(characters / 4.0).toInt()
}

fun findTurnStartIndex(
    entries: List<SessionEntry>,
    entryIndex: Int,
    startIndex: Int,
): Int {
    for (index in entryIndex downTo startIndex) {
        if (entryMessages(entries[index]).any(::isTurnStartMessage)) {
            return index
        }
    }
    return -1
}

fun findCutPoint(
    entries: List<SessionEntry>,
    startIndex: Int,
    endIndex: Int,
    keepRecentTokens: Int,
): CutPointResult {
    val cutPoints =
        (startIndex until endIndex).filter { index ->
            entries[index] !is CompactionEntry && entryMessages(entries[index]).any(::isCutPointMessage)
        }
    if (cutPoints.isEmpty()) {
        return CutPointResult(startIndex, -1, false)
    }

    var accumulated = 0
    var cutIndex = cutPoints.first()
    for (index in endIndex - 1 downTo startIndex) {
        val tokens = entryMessages(entries[index]).sumOf(::estimateTokens)
        if (tokens == 0) {
            continue
        }
        accumulated += tokens
        if (accumulated >= keepRecentTokens) {
            cutIndex = cutPoints.firstOrNull { it >= index } ?: cutPoints.last()
            break
        }
    }
    while (cutIndex > startIndex) {
        val previous = entries[cutIndex - 1]
        if (previous is CompactionEntry || entryMessages(previous).isNotEmpty()) {
            break
        }
        cutIndex--
    }
    val startsTurn = entryMessages(entries[cutIndex]).any(::isTurnStartMessage)
    val turnStart = if (startsTurn) -1 else findTurnStartIndex(entries, cutIndex, startIndex)
    return CutPointResult(cutIndex, turnStart, !startsTurn && turnStart >= 0)
}

fun prepareCompaction(
    pathEntries: List<SessionEntry>,
    settings: CompactionSettings = DEFAULT_COMPACTION_SETTINGS,
): CompactionPreparation? {
    if (pathEntries.isEmpty() || pathEntries.last() is CompactionEntry) {
        return null
    }
    val previousCompactionIndex = pathEntries.indexOfLast { it is CompactionEntry }
    val previousCompaction = pathEntries.getOrNull(previousCompactionIndex) as? CompactionEntry
    val boundaryStart =
        if (previousCompaction == null) {
            0
        } else {
            pathEntries.indexOfFirst { it.id == previousCompaction.firstKeptEntryId }
                .takeIf { it >= 0 }
                ?: (previousCompactionIndex + 1)
        }
    val cutPoint = findCutPoint(pathEntries, boundaryStart, pathEntries.size, settings.keepRecentTokens)
    val firstKeptEntry = pathEntries.getOrNull(cutPoint.firstKeptEntryIndex) ?: return null
    val historyEnd = if (cutPoint.isSplitTurn) cutPoint.turnStartIndex else cutPoint.firstKeptEntryIndex
    val messagesToSummarize =
        pathEntries
            .subList(boundaryStart, historyEnd.coerceAtLeast(boundaryStart))
            .flatMap(::entryMessagesForCompaction)
    val turnPrefix =
        if (cutPoint.isSplitTurn) {
            pathEntries
                .subList(cutPoint.turnStartIndex, cutPoint.firstKeptEntryIndex)
                .flatMap(::entryMessagesForCompaction)
        } else {
            emptyList()
        }
    if (messagesToSummarize.isEmpty() && turnPrefix.isEmpty()) {
        return null
    }
    return CompactionPreparation(
        firstKeptEntryId = firstKeptEntry.id,
        messagesToSummarize = messagesToSummarize,
        turnPrefixMessages = turnPrefix,
        isSplitTurn = cutPoint.isSplitTurn,
        tokensBefore = estimateContextTokens(buildSessionContext(pathEntries).messages).tokens,
        previousSummary = previousCompaction?.summary,
        settings = settings,
    )
}

fun serializeConversation(messages: List<Message>): String =
    messages.joinToString("\n") { message ->
        when (message) {
            is UserMessage -> "[User]: ${messageContentText(message.content)}"
            is AssistantMessage -> {
                val text =
                    message.content.joinToString("\n") { block ->
                        when (block) {
                            is TextContent -> block.text
                            is ThinkingContent -> "[Thinking]: ${block.thinking}"
                            is ToolCall -> "[Tool call ${block.name}]: ${block.arguments}"
                            is ImageContent -> "[Image: ${block.mimeType}]"
                        }
                    }
                "[Assistant]: $text"
            }

            is ToolResultMessage -> "[Tool result]: ${truncateToolResult(contentText(message.content))}"
            is CustomMessage -> "[Custom ${message.customType}]: ${messageContentText(message.content)}"
            is BashExecutionMessage -> "[Bash]: ${message.command}\n${message.output}"
            is BranchSummaryMessage -> "[Branch summary]: ${message.summary}"
            is CompactionSummaryMessage -> "[Compaction summary]: ${message.summary}"
        }
    }

suspend fun compact(
    preparation: CompactionPreparation,
    models: Models,
    model: Model,
    apiKey: String? = null,
    customInstructions: String? = null,
    thinkingLevel: ThinkingLevel? = null,
): CompactionResult {
    val (summary, usage) =
        if (preparation.isSplitTurn && preparation.turnPrefixMessages.isNotEmpty()) {
            val history =
                if (preparation.messagesToSummarize.isEmpty()) {
                    "No prior history." to Usage()
                } else {
                    generateSummary(
                        preparation.messagesToSummarize,
                        models,
                        model,
                        preparation.settings.reserveTokens,
                        apiKey,
                        customInstructions,
                        preparation.previousSummary,
                        thinkingLevel,
                        SUMMARIZATION_PROMPT,
                        0.8,
                    )
                }
            val turn =
                generateSummary(
                    preparation.turnPrefixMessages,
                    models,
                    model,
                    preparation.settings.reserveTokens,
                    apiKey,
                    null,
                    null,
                    thinkingLevel,
                    TURN_PREFIX_SUMMARIZATION_PROMPT,
                    0.5,
                )
            "${history.first}\n\n---\n\n**Turn Context (split turn):**\n\n${turn.first}" to
                combineUsage(history.second, turn.second)
        } else {
            generateSummary(
                preparation.messagesToSummarize,
                models,
                model,
                preparation.settings.reserveTokens,
                apiKey,
                customInstructions,
                preparation.previousSummary,
                thinkingLevel,
                SUMMARIZATION_PROMPT,
                0.8,
            )
        }
    return CompactionResult(
        summary = summary,
        firstKeptEntryId = preparation.firstKeptEntryId,
        tokensBefore = preparation.tokensBefore,
        usage = usage,
        details =
            buildJsonObject {
                put("readFiles", JsonArray(emptyList()))
                put("modifiedFiles", JsonArray(emptyList()))
            },
    )
}

private suspend fun generateSummary(
    messages: List<Message>,
    models: Models,
    model: Model,
    reserveTokens: Int,
    apiKey: String?,
    customInstructions: String?,
    previousSummary: String?,
    thinkingLevel: ThinkingLevel?,
    basePrompt: String,
    outputRatio: Double,
): Pair<String, Usage> {
    val prompt =
        buildString {
            append("<conversation>\n")
            append(serializeConversation(messages))
            append("\n</conversation>\n\n")
            previousSummary?.let {
                append("<previous-summary>\n")
                append(it)
                append("\n</previous-summary>\n\n")
            }
            append(if (previousSummary == null) basePrompt else UPDATE_SUMMARIZATION_PROMPT)
            customInstructions?.let {
                append("\n\nAdditional focus: ")
                append(it)
            }
        }
    val response =
        models.completeSimple(
            model,
            Context(
                systemPrompt = SUMMARIZATION_SYSTEM_PROMPT,
                messages = mutableListOf(UserMessage(prompt)),
            ),
            SimpleStreamOptions(
                stream =
                    StreamOptions(
                        apiKey = apiKey,
                        maxTokens = min((reserveTokens * outputRatio).toInt(), model.maxTokens.takeIf { it > 0 } ?: Int.MAX_VALUE),
                        cacheRetention = CacheRetention.NONE,
                        sessionId = uuidv7(),
                    ),
                reasoning = thinkingLevel.takeIf { model.reasoning },
            ),
        )
    check(response.stopReason != StopReason.ERROR && response.stopReason != StopReason.ABORTED) {
        "Summarization failed: ${response.errorMessage ?: response.stopReason.name.lowercase()}"
    }
    return contentText(response.content) to response.usage
}

private fun AssistantMessage.validUsage(): Usage? =
    usage.takeIf {
        stopReason != StopReason.ABORTED &&
            stopReason != StopReason.ERROR &&
            calculateContextTokens(it) > 0
    }

private fun contentCharacters(content: MessageContent): Int =
    when (content) {
        is MessageContent.Text -> content.text.length
        is MessageContent.Blocks -> content.blocks.sumOf(::contentBlockCharacters)
    }

private fun contentBlockCharacters(block: works.earendil.pi.ai.ContentBlock): Int =
    when (block) {
        is TextContent -> block.text.length
        is ThinkingContent -> block.thinking.length
        is ToolCall -> block.name.length + block.arguments.toString().length
        is ImageContent -> 4_800
    }

private fun messageContentText(content: MessageContent): String =
    when (content) {
        is MessageContent.Text -> content.text
        is MessageContent.Blocks ->
            content.blocks.joinToString("\n") { block ->
                when (block) {
                    is TextContent -> block.text
                    is ThinkingContent -> block.thinking
                    is ToolCall -> "${block.name}: ${block.arguments}"
                    is ImageContent -> "[Image: ${block.mimeType}]"
                }
            }
    }

private fun entryMessages(entry: SessionEntry): List<Message> =
    when (entry) {
        is SessionMessageEntry -> listOf(entry.message)
        is CustomMessageEntry ->
            listOf(
                CustomMessage(
                    customType = entry.customType,
                    content = entry.content,
                    display = entry.display,
                    details = entry.details,
                ),
            )

        is BranchSummaryEntry -> listOf(BranchSummaryMessage(entry.summary, entry.fromId))
        is CompactionEntry -> listOf(CompactionSummaryMessage(entry.summary, entry.tokensBefore))
        else -> emptyList()
    }

private fun entryMessagesForCompaction(entry: SessionEntry): List<Message> =
    if (entry is CompactionEntry) emptyList() else entryMessages(entry)

private fun isCutPointMessage(message: Message): Boolean = message !is ToolResultMessage

private fun isTurnStartMessage(message: Message): Boolean =
    message is UserMessage ||
        message is BashExecutionMessage ||
        message is CustomMessage ||
        message is BranchSummaryMessage ||
        message is CompactionSummaryMessage

private fun truncateToolResult(value: String): String =
    if (value.length <= 2_000) {
        value
    } else {
        value.take(2_000) + "\n[... ${value.length - 2_000} more characters truncated]"
    }

private fun combineUsage(
    first: Usage,
    second: Usage,
): Usage =
    Usage(
        input = first.input + second.input,
        output = first.output + second.output,
        cacheRead = first.cacheRead + second.cacheRead,
        cacheWrite = first.cacheWrite + second.cacheWrite,
        cacheWrite1h =
            if (first.cacheWrite1h != null || second.cacheWrite1h != null) {
                (first.cacheWrite1h ?: 0) + (second.cacheWrite1h ?: 0)
            } else {
                null
            },
        reasoning =
            if (first.reasoning != null || second.reasoning != null) {
                (first.reasoning ?: 0) + (second.reasoning ?: 0)
            } else {
                null
            },
        totalTokens = calculateContextTokens(first) + calculateContextTokens(second),
        cost =
            works.earendil.pi.ai.Cost(
                input = first.cost.input + second.cost.input,
                output = first.cost.output + second.cost.output,
                cacheRead = first.cost.cacheRead + second.cost.cacheRead,
                cacheWrite = first.cost.cacheWrite + second.cost.cacheWrite,
                total = first.cost.total + second.cost.total,
            ),
    )

private const val SUMMARIZATION_SYSTEM_PROMPT =
    "You are a context summarization assistant. Read the conversation and output only the requested structured summary."

private const val SUMMARIZATION_PROMPT = """The messages above are a conversation to summarize.

Use this exact structure:

## Goal
## Constraints & Preferences
## Progress
### Done
### In Progress
### Blocked
## Key Decisions
## Next Steps
## Critical Context

Keep each section concise. Preserve exact file paths, function names, and error messages."""

private const val UPDATE_SUMMARIZATION_PROMPT = """Update the previous structured summary with the new conversation.
Preserve existing goals, constraints, decisions, and critical context. Update progress and next steps."""

private const val TURN_PREFIX_SUMMARIZATION_PROMPT = """This is the prefix of a turn whose suffix is retained.
Summarize the original request, early progress, and context needed to understand the retained suffix."""

private val CONTEXT_OVERFLOW_PATTERN =
    Regex(
        listOf(
            "prompt is too long",
            "request_too_large",
            "input is too long for requested model",
            "exceeds the context window",
            "maximum context length",
            "input token count.*exceeds the maximum",
            "maximum prompt length",
            "exceeds the available context size",
            "context window exceeds limit",
            "exceeded model token limit",
            "context[_ ]length[_ ]exceeded",
            "too many tokens",
            "token limit exceeded",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )
