package works.earendil.pi.agent.harness

import works.earendil.pi.agent.harness.session.DurableEntry
import works.earendil.pi.agent.harness.session.DurableRecord
import works.earendil.pi.agent.harness.session.EntryPayload
import works.earendil.pi.agent.harness.session.OperationIntent
import works.earendil.pi.agent.harness.session.ProvisionedEntry
import works.earendil.pi.agent.harness.session.RecordPayload
import works.earendil.pi.agent.harness.session.deepCopy
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolResultMessage

enum class RecordLogCorruptionReason {
    MULTIPLE_OPEN_OPERATIONS,
    UNKNOWN_OPERATION,
    RECORD_AFTER_FINISH,
    NON_CONSECUTIVE_ATTEMPT,
    INVALID_COMPACTION_REASON,
    QUEUE_AFTER_ABORT,
    INVALID_QUEUE_CANCELLATION,
    INCONSISTENT_STEP,
    TOOL_CALL_MISMATCH,
    DUPLICATE_TOOL_INVOCATION,
    PROVISIONED_ENTRY_MISMATCH,
    INVALID_DEFERRED_HANDLE,
}

class RecordLogCorruption(
    val reason: RecordLogCorruptionReason,
    message: String,
) : RuntimeException(message)

data class RecordLogSlice(
    val lane: String,
    val openOperations: List<DurableRecord>,
    val records: List<DurableRecord>,
    val entries: List<DurableEntry>,
)

data class EffectiveLaneConfiguration(
    val model: ModelIdentity,
    val thinkingLevel: String,
    val activeToolNames: List<String>,
) {
    data class ModelIdentity(
        val provider: String,
        val modelId: String,
    )
}

data class TerminalFailureState(
    val entryId: String,
    val source: Source,
    val message: AssistantMessage,
) {
    enum class Source {
        STEP,
        DEFERRED_FETCH,
    }
}

data class ToolBatchState(
    val assistantEntryId: String,
    val calls: List<Call>,
    val truncated: Boolean,
    val unresolved: Boolean,
) {
    data class Call(
        val toolIndex: Int,
        val toolCall: ToolCall,
        val started: DurableRecord? = null,
        val resultExists: Boolean,
        val terminate: Boolean? = null,
    )
}

data class LaneState(
    val lane: String,
    val leafId: String?,
    val operation: Operation?,
    val pendingNextRun: List<ProvisionedEntry>,
) {
    data class Operation(
        val id: String,
        val kind: String,
        val intent: OperationIntent,
        val aborting: Boolean,
        val step: Step?,
        val toolBatch: ToolBatchState?,
        val missingInitialMessages: List<ProvisionedEntry>,
        val pendingSteer: List<ProvisionedEntry>,
        val pendingFollowUp: List<ProvisionedEntry>,
        val pendingWrites: List<ProvisionedEntry>,
        val deferred: works.earendil.pi.ai.DeferredHandle?,
        val overflowRecoveryUsed: Boolean,
        val newestOwn: NewestOwn?,
        val targets: Targets,
    )

    data class Step(
        val kind: String,
        val attempts: Int,
        val resultEntryId: String,
        val compactionReason: String? = null,
    )

    data class NewestOwn(
        val entryId: String,
        val type: String,
        val role: String? = null,
        val stopReason: StopReason? = null,
    )

    data class Targets(
        val result: Boolean? = null,
        val summary: Boolean? = null,
    )
}

data class LaneReductionInput(
    val lane: String,
    val openOperations: List<DurableRecord>,
    val records: List<DurableRecord>,
    val entries: List<DurableEntry>,
    val leafId: String?,
    val ownEntries: List<DurableEntry>,
    val configurationEntries: List<DurableEntry>,
    val defaults: EffectiveLaneConfiguration,
)

data class LaneReductionResult(
    val laneState: LaneState,
    val effectiveConfiguration: EffectiveLaneConfiguration,
    val terminalFailure: TerminalFailureState?,
)

