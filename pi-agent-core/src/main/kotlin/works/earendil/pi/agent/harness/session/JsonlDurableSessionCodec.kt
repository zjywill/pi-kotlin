package works.earendil.pi.agent.harness.session

import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.Usage

data class JsonlV4Header(
    val id: String,
    val createdAt: Long,
    val cwd: String,
    val parentSessionId: String? = null,
    val legacyParentSessionPath: String? = null,
    val metadata: JsonObject? = null,
)

class JsonlInvalidFileException(
    val path: Path,
    val line: Int,
    message: String,
    cause: Throwable? = null,
) : DurableSessionException(
        DurableSessionErrorCode.INVALID_ENTRY,
        "Invalid session file $path at line $line: $message",
        cause,
    )

fun encodeJsonlHeader(header: JsonlV4Header): String =
    buildJsonObject {
        put("kind", "header")
        put("version", 4)
        put("id", header.id)
        put("createdAt", header.createdAt)
        put("cwd", header.cwd)
        header.parentSessionId?.let { put("parentSessionId", it) }
        header.legacyParentSessionPath?.let { put("legacyParentSessionPath", it) }
        header.metadata?.let { put("metadata", it) }
    }.toString() + "\n"

fun parseJsonlHeader(
    line: String,
    path: Path,
): JsonlV4Header {
    val value = parseObject(line, path, 1)
    if (value.string("kind", path, 1) != "header") {
        invalidFile(path, 1, "is not a header")
    }
    if (value.long("version", path, 1) != 4L) {
        invalidFile(path, 1, "has unsupported session version")
    }
    val parentSessionId = value.optionalString("parentSessionId", path, 1)
    val legacyParentSessionPath = value.optionalString("legacyParentSessionPath", path, 1)
    if (parentSessionId != null && legacyParentSessionPath != null) {
        invalidFile(path, 1, "has both parentSessionId and legacyParentSessionPath")
    }
    val metadata =
        value["metadata"]?.let { element ->
            element as? JsonObject ?: invalidFile(path, 1, "has invalid metadata")
        }
    return JsonlV4Header(
        id = value.string("id", path, 1),
        createdAt = value.timestamp("createdAt", path, 1),
        cwd = value.string("cwd", path, 1),
        parentSessionId = parentSessionId,
        legacyParentSessionPath = legacyParentSessionPath,
        metadata = metadata,
    )
}

fun encodeJsonlMutation(mutation: DurableMutation): String =
    when (mutation) {
        is DurableMutation.Entry ->
            buildJsonObject {
                put("kind", "entry")
                mutation.lane?.let { put("lane", it) }
                put("id", mutation.entry.id)
                put("seq", mutation.entry.seq)
                put("parentId", mutation.entry.parentId)
                put("timestamp", mutation.entry.timestamp)
                entryPayloadFields(mutation.entry.payload).forEach(::put)
            }

        is DurableMutation.Record ->
            buildJsonObject {
                put("kind", "record")
                put("id", mutation.record.id)
                put("seq", mutation.record.seq)
                put("lane", mutation.record.lane)
                put("timestamp", mutation.record.timestamp)
                recordPayloadFields(mutation.record.payload).forEach(::put)
            }

        is DurableMutation.Lane ->
            buildJsonObject {
                put("kind", "lane")
                put("seq", mutation.seq)
                put("lane", mutation.lane)
                put("leafId", mutation.leafId)
            }

        is DurableMutation.Name ->
            buildJsonObject {
                put("kind", "fact")
                put("seq", mutation.seq)
                put("fact", "name")
                mutation.name?.let { put("name", it) }
            }

        is DurableMutation.Label ->
            buildJsonObject {
                put("kind", "fact")
                put("seq", mutation.seq)
                put("fact", "label")
                put("targetId", mutation.targetId)
                mutation.label?.let { put("label", it) }
            }
    }.toString() + "\n"

