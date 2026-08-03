package works.earendil.pi.agent.session

import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.BranchSummaryMessage
import works.earendil.pi.ai.CompactionSummaryMessage
import works.earendil.pi.ai.CustomMessage
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.uuidv7

typealias ContextEntryTransform = (List<SessionTreeEntry>) -> List<SessionTreeEntry>
typealias CustomEntryProjector = (CustomEntry, Int, List<SessionTreeEntry>) -> List<Message>

data class SessionContextBuildOptions(
    val entryTransforms: List<ContextEntryTransform> = emptyList(),
    val entryProjectors: Map<String, CustomEntryProjector> = emptyMap(),
)

fun defaultContextEntryTransform(pathEntries: List<SessionTreeEntry>): List<SessionTreeEntry> {
    val compaction = pathEntries.filterIsInstance<CompactionEntry>().lastOrNull() ?: return pathEntries.toList()
    val compactionIndex = pathEntries.indexOfFirst { it.id == compaction.id }
    val entries = mutableListOf<SessionTreeEntry>(compaction)
    if (compaction.retainedTail != null) {
        entries += pathEntries.drop(compactionIndex + 1)
        return entries
    }
    val firstKeptEntryId = compaction.firstKeptEntryId
    if (firstKeptEntryId != null) {
        var found = false
        pathEntries.take(compactionIndex).forEach { entry ->
            if (entry.id == firstKeptEntryId) {
                found = true
            }
            if (found) {
                entries += entry
            }
        }
    }
    entries += pathEntries.drop(compactionIndex + 1)
    return entries
}

fun buildContextEntries(
    pathEntries: List<SessionTreeEntry>,
    options: SessionContextBuildOptions = SessionContextBuildOptions(),
): List<SessionTreeEntry> =
    options.entryTransforms.fold(defaultContextEntryTransform(pathEntries)) { entries, transform ->
        transform(entries)
    }

fun buildSessionContext(
    pathEntries: List<SessionTreeEntry>,
    options: SessionContextBuildOptions = SessionContextBuildOptions(),
): SessionContext {
    var thinkingLevel = "off"
    var model: SessionModel? = null
    var activeToolNames: List<String>? = null
    pathEntries.forEach { entry ->
        when (entry) {
            is ThinkingLevelChangeEntry -> thinkingLevel = entry.thinkingLevel
            is ModelChangeEntry -> model = SessionModel(entry.provider, entry.modelId)
            is MessageEntry -> {
                val message = entry.message
                if (message is AssistantMessage) {
                    model = SessionModel(message.provider, message.model)
                }
            }

            is ActiveToolsChangeEntry -> activeToolNames = entry.activeToolNames.toList()
            else -> Unit
        }
    }
    val contextEntries = buildContextEntries(pathEntries, options)
    val messages =
        contextEntries.flatMapIndexed { index, entry ->
            when (entry) {
                is MessageEntry -> listOf(entry.message)
                is CustomMessageEntry ->
                    listOf(
                        CustomMessage(
                            customType = entry.customType,
                            content = entry.content,
                            display = entry.display,
                            details = entry.details,
                            timestamp = Instant.parse(entry.timestamp).toEpochMilli(),
                        ),
                    )

                is CompactionEntry ->
                    buildList {
                        add(
                            CompactionSummaryMessage(
                                summary = entry.summary,
                                tokensBefore = entry.tokensBefore,
                                timestamp = Instant.parse(entry.timestamp).toEpochMilli(),
                            ),
                        )
                        addAll(entry.retainedTail.orEmpty())
                    }

                is BranchSummaryEntry ->
                    entry.summary.takeIf(String::isNotEmpty)?.let { summary ->
                        listOf(
                            BranchSummaryMessage(
                                summary = summary,
                                fromId = entry.fromId,
                                timestamp = Instant.parse(entry.timestamp).toEpochMilli(),
                            ),
                        )
                    }.orEmpty()

                is CustomEntry -> options.entryProjectors[entry.customType]?.invoke(entry, index, contextEntries).orEmpty()
                else -> emptyList()
            }
        }
    return SessionContext(messages, thinkingLevel, model, activeToolNames)
}