fun validateRecordLog(input: RecordLogSlice) {
    if (input.openOperations.size > 1) {
        corrupt(
            RecordLogCorruptionReason.MULTIPLE_OPEN_OPERATIONS,
            "Lane ${input.lane} has at least two open operations",
        )
    }

    val entriesById = input.entries.associateBy(DurableEntry::id)
    validateDeferredHandles(entriesById.values)
    val starts = mutableMapOf<String, DurableRecord>()
    val finishedAt = mutableMapOf<String, Long>()
    val abortedAt = mutableMapOf<String, Long>()
    val queueEnqueues = mutableMapOf<String, DurableRecord>()
    val latestAttempt = mutableMapOf<String, DurableRecord>()
    val toolInvocations = mutableSetOf<String>()

    input.records.sortedBy(DurableRecord::seq).forEach { record ->
        val payload = record.payload
        if (payload is RecordPayload.OperationStarted) {
            starts[record.id] = record
            validateOperationResult(entriesById, payload.intent)
            return@forEach
        }

        val runId = record.runId
        if (runId != null) {
            if (runId !in starts) {
                corrupt(
                    RecordLogCorruptionReason.UNKNOWN_OPERATION,
                    "Record ${record.id} references unknown operation $runId",
                )
            }
            val finishSeq = finishedAt[runId]
            if (finishSeq != null && record.seq > finishSeq) {
                corrupt(
                    RecordLogCorruptionReason.RECORD_AFTER_FINISH,
                    "Record ${record.id} follows the finish of operation $runId",
                )
            }
        }

        when (payload) {
            is RecordPayload.OperationFinished -> finishedAt[payload.runId] = record.seq
            is RecordPayload.AbortRequested -> abortedAt[payload.runId] = record.seq
            is RecordPayload.StepAttempt -> {
                validateAttemptReason(record, payload)
                validateAttemptSequence(
                    record = record,
                    payload = payload,
                    previous = latestAttempt[payload.runId],
                    entriesById = entriesById,
                )
                validateAttemptResult(payload, entriesById)
                latestAttempt[payload.runId] = record
            }

            is RecordPayload.ToolStarted ->
                validateToolStart(record, payload, entriesById, toolInvocations)

            is RecordPayload.QueueEnqueued -> {
                if (
                    payload.queue != "nextRun" &&
                    payload.runId != null &&
                    abortedAt[payload.runId] != null &&
                    record.seq > abortedAt.getValue(payload.runId)
                ) {
                    corrupt(
                        RecordLogCorruptionReason.QUEUE_AFTER_ABORT,
                        "${payload.queue} item ${payload.target.id} was enqueued after abort",
                    )
                }
                queueEnqueues[payload.target.id] = record
                validateExactProvisionedEntry(entriesById, payload.target)
            }

            is RecordPayload.QueueCancelled -> {
                val enqueue = queueEnqueues[payload.entryId]
                val enqueuePayload = enqueue?.payload as? RecordPayload.QueueEnqueued
                if (
                    enqueue == null ||
                    enqueue.seq >= record.seq ||
                    enqueuePayload?.runId != payload.runId ||
                    payload.entryId in entriesById
                ) {
                    corrupt(
                        RecordLogCorruptionReason.INVALID_QUEUE_CANCELLATION,
                        "Queue cancellation ${record.id} has no pending matching enqueue",
                    )
                }
            }

            is RecordPayload.WriteDeferred ->
                validateExactProvisionedEntry(entriesById, payload.target)

            is RecordPayload.UsageValue -> Unit
            is RecordPayload.OperationStarted -> Unit
        }
    }
}