fun parseJsonlMutation(
    line: String,
    path: Path,
    lineNumber: Int,
): DurableMutation {
    val value = parseObject(line, path, lineNumber)
    val seq = value.sequence(path, lineNumber)
    return when (value.string("kind", path, lineNumber)) {
        "entry" -> {
            val lane = value.optionalString("lane", path, lineNumber)
            val entry =
                DurableEntry(
                    id = value.string("id", path, lineNumber),
                    seq = seq,
                    parentId = value.nullableString("parentId", path, lineNumber),
                    timestamp = value.timestamp("timestamp", path, lineNumber),
                    payload = parseEntryPayload(value, path, lineNumber),
                )
            DurableMutation.Entry(lane, entry)
        }

        "record" -> {
            val record =
                DurableRecord(
                    id = value.string("id", path, lineNumber),
                    seq = seq,
                    lane = value.string("lane", path, lineNumber),
                    timestamp = value.timestamp("timestamp", path, lineNumber),
                    payload = parseRecordPayload(value, path, lineNumber),
                )
            DurableMutation.Record(record)
        }

        "lane" ->
            DurableMutation.Lane(
                seq = seq,
                lane = value.string("lane", path, lineNumber),
                leafId = value.nullableString("leafId", path, lineNumber),
            )

        "fact" ->
            when (value.string("fact", path, lineNumber)) {
                "name" ->
                    DurableMutation.Name(
                        seq = seq,
                        name = value.optionalString("name", path, lineNumber),
                    )

                "label" ->
                    DurableMutation.Label(
                        seq = seq,
                        targetId = value.string("targetId", path, lineNumber),
                        label = value.optionalString("label", path, lineNumber),
                    )

                else -> invalidFile(path, lineNumber, "has unknown fact type")
            }

        else -> invalidFile(path, lineNumber, "has unknown mutation kind")
    }
}

private fun entryPayloadFields(payload: EntryPayload): JsonObject =
    buildJsonObject {
        put("type", payload.type)
        when (payload) {
            is EntryPayload.MessageValue -> {
                put(
                    "message",
                    durableSessionJson.encodeToJsonElement(Message.serializer(), payload.message),
                )
                if (payload.terminate) {
                    put("terminate", true)
                }
            }

            is EntryPayload.ModelChange -> {
                put("provider", payload.provider)
                put("modelId", payload.modelId)
            }

            is EntryPayload.ThinkingLevelChange -> put("thinkingLevel", payload.thinkingLevel)
            is EntryPayload.ActiveToolsChange ->
                put(
                    "activeToolNames",
                    JsonArray(payload.activeToolNames.map(::JsonPrimitive)),
                )

            is EntryPayload.Compaction -> {
                put("summary", payload.summary)
                put(
                    "retainedTail",
                    durableSessionJson.encodeToJsonElement(
                        ListSerializer(Message.serializer()),
                        payload.retainedTail,
                    ),
                )
                put("tokensBefore", payload.tokensBefore)
                payload.details?.let { put("details", it) }
                payload.usage?.let {
                    put("usage", durableSessionJson.encodeToJsonElement(Usage.serializer(), it))
                }
            }

            is EntryPayload.BranchSummary -> {
                put("fromId", payload.fromId)
                put("summary", payload.summary)
                payload.details?.let { put("details", it) }
                payload.usage?.let {
                    put("usage", durableSessionJson.encodeToJsonElement(Usage.serializer(), it))
                }
            }

            is EntryPayload.Custom -> {
                put("customType", payload.customType)
                payload.data?.let { put("data", it) }
            }
        }
    }

private fun provisionedEntryJson(entry: ProvisionedEntry): JsonObject =
    buildJsonObject {
        put("id", entry.id)
        entryPayloadFields(entry.payload).forEach(::put)
    }

private fun parseProvisionedEntry(
    value: JsonObject,
    path: Path,
    line: Int,
): ProvisionedEntry =
    ProvisionedEntry(
        id = value.string("id", path, line),
        payload = parseEntryPayload(value, path, line),
    )

private fun recordPayloadFields(payload: RecordPayload): JsonObject =
    buildJsonObject {
        put("type", payload.type)
        when (payload) {
            is RecordPayload.OperationStarted -> {
                put("sourceLeafId", payload.sourceLeafId)
                put("intent", operationIntentJson(payload.intent))
            }

            is RecordPayload.AbortRequested -> put("runId", payload.runId)
            is RecordPayload.OperationFinished -> {
                put("runId", payload.runId)
                put("outcome", payload.outcome)
                payload.error?.let {
                    put(
                        "error",
                        buildJsonObject {
                            put("code", it.code)
                            put("message", it.message)
                        },
                    )
                }
            }

            is RecordPayload.StepAttempt -> {
                put("runId", payload.runId)
                put("step", payload.step)
                put("attempt", payload.attempt)
                put("resultEntryId", payload.resultEntryId)
                payload.compactionReason?.let { put("compactionReason", it) }
            }

            is RecordPayload.ToolStarted -> {
                put("runId", payload.runId)
                put("assistantEntryId", payload.assistantEntryId)
                put("toolIndex", payload.toolIndex)
                put("toolCallId", payload.toolCallId)
                put("toolName", payload.toolName)
                put("effectiveArgs", payload.effectiveArgs)
                put("resultEntryId", payload.resultEntryId)
                put("replay", payload.replay)
            }

            is RecordPayload.QueueEnqueued -> {
                put("queue", payload.queue)
                payload.runId?.let { put("runId", it) }
                put("target", provisionedEntryJson(payload.target))
            }

            is RecordPayload.QueueCancelled -> {
                payload.runId?.let { put("runId", it) }
                put("entryId", payload.entryId)
            }

            is RecordPayload.WriteDeferred -> {
                put("runId", payload.runId)
                put("target", provisionedEntryJson(payload.target))
            }

            is RecordPayload.UsageValue -> {
                put("usage", durableSessionJson.encodeToJsonElement(Usage.serializer(), payload.usage))
                put("cause", payload.cause)
                payload.runId?.let { put("runId", it) }
                payload.entryId?.let { put("entryId", it) }
                payload.attempt?.let { put("attempt", it) }
                payload.stopReason?.let { put("stopReason", stopReasonValue(it)) }
                payload.toolCallId?.let { put("toolCallId", it) }
                payload.details?.let { put("details", it) }
            }
        }
    }

