@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package works.earendil.pi.agent.harness.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import works.earendil.pi.ai.DeferredHandle
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.Usage

enum class DurableSessionErrorCode {
    NOT_FOUND,
    ALREADY_EXISTS,
    INVALID_ENTRY,
    INVALID_PAYLOAD,
    INVALID_LANE,
    INVALID_QUERY,
    INVALID_FORK_TARGET,
    STORAGE,
}

open class DurableSessionException(
    val code: DurableSessionErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@Serializable
@JsonClassDiscriminator("_kind")
sealed interface EntryPayload {
    val type: String

    @Serializable
    @SerialName("message")
    data class MessageValue(
        val message: Message,
        val terminate: Boolean = false,
    ) : EntryPayload {
        override val type: String = "message"
    }

    @Serializable
    @SerialName("model_change")
    data class ModelChange(
        val provider: String,
        val modelId: String,
    ) : EntryPayload {
        override val type: String = "model_change"
    }

    @Serializable
    @SerialName("thinking_level_change")
    data class ThinkingLevelChange(
        val thinkingLevel: String,
    ) : EntryPayload {
        override val type: String = "thinking_level_change"
    }

    @Serializable
    @SerialName("active_tools_change")
    data class ActiveToolsChange(
        val activeToolNames: List<String>,
    ) : EntryPayload {
        override val type: String = "active_tools_change"
    }

    @Serializable
    @SerialName("compaction")
    data class Compaction(
        val summary: String,
        val retainedTail: List<Message>,
        val tokensBefore: Int,
        val details: JsonElement? = null,
        val usage: Usage? = null,
    ) : EntryPayload {
        override val type: String = "compaction"
    }

    @Serializable
    @SerialName("branch_summary")
    data class BranchSummary(
        val fromId: String,
        val summary: String,
        val details: JsonElement? = null,
        val usage: Usage? = null,
    ) : EntryPayload {
        override val type: String = "branch_summary"
    }

    @Serializable
    @SerialName("custom")
    data class Custom(
        val customType: String,
        val data: JsonElement? = null,
    ) : EntryPayload {
        override val type: String = "custom"
    }
}

@Serializable
data class ProvisionedEntry(
    val id: String,
    val payload: EntryPayload,
)

@Serializable
data class DurableEntry(
    val id: String,
    val seq: Long,
    val parentId: String?,
    val timestamp: Long,
    val payload: EntryPayload,
) {
    val type: String
        get() = payload.type

    fun provisioned(): ProvisionedEntry = ProvisionedEntry(id, payload)
}

@Serializable
@JsonClassDiscriminator("_kind")
sealed interface OperationIntent {
    val kind: String

    @Serializable
    @SerialName("run")
    data class Run(
        val originalPrompt: List<Message>,
        val initialMessages: List<ProvisionedEntry>,
        val systemPromptOverride: String? = null,
        val resumeData: Map<String, JsonElement> = emptyMap(),
    ) : OperationIntent {
        override val kind: String = "run"
    }

    @Serializable
    @SerialName("compaction")
    data class Compaction(
        val customInstructions: String? = null,
        val resultEntryId: String,
    ) : OperationIntent {
        override val kind: String = "compaction"
    }

    @Serializable
    @SerialName("navigation")
    data class Navigation(
        val targetId: String?,
        val summarize: Boolean,
        val customInstructions: String? = null,
        val label: String? = null,
        val summaryEntryId: String? = null,
    ) : OperationIntent {
        override val kind: String = "navigation"
    }
}

@Serializable
data class OperationError(
    val code: String,
    val message: String,
)

@Serializable
@JsonClassDiscriminator("_kind")
sealed interface RecordPayload {
    val type: String

    @Serializable
    @SerialName("operation_started")
    data class OperationStarted(
        val sourceLeafId: String?,
        val intent: OperationIntent,
    ) : RecordPayload {
        override val type: String = "operation_started"
    }

    @Serializable
    @SerialName("abort_requested")
    data class AbortRequested(
        val runId: String,
    ) : RecordPayload {
        override val type: String = "abort_requested"
    }

    @Serializable
    @SerialName("operation_finished")
    data class OperationFinished(
        val runId: String,
        val outcome: String,
        val error: OperationError? = null,
    ) : RecordPayload {
        override val type: String = "operation_finished"
    }

    @Serializable
    @SerialName("step_attempt")
    data class StepAttempt(
        val runId: String,
        val step: String,
        val attempt: Int,
        val resultEntryId: String,
        val compactionReason: String? = null,
    ) : RecordPayload {
        override val type: String = "step_attempt"
    }

    @Serializable
    @SerialName("tool_started")
    data class ToolStarted(
        val runId: String,
        val assistantEntryId: String,
        val toolIndex: Int,
        val toolCallId: String,
        val toolName: String,
        val effectiveArgs: JsonObject,
        val resultEntryId: String,
        val replay: String,
    ) : RecordPayload {
        override val type: String = "tool_started"
    }

    @Serializable
    @SerialName("queue_enqueued")
    data class QueueEnqueued(
        val queue: String,
        val runId: String? = null,
        val target: ProvisionedEntry,
    ) : RecordPayload {
        override val type: String = "queue_enqueued"
    }

    @Serializable
    @SerialName("queue_cancelled")
    data class QueueCancelled(
        val runId: String? = null,
        val entryId: String,
    ) : RecordPayload {
        override val type: String = "queue_cancelled"
    }

    @Serializable
    @SerialName("write_deferred")
    data class WriteDeferred(
        val runId: String,
        val target: ProvisionedEntry,
    ) : RecordPayload {
        override val type: String = "write_deferred"
    }

    @Serializable
    @SerialName("usage")
    data class UsageValue(
        val usage: Usage,
        val cause: String,
        val runId: String? = null,
        val entryId: String? = null,
        val attempt: Int? = null,
        val stopReason: StopReason? = null,
        val toolCallId: String? = null,
        val details: JsonElement? = null,
    ) : RecordPayload {
        override val type: String = "usage"
    }
}

@Serializable
data class NewDurableRecord(
    val id: String,
    val lane: String,
    val payload: RecordPayload,
)

@Serializable
data class DurableRecord(
    val id: String,
    val seq: Long,
    val lane: String,
    val timestamp: Long,
    val payload: RecordPayload,
) {
    val type: String
        get() = payload.type

    val runId: String?
        get() =
            when (val value = payload) {
                is RecordPayload.OperationStarted -> id
                is RecordPayload.AbortRequested -> value.runId
                is RecordPayload.OperationFinished -> value.runId
                is RecordPayload.StepAttempt -> value.runId
                is RecordPayload.ToolStarted -> value.runId
                is RecordPayload.QueueEnqueued -> value.runId
                is RecordPayload.QueueCancelled -> value.runId
                is RecordPayload.WriteDeferred -> value.runId
                is RecordPayload.UsageValue -> value.runId
            }
}

enum class EntryOrder {
    NEWEST_FIRST,
    OLDEST_FIRST,
}

data class EntryCursor(
    val afterSeq: Long,
)

data class EntryQuery(
    val type: String? = null,
    val customType: String? = null,
    val order: EntryOrder = EntryOrder.NEWEST_FIRST,
    val limit: Int? = null,
    val cursor: EntryCursor? = null,
)

data class BranchBounds(
    val start: String? = null,
    val stopAtType: String? = null,
    val stopAtId: String? = null,
)

data class BranchEntryQuery(
    val entry: EntryQuery = EntryQuery(),
    val bounds: BranchBounds = BranchBounds(),
)

data class RecordQuery(
    val lane: String? = null,
    val type: String? = null,
    val runId: String? = null,
    val operationKind: String? = null,
    val afterSeq: Long? = null,
    val order: EntryOrder = EntryOrder.NEWEST_FIRST,
    val limit: Int? = null,
)

data class LogOptions(
    val afterSeq: Long? = null,
    val limit: Int? = null,
)

data class LanePointer(
    val lane: String,
    val leafId: String?,
)

open class DurableSessionMetadata(
    open val id: String,
    open val createdAt: Long,
    open val parentSessionId: String? = null,
)

data class DurableSessionStats(
    val messageCount: Int = 0,
    val cachedTokens: Int = 0,
    val uncachedTokens: Int = 0,
    val totalTokens: Int = 0,
    val costTotal: Double = 0.0,
)

sealed interface DurableLogItem {
    val seq: Long

    data class Entry(
        override val seq: Long,
        val entry: DurableEntry,
    ) : DurableLogItem

    data class Record(
        override val seq: Long,
        val record: DurableRecord,
    ) : DurableLogItem

    data class Lane(
        override val seq: Long,
        val lane: String,
        val leafId: String?,
    ) : DurableLogItem

    data class Name(
        override val seq: Long,
        val name: String,
    ) : DurableLogItem

    data class Label(
        override val seq: Long,
        val targetId: String,
        val label: String?,
    ) : DurableLogItem
}

sealed interface DurableMutation {
    val seq: Long

    data class Entry(
        val lane: String?,
        val entry: DurableEntry,
    ) : DurableMutation {
        override val seq: Long = entry.seq
    }

    data class Record(
        val record: DurableRecord,
    ) : DurableMutation {
        override val seq: Long = record.seq
    }

    data class Lane(
        override val seq: Long,
        val lane: String,
        val leafId: String?,
    ) : DurableMutation

    data class Name(
        override val seq: Long,
        val name: String,
    ) : DurableMutation

    data class Label(
        override val seq: Long,
        val targetId: String,
        val label: String?,
    ) : DurableMutation
}

data class DeferredAssistantState(
    val entryId: String,
    val handle: DeferredHandle,
)