fun reduceLaneState(input: LaneReductionInput): LaneReductionResult {
    validateRecordLog(
        RecordLogSlice(
            lane = input.lane,
            openOperations = input.openOperations,
            records = input.records,
            entries = input.entries,
        ),
    )

    val records = input.records.sortedBy(DurableRecord::seq)
    val ownEntries = input.ownEntries.sortedBy(DurableEntry::seq)
    val entriesById =
        (input.entries + ownEntries)
            .associateBy(DurableEntry::id)
            .toMutableMap()
    val cancelledQueueIds =
        records
            .mapNotNull { record ->
                (record.payload as? RecordPayload.QueueCancelled)?.entryId
            }.toSet()
    val pendingQueueRecords =
        records.filter { record ->
            val payload = record.payload as? RecordPayload.QueueEnqueued ?: return@filter false
            payload.target.id !in entriesById && payload.target.id !in cancelledQueueIds
        }
    val started = input.openOperations.firstOrNull()
    val startedPayload = started?.payload as? RecordPayload.OperationStarted
    val capturedInitialMessageIds =
        (startedPayload?.intent as? OperationIntent.Run)
            ?.initialMessages
            ?.map(ProvisionedEntry::id)
            ?.toSet()
            .orEmpty()
    val pendingNextRun =
        pendingQueueRecords
            .mapNotNull { record -> record.payload as? RecordPayload.QueueEnqueued }
            .filter { payload ->
                payload.queue == "nextRun" && payload.target.id !in capturedInitialMessageIds
            }.map { it.target.deepCopy() }
    val effectiveConfiguration = deriveEffectiveConfiguration(input)

    if (started == null || startedPayload == null) {
        return LaneReductionResult(
            laneState =
                LaneState(
                    lane = input.lane,
                    leafId = input.leafId,
                    operation = null,
                    pendingNextRun = pendingNextRun,
                ),
            effectiveConfiguration = effectiveConfiguration,
            terminalFailure = null,
        )
    }

    val operationRecords =
        records.filter { record ->
            record.id == started.id || record.runId == started.id
        }
    val aborting = operationRecords.any { it.payload is RecordPayload.AbortRequested }
    val pendingSteer =
        pendingQueue(
            operationRecords,
            pendingQueueRecords,
            started.id,
            "steer",
            aborting,
        )
    val pendingFollowUp =
        pendingQueue(
            operationRecords,
            pendingQueueRecords,
            started.id,
            "followUp",
            aborting,
        )
    val pendingWrites =
        operationRecords
            .mapNotNull { it.payload as? RecordPayload.WriteDeferred }
            .filter { it.target.id !in entriesById }
            .map { it.target.deepCopy() }
    val missingInitialMessages =
        (startedPayload.intent as? OperationIntent.Run)
            ?.initialMessages
            ?.filter { it.id !in entriesById }
            ?.map(ProvisionedEntry::deepCopy)
            .orEmpty()

    val newestAttempt =
        operationRecords
            .mapNotNull { record ->
                (record.payload as? RecordPayload.StepAttempt)?.let { record to it }
            }.lastOrNull()
    val step =
        newestAttempt
            ?.takeIf { (_, payload) -> payload.resultEntryId !in entriesById }
            ?.let { (_, payload) ->
                LaneState.Step(
                    kind = payload.step,
                    attempts = payload.attempt,
                    resultEntryId = payload.resultEntryId,
                    compactionReason = payload.compactionReason,
                )
            }

    val consumedInputIds = mutableSetOf<String>()
    (startedPayload.intent as? OperationIntent.Run)
        ?.initialMessages
        ?.mapTo(consumedInputIds, ProvisionedEntry::id)
    operationRecords.forEach { record ->
        val payload = record.payload as? RecordPayload.QueueEnqueued
        if (payload != null && payload.queue != "nextRun") {
            consumedInputIds += payload.target.id
        }
    }
    val newestConsumedInputSequence =
        consumedInputIds
            .mapNotNull { entriesById[it] }
            .filter { it.payload is EntryPayload.MessageValue }
            .maxOfOrNull(DurableEntry::seq)
            ?: Long.MIN_VALUE
    val overflowRecoveryUsed =
        operationRecords.any { record ->
            val payload = record.payload as? RecordPayload.StepAttempt
            payload?.step == "compaction" &&
                payload.compactionReason == "overflow" &&
                record.seq > newestConsumedInputSequence
        }

    val newestOwnEntry = ownEntries.lastOrNull()
    val newestOwn = deriveNewestOwn(newestOwnEntry)
    val deferred =
        newestOwnEntry
            ?.message()
            ?.takeIf { it.stopReason == StopReason.DEFERRED }
            ?.deferred
    val intent = startedPayload.intent
    val targets =
        when (intent) {
            is OperationIntent.Compaction ->
                LaneState.Targets(result = intent.resultEntryId in entriesById)

            is OperationIntent.Navigation ->
                LaneState.Targets(
                    summary = intent.summaryEntryId?.let(entriesById::containsKey),
                )

            is OperationIntent.Run -> LaneState.Targets()
        }

    val deferredWriteIds =
        operationRecords
            .mapNotNull { (it.payload as? RecordPayload.WriteDeferred)?.target?.id }
            .toSet()
    val terminalFailure =
        deriveTerminalFailure(
            newestOwnEntry = newestOwnEntry,
            previousOwnEntry = ownEntries.getOrNull(ownEntries.lastIndex - 1),
            operationRecords = operationRecords,
            deferredWriteIds = deferredWriteIds,
        )

    return LaneReductionResult(
        laneState =
            LaneState(
                lane = input.lane,
                leafId = input.leafId,
                operation =
                    LaneState.Operation(
                        id = started.id,
                        kind = intent.kind,
                        intent = intent,
                        aborting = aborting,
                        step = step,
                        toolBatch =
                            deriveToolBatch(
                                operationId = started.id,
                                records = operationRecords,
                                ownEntries = ownEntries,
                                entriesById = entriesById,
                                deferredWriteIds = deferredWriteIds,
                            ),
                        missingInitialMessages = missingInitialMessages,
                        pendingSteer = pendingSteer,
                        pendingFollowUp = pendingFollowUp,
                        pendingWrites = pendingWrites,
                        deferred = deferred,
                        overflowRecoveryUsed = overflowRecoveryUsed,
                        newestOwn = newestOwn,
                        targets = targets,
                    ),
                pendingNextRun = pendingNextRun,
            ),
        effectiveConfiguration = effectiveConfiguration,
        terminalFailure = terminalFailure,
    )
}