private fun operationIntentJson(intent: OperationIntent): JsonObject =
    buildJsonObject {
        put("kind", intent.kind)
        when (intent) {
            is OperationIntent.Run -> {
                put(
                    "originalPrompt",
                    durableSessionJson.encodeToJsonElement(
                        ListSerializer(Message.serializer()),
                        intent.originalPrompt,
                    ),
                )
                put(
                    "initialMessages",
                    JsonArray(intent.initialMessages.map(::provisionedEntryJson)),
                )
                intent.systemPromptOverride?.let { put("systemPromptOverride", it) }
                if (intent.resumeData.isNotEmpty()) {
                    put("resumeData", JsonObject(intent.resumeData))
                }
            }

            is OperationIntent.Compaction -> {
                intent.customInstructions?.let { put("customInstructions", it) }
                put("resultEntryId", intent.resultEntryId)
            }

            is OperationIntent.Navigation -> {
                put("targetId", intent.targetId)
                put("summarize", intent.summarize)
                intent.customInstructions?.let { put("customInstructions", it) }
                intent.label?.let { put("label", it) }
                intent.summaryEntryId?.let { put("summaryEntryId", it) }
            }
        }
    }

private fun parseEntryPayload(
    value: JsonObject,
    path: Path,
    line: Int,
): EntryPayload =
    when (val type = value.string("type", path, line)) {
        "message" ->
            EntryPayload.MessageValue(
                message =
                    decodeField(
                        value,
                        "message",
                        Message.serializer(),
                        path,
                        line,
                    ),
                terminate = value["terminate"]?.jsonPrimitive?.booleanOrNull == true,
            )

        "model_change" ->
            EntryPayload.ModelChange(
                provider = value.string("provider", path, line),
                modelId = value.string("modelId", path, line),
            )

        "thinking_level_change" ->
            EntryPayload.ThinkingLevelChange(
                thinkingLevel = value.string("thinkingLevel", path, line),
            )

        "active_tools_change" ->
            EntryPayload.ActiveToolsChange(
                activeToolNames =
                    value.array("activeToolNames", path, line).map { element ->
                        element.jsonPrimitive.contentOrNull
                            ?: invalidFile(path, line, "has invalid activeToolNames")
                    },
            )

        "compaction" ->
            EntryPayload.Compaction(
                summary = value.string("summary", path, line),
                retainedTail =
                    decodeField(
                        value,
                        "retainedTail",
                        ListSerializer(Message.serializer()),
                        path,
                        line,
                    ),
                tokensBefore = value.int("tokensBefore", path, line),
                details = value["details"],
                usage = value["usage"]?.let {
                    decodeElement(it, Usage.serializer(), path, line, "usage")
                },
            )

        "branch_summary" ->
            EntryPayload.BranchSummary(
                fromId = value.string("fromId", path, line),
                summary = value.string("summary", path, line),
                details = value["details"],
                usage = value["usage"]?.let {
                    decodeElement(it, Usage.serializer(), path, line, "usage")
                },
            )

        "custom" ->
            EntryPayload.Custom(
                customType = value.string("customType", path, line),
                data = value["data"],
            )

        else -> invalidFile(path, line, "has unknown entry type $type")
    }

