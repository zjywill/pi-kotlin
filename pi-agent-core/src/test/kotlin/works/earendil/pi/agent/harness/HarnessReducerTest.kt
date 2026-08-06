package works.earendil.pi.agent.harness

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import works.earendil.pi.agent.harness.session.DurableEntry
import works.earendil.pi.agent.harness.session.DurableRecord
import works.earendil.pi.agent.harness.session.EntryPayload
import works.earendil.pi.agent.harness.session.OperationIntent
import works.earendil.pi.agent.harness.session.ProvisionedEntry
import works.earendil.pi.agent.harness.session.RecordPayload
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.DeferredHandle
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.ai.fauxAssistantMessage

class HarnessReducerTest {
    @Test
    fun `idle lane keeps pending next run and folds persisted configuration`() {
        val next =
            record(
                4,
                "next",
                RecordPayload.QueueEnqueued(
                    queue = "nextRun",
                    target = ProvisionedEntry("queued", EntryPayload.MessageValue(UserMessage("next"))),
                ),
            )
        val result =
            reduceLaneState(
                baseInput(
                    records = listOf(next),
                    configurationEntries =
                        listOf(
                            entry(
                                1,
                                "model",
                                EntryPayload.ModelChange("anthropic", "claude"),
                            ),
                            entry(
                                2,
                                "thinking",
                                EntryPayload.ThinkingLevelChange("high"),
                            ),
                            entry(
                                3,
                                "tools",
                                EntryPayload.ActiveToolsChange(listOf("read")),
                            ),
                        ),
                ),
            )

        assertNull(result.laneState.operation)
        assertEquals(listOf("queued"), result.laneState.pendingNextRun.map(ProvisionedEntry::id))
        assertEquals(
            EffectiveLaneConfiguration(
                model = EffectiveLaneConfiguration.ModelIdentity("anthropic", "claude"),
                thinkingLevel = "high",
                activeToolNames = listOf("read"),
            ),
            result.effectiveConfiguration,
        )
    }

    @Test
    fun `validation rejects deferred assistants without handles`() {
        val deferred =
            entry(
                1,
                "deferred",
                EntryPayload.MessageValue(
                    fauxAssistantMessage(
                        content = emptyList(),
                        stopReason = StopReason.DEFERRED,
                    ),
                ),
            )

        val error =
            assertFailsWith<RecordLogCorruption> {
                validateRecordLog(
                    RecordLogSlice(
                        lane = "main",
                        openOperations = emptyList(),
                        records = emptyList(),
                        entries = listOf(deferred),
                    ),
                )
            }

        assertEquals(RecordLogCorruptionReason.INVALID_DEFERRED_HANDLE, error.reason)
    }

    @Test
    fun `validation rejects unknown operations records after finish and queue after abort`() {
        val unknown =
            record(1, "usage", RecordPayload.UsageValue(Usage(), "hook", runId = "missing"))
        assertReason(RecordLogCorruptionReason.UNKNOWN_OPERATION, listOf(unknown))

        val start = runStart(1)
        val finish = record(2, "finish", RecordPayload.OperationFinished("run", "completed"))
        val after = record(3, "usage", RecordPayload.UsageValue(Usage(), "hook", runId = "run"))
        assertReason(RecordLogCorruptionReason.RECORD_AFTER_FINISH, listOf(start, finish, after))

        val abort = record(2, "abort", RecordPayload.AbortRequested("run"))
        val queue =
            record(
                3,
                "queue",
                RecordPayload.QueueEnqueued(
                    queue = "steer",
                    runId = "run",
                    target = ProvisionedEntry("queued", EntryPayload.MessageValue(UserMessage("late"))),
                ),
            )
        assertReason(RecordLogCorruptionReason.QUEUE_AFTER_ABORT, listOf(start, abort, queue))
    }