private fun deriveEffectiveConfiguration(input: LaneReductionInput): EffectiveLaneConfiguration {
    var configuration =
        input.defaults.copy(
            model = input.defaults.model.copy(),
            activeToolNames = input.defaults.activeToolNames.toList(),
        )
    val entries =
        (input.configurationEntries + input.ownEntries)
            .associateBy(DurableEntry::id)
            .values
            .sortedBy(DurableEntry::seq)
    entries.forEach { entry ->
        when (val payload = entry.payload) {
            is EntryPayload.ModelChange ->
                configuration =
                    configuration.copy(
                        model =
                            EffectiveLaneConfiguration.ModelIdentity(
                                payload.provider,
                                payload.modelId,
                            ),
                    )

            is EntryPayload.ThinkingLevelChange ->
                configuration = configuration.copy(thinkingLevel = payload.thinkingLevel)

            is EntryPayload.ActiveToolsChange ->
                configuration =
                    configuration.copy(activeToolNames = payload.activeToolNames.toList())

            is EntryPayload.MessageValue -> {
                val assistant = payload.message as? AssistantMessage
                if (assistant != null) {
                    configuration =
                        configuration.copy(
                            model =
                                EffectiveLaneConfiguration.ModelIdentity(
                                    assistant.provider,
                                    assistant.model,
                                ),
                        )
                }
            }

            else -> Unit
        }
    }
    return configuration
}