private fun parseRecordPayload(
    value: JsonObject,
    path: Path,
    line: Int,
): RecordPayload =
    when (val type = value.string("type", path, line)) {
        "operation_started" ->
            RecordPayload.OperationStarted(
                sourceLeafId = value.nullableString("sourceLeafId", path, line),
                intent =
                    parseOperationIntent(
                        value.objectValue("intent", path, line),
                        path,
                        line,
                    ),
            )

        "abort_requested" ->
            RecordPayload.AbortRequested(
                runId = value.string("runId", path, line),
            )

        "operation_finished" ->
            RecordPayload.OperationFinished(
                runId = value.string("runId", path, line),
                outcome = value.string("outcome", path, line),
                error =
                    value["error"]?.let { element ->
                        val error = element as? JsonObject ?: invalidFile(path, line, "has invalid error")
                        OperationError(
                            code = error.string("code", path, line),
                            message = error.string("message", path, line),
                        )
                    },
            )

        "step_attempt" ->
            RecordPayload.StepAttempt(
                runId = value.string("runId", path, line),
                step = value.string("step", path, line),
                attempt = value.int("attempt", path, line),
                resultEntryId = value.string("resultEntryId", path, line),
                compactionReason = value.optionalString("compactionReason", path, line),
            )

        "tool_started" ->
            RecordPayload.ToolStarted(
                runId = value.string("runId", path, line),
                assistantEntryId = value.string("assistantEntryId", path, line),
                toolIndex = value.int("toolIndex", path, line),
                toolCallId = value.string("toolCallId", path, line),
                toolName = value.string("toolName", path, line),
                effectiveArgs = value.objectValue("effectiveArgs", path, line),
                resultEntryId = value.string("resultEntryId", path, line),
                replay = value.string("replay", path, line),
            )

        "queue_enqueued" ->
            RecordPayload.QueueEnqueued(
                queue = value.string("queue", path, line),
                runId = value.optionalString("runId", path, line),
                target =
                    parseProvisionedEntry(
                        value.objectValue("target", path, line),
                        path,
                        line,
                    ),
            )

        "queue_cancelled" ->
            RecordPayload.QueueCancelled(
                runId = value.optionalString("runId", path, line),
                entryId = value.string("entryId", path, line),
            )

        "write_deferred" ->
            RecordPayload.WriteDeferred(
                runId = value.string("runId", path, line),
                target =
                    parseProvisionedEntry(
                        value.objectValue("target", path, line),
                        path,
                        line,
                    ),
            )

        "usage" ->
            RecordPayload.UsageValue(
                usage = decodeField(value, "usage", Usage.serializer(), path, line),
                cause = value.string("cause", path, line),
                runId = value.optionalString("runId", path, line),
                entryId = value.optionalString("entryId", path, line),
                attempt = value.optionalInt("attempt", path, line),
                stopReason =
                    value.optionalString("stopReason", path, line)?.let(::parseStopReason),
                toolCallId = value.optionalString("toolCallId", path, line),
                details = value["details"],
            )

        else -> invalidFile(path, line, "has unknown record type $type")
    }

private fun parseOperationIntent(
    value: JsonObject,
    path: Path,
    line: Int,
): OperationIntent =
    when (val kind = value.string("kind", path, line)) {
        "run" ->
            OperationIntent.Run(
                originalPrompt =
                    decodeField(
                        value,
                        "originalPrompt",
                        ListSerializer(Message.serializer()),
                        path,
                        line,
                    ),
                initialMessages =
                    value.array("initialMessages", path, line).map { element ->
                        parseProvisionedEntry(
                            element as? JsonObject
                                ?: invalidFile(path, line, "has invalid initialMessages"),
                            path,
                            line,
                        )
                    },
                systemPromptOverride = value.optionalString("systemPromptOverride", path, line),
                resumeData =
                    value["resumeData"]
                        ?.let { it as? JsonObject ?: invalidFile(path, line, "has invalid resumeData") }
                        ?.toMap()
                        .orEmpty(),
            )

        "compaction" ->
            OperationIntent.Compaction(
                customInstructions = value.optionalString("customInstructions", path, line),
                resultEntryId = value.string("resultEntryId", path, line),
            )

        "navigation" ->
            OperationIntent.Navigation(
                targetId = value.nullableString("targetId", path, line),
                summarize =
                    value["summarize"]?.jsonPrimitive?.booleanOrNull
                        ?: invalidFile(path, line, "has invalid summarize"),
                customInstructions = value.optionalString("customInstructions", path, line),
                label = value.optionalString("label", path, line),
                summaryEntryId = value.optionalString("summaryEntryId", path, line),
            )

        else -> invalidFile(path, line, "has unknown operation kind $kind")
    }

private fun stopReasonValue(reason: StopReason): String =
    when (reason) {
        StopReason.PENDING -> "pending"
        StopReason.STOP -> "stop"
        StopReason.LENGTH -> "length"
        StopReason.TOOL_USE -> "toolUse"
        StopReason.ERROR -> "error"
        StopReason.ABORTED -> "aborted"
        StopReason.DEFERRED -> "deferred"
    }