    @Test
    fun `open run derives queues writes step and deferred suspension`() {
        val initial =
            ProvisionedEntry(
                "initial",
                EntryPayload.MessageValue(UserMessage("initial")),
            )
        val start =
            runStart(
                seq = 1,
                initialMessages = listOf(initial),
            )
        val steer =
            record(
                2,
                "steer",
                RecordPayload.QueueEnqueued(
                    queue = "steer",
                    runId = "run",
                    target = ProvisionedEntry("steer-message", EntryPayload.MessageValue(UserMessage("steer"))),
                ),
            )
        val followUp =
            record(
                3,
                "follow",
                RecordPayload.QueueEnqueued(
                    queue = "followUp",
                    runId = "run",
                    target = ProvisionedEntry("follow-message", EntryPayload.MessageValue(UserMessage("follow"))),
                ),
            )
        val write =
            record(
                4,
                "write",
                RecordPayload.WriteDeferred(
                    runId = "run",
                    target =
                        ProvisionedEntry(
                            "pending-write",
                            EntryPayload.Custom("note"),
                        ),
                ),
            )
        val attempt =
            record(
                5,
                "attempt",
                RecordPayload.StepAttempt(
                    runId = "run",
                    step = "assistant",
                    attempt = 1,
                    resultEntryId = "assistant",
                ),
            )
        val handle =
            DeferredHandle(
                provider = "faux",
                modelId = "faux-1",
                api = "faux",
                id = "deferred-id",
            )
        val deferred =
            entry(
                6,
                "assistant",
                EntryPayload.MessageValue(
                    fauxAssistantMessage(
                        content = emptyList(),
                        stopReason = StopReason.DEFERRED,
                        deferred = handle,
                    ),
                ),
            )
        val records = listOf(start, steer, followUp, write, attempt)
        val result =
            reduceLaneState(
                baseInput(
                    openOperations = listOf(start),
                    records = records,
                    entries = listOf(deferred),
                    ownEntries = listOf(deferred),
                    leafId = "assistant",
                ),
            )
        val operation = assertNotNull(result.laneState.operation)

        assertEquals(listOf("initial"), operation.missingInitialMessages.map(ProvisionedEntry::id))
        assertEquals(listOf("steer-message"), operation.pendingSteer.map(ProvisionedEntry::id))
        assertEquals(listOf("follow-message"), operation.pendingFollowUp.map(ProvisionedEntry::id))
        assertEquals(listOf("pending-write"), operation.pendingWrites.map(ProvisionedEntry::id))
        assertNull(operation.step)
        assertEquals(handle, operation.deferred)
        assertEquals(StopReason.DEFERRED, operation.newestOwn?.stopReason)
        assertFalse(operation.aborting)
    }

    @Test
    fun `abort clears active queues while preserving pending writes`() {
        val start = runStart(1)
        val steer =
            record(
                2,
                "steer",
                RecordPayload.QueueEnqueued(
                    queue = "steer",
                    runId = "run",
                    target = ProvisionedEntry("steer-message", EntryPayload.MessageValue(UserMessage("steer"))),
                ),
            )
        val write =
            record(
                3,
                "write",
                RecordPayload.WriteDeferred(
                    runId = "run",
                    target = ProvisionedEntry("pending-write", EntryPayload.Custom("note")),
                ),
            )
        val abort = record(4, "abort", RecordPayload.AbortRequested("run"))
        val result =
            reduceLaneState(
                baseInput(
                    openOperations = listOf(start),
                    records = listOf(start, steer, write, abort),
                ),
            )
        val operation = assertNotNull(result.laneState.operation)

        assertTrue(operation.aborting)
        assertEquals(emptyList(), operation.pendingSteer)
        assertEquals(listOf("pending-write"), operation.pendingWrites.map(ProvisionedEntry::id))
    }