private fun pendingQueue(
    operationRecords: List<DurableRecord>,
    pendingQueueRecords: List<DurableRecord>,
    operationId: String,
    queue: String,
    aborting: Boolean,
): List<ProvisionedEntry> {
    if (aborting) {
        return emptyList()
    }
    val operationRecordIds = operationRecords.map(DurableRecord::id).toSet()
    return pendingQueueRecords
        .filter { it.id in operationRecordIds || it.runId == operationId }
        .mapNotNull { it.payload as? RecordPayload.QueueEnqueued }
        .filter { it.queue == queue && it.runId == operationId }
        .map { it.target.deepCopy() }
}

private fun deriveNewestOwn(entry: DurableEntry?): LaneState.NewestOwn? {
    entry ?: return null
    val message = (entry.payload as? EntryPayload.MessageValue)?.message
    return when (message) {
        is AssistantMessage ->
            LaneState.NewestOwn(
                entryId = entry.id,
                type = entry.type,
                role = "assistant",
                stopReason = message.stopReason,
            )

        is ToolResultMessage ->
            LaneState.NewestOwn(
                entryId = entry.id,
                type = entry.type,
                role = "toolResult",
            )

        null -> LaneState.NewestOwn(entry.id, entry.type)
        else -> LaneState.NewestOwn(entry.id, entry.type, role = "user")
    }
}

private fun deriveToolBatch(
    operationId: String,
    records: List<DurableRecord>,
    ownEntries: List<DurableEntry>,
    entriesById: Map<String, DurableEntry>,
    deferredWriteIds: Set<String>,
): ToolBatchState? {
    val assistantEntry =
        ownEntries
            .asReversed()
            .firstOrNull { entry ->
                entry.message()
                    ?.content
                    ?.any { it is ToolCall } == true
            } ?: return null
    val assistant = assistantEntry.message() ?: return null
    val toolCalls = assistant.content.filterIsInstance<ToolCall>()
    val starts =
        records
            .mapNotNull { record ->
                (record.payload as? RecordPayload.ToolStarted)
                    ?.takeIf {
                        it.runId == operationId &&
                            it.assistantEntryId == assistantEntry.id
                    }?.let { it.toolIndex to record }
            }.toMap()
    val calls =
        toolCalls.mapIndexed { index, toolCall ->
            val started = starts[index]
            val startedPayload = started?.payload as? RecordPayload.ToolStarted
            val startedResult = startedPayload?.let { entriesById[it.resultEntryId] }
            val blockedResult =
                ownEntries.firstOrNull { entry ->
                    entry.seq > assistantEntry.seq &&
                        entry.id !in deferredWriteIds &&
                        (entry.payload as? EntryPayload.MessageValue)
                            ?.message
                            .let { message ->
                                message is ToolResultMessage && message.toolCallId == toolCall.id
                            }
                }
            val result = startedResult ?: blockedResult
            ToolBatchState.Call(
                toolIndex = index,
                toolCall = toolCall,
                started = started?.deepCopy(),
                resultExists = result != null,
                terminate =
                    (result?.payload as? EntryPayload.MessageValue)
                        ?.takeIf(EntryPayload.MessageValue::terminate)
                        ?.let { true },
            )
        }
    return ToolBatchState(
        assistantEntryId = assistantEntry.id,
        calls = calls,
        truncated = assistant.stopReason == StopReason.LENGTH,
        unresolved = calls.any { !it.resultExists },
    )
}

