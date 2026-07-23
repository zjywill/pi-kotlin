package works.earendil.pi.codingagent.session

import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.Usage

const val CURRENT_SESSION_VERSION = 3

sealed interface FileEntry

data class SessionHeader(
    val version: Int = CURRENT_SESSION_VERSION,
    val id: String,
    val timestamp: String,
    val cwd: String,
    val parentSession: String? = null,
) : FileEntry

sealed interface SessionEntry : FileEntry {
    val id: String
    val parentId: String?
    val timestamp: String
}

data class SessionMessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val message: Message,
) : SessionEntry

data class ThinkingLevelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val thinkingLevel: String,
) : SessionEntry

data class ModelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val provider: String,
    val modelId: String,
) : SessionEntry

data class CompactionEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val summary: String,
    val firstKeptEntryId: String,
    val tokensBefore: Int,
    val details: JsonElement? = null,
    val usage: Usage? = null,
    val fromHook: Boolean? = null,
) : SessionEntry

data class BranchSummaryEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val fromId: String,
    val summary: String,
    val details: JsonElement? = null,
    val usage: Usage? = null,
    val fromHook: Boolean? = null,
) : SessionEntry

data class CustomEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val customType: String,
    val data: JsonElement? = null,
) : SessionEntry

data class CustomMessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val customType: String,
    val content: MessageContent,
    val details: JsonElement? = null,
    val display: Boolean,
) : SessionEntry

data class LabelEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val targetId: String,
    val label: String? = null,
) : SessionEntry

data class SessionInfoEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val name: String? = null,
) : SessionEntry

data class SessionModel(
    val provider: String,
    val modelId: String,
)

data class SessionContext(
    val messages: List<Message>,
    val thinkingLevel: String,
    val model: SessionModel?,
)

data class SessionTreeNode(
    val entry: SessionEntry,
    val children: MutableList<SessionTreeNode> = mutableListOf(),
    val label: String? = null,
    val labelTimestamp: String? = null,
)

data class NewSessionOptions(
    val id: String? = null,
    val parentSession: String? = null,
)

data class SessionInfo(
    val path: Path,
    val id: String,
    val cwd: Path?,
    val name: String?,
    val parentSessionPath: Path?,
    val created: Instant,
    val modified: Instant,
    val messageCount: Int,
    val firstMessage: String,
    val allMessagesText: String,
)