    @Test
    fun `tool batch matches ordinal and terminal errors retain their source`() {
        val toolCall =
            ToolCall(
                id = "tool-call",
                name = "read",
                arguments = JsonObject(emptyMap()),
            )
        val assistant =
            entry(
                2,
                "assistant",
                EntryPayload.MessageValue(
                    assistant(
                        content = listOf(toolCall),
                        stopReason = StopReason.TOOL_USE,
                    ),
                ),
            )
        val resultEntry =
            entry(
                4,
                "tool-result",
                EntryPayload.MessageValue(
                    ToolResultMessage(
                        toolCallId = "tool-call",
                        toolName = "read",
                        content = listOf(TextContent("ok")),
                        isError = false,
                    ),
                ),
            )
        val start = runStart(1)
        val tool =
            record(
                3,
                "tool",
                RecordPayload.ToolStarted(
                    runId = "run",
                    assistantEntryId = "assistant",
                    toolIndex = 0,
                    toolCallId = "tool-call",
                    toolName = "read",
                    effectiveArgs = JsonObject(emptyMap()),
                    resultEntryId = "tool-result",
                    replay = "safe",
                ),
            )
        val reduced =
            reduceLaneState(
                baseInput(
                    openOperations = listOf(start),
                    records = listOf(start, tool),
                    entries = listOf(assistant, resultEntry),
                    ownEntries = listOf(assistant, resultEntry),
                    leafId = "tool-result",
                ),
            )
        val batch = assertNotNull(reduced.laneState.operation?.toolBatch)
        assertFalse(batch.unresolved)
        assertTrue(batch.calls.single().resultExists)

        val errorEntry =
            entry(
                3,
                "error",
                EntryPayload.MessageValue(
                    assistant(
                        content = emptyList(),
                        stopReason = StopReason.ERROR,
                    ),
                ),
            )
        val attempt =
            record(
                2,
                "attempt",
                RecordPayload.StepAttempt(
                    runId = "run",
                    step = "assistant",
                    attempt = 1,
                    resultEntryId = "error",
                ),
            )
        val failed =
            reduceLaneState(
                baseInput(
                    openOperations = listOf(start),
                    records = listOf(start, attempt),
                    entries = listOf(errorEntry),
                    ownEntries = listOf(errorEntry),
                ),
            )
        assertEquals(TerminalFailureState.Source.STEP, failed.terminalFailure?.source)
    }

    private fun assertReason(
        expected: RecordLogCorruptionReason,
        records: List<DurableRecord>,
    ) {
        val error =
            assertFailsWith<RecordLogCorruption> {
                validateRecordLog(
                    RecordLogSlice(
                        lane = "main",
                        openOperations = emptyList(),
                        records = records,
                        entries = emptyList(),
                    ),
                )
            }
        assertEquals(expected, error.reason)
    }

    private fun baseInput(
        openOperations: List<DurableRecord> = emptyList(),
        records: List<DurableRecord> = emptyList(),
        entries: List<DurableEntry> = emptyList(),
        leafId: String? = null,
        ownEntries: List<DurableEntry> = emptyList(),
        configurationEntries: List<DurableEntry> = emptyList(),
    ): LaneReductionInput =
        LaneReductionInput(
            lane = "main",
            openOperations = openOperations,
            records = records,
            entries = entries,
            leafId = leafId,
            ownEntries = ownEntries,
            configurationEntries = configurationEntries,
            defaults =
                EffectiveLaneConfiguration(
                    model = EffectiveLaneConfiguration.ModelIdentity("faux", "faux-1"),
                    thinkingLevel = "off",
                    activeToolNames = emptyList(),
                ),
        )

    private fun runStart(
        seq: Long,
        initialMessages: List<ProvisionedEntry> = emptyList(),
    ): DurableRecord =
        record(
            seq,
            "run",
            RecordPayload.OperationStarted(
                sourceLeafId = null,
                intent =
                    OperationIntent.Run(
                        originalPrompt = emptyList(),
                        initialMessages = initialMessages,
                    ),
            ),
        )

    private fun record(
        seq: Long,
        id: String,
        payload: RecordPayload,
    ): DurableRecord =
        DurableRecord(
            id = id,
            seq = seq,
            lane = "main",
            timestamp = seq,
            payload = payload,
        )

    private fun entry(
        seq: Long,
        id: String,
        payload: EntryPayload,
    ): DurableEntry =
        DurableEntry(
            id = id,
            seq = seq,
            parentId = null,
            timestamp = seq,
            payload = payload,
        )

    private fun assistant(
        content: List<works.earendil.pi.ai.ContentBlock>,
        stopReason: StopReason,
    ): AssistantMessage =
        AssistantMessage(
            content = content,
            api = "faux",
            provider = "faux",
            model = "faux-1",
            stopReason = stopReason,
        )
}