private fun deriveTerminalFailure(
    newestOwnEntry: DurableEntry?,
    previousOwnEntry: DurableEntry?,
    operationRecords: List<DurableRecord>,
    deferredWriteIds: Set<String>,
): TerminalFailureState? {
    val assistant = newestOwnEntry?.message() ?: return null
    if (
        assistant.stopReason != StopReason.ERROR ||
        newestOwnEntry.id in deferredWriteIds
    ) {
        return null
    }
    val producedByStep =
        operationRecords.any { record ->
            (record.payload as? RecordPayload.StepAttempt)?.resultEntryId == newestOwnEntry.id
        }
    val producedByDeferredFetch =
        operationRecords.any { record ->
            val payload = record.payload as? RecordPayload.UsageValue
            payload?.cause == "deferred_fetch" && payload.entryId == newestOwnEntry.id
        } ||
            previousOwnEntry
                ?.message()
                ?.stopReason == StopReason.DEFERRED
    if (!producedByStep && !producedByDeferredFetch) {
        return null
    }
    return TerminalFailureState(
        entryId = newestOwnEntry.id,
        source =
            if (producedByStep) {
                TerminalFailureState.Source.STEP
            } else {
                TerminalFailureState.Source.DEFERRED_FETCH
            },
        message = assistant,
    )
}

private fun validateDeferredHandles(entries: Collection<DurableEntry>) {
    entries.forEach { entry ->
        val assistant = entry.message()
        if (assistant?.stopReason == StopReason.DEFERRED && assistant.deferred == null) {
            corrupt(
                RecordLogCorruptionReason.INVALID_DEFERRED_HANDLE,
                "Deferred assistant entry ${entry.id} does not carry a handle",
            )
        }
    }
}

private fun validateOperationResult(
    entriesById: Map<String, DurableEntry>,
    intent: OperationIntent,
) {
    when (intent) {
        is OperationIntent.Run ->
            intent.initialMessages.forEach { validateExactProvisionedEntry(entriesById, it) }

        is OperationIntent.Compaction ->
            validateResultEntry(
                entriesById,
                intent.resultEntryId,
                expectedType = "compaction",
                description = "manual compaction",
            )

        is OperationIntent.Navigation ->
            intent.summaryEntryId?.let {
                validateResultEntry(
                    entriesById,
                    it,
                    expectedType = "branch_summary",
                    description = "navigation summary",
                )
            }
    }
}

private fun validateAttemptReason(
    record: DurableRecord,
    payload: RecordPayload.StepAttempt,
) {
    val reason = payload.compactionReason
    if (payload.step == "compaction") {
        if (reason !in setOf("manual", "threshold", "overflow")) {
            corrupt(
                RecordLogCorruptionReason.INVALID_COMPACTION_REASON,
                "Compaction attempt ${record.id} has no valid compaction reason",
            )
        }
    } else if (reason != null) {
        corrupt(
            RecordLogCorruptionReason.INVALID_COMPACTION_REASON,
            "${payload.step} attempt ${record.id} has a compaction reason",
        )
    }
}

private fun validateAttemptSequence(
    record: DurableRecord,
    payload: RecordPayload.StepAttempt,
    previous: DurableRecord?,
    entriesById: Map<String, DurableEntry>,
) {
    val previousPayload = previous?.payload as? RecordPayload.StepAttempt
    val previousResult = previousPayload?.let { entriesById[it.resultEntryId] }
    val continuesSeries =
        previousPayload != null &&
            previousPayload.step == payload.step &&
            (previousResult == null || previousResult.seq >= record.seq)
    val expectedAttempt = if (continuesSeries) previousPayload.attempt + 1 else 1
    if (payload.attempt != expectedAttempt) {
        corrupt(
            RecordLogCorruptionReason.NON_CONSECUTIVE_ATTEMPT,
            "${payload.step} attempt ${record.id} is ${payload.attempt}; expected $expectedAttempt",
        )
    }
    if (!continuesSeries || payload.step == "assistant") {
        return
    }
    if (payload.resultEntryId != previousPayload.resultEntryId) {
        corrupt(
            RecordLogCorruptionReason.INCONSISTENT_STEP,
            "${payload.step} attempts disagree on their result entry id",
        )
    }
    if (payload.compactionReason != previousPayload.compactionReason) {
        corrupt(
            RecordLogCorruptionReason.INCONSISTENT_STEP,
            "${payload.step} attempts disagree on their compaction reason",
        )
    }
}