private fun parseStopReason(value: String): StopReason =
    when (value) {
        "pending" -> StopReason.PENDING
        "stop" -> StopReason.STOP
        "length" -> StopReason.LENGTH
        "toolUse" -> StopReason.TOOL_USE
        "error" -> StopReason.ERROR
        "aborted" -> StopReason.ABORTED
        "deferred" -> StopReason.DEFERRED
        else ->
            throw DurableSessionException(
                DurableSessionErrorCode.INVALID_ENTRY,
                "Unknown stop reason: $value",
            )
    }

private fun parseObject(
    line: String,
    path: Path,
    lineNumber: Int,
): JsonObject {
    val element =
        try {
            durableSessionJson.parseToJsonElement(line)
        } catch (error: SerializationException) {
            throw JsonlInvalidFileException(
                path,
                lineNumber,
                "is not valid JSON",
                error,
            )
        } catch (error: IllegalArgumentException) {
            throw JsonlInvalidFileException(
                path,
                lineNumber,
                "is not valid JSON",
                error,
            )
        }
    return element as? JsonObject ?: invalidFile(path, lineNumber, "is not a JSON object")
}

private fun JsonObject.string(
    name: String,
    path: Path,
    line: Int,
): String =
    this[name]?.jsonPrimitive?.contentOrNull
        ?: invalidFile(path, line, "has invalid $name")

private fun JsonObject.optionalString(
    name: String,
    path: Path,
    line: Int,
): String? {
    val value = this[name] ?: return null
    if (value is JsonNull) {
        invalidFile(path, line, "has invalid $name")
    }
    return value.jsonPrimitive.contentOrNull
        ?: invalidFile(path, line, "has invalid $name")
}

private fun JsonObject.nullableString(
    name: String,
    path: Path,
    line: Int,
): String? {
    val value = this[name] ?: invalidFile(path, line, "has invalid $name")
    if (value is JsonNull) {
        return null
    }
    return value.jsonPrimitive.contentOrNull
        ?: invalidFile(path, line, "has invalid $name")
}

private fun JsonObject.long(
    name: String,
    path: Path,
    line: Int,
): Long =
    this[name]?.jsonPrimitive?.longOrNull
        ?: invalidFile(path, line, "has invalid $name")

private fun JsonObject.int(
    name: String,
    path: Path,
    line: Int,
): Int {
    val value = long(name, path, line)
    if (value !in Int.MIN_VALUE..Int.MAX_VALUE) {
        invalidFile(path, line, "has invalid $name")
    }
    return value.toInt()
}

private fun JsonObject.optionalInt(
    name: String,
    path: Path,
    line: Int,
): Int? {
    if (name !in this) {
        return null
    }
    return int(name, path, line)
}

private fun JsonObject.sequence(
    path: Path,
    line: Int,
): Long =
    long("seq", path, line).takeIf { it > 0 }
        ?: invalidFile(path, line, "has invalid seq")

private fun JsonObject.timestamp(
    name: String,
    path: Path,
    line: Int,
): Long =
    long(name, path, line).takeIf { it >= 0 }
        ?: invalidFile(path, line, "has invalid $name")

private fun JsonObject.array(
    name: String,
    path: Path,
    line: Int,
): JsonArray =
    this[name]?.let { it as? JsonArray }
        ?: invalidFile(path, line, "has invalid $name")

private fun JsonObject.objectValue(
    name: String,
    path: Path,
    line: Int,
): JsonObject =
    this[name]?.let { it as? JsonObject }
        ?: invalidFile(path, line, "has invalid $name")

private fun <T> decodeField(
    value: JsonObject,
    name: String,
    serializer: kotlinx.serialization.KSerializer<T>,
    path: Path,
    line: Int,
): T =
    decodeElement(
        value[name] ?: invalidFile(path, line, "has invalid $name"),
        serializer,
        path,
        line,
        name,
    )

private fun <T> decodeElement(
    value: JsonElement,
    serializer: kotlinx.serialization.KSerializer<T>,
    path: Path,
    line: Int,
    name: String,
): T =
    try {
        durableSessionJson.decodeFromJsonElement(serializer, value)
    } catch (error: SerializationException) {
        throw JsonlInvalidFileException(path, line, "has invalid $name")
    } catch (error: IllegalArgumentException) {
        throw JsonlInvalidFileException(path, line, "has invalid $name")
    }

private fun invalidFile(
    path: Path,
    line: Int,
    message: String,
): Nothing = throw JsonlInvalidFileException(path, line, message)
