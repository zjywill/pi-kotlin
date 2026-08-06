package works.earendil.pi.agent.harness

import kotlinx.serialization.json.JsonElement
import works.earendil.pi.agent.AgentThinkingLevel
import works.earendil.pi.agent.AgentTool
import works.earendil.pi.agent.QueueMode
import works.earendil.pi.agent.harness.session.DurableSession
import works.earendil.pi.agent.harness.session.DurableSessionTree
import works.earendil.pi.agent.harness.session.RecordQuery
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.Usage

class HarnessFault(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

class HarnessClosed : RuntimeException("AgentHarness was closed while the operation was active")

class HarnessNotImplemented(
    val operation: String,
) : RuntimeException("AgentHarness.$operation is not implemented yet")

interface HarnessTool : AgentTool {
    val replay: String
        get() = "never"
}

data class HarnessSkill(
    val name: String,
    val description: String,
    val content: String,
    val filePath: String,
    val disableModelInvocation: Boolean = false,
)

data class HarnessPromptTemplate(
    val name: String,
    val description: String? = null,
    val content: String,
)

data class HarnessResources(
    val skills: List<HarnessSkill>? = null,
    val promptTemplates: List<HarnessPromptTemplate>? = null,
) {
    fun defensiveCopy(): HarnessResources =
        HarnessResources(
            skills = skills?.map(HarnessSkill::copy),
            promptTemplates = promptTemplates?.map(HarnessPromptTemplate::copy),
        )
}

data class HarnessRetryPolicy(
    val enabled: Boolean = false,
    val maxRetries: Int = 0,
    val baseDelayMs: Long = 1_000,
)

data class HarnessCompactionSettings(
    val enabled: Boolean = true,
    val reserveTokens: Int = 16_384,
    val keepRecentTokens: Int = 20_000,
)

data class AgentHarnessOptions(
    val session: DurableSession<*>,
    val models: Models,
    val model: Model,
    val thinkingLevel: AgentThinkingLevel = AgentThinkingLevel.OFF,
    val activeToolNames: List<String>? = null,
    val tools: List<HarnessTool> = emptyList(),
    val resources: HarnessResources = HarnessResources(),
    val streamOptions: SimpleStreamOptions = SimpleStreamOptions(),
    val retry: HarnessRetryPolicy = HarnessRetryPolicy(),
    val compaction: HarnessCompactionSettings = HarnessCompactionSettings(),
    val steeringMode: QueueMode = QueueMode.ONE_AT_A_TIME,
    val followUpMode: QueueMode = QueueMode.ONE_AT_A_TIME,
)

data class SuspendedOperation(
    val lane: String,
    val kind: String,
    val id: String,
    val startedAt: Long,
    val reason: String,
)

data class AgentHarnessCreation(
    val harness: AgentHarness,
    val suspended: List<SuspendedOperation>,
)

data class LaneInfo(
    val name: String,
    val leafId: String?,
    val operation: Operation? = null,
) {
    data class Operation(
        val id: String,
        val kind: String,
        val status: String,
    )
}

fun interface HarnessRegistration {
    fun close()
}

class UnavailableRegistry internal constructor(
    private val operation: String,
    private val isClosed: () -> Boolean,
) {
    fun on(
        name: String,
        handler: suspend (Any?) -> Any?,
        id: String? = null,
    ): HarnessRegistration {
        name.length
        handler.hashCode()
        id?.length
        if (isClosed()) {
            throw HarnessClosed()
        }
        throw HarnessNotImplemented(operation)
    }
}

class AgentHarness private constructor(
    options: AgentHarnessOptions,
) {
    val name: String = "main"
    val session: DurableSessionTree = options.session
    val hooks = UnavailableRegistry("hooks.on", ::isClosed)
    val events = UnavailableRegistry("events.on", ::isClosed)

    private var closed = false
    private var model = options.model
    private var thinkingLevel = options.thinkingLevel
    private var activeToolNames =
        (options.activeToolNames ?: options.tools.map(HarnessTool::name)).toList()
    private var tools = options.tools.toList()
    private var resources = options.resources.defensiveCopy()
    private var streamOptions = options.streamOptions.copy()
    private var retryPolicy = options.retry.copy()
    private var compactionSettings = options.compaction.copy()
    private var steeringMode = options.steeringMode
    private var followUpMode = options.followUpMode

    suspend fun getLeafId(): String? = session.getLeafId()

    suspend fun prompt(input: Any, images: List<Any> = emptyList()): Nothing {
        input.hashCode()
        images.size
        return unavailable("prompt")
    }

    suspend fun skill(
        name: String,
        additionalInstructions: String? = null,
    ): Nothing {
        name.length
        additionalInstructions?.length
        return unavailable("skill")
    }

    suspend fun promptFromTemplate(
        name: String,
        arguments: List<String> = emptyList(),
    ): Nothing {
        name.length
        arguments.size
        return unavailable("promptFromTemplate")
    }

    suspend fun compact(customInstructions: String? = null): Nothing {
        customInstructions?.length
        return unavailable("compact")
    }

    suspend fun navigateTree(
        targetId: String?,
        summarize: Boolean = false,
    ): Nothing {
        targetId?.length
        summarize.hashCode()
        return unavailable("navigateTree")
    }

    suspend fun resume(): Nothing = unavailable("resume")

    suspend fun abort(): Nothing = unavailable("abort")

    suspend fun steer(input: Any): Nothing {
        input.hashCode()
        return unavailable("steer")
    }

    suspend fun followUp(input: Any): Nothing {
        input.hashCode()
        return unavailable("followUp")
    }

    suspend fun nextRun(input: Any): Nothing {
        input.hashCode()
        return unavailable("nextRun")
    }

    suspend fun cancelQueued(entryId: String): Nothing {
        entryId.length
        return unavailable("cancelQueued")
    }

    suspend fun recordUsage(
        usage: Usage,
        entryId: String? = null,
        details: JsonElement? = null,
    ): Nothing {
        usage.totalTokens
        entryId?.length
        details?.hashCode()
        return unavailable("recordUsage")
    }

    suspend fun waitForIdle(): Nothing = unavailable("waitForIdle")

    suspend fun runWhenIdle(callback: suspend () -> Unit): Nothing {
        callback.hashCode()
        return unavailable("runWhenIdle")
    }

    suspend fun peekAction(): Nothing = unavailable("peekAction")

    suspend fun executeAction(): Nothing = unavailable("executeAction")

    suspend fun runToCompletion(): Nothing = unavailable("runToCompletion")

    suspend fun watch(): Nothing = unavailable("watch")

    suspend fun lane(name: String): Nothing {
        name.length
        return unavailable("lane")
    }

    suspend fun createLane(
        name: String,
        at: String?,
    ): Nothing {
        name.length
        at?.length
        return unavailable("createLane")
    }

    suspend fun lanes(): Nothing = unavailable("lanes")

    suspend fun watchSession(): Nothing = unavailable("watchSession")

    suspend fun getModel(): Model = model

    suspend fun setModel(value: Model) {
        model = value
    }

    suspend fun getThinkingLevel(): AgentThinkingLevel = thinkingLevel

    suspend fun setThinkingLevel(value: AgentThinkingLevel) {
        thinkingLevel = value
    }

    suspend fun getActiveTools(): List<String> = activeToolNames.toList()

    suspend fun setActiveTools(names: List<String>) {
        activeToolNames = names.toList()
    }

    suspend fun getTools(): List<HarnessTool> = tools.toList()

    suspend fun setTools(
        values: List<HarnessTool>,
        activeNames: List<String>? = null,
    ) {
        tools = values.toList()
        activeToolNames = (activeNames ?: values.map(HarnessTool::name)).toList()
    }

    suspend fun getResources(): HarnessResources = resources.defensiveCopy()

    suspend fun setResources(value: HarnessResources) {
        resources = value.defensiveCopy()
    }

    suspend fun getStreamOptions(): SimpleStreamOptions = streamOptions.copy()

    suspend fun setStreamOptions(value: SimpleStreamOptions) {
        streamOptions = value.copy()
    }

    suspend fun getRetryPolicy(): HarnessRetryPolicy = retryPolicy.copy()

    suspend fun setRetryPolicy(value: HarnessRetryPolicy) {
        retryPolicy = value.copy()
    }

    suspend fun getCompactionSettings(): HarnessCompactionSettings = compactionSettings.copy()

    suspend fun setCompactionSettings(value: HarnessCompactionSettings) {
        compactionSettings = value.copy()
    }

    suspend fun getSteeringMode(): QueueMode = steeringMode

    suspend fun setSteeringMode(value: QueueMode) {
        steeringMode = value
    }

    suspend fun getFollowUpMode(): QueueMode = followUpMode

    suspend fun setFollowUpMode(value: QueueMode) {
        followUpMode = value
    }

    suspend fun close() {
        closed = true
    }

    private fun isClosed(): Boolean = closed

    private fun unavailable(operation: String): Nothing {
        if (closed) {
            throw HarnessClosed()
        }
        throw HarnessNotImplemented(operation)
    }

    companion object {
        suspend fun create(options: AgentHarnessOptions): AgentHarnessCreation {
            if (options.session.findRecords(RecordQuery(limit = 1)).isNotEmpty()) {
                throw HarnessNotImplemented("create.restore")
            }
            options.models.hashCode()
            return AgentHarnessCreation(
                harness = AgentHarness(options),
                suspended = emptyList(),
            )
        }
    }
}