private fun validateAttemptResult(
    payload: RecordPayload.StepAttempt,
    entriesById: Map<String, DurableEntry>,
) {
    val expected =
        when (payload.step) {
            "assistant" -> "message"
            "compaction" -> "compaction"
            "branch_summary" -> "branch_summary"
            else ->
                corrupt(
                    RecordLogCorruptionReason.INCONSISTENT_STEP,
                    "Unknown step ${payload.step}",
                )
        }
    validateResultEntry(
        entriesById,
        payload.resultEntryId,
        expectedType = expected,
        description = "${payload.step} result",
        requireAssistant = payload.step == "assistant",
    )
}

private fun validateToolStart(
    record: DurableRecord,
    payload: RecordPayload.ToolStarted,
    entriesById: Map<String, DurableEntry>,
    invocations: MutableSet<String>,
) {
    val invocation = "${payload.assistantEntryId}\u0000${payload.toolIndex}"
    if (!invocations.add(invocation)) {
        corrupt(
            RecordLogCorruptionReason.DUPLICATE_TOOL_INVOCATION,
            "Tool invocation ${payload.assistantEntryId}:${payload.toolIndex} is duplicated",
        )
    }
    val assistant = entriesById[payload.assistantEntryId]?.message()
    if (assistant == null) {
        corrupt(
            RecordLogCorruptionReason.TOOL_CALL_MISMATCH,
            "Tool start ${record.id} does not reference an assistant entry",
        )
    }
    val toolCall = assistant.content.filterIsInstance<ToolCall>().getOrNull(payload.toolIndex)
    if (
        toolCall == null ||
        toolCall.id != payload.toolCallId ||
        toolCall.name != payload.toolName
    ) {
        corrupt(
            RecordLogCorruptionReason.TOOL_CALL_MISMATCH,
            "Tool start ${record.id} does not match its assistant tool-call ordinal",
        )
    }
    val result = entriesById[payload.resultEntryId]
    if (result != null) {
        val toolResult = (result.payload as? EntryPayload.MessageValue)?.message as? ToolResultMessage
        if (
            toolResult == null ||
            toolResult.toolCallId != payload.toolCallId ||
            toolResult.toolName != payload.toolName
        ) {
            corrupt(
                RecordLogCorruptionReason.PROVISIONED_ENTRY_MISMATCH,
                "Provisioned tool result entry ${payload.resultEntryId} exists with different content",
            )
        }
    }
}

private fun validateExactProvisionedEntry(
    entriesById: Map<String, DurableEntry>,
    target: ProvisionedEntry,
) {
    val entry = entriesById[target.id] ?: return
    if (entry.provisioned().canonical() != target.canonical()) {
        corrupt(
            RecordLogCorruptionReason.PROVISIONED_ENTRY_MISMATCH,
            "Provisioned entry ${target.id} exists with content different from its intent",
        )
    }
}

private fun validateResultEntry(
    entriesById: Map<String, DurableEntry>,
    resultEntryId: String,
    expectedType: String,
    description: String,
    requireAssistant: Boolean = false,
) {
    val entry = entriesById[resultEntryId] ?: return
    val typeMatches = entry.type == expectedType
    val assistantMatches = !requireAssistant || entry.message() != null
    if (!typeMatches || !assistantMatches) {
        corrupt(
            RecordLogCorruptionReason.PROVISIONED_ENTRY_MISMATCH,
            "Provisioned $description entry $resultEntryId exists with different content",
        )
    }
}

private fun DurableEntry.message(): AssistantMessage? =
    (payload as? EntryPayload.MessageValue)?.message as? AssistantMessage

private fun ProvisionedEntry.canonical(): String =
    works.earendil.pi.agent.harness.session.durableSessionJson.encodeToString(
        ProvisionedEntry.serializer(),
        this,
    )

private fun corrupt(
    reason: RecordLogCorruptionReason,
    message: String,
): Nothing = throw RecordLogCorruption(reason, message)
