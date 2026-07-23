package works.earendil.pi.agent.session

import java.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.Usage

enum class SessionErrorCode {
    NOT_FOUND,
    INVALID_SESSION,
    INVALID_ENTRY,
    INVALID_FORK_TARGET,
    STORAGE,
    UNKNOWN,
}

class SessionException(
    val code: SessionErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface SessionTreeEntry {
    val id: String
    val parentId: String?
    val timestamp: String
}

@Serializable
@SerialName("message")
data class MessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val message: Message,
) : SessionTreeEntry

@Serializable
@SerialName("thinking_level_change")
data class ThinkingLevelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val thinkingLevel: String,
) : SessionTreeEntry

@Serializable
@SerialName("model_change")
data class ModelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val provider: String,
    val modelId: String,
) : SessionTreeEntry

@Serializable
@SerialName("active_tools_change")
data class ActiveToolsChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val activeToolNames: List<String>,
) : SessionTreeEntry

@Serializable
@SerialName("compaction")
data class CompactionEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val summary: String,
    val firstKeptEntryId: String? = null,
    val tokensBefore: Int,
    val retainedTail: List<Message>? = null,
    val details: JsonElement? = null,
    val usage: Usage? = null,
    val fromHook: Boolean? = null,
) : SessionTreeEntry

@Serializable
@SerialName("branch_summary")
data class BranchSummaryEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val fromId: String,
    val summary: String,
    val details: JsonElement? = null,
    val usage: Usage? = null,
    val fromHook: Boolean? = null,
) : SessionTreeEntry

@Serializable
@SerialName("custom")
data class CustomEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val customType: String,
    val data: JsonElement? = null,
) : SessionTreeEntry

@Serializable
@SerialName("custom_message")
data class CustomMessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val customType: String,
    val content: MessageContent,
    val details: JsonElement? = null,
    val display: Boolean,
) : SessionTreeEntry

@Serializable
@SerialName("label")
data class LabelEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val targetId: String,
    val label: String? = null,
) : SessionTreeEntry

@Serializable
@SerialName("session_info")
data class SessionInfoEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val name: String? = null,
) : SessionTreeEntry

@Serializable
@SerialName("leaf")
data class LeafEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val targetId: String?,
) : SessionTreeEntry

data class SessionContext(
    val messages: List<Message>,
    val thinkingLevel: String,
    val model: SessionModel?,
    val activeToolNames: List<String>?,
)

data class SessionModel(
    val provider: String,
    val modelId: String,
)

data class SessionStats(
    val messageCount: Int,
    val cachedTokens: Int,
    val uncachedTokens: Int,
    val totalTokens: Int,
    val costTotal: Double,
)

open class SessionMetadata(
    open val id: String,
    open val createdAt: String,
)

data class SessionEntryCursorOptions(
    val afterEntrySeq: Int? = null,
    val limit: Int? = null,
)

interface SessionStorage<M : SessionMetadata> {
    suspend fun getMetadata(): M

    suspend fun getLeafId(): String?

    suspend fun setLeafId(leafId: String?)

    suspend fun createEntryId(): String

    suspend fun appendEntry(entry: SessionTreeEntry)

    suspend fun getEntry(id: String): SessionTreeEntry?

    suspend fun findEntries(type: String): List<SessionTreeEntry>

    suspend fun getLabel(id: String): String?

    suspend fun getSessionName(): String?

    suspend fun getSessionStats(): SessionStats

    suspend fun getPathToRootOrCompaction(leafId: String?): List<SessionTreeEntry>

    suspend fun getEntries(options: SessionEntryCursorOptions? = null): List<SessionTreeEntry>

    suspend fun close() = Unit
}

data class SessionForkOptions(
    val entryId: String? = null,
    val position: Position = Position.BEFORE,
    val id: String? = null,
) {
    enum class Position {
        BEFORE,
        AT,
    }
}

interface SessionRepository<M : SessionMetadata, C, L> {
    suspend fun create(options: C): Session<M>

    suspend fun open(metadata: M): Session<M>

    suspend fun list(options: L? = null): List<M>

    suspend fun delete(metadata: M)
}

fun nowTimestamp(): String = Instant.now().toString()

fun entryType(entry: SessionTreeEntry): String =
    when (entry) {
        is MessageEntry -> "message"
        is ThinkingLevelChangeEntry -> "thinking_level_change"
        is ModelChangeEntry -> "model_change"
        is ActiveToolsChangeEntry -> "active_tools_change"
        is CompactionEntry -> "compaction"
        is BranchSummaryEntry -> "branch_summary"
        is CustomEntry -> "custom"
        is CustomMessageEntry -> "custom_message"
        is LabelEntry -> "label"
        is SessionInfoEntry -> "session_info"
        is LeafEntry -> "leaf"
    }

fun leafIdAfterEntry(entry: SessionTreeEntry): String? =
    if (entry is LeafEntry) entry.targetId else entry.id