class Session<M : SessionMetadata>(
    private val storage: SessionStorage<M>,
    initialLeafId: String?,
    private val contextBuildOptions: SessionContextBuildOptions = SessionContextBuildOptions(),
) {
    private val appendMutex = Mutex()
    private val metadata = storage.metadata
    private var leafId = initialLeafId

    suspend fun getMetadata(): M = metadata

    fun getStorage(): SessionStorage<M> = storage

    suspend fun getLeafId(): String? = leafId

    suspend fun getEntry(id: String): SessionTreeEntry? = storage.readEntry(id)

    suspend fun getEntries(options: SessionEntryCursorOptions? = null): List<SessionTreeEntry> =
        storage.readEntries(options)

    suspend fun getBranch(fromId: String? = null): List<SessionTreeEntry> =
        storage.readPathToRootOrCompaction(fromId ?: leafId)

    suspend fun findEntriesOnBranch(query: SessionBranchQuery = SessionBranchQuery()): List<SessionTreeEntry> =
        storage.findEntriesOnBranch(
            if (query.startFromActiveLeaf) {
                query.copy(start = leafId, startFromActiveLeaf = false)
            } else {
                query
            },
        )

    suspend fun findEntryOnBranch(query: SessionBranchQuery = SessionBranchQuery()): SessionTreeEntry? =
        findEntriesOnBranch(query.copy(limit = 1)).firstOrNull()

    suspend fun buildContext(options: SessionContextBuildOptions = SessionContextBuildOptions()): SessionContext =
        buildSessionContext(
            getBranch(),
            SessionContextBuildOptions(
                entryTransforms = contextBuildOptions.entryTransforms + options.entryTransforms,
                entryProjectors = contextBuildOptions.entryProjectors + options.entryProjectors,
            ),
        )

    suspend fun getLabel(id: String): String? = storage.getLabel(id)

    suspend fun getSessionStats(): SessionStats = storage.getStats()

    suspend fun getSessionName(): String? = storage.getName()

    suspend fun appendMessage(message: Message): String =
        append { id, parentId, timestamp -> MessageEntry(id, parentId, timestamp, message) }

    suspend fun appendThinkingLevelChange(thinkingLevel: String): String =
        append { id, parentId, timestamp ->
            ThinkingLevelChangeEntry(id, parentId, timestamp, thinkingLevel)
        }

    suspend fun appendModelChange(
        provider: String,
        modelId: String,
    ): String =
        append { id, parentId, timestamp ->
            ModelChangeEntry(id, parentId, timestamp, provider, modelId)
        }

    suspend fun appendActiveToolsChange(activeToolNames: List<String>): String =
        append { id, parentId, timestamp ->
            ActiveToolsChangeEntry(id, parentId, timestamp, activeToolNames.toList())
        }

    suspend fun appendCompaction(
        summary: String,
        firstKeptEntryId: String?,
        tokensBefore: Int,
        details: JsonElement? = null,
        fromHook: Boolean? = null,
        usage: Usage? = null,
        retainedTail: List<Message>? = null,
    ): String =
        append { id, parentId, timestamp ->
            CompactionEntry(
                id,
                parentId,
                timestamp,
                summary,
                firstKeptEntryId,
                tokensBefore,
                retainedTail,
                details,
                usage,
                fromHook,
            )
        }

    suspend fun appendCustomEntry(
        customType: String,
        data: JsonElement? = null,
    ): String =
        append { id, parentId, timestamp ->
            CustomEntry(id, parentId, timestamp, customType, data)
        }

    suspend fun appendCustomMessageEntry(
        customType: String,
        content: MessageContent,
        display: Boolean,
        details: JsonElement? = null,
    ): String =
        append { id, parentId, timestamp ->
            CustomMessageEntry(id, parentId, timestamp, customType, content, details, display)
        }

    suspend fun appendLabel(
        targetId: String,
        label: String?,
    ): String {
        if (storage.readEntry(targetId) == null) {
            throw SessionException(SessionErrorCode.NOT_FOUND, "Entry $targetId not found")
        }
        return append { id, parentId, timestamp ->
            LabelEntry(id, parentId, timestamp, targetId, label)
        }
    }

    suspend fun appendSessionName(name: String): String {
        val sanitized = name.replace(Regex("[\\r\\n]+"), " ").trim()
        return append { id, parentId, timestamp ->
            SessionInfoEntry(id, parentId, timestamp, sanitized)
        }
    }

    suspend fun moveTo(
        entryId: String?,
        summary: BranchMoveSummary? = null,
    ): String? =
        appendMutex.withLock {
            if (entryId != null && storage.readEntry(entryId) == null) {
                throw SessionException(SessionErrorCode.NOT_FOUND, "Entry $entryId not found")
            }
            appendWithParentLocked(leafId) { id, parentId, timestamp ->
                LeafEntry(id, parentId, timestamp, entryId)
            }
            summary?.let {
                appendWithParentLocked(entryId) { id, parentId, timestamp ->
                    BranchSummaryEntry(
                        id,
                        parentId,
                        timestamp,
                        entryId ?: "root",
                        it.summary,
                        it.details,
                        it.usage,
                        it.fromHook,
                    )
                }
            }
        }

    suspend fun close() = storage.close()

    private suspend fun append(
        create: (String, String?, String) -> SessionTreeEntry,
    ): String =
        appendMutex.withLock {
            appendWithParentLocked(leafId, create)
        }

    private suspend fun appendWithParentLocked(
        parentId: String?,
        create: (String, String?, String) -> SessionTreeEntry,
    ): String {
        val entry = create(createEntryId(), parentId, nowTimestamp())
        storage.appendEntry(entry)
        leafId = leafIdAfterEntry(entry)
        return entry.id
    }

    private suspend fun createEntryId(): String {
        repeat(100) {
            val candidate = uuidv7().takeLast(8)
            if (storage.readEntry(candidate) == null) {
                return candidate
            }
        }
        return uuidv7()
    }
}

suspend fun <M : SessionMetadata> createSession(
    storage: SessionStorage<M>,
    contextBuildOptions: SessionContextBuildOptions = SessionContextBuildOptions(),
): Session<M> =
    Session(
        storage = storage,
        initialLeafId = storage.readHead().leafId,
        contextBuildOptions = contextBuildOptions,
    )

data class BranchMoveSummary(
    val summary: String,
    val details: JsonElement? = null,
    val usage: Usage? = null,
    val fromHook: Boolean? = null,
)
