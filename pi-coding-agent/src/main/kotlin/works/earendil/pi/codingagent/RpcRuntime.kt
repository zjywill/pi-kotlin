package works.earendil.pi.codingagent

import java.io.BufferedReader
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.agent.Agent
import works.earendil.pi.agent.AgentEvent
import works.earendil.pi.agent.AgentInitialState
import works.earendil.pi.agent.AgentOptions
import works.earendil.pi.agent.AgentThinkingLevel
import works.earendil.pi.agent.AgentTool
import works.earendil.pi.agent.QueueMode
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.BashExecutionMessage
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.CustomMessage
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelThinkingLevel
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StreamFunction
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.ai.contentText
import works.earendil.pi.codingagent.session.NewSessionOptions
import works.earendil.pi.codingagent.session.SessionEntry
import works.earendil.pi.codingagent.session.SessionManager
import works.earendil.pi.codingagent.session.SessionMessageEntry
import works.earendil.pi.codingagent.session.SessionTreeNode
import works.earendil.pi.codingagent.session.encodeEntry
import works.earendil.pi.codingagent.tools.truncateTail
import works.earendil.pi.tui.InputListenerResult
import kotlin.math.max

private const val DIRECT_EXTENSION_UI_CANCEL_WAIT_MS = 1_000L
private val HTML_TEMPLATE_RENDERED_TOOLS = setOf("bash", "read", "write", "edit", "ls")
private val NON_RETRYABLE_LIMIT_PATTERN =
    Regex(
        listOf(
            "GoUsageLimitError",
            "FreeUsageLimitError",
            "Monthly usage limit reached",
            "available balance",
            "insufficient_quota",
            "out of budget",
            "quota exceeded",
            "billing",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )
private val RETRYABLE_ERROR_PATTERN =
    Regex(
        listOf(
            "overloaded",
            "rate.?limit",
            "too many requests",
            "429",
            "500",
            "502",
            "503",
            "504",
            "524",
            "service.?unavailable",
            "server.?error",
            "internal.?error",
            "provider.?returned.?error",
            "network.?error",
            "connection.?error",
            "connection.?refused",
            "connection.?lost",
            "other side closed",
            "fetch failed",
            "getaddrinfo",
            "ENOTFOUND",
            "EAI_AGAIN",
            "upstream.?connect",
            "reset before headers",
            "socket hang up",
            "socket connection was closed",
            "timed? out",
            "timeout",
            "terminated",
            "websocket.?closed",
            "websocket.?error",
            "ended without",
            "stream ended before message_stop",
            "stream ended before a terminal response event",
            "http2 request did not get a response",
            "retry delay",
            "you can retry your request",
            "try your request again",
            "please retry your request",
            "ResourceExhausted",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )
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

data class RpcRuntimeOptions(
    val cwd: Path = Path.of("").toAbsolutePath().normalize(),
    val agentDir: Path = defaultAgentDirectory(),
    val sessionDir: Path? = null,
    val noSession: Boolean = false,
    val sessionId: String? = null,
    val sessionPath: Path? = null,
    val forkPath: Path? = null,
    val continueRecent: Boolean = false,
    val provider: String? = null,
    val model: String? = null,
    val modelPatterns: List<String>? = null,
    val apiKey: String? = null,
    val systemPrompt: String? = null,
    val appendSystemPrompt: List<String> = emptyList(),
    val noContextFiles: Boolean = false,
    val skillPaths: List<String> = emptyList(),
    val noSkills: Boolean = false,
    val promptTemplatePaths: List<String> = emptyList(),
    val noPromptTemplates: Boolean = false,
    val themePaths: List<String> = emptyList(),
    val noThemes: Boolean = false,
    val projectTrusted: Boolean? = null,
    val extensionPaths: List<String> = emptyList(),
    val noExtensions: Boolean = false,
    val offline: Boolean = false,
    val extensionFlagValues: Map<String, Any> = emptyMap(),
    val extensionMode: ExtensionMode = ExtensionMode.RPC,
    val noTools: Boolean = false,
    val noBuiltinTools: Boolean = false,
    val tools: List<String>? = null,
    val excludeTools: List<String>? = null,
    val thinking: works.earendil.pi.codingagent.AgentThinkingLevel? = null,
    val projectTrustPrompt: ((Path, List<String>) -> Int?)? = null,
    val extensionUiHandler: ((JsonObject) -> JsonObject)? = null,
    val cancellableExtensionUiHandler: CancellableExtensionUiHandler? = null,
    val extensionRenderOptionsProvider: (() -> ExtensionRenderOptions)? = null,
)

fun interface CancellableExtensionUiHandler {
    fun handle(
        request: JsonObject,
        cancellation: ExtensionUiCancellation,
    ): JsonObject
}

class ExtensionUiCancellation internal constructor() {
    private val cancelled = AtomicBoolean(false)
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()

    val isCancelled: Boolean
        get() = cancelled.get()

    fun onCancellation(callback: () -> Unit): AutoCloseable {
        val invoked = AtomicBoolean(false)
        val wrapped = {
            if (invoked.compareAndSet(false, true)) {
                callback()
            }
        }
        if (cancelled.get()) {
            wrapped()
            return AutoCloseable {}
        }
        callbacks += wrapped
        if (cancelled.get() && callbacks.remove(wrapped)) {
            wrapped()
        }
        return AutoCloseable { callbacks.remove(wrapped) }
    }

    internal fun cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return
        }
        callbacks.toList().forEach { callback ->
            runCatching(callback)
        }
        callbacks.clear()
    }
}

private data class PendingDirectExtensionUiRequest(
    val cancellation: ExtensionUiCancellation = ExtensionUiCancellation(),
    val finished: CountDownLatch = CountDownLatch(1),
)

class RpcRuntime(
    private val models: Models,
    private val options: RpcRuntimeOptions = RpcRuntimeOptions(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listeners = CopyOnWriteArrayList<(JsonObject) -> Unit>()
    private val pendingEvents = mutableListOf<JsonObject>()
    private var initializingExtensions = true
    private var sessionManager = createInitialSession()
    private var steeringMode = QueueMode.ONE_AT_A_TIME
    private var followUpMode = QueueMode.ONE_AT_A_TIME
    private var autoCompactionEnabled = true
    private var imageAutoResize = true
    private var autoRetryEnabled = true
    private var retryMaxAttempts = 3
    private var retryBaseDelayMs = 2_000L
    private var retryAttempt = 0
    private var retryDelayJob: Job? = null
    private var overflowRecoveryAttempted = false
    private var toolOutputExpandedOverride: Boolean? = null
    private val compacting = AtomicBoolean(false)
    private var compactionSettings = DEFAULT_COMPACTION_SETTINGS
    private val steeringMessages = CopyOnWriteArrayList<String>()
    private val followUpMessages = CopyOnWriteArrayList<String>()
    private var promptJob: Job? = null
    private val promptStateLock = Any()
    private val activeBashes = ConcurrentHashMap.newKeySet<RunningBash>()
    private val pendingBashMessages = mutableListOf<BashExecutionMessage>()
    private val pendingExtensionUiRequests = ConcurrentHashMap<String, (JsonObject) -> Unit>()
    private val pendingDirectExtensionUiRequests = ConcurrentHashMap<String, PendingDirectExtensionUiRequest>()
    private val closing = AtomicBoolean(false)
    private val extensionActionLock = Any()
    private var promptResources: PromptResources? = null
    private var extensionHost: ExtensionHost? = null
    private val extensionProviders = ExtensionProviderRegistry(models, extensionHost = { extensionHost })
    private var extensionContextProvider: () -> JsonObject = { JsonObject(emptyMap()) }
    private var runtimeSettingsStore: SettingsStore? = null
    private var baseSystemPrompt: String = ""
    private var availableTools: List<AgentTool> = emptyList()
    private var scopedModels: List<ScopedModel> = emptyList()
    private var agentUnsubscribe: (() -> Unit)? = null
    private var agent = createAgent()

    init {
        activateExtensionSession(if (agent.state.messages.isEmpty()) "startup" else "resume")
        initializingExtensions = false
    }

    fun subscribe(listener: (JsonObject) -> Unit): () -> Unit {
        listeners += listener
        val pending =
            synchronized(pendingEvents) {
                pendingEvents.toList().also { pendingEvents.clear() }
            }
        pending.forEach(listener)
        return { listeners -= listener }
    }

    internal fun currentCwd(): Path = sessionManager.getCwd()

    internal fun currentProjectTrusted(): Boolean =
        extensionContextProvider()["projectTrusted"]
            ?.jsonPrimitive
            ?.booleanOrNull
            ?: false

    internal fun currentTheme(): Theme = requireNotNull(promptResources).themeRegistry.activeTheme

    internal fun currentPromptResources(): PromptResources = requireNotNull(promptResources)

    internal val activeBashCount: Int
        get() = activeBashes.size

    internal val pendingExtensionUiCount: Int
        get() = pendingExtensionUiRequests.size + pendingDirectExtensionUiRequests.size

    internal fun extensionShortcuts(): ExtensionShortcutResolution =
        resolveExtensionShortcuts(
            registrations = extensionHost?.registrations?.extensions.orEmpty(),
            resolvedKeybindings = loadExtensionShortcutKeybindings(options.agentDir),
        )

    internal fun extensionAutocompleteProviderCount(): Int =
        extensionHost?.registrations?.autocompleteProviderCount ?: 0

    internal fun extensionMarkdownTransformerCount(): Int =
        extensionHost?.registrations?.markdownTransformerCount ?: 0

    internal fun transformMarkdown(
        markdown: String,
        messageType: String,
        isStreaming: Boolean,
        availableWidth: Int,
    ): String =
        extensionHost?.invokeMarkdownTransform(
            markdown = markdown,
            messageType = messageType,
            isStreaming = isStreaming,
            availableWidth = availableWidth,
            context = extensionContextProvider(),
        ) ?: markdown

    internal suspend fun invokeExtensionShortcut(id: String): Boolean {
        val host = extensionHost ?: return false
        val registration =
            host.registrations.extensions
                .asSequence()
                .flatMap { it.shortcuts.asSequence() }
                .firstOrNull { it.id == id }
                ?: return false
        return runCatching {
            val invocation =
                withContext(Dispatchers.IO) {
                    host.invokeShortcut(id, extensionContextProvider())
                }
            applyExtensionActions(invocation.actions)
        }.onFailure { error ->
            emitExtensionError(
                ExtensionDiagnostic(
                    extensionPath = registration.extensionPath.toString(),
                    event = "shortcut",
                    error = error.message ?: error::class.simpleName.orEmpty(),
                ),
            )
        }.isSuccess
    }

    internal fun invokeExtensionTerminalInput(
        listenerId: String,
        data: String,
    ): InputListenerResult? {
        val response = extensionHost?.invokeTerminalInput(listenerId, data) ?: return null
        if (response.stringValue("error") != null) {
            return null
        }
        val consume = response["consume"]?.jsonPrimitive?.booleanOrNull ?: false
        val replacement = response.stringValue("data")
        return if (!consume && replacement == null) {
            null
        } else {
            InputListenerResult(consume = consume, data = replacement)
        }
    }

    internal fun invokeExtensionEditorComponent(
        componentId: String,
        operation: String,
        width: Int,
        data: String? = null,
        text: String? = null,
    ): JsonObject? =
        extensionHost?.invokeEditorComponent(
            componentId = componentId,
            operation = operation,
            width = width,
            data = data,
            text = text,
        )

    internal fun invokeExtensionAutocomplete(
        method: String,
        payload: JsonObject,
        baseTriggerCharacters: List<String>,
        onBaseRequest: (JsonObject) -> JsonElement,
    ): JsonObject? =
        extensionHost?.invokeAutocomplete(
            method = method,
            payload = payload,
            baseTriggerCharacters = baseTriggerCharacters,
            onBaseRequest = onBaseRequest,
        )

    internal fun renderExtensionTranscript() {
        sessionManager.getBranch().forEach(::emitExtensionRendering)
    }

    suspend fun handleLine(line: String): JsonObject? {
        val command =
            try {
                protocolJson.parseToJsonElement(line).jsonObject
            } catch (error: Exception) {
                return errorResponse(null, "parse", "Failed to parse command: ${error.message}")
            }
        return handle(command)
    }

    suspend fun handle(command: JsonObject): JsonObject? {
        synchronized(extensionActionLock) {
            // Establish visibility for background extension registration updates.
        }
        val id = command.string("id")
        val type = command.string("type") ?: return errorResponse(id, "unknown", "Command type is required")
        return try {
            when (type) {
                "prompt" -> handlePrompt(command, id)
                "steer" -> {
                    queueSteering(command)
                    delay(1)
                    successResponse(id, type)
                }

                "follow_up" -> {
                    queueFollowUp(command)
                    delay(1)
                    successResponse(id, type)
                }

                "abort" -> {
                    extensionProviders.abortActiveOperations()
                    promptJob?.cancel(CancellationException("Operation aborted"))
                    promptJob?.join()
                    retryDelayJob?.cancel()
                    successResponse(id, type)
                }

                "new_session" -> {
                    ensureIdle("new_session")
                    val parentSession = command.string("parentSession")
                    replaceSession(
                        if (options.noSession) {
                            SessionManager.inMemory(
                                options.cwd,
                                NewSessionOptions(parentSession = parentSession),
                            )
                        } else {
                            SessionManager.create(
                                options.cwd,
                                options.sessionDir,
                                NewSessionOptions(parentSession = parentSession),
                            )
                        },
                    )
                    successResponse(id, type, buildJsonObject { put("cancelled", false) })
                }

                "get_state" -> successResponse(id, type, stateJson())
                "set_model" -> handleSetModel(command, id)
                "cycle_model" -> handleCycleModel(id)
                "get_available_models" ->
                    successResponse(
                        id,
                        type,
                        buildJsonObject {
                            put(
                                "models",
                                JsonArray(
                                    models.getAvailable().map {
                                        protocolJson.encodeToJsonElement(Model.serializer(), it)
                                    },
                                ),
                            )
                        },
                    )

                "set_thinking_level" -> {
                    val level = command.string("level")?.toAgentThinking()
                        ?: return errorResponse(id, type, "Invalid thinking level")
                    setThinkingLevel(level)
                    successResponse(id, type)
                }

                "cycle_thinking_level" -> handleCycleThinking(id)
                "get_available_thinking_levels" ->
                    successResponse(
                        id,
                        type,
                        buildJsonObject {
                            put(
                                "levels",
                                JsonArray(availableThinkingLevels().map { JsonPrimitive(it.toProtocolValue()) }),
                            )
                        },
                    )

                "set_steering_mode" -> {
                    steeringMode = command.string("mode").toQueueMode()
                    agent.setSteeringMode(steeringMode)
                    runtimeSettingsStore?.setSteeringMode(steeringMode.toProtocolValue())
                    successResponse(id, type)
                }

                "set_follow_up_mode" -> {
                    followUpMode = command.string("mode").toQueueMode()
                    agent.setFollowUpMode(followUpMode)
                    runtimeSettingsStore?.setFollowUpMode(followUpMode.toProtocolValue())
                    successResponse(id, type)
                }

                "compact" -> handleCompact(command, id)
                "set_auto_compaction" -> {
                    autoCompactionEnabled = command["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                    runtimeSettingsStore?.setAutoCompactionEnabled(autoCompactionEnabled)
                    successResponse(id, type)
                }

                "set_auto_retry" -> {
                    autoRetryEnabled = command["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                    runtimeSettingsStore?.setAutoRetryEnabled(autoRetryEnabled)
                    successResponse(id, type)
                }

                "abort_retry" -> {
                    retryDelayJob?.cancel()
                    successResponse(id, type)
                }
                "bash" -> handleBash(command, id)
                "abort_bash" -> {
                    abortBashes()
                    successResponse(id, type)
                }
                "extension_ui_response" -> {
                    handleExtensionUiResponse(command)
                    null
                }

                "get_session_stats" -> successResponse(id, type, sessionStatsJson())
                "export_html" -> {
                    val outputPath = command.string("outputPath")?.let(::resolvePath)
                    val path =
                        exportSession(
                            sessionManager,
                            outputPath,
                            SessionHtmlExportOptions(
                                theme = currentTheme(),
                                systemPrompt = agent.state.systemPrompt,
                                tools = agent.state.tools,
                                renderedTools = preRenderHtmlTools(),
                            ),
                        )
                    successResponse(
                        id,
                        type,
                        buildJsonObject { put("path", path.toString()) },
                    )
                }
                "switch_session" -> handleSwitchSession(command, id)
                "fork" -> handleFork(command, id, clone = false)
                "clone" -> handleFork(command, id, clone = true)
                "get_fork_messages" -> successResponse(id, type, forkMessagesJson())
                "get_entries" -> handleGetEntries(command, id)
                "get_tree" ->
                    successResponse(
                        id,
                        type,
                        buildJsonObject {
                            put("tree", JsonArray(sessionManager.getTree().map(::encodeTreeNode)))
                            val leafId = sessionManager.getLeafId()
                            if (leafId == null) {
                                put("leafId", JsonNull)
                            } else {
                                put("leafId", leafId)
                            }
                        },
                    )

                "get_last_assistant_text" ->
                    successResponse(
                        id,
                        type,
                        buildJsonObject {
                            val text = lastAssistantText()
                            if (text == null) {
                                put("text", JsonNull)
                            } else {
                                put("text", text)
                            }
                        },
                    )

                "set_session_name" -> {
                    val name = command.string("name").orEmpty().trim()
                    if (name.isEmpty()) {
                        errorResponse(id, type, "Session name cannot be empty")
                    } else {
                        setSessionName(name)
                        successResponse(id, type)
                    }
                }

                "get_messages" ->
                    successResponse(
                        id,
                        type,
                        buildJsonObject {
                            put(
                                "messages",
                                JsonArray(agent.state.messages.map(::encodeRpcMessage)),
                            )
                        },
                    )

                "get_commands" ->
                    successResponse(
                        id,
                        type,
                        buildJsonObject {
                            val resources = promptResources
                            val commands =
                                buildList {
                                    extensionHost?.registrations?.commands.orEmpty().forEach { command ->
                                        add(
                                            buildJsonObject {
                                                put("name", command.invocationName)
                                                command.description?.let { put("description", it) }
                                                put("source", "extension")
                                                put("sourceInfo", sourceInfoJson(command.sourceInfo))
                                            },
                                        )
                                    }
                                    resources?.promptTemplates.orEmpty().forEach { template ->
                                        add(
                                            buildJsonObject {
                                                put("name", template.name)
                                                put("description", template.description)
                                                put("source", "prompt")
                                                put("sourceInfo", sourceInfoJson(template.sourceInfo))
                                            },
                                        )
                                    }
                                    resources?.skills.orEmpty().forEach { skill ->
                                        add(
                                            buildJsonObject {
                                                put("name", "skill:${skill.name}")
                                                put("description", skill.description)
                                                put("source", "skill")
                                                put("sourceInfo", sourceInfoJson(skill.sourceInfo))
                                            },
                                        )
                                    }
                                }
                            put("commands", JsonArray(commands))
                        },
                    )

                else -> errorResponse(id, type, "Unknown command: $type")
            }
        } catch (error: Exception) {
            errorResponse(id, type, error.message ?: error::class.simpleName.orEmpty())
        }
    }

    suspend fun close() {
        if (!closing.compareAndSet(false, true)) {
            return
        }
        promptJob?.cancel()
        abortBashes()
        cancelPendingExtensionUiRequests()
        detachAgent()
        runCatching {
            emitExtensionEvent(
                host = extensionHost,
                event = buildJsonObject { put("type", "session_shutdown") },
                context = extensionContextProvider,
                onActions = { applyExtensionActions(it) },
            )
        }
        extensionProviders.reset()
        extensionHost?.close()
        extensionHost = null
        scope.cancel()
    }

    suspend fun waitForIdle() {
        promptJob?.join()
        agent.waitForIdle()
    }

    fun reloadResources() {
        ensureIdle("reload")
        cancelPendingExtensionUiRequests()
        detachAgent()
        shutdownExtensionSession()
        agent = createAgent()
        activateExtensionSession("reload")
    }

    private suspend fun handlePrompt(
        command: JsonObject,
        id: String?,
    ): JsonObject {
        if (compacting.get()) {
            return errorResponse(
                id,
                "prompt",
                "Cannot submit a prompt while compaction is in progress. Wait for compaction to finish and retry.",
            )
        }
        findExtensionCommand(extensionHost, command.string("message").orEmpty())?.let { (name, args) ->
            val host = extensionHost
                ?: return@let
            val invocation =
                withContext(Dispatchers.IO) {
                    host.invokeCommand(name, args, extensionContextProvider())
                }
            applyExtensionActions(invocation.actions)
            if (options.extensionMode == ExtensionMode.TUI) {
                emit(buildJsonObject { put("type", "agent_settled") })
            }
            return successResponse(id, "prompt")
        }
        if (agent.state.isStreaming || promptJob?.isActive == true) {
            return when (command.string("streamingBehavior")) {
                "steer" -> {
                    queueSteering(command)
                    successResponse(id, "prompt")
                }

                "followUp" -> {
                    queueFollowUp(command)
                    successResponse(id, "prompt")
                }

                else -> errorResponse(id, "prompt", "Agent is already processing a prompt")
            }
        }
        val prompt = userMessage(command)
        startPrompt(prompt)
        return successResponse(id, "prompt")
    }

    private fun startPrompt(prompt: Message): Boolean =
        synchronized(promptStateLock) {
            if (closing.get() || compacting.get() || promptJob?.isActive == true || agent.state.isStreaming) {
                return@synchronized false
            }
            lateinit var launched: Job
            launched =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val before =
                            emitExtensionBeforeAgentStart(
                                host = extensionHost,
                                prompt =
                                    when (prompt) {
                                        is UserMessage -> contentText(prompt.content)
                                        is CustomMessage -> contentText(prompt.content)
                                        else -> ""
                                    },
                                systemPrompt = baseSystemPrompt,
                                context = extensionContextProvider,
                                onActions = { applyExtensionActions(it) },
                            )
                        agent.state.systemPrompt = before?.systemPrompt ?: baseSystemPrompt
                        runPromptWithRetry(prompt)
                    } finally {
                        flushPendingBashMessages()
                        emit(buildJsonObject { put("type", "agent_settled") })
                        synchronized(promptStateLock) {
                            if (promptJob === launched) {
                                promptJob = null
                            }
                        }
                    }
                }
            promptJob = launched
            launched.start()
            true
        }

    private suspend fun runPromptWithRetry(prompt: Message) {
        overflowRecoveryAttempted = false
        var messages: List<Message> = listOf(prompt)
        while (true) {
            agent.prompt(messages)
            val assistant = agent.state.messages.filterIsInstance<AssistantMessage>().lastOrNull()
                ?: return
            if (recoverContextFailure(assistant)) {
                messages = emptyList()
                continue
            }
            if (!shouldRetry(assistant)) {
                if (assistant.stopReason == StopReason.ERROR && retryAttempt > 0) {
                    emitAutoRetryEnd(
                        success = false,
                        attempt = retryAttempt,
                        finalError = assistant.errorMessage,
                    )
                    retryAttempt = 0
                }
                if (assistant.stopReason == StopReason.STOP) {
                    compactAtThreshold()
                }
                return
            }
            retryAttempt += 1
            if (retryAttempt > retryMaxAttempts) {
                retryAttempt -= 1
                if (retryAttempt > 0) {
                    emitAutoRetryEnd(
                        success = false,
                        attempt = retryAttempt,
                        finalError = assistant.errorMessage,
                    )
                    retryAttempt = 0
                }
                return
            }
            val retryDelay = retryBaseDelayMs * (1L shl (retryAttempt - 1).coerceAtMost(20))
            if (agent.state.messages.lastOrNull() is AssistantMessage) {
                agent.state.messages = agent.state.messages.dropLast(1)
            }
            if (
                !waitForRetryDelay(retryDelay) {
                    emit(
                        buildJsonObject {
                            put("type", "auto_retry_start")
                            put("attempt", retryAttempt)
                            put("maxAttempts", retryMaxAttempts)
                            put("delayMs", retryDelay)
                            put("errorMessage", assistant.errorMessage ?: "Unknown error")
                        },
                    )
                }
            ) {
                val attempt = retryAttempt
                retryAttempt = 0
                emitAutoRetryEnd(
                    success = false,
                    attempt = attempt,
                    finalError = "Retry cancelled",
                )
                return
            }
            messages = emptyList()
        }
    }

    private suspend fun recoverContextFailure(message: AssistantMessage): Boolean {
        val model = agent.state.model
        val sameModel = message.provider == model.provider && message.model == model.id
        val recoverable =
            sameModel &&
                (
                    isContextOverflow(message, model.contextWindow) ||
                        isRecoverableLength(message, model.maxTokens)
                )
        if (!autoCompactionEnabled || !recoverable || overflowRecoveryAttempted) {
            return false
        }
        val preparation = prepareCompaction(sessionManager.getBranch(), compactionSettings) ?: return false
        overflowRecoveryAttempted = true
        if (agent.state.messages.lastOrNull() is AssistantMessage) {
            agent.state.messages = agent.state.messages.dropLast(1)
        }
        return performAutomaticCompaction(
            preparation = preparation,
            reason = "overflow",
            willRetry = true,
            removeRetryableTail = true,
        )
    }

    private suspend fun compactAtThreshold() {
        if (!autoCompactionEnabled) {
            return
        }
        val contextTokens = estimateContextTokens(agent.state.messages).tokens
        if (!shouldCompact(contextTokens, agent.state.model.contextWindow, compactionSettings)) {
            return
        }
        val preparation = prepareCompaction(sessionManager.getBranch(), compactionSettings) ?: return
        performAutomaticCompaction(
            preparation = preparation,
            reason = "threshold",
            willRetry = false,
            removeRetryableTail = false,
        )
    }

    private suspend fun performAutomaticCompaction(
        preparation: CompactionPreparation,
        reason: String,
        willRetry: Boolean,
        removeRetryableTail: Boolean,
    ): Boolean {
        if (!compacting.compareAndSet(false, true)) {
            return false
        }
        emit(
            buildJsonObject {
                put("type", "compaction_start")
                put("reason", reason)
            },
        )
        return try {
            val result =
                compactWithRetry(
                    preparation = preparation,
                    customInstructions = null,
                    reason = reason,
                )
            sessionManager.appendCompaction(
                result.summary,
                result.firstKeptEntryId,
                result.tokensBefore,
                result.details,
                fromHook = false,
                usage = result.usage,
            )
            agent.state.messages = sessionManager.buildSessionContext().messages
            if (
                removeRetryableTail &&
                agent.state.messages.lastOrNull()
                    ?.let { it as? AssistantMessage }
                    ?.stopReason
                    .let { it == StopReason.ERROR || it == StopReason.LENGTH }
            ) {
                agent.state.messages = agent.state.messages.dropLast(1)
            }
            val data =
                buildJsonObject {
                    put("summary", result.summary)
                    put("firstKeptEntryId", result.firstKeptEntryId)
                    put("tokensBefore", result.tokensBefore)
                    put("estimatedTokensAfter", agent.state.messages.sumOf(::estimateTokens))
                    put("usage", rpcPayloadJson.encodeToJsonElement(Usage.serializer(), result.usage))
                    put("details", result.details)
                }
            emit(
                buildJsonObject {
                    put("type", "compaction_end")
                    put("reason", reason)
                    put("result", data)
                    put("aborted", false)
                    put("willRetry", willRetry)
                },
            )
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            emit(
                buildJsonObject {
                    put("type", "compaction_end")
                    put("reason", reason)
                    put("aborted", false)
                    put("willRetry", false)
                    put("errorMessage", error.message ?: error::class.simpleName.orEmpty())
                },
            )
            false
        } finally {
            compacting.set(false)
        }
    }

    private suspend fun waitForRetryDelay(
        delayMs: Long,
        onReady: () -> Unit,
    ): Boolean {
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMs)
            }
        retryDelayJob = job
        return try {
            onReady()
            job.start()
            job.join()
            !job.isCancelled
        } finally {
            if (retryDelayJob === job) {
                retryDelayJob = null
            }
        }
    }

    private fun shouldRetry(message: AssistantMessage): Boolean =
        autoRetryEnabled &&
            retryAttempt < retryMaxAttempts &&
            message.stopReason == StopReason.ERROR &&
            message.errorMessage?.let(::isRetryableErrorMessage) == true

    private fun isRetryableErrorMessage(message: String): Boolean {
        if (NON_RETRYABLE_LIMIT_PATTERN.containsMatchIn(message)) {
            return false
        }
        if (CONTEXT_OVERFLOW_PATTERN.containsMatchIn(message)) {
            return false
        }
        return RETRYABLE_ERROR_PATTERN.containsMatchIn(message)
    }

    private fun emitAutoRetryEnd(
        success: Boolean,
        attempt: Int,
        finalError: String? = null,
    ) {
        emit(
            buildJsonObject {
                put("type", "auto_retry_end")
                put("success", success)
                put("attempt", attempt)
                finalError?.let { put("finalError", it) }
            },
        )
    }

    private fun queueSteering(command: JsonObject) {
        val message = userMessage(command)
        steeringMessages += contentText(message.content)
        emitQueueUpdate()
        agent.steer(message)
    }

    private fun queueFollowUp(command: JsonObject) {
        val message = userMessage(command)
        followUpMessages += contentText(message.content)
        emitQueueUpdate()
        agent.followUp(message)
    }

    private fun queueExtensionMessage(
        message: Message,
        followUp: Boolean,
    ) {
        if (message is UserMessage) {
            val text = contentText(message.content)
            if (followUp) {
                followUpMessages += text
            } else {
                steeringMessages += text
            }
            emitQueueUpdate()
        }
        if (followUp) {
            agent.followUp(message)
        } else {
            agent.steer(message)
        }
    }

    private fun consumeQueuedMessage(message: Message) {
        val user = message as? UserMessage ?: return
        val text = contentText(user.content)
        val steeringIndex = steeringMessages.indexOf(text)
        if (steeringIndex >= 0) {
            steeringMessages.removeAt(steeringIndex)
            emitQueueUpdate()
            return
        }
        val followUpIndex = followUpMessages.indexOf(text)
        if (followUpIndex >= 0) {
            followUpMessages.removeAt(followUpIndex)
            emitQueueUpdate()
        }
    }

    private fun emitQueueUpdate() {
        emit(
            buildJsonObject {
                put("type", "queue_update")
                put("steering", JsonArray(steeringMessages.map(::JsonPrimitive)))
                put("followUp", JsonArray(followUpMessages.map(::JsonPrimitive)))
            },
        )
    }

    private fun setSessionName(name: String) {
        sessionManager.appendSessionInfo(name)
        emit(
            buildJsonObject {
                put("type", "session_info_changed")
                put("name", name)
            },
        )
    }

    private fun emitEntryAppended(entryId: String) {
        val entry = sessionManager.getEntry(entryId) ?: return
        emit(
            buildJsonObject {
                put("type", "entry_appended")
                put("entry", encodeEntry(entry))
            },
        )
        emitExtensionRendering(entry)
    }

    private fun setThinkingLevel(requested: AgentThinkingLevel) {
        val effective = clampThinkingLevel(requested)
        val previous = agent.state.thinkingLevel
        agent.state.thinkingLevel = effective
        if (effective == previous) {
            return
        }
        sessionManager.appendThinkingLevelChange(effective.toProtocolValue())
        if (agent.state.model.reasoning || effective != AgentThinkingLevel.OFF) {
            runtimeSettingsStore?.setDefaultThinkingLevel(effective.toProtocolValue())
        }
        emit(
            buildJsonObject {
                put("type", "thinking_level_changed")
                put("level", effective.toProtocolValue())
            },
        )
    }

    private fun clampThinkingLevel(requested: AgentThinkingLevel): AgentThinkingLevel {
        val available = availableThinkingLevels()
        if (requested in available) {
            return requested
        }
        val requestedIndex = AgentThinkingLevel.entries.indexOf(requested)
        for (index in requestedIndex until AgentThinkingLevel.entries.size) {
            AgentThinkingLevel.entries[index].takeIf { it in available }?.let { return it }
        }
        for (index in requestedIndex - 1 downTo 0) {
            AgentThinkingLevel.entries[index].takeIf { it in available }?.let { return it }
        }
        return available.firstOrNull() ?: AgentThinkingLevel.OFF
    }

    private suspend fun handleSetModel(
        command: JsonObject,
        id: String?,
    ): JsonObject {
        val provider = command.string("provider").orEmpty()
        val modelId = command.string("modelId").orEmpty()
        val model =
            models
                .getAvailable(provider)
                .firstOrNull { it.id == modelId }
                ?: return errorResponse(id, "set_model", "Model not found: $provider/$modelId")
        agent.state.model = model
        sessionManager.appendModelChange(provider, modelId)
        runtimeSettingsStore?.setDefaultModelAndProvider(provider, modelId)
        setThinkingLevel(agent.state.thinkingLevel)
        return successResponse(
            id,
            "set_model",
            protocolJson.encodeToJsonElement(Model.serializer(), model).jsonObject,
        )
    }

    private suspend fun handleCycleModel(id: String?): JsonObject {
        val scoped = scopedModels
        val available =
            if (scoped.isNotEmpty()) {
                scoped.map(ScopedModel::model)
            } else {
                models
                    .getAvailable()
                    .sortedWith(compareBy<Model> { it.provider }.thenBy { it.id })
            }
        if (available.size <= 1) {
            return successResponse(id, "cycle_model", JsonNull)
        }
        val currentIndex = available.indexOfFirst { it.provider == agent.state.model.provider && it.id == agent.state.model.id }
        val next = available[(currentIndex + 1).mod(available.size)]
        val inheritedThinking = agent.state.thinkingLevel
        agent.state.model = next
        val requestedThinking =
            scoped
                .firstOrNull { it.model.provider == next.provider && it.model.id == next.id }
                ?.thinkingLevel
                ?.toCoreThinking()
                ?: inheritedThinking
        sessionManager.appendModelChange(next.provider, next.id)
        runtimeSettingsStore?.setDefaultModelAndProvider(next.provider, next.id)
        setThinkingLevel(requestedThinking)
        return successResponse(
            id,
            "cycle_model",
            buildJsonObject {
                put("model", protocolJson.encodeToJsonElement(Model.serializer(), next))
                put("thinkingLevel", agent.state.thinkingLevel.toProtocolValue())
                put("isScoped", scoped.isNotEmpty())
            },
        )
    }

    private fun handleCycleThinking(id: String?): JsonObject {
        if (!agent.state.model.reasoning) {
            return successResponse(id, "cycle_thinking_level", JsonNull)
        }
        val levels = availableThinkingLevels()
        if (levels.isEmpty()) {
            return successResponse(id, "cycle_thinking_level", JsonNull)
        }
        val current = levels.indexOf(agent.state.thinkingLevel)
        val next = levels[(current + 1).mod(levels.size)]
        setThinkingLevel(next)
        return successResponse(
            id,
            "cycle_thinking_level",
            buildJsonObject { put("level", next.toProtocolValue()) },
        )
    }

    private suspend fun handleCompact(
        command: JsonObject,
        id: String?,
    ): JsonObject {
        check(compacting.compareAndSet(false, true)) { "Compaction is already in progress" }
        try {
            promptJob?.cancel()
            promptJob?.join()
            agent.waitForIdle()
            val entries = sessionManager.getBranch()
            val preparation =
                prepareCompaction(entries, compactionSettings)
                    ?: error(
                        if (entries.lastOrNull() is works.earendil.pi.codingagent.session.CompactionEntry) {
                            "Already compacted"
                        } else {
                            "Nothing to compact (session too small)"
                        },
                    )
            emit(
                buildJsonObject {
                    put("type", "compaction_start")
                    put("reason", "manual")
                },
            )
            val result =
                compactWithRetry(
                    preparation = preparation,
                    customInstructions = command.string("customInstructions"),
                    reason = "manual",
                )
            sessionManager.appendCompaction(
                result.summary,
                result.firstKeptEntryId,
                result.tokensBefore,
                result.details,
                fromHook = false,
                usage = result.usage,
            )
            agent.state.messages = sessionManager.buildSessionContext().messages
            val data =
                buildJsonObject {
                    put("summary", result.summary)
                    put("firstKeptEntryId", result.firstKeptEntryId)
                    put("tokensBefore", result.tokensBefore)
                    put("estimatedTokensAfter", agent.state.messages.sumOf(::estimateTokens))
                    put("usage", rpcPayloadJson.encodeToJsonElement(Usage.serializer(), result.usage))
                    put("details", result.details)
                }
            emit(
                buildJsonObject {
                    put("type", "compaction_end")
                    put("reason", "manual")
                    put("result", data)
                    put("aborted", false)
                    put("willRetry", false)
                },
            )
            return successResponse(id, "compact", data)
        } finally {
            compacting.set(false)
        }
    }

    private suspend fun compactWithRetry(
        preparation: CompactionPreparation,
        customInstructions: String?,
        reason: String,
    ): CompactionResult {
        var attempt = 0
        var retried = false
        try {
            while (true) {
                try {
                    return compact(
                        preparation = preparation,
                        models = models,
                        model = agent.state.model,
                        apiKey = options.apiKey,
                        customInstructions = customInstructions,
                        thinkingLevel = agent.state.thinkingLevel.toProviderThinking(),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val message = error.message ?: error::class.simpleName.orEmpty()
                    val retryMessage = message.removePrefix("Summarization failed: ")
                    if (
                        !autoRetryEnabled ||
                        attempt >= retryMaxAttempts ||
                        !isRetryableErrorMessage(retryMessage)
                    ) {
                        throw error
                    }
                    attempt += 1
                    retried = true
                    val retryDelay = retryBaseDelayMs * (1L shl (attempt - 1).coerceAtMost(20))
                    emit(
                        buildJsonObject {
                            put("type", "summarization_retry_scheduled")
                            put("attempt", attempt)
                            put("maxAttempts", retryMaxAttempts)
                            put("delayMs", retryDelay)
                            put("errorMessage", retryMessage)
                        },
                    )
                    delay(retryDelay)
                    emit(
                        buildJsonObject {
                            put("type", "summarization_retry_attempt_start")
                            put("source", "compaction")
                            put("reason", reason)
                        },
                    )
                }
            }
        } finally {
            if (retried) {
                emit(buildJsonObject { put("type", "summarization_retry_finished") })
            }
        }
    }

    private suspend fun handleBash(
        command: JsonObject,
        id: String?,
    ): JsonObject {
        val shellCommand = command.string("command")
            ?: return errorResponse(id, "bash", "Command is required")
        val excludeFromContext =
            command["excludeFromContext"]?.jsonPrimitive?.booleanOrNull
        val extensionOutput = StringBuilder()
        var extensionRunning: RunningBash? = null
        val extensionResult =
            extensionHost?.let { host ->
                val invocation =
                    try {
                        withContext(Dispatchers.IO) {
                            host.emitUserBash(
                                event =
                                    buildJsonObject {
                                        put("type", "user_bash")
                                        put("command", shellCommand)
                                        put("excludeFromContext", excludeFromContext ?: false)
                                        put("cwd", sessionManager.getCwd().toString())
                                    },
                                context = extensionContextProvider(),
                                onOperationStart = { operationId ->
                                    val running =
                                        RunningBash {
                                            host.abortBashOperation(operationId)
                                        }
                                    extensionRunning = running
                                    activeBashes += running
                                },
                                onUpdate = { delta ->
                                    extensionOutput.append(delta)
                                    emitBashUpdate(id, delta)
                                },
                            )
                        }
                    } finally {
                        extensionRunning?.let(activeBashes::remove)
                    }
                applyExtensionActions(invocation.actions)
                invocation.result as? JsonObject
            }
        val replacement = extensionResult?.get("result") as? JsonObject
        if (replacement != null) {
            val result = normalizeBashResult(replacement)
            recordBashResult(shellCommand, result, excludeFromContext)
            return successResponse(id, "bash", result)
        }
        val operationsResult = extensionResult?.get("operationsResult") as? JsonObject
        if (operationsResult != null) {
            val truncated = truncateTail(extensionOutput.toString())
            val cancelled =
                operationsResult["cancelled"]?.jsonPrimitive?.booleanOrNull == true ||
                    extensionRunning?.cancelled?.get() == true
            val result =
                buildJsonObject {
                    put("output", truncated.content)
                    val exitCode =
                        operationsResult["exitCode"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.toIntOrNull()
                    if (!cancelled && exitCode != null) {
                        put("exitCode", exitCode)
                    }
                    put("cancelled", cancelled)
                    put("truncated", truncated.truncated)
                }
            recordBashResult(shellCommand, result, excludeFromContext)
            return successResponse(id, "bash", result)
        }
        val result =
            withContext(Dispatchers.IO) {
                val shell =
                    if (System.getProperty("os.name").lowercase().contains("win")) {
                        listOf("cmd.exe", "/c", shellCommand)
                    } else {
                        listOf(System.getenv("SHELL") ?: "/bin/zsh", "-lc", shellCommand)
                    }
                val process =
                    ProcessBuilder(shell)
                        .directory(sessionManager.getCwd().toFile())
                        .withPiAgentEnvironment()
                        .redirectErrorStream(true)
                        .start()
                val running =
                    RunningBash {
                        runCatching {
                            process
                                .toHandle()
                                .descendants()
                                .toList()
                                .asReversed()
                                .forEach { handle -> handle.destroyForcibly() }
                        }
                        runCatching { process.destroyForcibly() }
                    }
                activeBashes += running
                try {
                    val output = StringBuilder()
                    process.inputStream.reader(StandardCharsets.UTF_8).use { reader ->
                        val buffer = CharArray(8_192)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) {
                                break
                            }
                            val delta = String(buffer, 0, count)
                            output.append(delta)
                            emitBashUpdate(id, delta)
                        }
                    }
                    val exitCode = process.waitFor()
                    val truncated = truncateTail(output.toString())
                    buildJsonObject {
                        put("output", truncated.content)
                        if (!running.cancelled.get()) {
                            put("exitCode", exitCode)
                        }
                        put("cancelled", running.cancelled.get())
                        put("truncated", truncated.truncated)
                    }
                } finally {
                    activeBashes -= running
                }
            }
        recordBashResult(shellCommand, result, excludeFromContext)
        return successResponse(id, "bash", result)
    }

    private fun emitBashUpdate(
        id: String?,
        delta: String,
    ) {
        emit(
            buildJsonObject {
                put("type", "bash_execution_update")
                id?.let { put("id", it) }
                put("delta", delta)
            },
        )
    }

    private fun handleSwitchSession(
        command: JsonObject,
        id: String?,
    ): JsonObject {
        ensureIdle("switch_session")
        val rawPath = command.string("sessionPath")
            ?: return errorResponse(id, "switch_session", "Session path is required")
        val path = resolvePath(rawPath)
        replaceSession(SessionManager.open(path, options.sessionDir))
        return successResponse(
            id,
            "switch_session",
            buildJsonObject { put("cancelled", false) },
        )
    }

    private fun handleFork(
        command: JsonObject,
        id: String?,
        clone: Boolean,
    ): JsonObject {
        ensureIdle(if (clone) "clone" else "fork")
        val selectedEntry =
            if (clone) {
                sessionManager.getLeafEntry()
                    ?: return errorResponse(id, "clone", "Cannot clone session: no current entry selected")
            } else {
                val entryId = command.string("entryId")
                    ?: return errorResponse(id, "fork", "Entry id is required")
                sessionManager
                    .getEntry(entryId)
                    ?.takeIf { entry ->
                        entry is SessionMessageEntry && entry.message is UserMessage
                    }
                    ?: return errorResponse(id, "fork", "Invalid entry ID for forking")
            }
        val sourceFile =
            sessionManager.getSessionFile()
                ?: return errorResponse(id, if (clone) "clone" else "fork", "Session is not persisted")
        if (!Files.exists(sourceFile)) {
            return errorResponse(id, if (clone) "clone" else "fork", "Session has no assistant response yet")
        }
        val forked =
            SessionManager.forkFrom(
                sourceFile,
                options.cwd,
                options.sessionDir,
            )
        val targetLeafId = if (clone) selectedEntry.id else selectedEntry.parentId
        if (targetLeafId == null) {
            forked.resetLeaf()
        } else {
            forked.branch(targetLeafId)
        }
        val selectedText =
            (selectedEntry as? SessionMessageEntry)
                ?.message
                ?.let { it as? UserMessage }
                ?.let { contentText(it.content) }
        replaceSession(forked)
        return if (clone) {
            successResponse(id, "clone", buildJsonObject { put("cancelled", false) })
        } else {
            successResponse(
                id,
                "fork",
                buildJsonObject {
                    put("text", selectedText.orEmpty())
                    put("cancelled", false)
                },
            )
        }
    }

    private fun handleGetEntries(
        command: JsonObject,
        id: String?,
    ): JsonObject {
        var entries = sessionManager.getEntries()
        command.string("since")?.let { since ->
            val index = entries.indexOfFirst { it.id == since }
            if (index < 0) {
                return errorResponse(id, "get_entries", "Entry not found: $since")
            }
            entries = entries.drop(index + 1)
        }
        return successResponse(
            id,
            "get_entries",
            buildJsonObject {
                put("entries", JsonArray(entries.map(::encodeEntry)))
                val leafId = sessionManager.getLeafId()
                if (leafId == null) {
                    put("leafId", JsonNull)
                } else {
                    put("leafId", leafId)
                }
            },
        )
    }

    private fun forkMessagesJson(): JsonObject =
        buildJsonObject {
            put(
                "messages",
                JsonArray(
                    sessionManager
                        .getEntries()
                        .filterIsInstance<SessionMessageEntry>()
                        .mapNotNull { entry ->
                            val message = entry.message as? UserMessage ?: return@mapNotNull null
                            buildJsonObject {
                                put("entryId", entry.id)
                                put("text", contentText(message.content))
                            }
                        },
                ),
            )
        }

    private fun sessionStatsJson(): JsonObject {
        var userMessages = 0
        var assistantMessages = 0
        var toolCalls = 0
        var toolResults = 0
        var totalMessages = 0
        var input = 0
        var output = 0
        var cacheRead = 0
        var cacheWrite = 0
        var cost = 0.0

        fun addUsage(usage: Usage) {
            input += usage.input
            output += usage.output
            cacheRead += usage.cacheRead
            cacheWrite += usage.cacheWrite
            cost += usage.cost.total
        }

        sessionManager.getEntries().forEach { entry ->
            when (entry) {
                is works.earendil.pi.codingagent.session.CompactionEntry ->
                    entry.usage?.let(::addUsage)

                is works.earendil.pi.codingagent.session.BranchSummaryEntry ->
                    entry.usage?.let(::addUsage)

                is SessionMessageEntry -> {
                    totalMessages += 1
                    when (val message = entry.message) {
                        is UserMessage -> userMessages += 1
                        is AssistantMessage -> {
                            assistantMessages += 1
                            toolCalls += message.content.count { it is ToolCall }
                            addUsage(message.usage)
                        }

                        is ToolResultMessage -> {
                            toolResults += 1
                            message.usage?.let(::addUsage)
                        }

                        else -> Unit
                    }
                }

                else -> Unit
            }
        }

        return buildJsonObject {
            sessionManager.getSessionFile()?.let { put("sessionFile", it.toString()) }
            put("sessionId", sessionManager.getSessionId())
            put("userMessages", userMessages)
            put("assistantMessages", assistantMessages)
            put("toolCalls", toolCalls)
            put("toolResults", toolResults)
            put("totalMessages", totalMessages)
            put(
                "tokens",
                buildJsonObject {
                    put("input", input)
                    put("output", output)
                    put("cacheRead", cacheRead)
                    put("cacheWrite", cacheWrite)
                    put("total", input + output + cacheRead + cacheWrite)
                },
            )
            put("cost", cost)
            contextUsageJson()?.let { put("contextUsage", it) }
        }
    }

    private fun contextUsageJson(): JsonObject? {
        val contextWindow = agent.state.model.contextWindow
        if (contextWindow <= 0) {
            return null
        }
        val branch = sessionManager.getBranch()
        val latestCompactionIndex =
            branch.indexOfLast {
                it is works.earendil.pi.codingagent.session.CompactionEntry
            }
        if (latestCompactionIndex >= 0) {
            val hasPostCompactionUsage =
                branch
                    .drop(latestCompactionIndex + 1)
                    .filterIsInstance<SessionMessageEntry>()
                    .mapNotNull { it.message as? AssistantMessage }
                    .any {
                        it.stopReason != StopReason.ABORTED &&
                            it.stopReason != StopReason.ERROR &&
                            it.usage.totalTokens > 0
                    }
            if (!hasPostCompactionUsage) {
                return buildJsonObject {
                    put("tokens", JsonNull)
                    put("contextWindow", contextWindow)
                    put("percent", JsonNull)
                }
            }
        }
        val tokens = estimateContextTokens(agent.state.messages).tokens
        return buildJsonObject {
            put("tokens", tokens)
            put("contextWindow", contextWindow)
            put("percent", tokens.toDouble() / contextWindow.toDouble() * 100.0)
        }
    }

    private fun stateJson(): JsonObject =
        buildJsonObject {
            put("model", protocolJson.encodeToJsonElement(Model.serializer(), agent.state.model))
            put("thinkingLevel", agent.state.thinkingLevel.toProtocolValue())
            put("isStreaming", agent.state.isStreaming)
            put("isCompacting", compacting.get())
            put("steeringMode", steeringMode.toProtocolValue())
            put("followUpMode", followUpMode.toProtocolValue())
            sessionManager.getSessionFile()?.let { put("sessionFile", it.toString()) }
            put("sessionId", sessionManager.getSessionId())
            sessionManager.getSessionName()?.let { put("sessionName", it) }
            put("autoCompactionEnabled", autoCompactionEnabled)
            put("messageCount", agent.state.messages.size)
            put("pendingMessageCount", steeringMessages.size + followUpMessages.size)
        }

    private fun lastAssistantText(): String? =
        agent.state.messages
            .asReversed()
            .filterIsInstance<AssistantMessage>()
            .firstOrNull { message ->
                message.stopReason != StopReason.ABORTED || message.content.isNotEmpty()
            }
            ?.let { message -> contentText(message.content, "").trim() }
            ?.takeIf(String::isNotEmpty)

    private fun createInitialSession(): SessionManager =
        when {
            options.noSession ->
                SessionManager.inMemory(
                    options.cwd,
                    options.sessionId?.let { NewSessionOptions(id = it) },
                )

            options.forkPath != null ->
                SessionManager.forkFrom(
                    options.forkPath,
                    options.cwd,
                    options.sessionDir,
                    options.sessionId?.let { NewSessionOptions(id = it) },
                )

            options.sessionPath != null ->
                SessionManager.open(
                    options.sessionPath,
                    options.sessionDir,
                )

            options.continueRecent ->
                SessionManager.continueRecent(
                    options.cwd,
                    options.sessionDir,
                )

            else ->
                SessionManager.create(
                    options.cwd,
                    options.sessionDir,
                    options.sessionId?.let { NewSessionOptions(id = it) },
                )
        }

    private fun createAgent(): Agent {
        detachAgent()
        extensionProviders.reset()
        extensionHost?.close()
        extensionHost = null
        val context = sessionManager.buildSessionContext()
        var thinking =
            (options.thinking ?: parseModelReference(options.provider, options.model).thinking)?.toCoreThinking()
                ?: context.thinkingLevel.toAgentThinking()
                ?: AgentThinkingLevel.OFF
        val initialBuiltInTools =
            createSelectedCodingTools(
                cwd = sessionManager.getCwd(),
                noTools = options.noTools,
                noBuiltinTools = options.noBuiltinTools,
                allowedTools = options.tools,
                excludedTools = options.excludeTools,
            )
        var createdRef: Agent? = null
        var selectedTools: List<AgentTool> = initialBuiltInTools
        var projectTrusted = false
        var sessionScopedModels: List<ScopedModel> = emptyList()
        var modelRef: Model? =
            context.model?.let { models.getModel(it.provider, it.modelId) }
                ?: models.getModels().firstOrNull()
        val initialThemeRegistries = mutableMapOf<Boolean, ThemeRegistry>()

        fun themeRegistry(trusted: Boolean): ThemeRegistry =
            promptResources?.themeRegistry
                ?: initialThemeRegistries.getOrPut(trusted) {
                    createThemeRegistry(
                        cwd = sessionManager.getCwd(),
                        agentDir = options.agentDir,
                        projectTrusted = trusted,
                        themePaths = options.themePaths,
                        noThemes = options.noThemes,
                        offline = options.offline,
                    )
                }

        fun currentExtensionUiWidth(): Int? =
            if (options.extensionMode == ExtensionMode.TUI) {
                options.extensionRenderOptionsProvider
                    ?.invoke()
                    ?.width
                    ?.coerceAtLeast(1)
            } else {
                null
            }

        fun currentExtensionContext(): JsonObject {
            val state = createdRef?.state
            return extensionContextJson(
                cwd = sessionManager.getCwd(),
                mode = options.extensionMode,
                projectTrusted = projectTrusted,
                model = state?.model ?: modelRef,
                thinkingLevel = (state?.thinkingLevel ?: thinking).toProtocolValue(),
                systemPrompt = state?.systemPrompt.orEmpty(),
                activeTools = (state?.tools ?: selectedTools).map(AgentTool::name),
                allTools = availableTools.ifEmpty { selectedTools },
                sessionName = sessionManager.getSessionName(),
                sessionId = sessionManager.getSessionId(),
                sessionFile = sessionManager.getSessionFile(),
                isIdle = state?.isStreaming != true,
                hasPendingMessages = createdRef?.hasQueuedMessages() == true,
                flagValues = options.extensionFlagValues,
                scopedModels = sessionScopedModels,
                uiWidth = currentExtensionUiWidth(),
                autocompleteMaxVisible =
                    SettingsStore(
                        cwd = sessionManager.getCwd(),
                        agentDir = options.agentDir,
                        projectTrusted = projectTrusted,
                    ).mergedAutocompleteMaxVisible(),
                toolsExpanded = currentToolOutputExpanded(),
                themeRegistry = themeRegistry(projectTrusted),
            )
        }

        val bootstrap =
            bootstrapExtensions(
                cwd = sessionManager.getCwd(),
                agentDir = options.agentDir,
                trustOverride = options.projectTrusted,
                explicitPaths = options.extensionPaths,
                noExtensions = options.noExtensions,
                mode = options.extensionMode,
                flagValues = options.extensionFlagValues,
                offline = options.offline,
                context = { trusted ->
                    extensionContextJson(
                        cwd = sessionManager.getCwd(),
                        mode = options.extensionMode,
                        projectTrusted = trusted,
                        model = modelRef,
                        thinkingLevel = thinking.toProtocolValue(),
                        systemPrompt = "",
                        activeTools = initialBuiltInTools.map(AgentTool::name),
                        allTools = initialBuiltInTools,
                        sessionName = sessionManager.getSessionName(),
                        sessionId = sessionManager.getSessionId(),
                        sessionFile = sessionManager.getSessionFile(),
                        isIdle = true,
                        hasPendingMessages = false,
                        flagValues = options.extensionFlagValues,
                        scopedModels =
                            resolveConfiguredModelScope(
                                explicitPatterns = options.modelPatterns,
                                availableModels = models.getModels(),
                                cwd = sessionManager.getCwd(),
                                agentDir = options.agentDir,
                                projectTrusted = trusted,
                            ).scopedModels,
                        uiWidth = currentExtensionUiWidth(),
                        autocompleteMaxVisible =
                            SettingsStore(
                                cwd = sessionManager.getCwd(),
                                agentDir = options.agentDir,
                                projectTrusted = trusted,
                            ).mergedAutocompleteMaxVisible(),
                        toolsExpanded = currentToolOutputExpanded(),
                        themeRegistry = themeRegistry(trusted),
                    )
                },
                onWarning = { warning ->
                    emitExtensionError(
                        ExtensionDiagnostic("<resources>", "load", warning),
                    )
                },
                onDiagnostic = ::emitExtensionError,
                onLog = { line ->
                    emit(
                        buildJsonObject {
                            put("type", "extension_log")
                            put("message", line)
                        },
                    )
                },
                onBootstrapActions = ::applyBootstrapExtensionActions,
                onUiRequest = ::handleHostedUiRequest,
                onUiCancelled = ::handleHostedUiCancellation,
                onUiControl = ::handleHostedUiControl,
                onProjectTrustPrompt =
                    options.projectTrustPrompt?.let { prompt ->
                        { path, choices ->
                            prompt(path, choices.map(ProjectTrustOption::label))
                                ?.let(choices::getOrNull)
                        }
                    },
            )
        projectTrusted = bootstrap.projectTrusted
        val settingsStore =
            SettingsStore(
                cwd = sessionManager.getCwd(),
                agentDir = options.agentDir,
                projectTrusted = projectTrusted,
            )
        runtimeSettingsStore = settingsStore
        val runtimeSettings = settingsStore.agentRuntimeSettings()
        steeringMode = runtimeSettings.steeringMode.toQueueMode()
        followUpMode = runtimeSettings.followUpMode.toQueueMode()
        autoCompactionEnabled = runtimeSettings.autoCompactionEnabled
        imageAutoResize = runtimeSettings.imageAutoResize
        compactionSettings =
            CompactionSettings(
                enabled = runtimeSettings.autoCompactionEnabled,
                reserveTokens = runtimeSettings.compactionReserveTokens,
                keepRecentTokens = runtimeSettings.compactionKeepRecentTokens,
            )
        autoRetryEnabled = runtimeSettings.autoRetryEnabled
        retryMaxAttempts = runtimeSettings.retryMaxAttempts
        retryBaseDelayMs = runtimeSettings.retryBaseDelayMs
        if (
            options.thinking == null &&
            sessionManager.getBranch().none {
                it is works.earendil.pi.codingagent.session.ThinkingLevelChangeEntry
            }
        ) {
            runtimeSettings.defaultThinkingLevel
                ?.toCoreThinkingLevel()
                ?.let { thinking = it }
        }
        val host = bootstrap.host
        extensionHost = host
        extensionContextProvider = ::currentExtensionContext
        applyBootstrapExtensionActions(host?.drainStartupActions().orEmpty())
        val scopeResolution =
            resolveConfiguredModelScope(
                explicitPatterns = options.modelPatterns,
                availableModels = runBlocking { models.getAvailable() },
                cwd = sessionManager.getCwd(),
                agentDir = options.agentDir,
                projectTrusted = projectTrusted,
            )
        sessionScopedModels = scopeResolution.scopedModels
        scopedModels = sessionScopedModels
        scopeResolution.diagnostics.forEach { diagnostic ->
            emit(
                buildJsonObject {
                    put("type", "model_scope_warning")
                    put("pattern", diagnostic.pattern)
                    put("message", diagnostic.message)
                },
            )
        }
        if (
            options.thinking == null &&
            options.model == null &&
            context.model == null
        ) {
            sessionScopedModels.firstOrNull()?.thinkingLevel?.toCoreThinking()?.let { thinking = it }
        }
        val model =
            resolveModel(
                context.model?.provider ?: runtimeSettings.defaultProvider,
                context.model?.modelId ?: runtimeSettings.defaultModel,
                sessionScopedModels,
            )
                ?: error("No model is available")
        modelRef = model
        if (sessionManager.getEntries().isEmpty()) {
            sessionManager.appendModelChange(model.provider, model.id)
            sessionManager.appendThinkingLevelChange(thinking.toProtocolValue())
        }
        val promptResources =
            loadPromptResources(
                cwd = sessionManager.getCwd(),
                agentDir = options.agentDir,
                systemPromptSource = options.systemPrompt,
                appendPromptSources = options.appendSystemPrompt,
                noContextFiles = options.noContextFiles,
                skillPaths = options.skillPaths,
                noSkills = options.noSkills,
                promptTemplatePaths = options.promptTemplatePaths,
                noPromptTemplates = options.noPromptTemplates,
                themePaths = options.themePaths,
                noThemes = options.noThemes,
                projectTrusted = projectTrusted,
                resolvedPackageResources = bootstrap.packageResources,
            )
        this.promptResources = promptResources
        val extensionTools =
            host
                ?.registrations
                ?.tools
                .orEmpty()
                .map { registration ->
                    HostedExtensionTool(
                        registration = registration,
                        host = requireNotNull(host),
                        context = ::currentExtensionContext,
                        onActions = { applyExtensionActions(it) },
                    )
                }
        selectedTools =
            createSelectedCodingTools(
                cwd = sessionManager.getCwd(),
                noTools = options.noTools,
                noBuiltinTools = options.noBuiltinTools,
                allowedTools = options.tools,
                excludedTools = options.excludeTools,
                extensionTools = extensionTools,
            )
        availableTools = selectedTools
        baseSystemPrompt = buildCodingSystemPrompt(sessionManager.getCwd(), selectedTools, promptResources)
        val created =
            Agent(
                AgentOptions(
                    streamFunction =
                        StreamFunction { requestModel, requestContext: Context, streamOptions: SimpleStreamOptions ->
                            models.streamSimple(requestModel, requestContext, streamOptions)
                        },
                    convertToLlm = { messages -> convertCodingMessagesToLlm(messages) },
                    beforeToolCall = { call ->
                        emitExtensionBeforeToolCall(
                            host = host,
                            context = ::currentExtensionContext,
                            onActions = { applyExtensionActions(it) },
                            call = call,
                        )
                    },
                    afterToolCall = { call ->
                        val patch =
                            emitExtensionAfterToolCall(
                                host = host,
                                context = ::currentExtensionContext,
                                onActions = { applyExtensionActions(it) },
                                call = call,
                            )
                        val content = patch?.content ?: call.result.content
                        val normalized = normalizeToolResultImages(content, imageAutoResize)
                        if (patch == null && normalized === content) {
                            null
                        } else {
                            (patch ?: works.earendil.pi.agent.AfterToolCallResult()).copy(content = normalized)
                        }
                    },
                    initialState =
                        AgentInitialState(
                            systemPrompt = baseSystemPrompt,
                            model = model,
                            thinkingLevel = thinking,
                            tools = selectedTools,
                            messages = context.messages,
                        ),
                    steeringMode = steeringMode,
                    followUpMode = followUpMode,
                    streamOptions =
                        SimpleStreamOptions(
                            stream =
                                StreamOptions(
                                    apiKey = options.apiKey,
                                    sessionId = sessionManager.getSessionId(),
                                ),
                            reasoning = thinking.toProviderThinking(),
                        ),
                ),
            )
        createdRef = created
        host?.bindBackgroundActions { applyExtensionActions(it) }
        agentUnsubscribe =
            created.subscribe { event ->
                if (event is AgentEvent.MessageStart) {
                    consumeQueuedMessage(event.message)
                }
                val encoded =
                    encodeAgentEvent(
                        event = event,
                        linearStreaming = true,
                        willRetry =
                            if (event is AgentEvent.AgentEnd) {
                                event.messages
                                    .filterIsInstance<AssistantMessage>()
                                    .lastOrNull()
                                    ?.let(::shouldRetry)
                                    ?: false
                            } else {
                                false
                            },
                    )
                val emitBeforeExtension =
                    event is AgentEvent.MessageStart && event.message is AssistantMessage
                if (emitBeforeExtension) {
                    emit(encoded)
                }
                emitExtensionAgentEvent(
                    host = host,
                    event = event,
                    context = ::currentExtensionContext,
                    onActions = { applyExtensionActions(it) },
                )
                if (!emitBeforeExtension) {
                    emit(encoded)
                }
                if (event is AgentEvent.MessageEnd) {
                    val entryId = appendAgentMessage(sessionManager, event.message)
                    if (event.message is CustomMessage) {
                        sessionManager.getEntry(entryId)?.let(::emitExtensionRendering)
                    }
                    val assistant = event.message as? AssistantMessage
                    if (
                        assistant != null &&
                        assistant.stopReason != StopReason.ERROR &&
                        retryAttempt > 0
                    ) {
                        emitAutoRetryEnd(success = true, attempt = retryAttempt)
                        retryAttempt = 0
                    }
                }
                if (event is AgentEvent.AgentEnd) {
                    flushPendingBashMessages()
                }
            }
        return created
    }

    private fun activateExtensionSession(reason: String) {
        val host = extensionHost ?: return
        runCatching {
            val invocation =
                host.emit(
                    event =
                        buildJsonObject {
                            put("type", "session_start")
                            put("reason", reason)
                        },
                    context = extensionContextProvider(),
                )
            applyExtensionActions(invocation.actions)
            val resources =
                discoverExtensionResources(
                    host = host,
                    cwd = sessionManager.getCwd(),
                    reason = if (reason == "reload") "reload" else "startup",
                    context = extensionContextProvider(),
                    onActions = ::applyExtensionActions,
                )
            if (
                resources.skills.isNotEmpty() ||
                resources.prompts.isNotEmpty() ||
                resources.themes.isNotEmpty()
            ) {
                val current = requireNotNull(promptResources)
                val extended =
                    loadPromptResources(
                        cwd = sessionManager.getCwd(),
                        agentDir = options.agentDir,
                        systemPromptSource = options.systemPrompt,
                        appendPromptSources = options.appendSystemPrompt,
                        noContextFiles = options.noContextFiles,
                        skillPaths = options.skillPaths,
                        noSkills = options.noSkills,
                        promptTemplatePaths = options.promptTemplatePaths,
                        noPromptTemplates = options.noPromptTemplates,
                        themePaths = options.themePaths,
                        noThemes = options.noThemes,
                        projectTrusted = extensionContextProvider()["projectTrusted"]
                            ?.jsonPrimitive
                            ?.booleanOrNull == true,
                        resolvedPackageResources = current.packageResources.merge(resources),
                    )
                promptResources = extended
                baseSystemPrompt = buildCodingSystemPrompt(sessionManager.getCwd(), availableTools, extended)
                agent.state.systemPrompt = baseSystemPrompt
            }
        }.onFailure { error ->
            emitExtensionError(
                ExtensionDiagnostic(
                    extensionPath = "<host>",
                    event = "session_start",
                    error = error.message ?: error::class.simpleName.orEmpty(),
                ),
            )
        }
    }

    private fun shutdownExtensionSession() {
        val host = extensionHost ?: return
        runCatching {
            val invocation =
                host.emit(
                    event = buildJsonObject { put("type", "session_shutdown") },
                    context = extensionContextProvider(),
                )
            applyExtensionActions(invocation.actions)
        }.onFailure { error ->
            emitExtensionError(
                ExtensionDiagnostic(
                    extensionPath = "<host>",
                    event = "session_shutdown",
                    error = error.message ?: error::class.simpleName.orEmpty(),
                ),
            )
        }
        host.close()
        extensionProviders.reset()
        extensionHost = null
    }

    private fun applyExtensionActions(actions: List<ExtensionAction>) {
        synchronized(extensionActionLock) {
            actions.forEach { action ->
                when (action.type) {
                "ui" ->
                    emit(rpcExtensionUiEvent(action.data))

                "append_entry" ->
                    action.data.stringValue("customType")?.let { customType ->
                        val entryId = sessionManager.appendCustomEntry(customType, action.data["data"])
                        emitEntryAppended(entryId)
                    }

                "set_session_name" ->
                    action.data.stringValue("name")?.let(::setSessionName)

                "set_label" -> {
                    val entryId = action.data.stringValue("entryId")
                    if (entryId != null) {
                        runCatching {
                            sessionManager.appendLabelChange(entryId, action.data.stringValue("label"))
                        }.onFailure { error ->
                            emitExtensionError(
                                ExtensionDiagnostic(
                                    extensionPath = "<action>",
                                    event = "set_label",
                                    error = error.message ?: error::class.simpleName.orEmpty(),
                                ),
                            )
                        }
                    }
                }

                "set_active_tools" -> {
                    val names =
                        action.data["toolNames"]
                            ?.jsonArray
                            .orEmpty()
                            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    agent.state.tools = availableTools.filter { it.name in names }
                }

                "set_tools_expanded" -> {
                    toolOutputExpandedOverride =
                        action.data["expanded"]
                            ?.jsonPrimitive
                            ?.booleanOrNull
                            ?: toolOutputExpandedOverride
                }

                "set_thinking_level" ->
                    action.data.stringValue("level")
                        ?.toCoreThinkingLevel()
                        ?.let(::setThinkingLevel)

                "set_theme" -> {
                    val name = action.data.stringValue("name")
                    val persist =
                        action.data.stringValue("persist")
                            ?.toBooleanStrictOrNull()
                            ?: true
                    val result = name?.let { promptResources?.themeRegistry?.setTheme(it, persist) }
                    if (result?.success != true) {
                        emitExtensionError(
                            ExtensionDiagnostic(
                                extensionPath = "<action>",
                                event = "set_theme",
                                error = result?.error ?: "Extension requested an unavailable theme",
                            ),
                        )
                    }
                }

                "set_theme_instance" -> {
                    val value = action.data["theme"] as? JsonObject
                    runCatching {
                        Theme.fromExtensionJson(requireNotNull(value))
                    }.onSuccess { theme ->
                        promptResources?.themeRegistry?.setThemeInstance(theme)
                    }.onFailure { error ->
                        emitExtensionError(
                            ExtensionDiagnostic(
                                extensionPath = "<action>",
                                event = "set_theme_instance",
                                error = error.message ?: "Invalid in-memory theme",
                            ),
                        )
                    }
                }

                "set_model" -> {
                    val requested = action.data["model"] as? JsonObject
                    val provider = requested?.stringValue("provider")
                    val modelId = requested?.stringValue("id")
                    val model =
                        if (provider == null || modelId == null) {
                            null
                        } else {
                            models.getModel(provider, modelId)
                        }
                    if (model == null) {
                        emitExtensionError(
                            ExtensionDiagnostic(
                                extensionPath = "<action>",
                                event = "set_model",
                                error = "Extension requested an unavailable model",
                            ),
                        )
                    } else {
                        agent.state.model = model
                        sessionManager.appendModelChange(model.provider, model.id)
                    }
                }

                "send_message" -> {
                    val message = extensionCustomMessage(action.data["message"])
                    if (message != null) {
                        val options = action.data["options"] as? JsonObject
                        val deliverAs = options?.stringValue("deliverAs")
                        when {
                            deliverAs == "nextTurn" ->
                                queueExtensionMessage(message, followUp = true)

                            agent.state.isStreaming || promptJob?.isActive == true ->
                                queueExtensionMessage(message, followUp = deliverAs == "followUp")

                            options?.get("triggerTurn")?.jsonPrimitive?.booleanOrNull == true ->
                                startPrompt(message)

                            else -> {
                                val entryId = appendExtensionMessage(sessionManager, action.data["message"])
                                entryId?.let(sessionManager::getEntry)?.let(::emitExtensionRendering)
                            }
                        }
                    }
                }

                "send_user_message" -> {
                    val message = extensionUserMessage(action.data)
                    val deliverAs =
                        (action.data["options"] as? JsonObject)
                            ?.stringValue("deliverAs")
                    if (agent.state.isStreaming || promptJob?.isActive == true) {
                        queueExtensionMessage(message, followUp = deliverAs == "followUp")
                    } else if (!startPrompt(message)) {
                        emitExtensionError(
                            ExtensionDiagnostic(
                                extensionPath = "<action>",
                                event = "send_user_message",
                                error = "Extension-triggered turn could not be started",
                            ),
                        )
                    }
                }

                "register_provider" -> {
                    val name = action.data.stringValue("name")
                    val config = action.data["config"] as? JsonObject
                    if (name == null || config == null) {
                        emitExtensionError(
                            ExtensionDiagnostic(
                                extensionPath = "<action>",
                                event = action.type,
                                error = "Extension provider registration is invalid",
                            ),
                        )
                    } else {
                        runCatching { extensionProviders.register(name, config) }
                            .onSuccess { refreshCurrentModelFromRegistry() }
                            .onFailure { error ->
                                emitExtensionError(
                                    ExtensionDiagnostic(
                                        extensionPath = "<action>",
                                        event = action.type,
                                        error = error.message ?: "Failed to register provider $name",
                                    ),
                                )
                            }
                    }
                }

                "unregister_provider" ->
                    action.data.stringValue("name")?.let { name ->
                        extensionProviders.unregister(name)
                        refreshCurrentModelFromRegistry()
                    }

                "registrations_changed" -> refreshExtensionRegistrations()

                "unsupported",
                "new_session",
                "fork",
                "navigate_tree",
                "switch_session",
                "reload",
                "compact",
                "abort",
                "shutdown",
                ->
                    emitExtensionError(
                        ExtensionDiagnostic(
                            extensionPath = "<action>",
                            event = action.type,
                            error = "Extension action is not available in the Kotlin runtime yet",
                        ),
                    )
                }
            }
        }
    }

    private fun refreshExtensionRegistrations() {
        val host = extensionHost ?: return
        val previousRegistryNames = availableTools.mapTo(mutableSetOf(), AgentTool::name)
        val previousActiveNames = agent.state.tools.mapTo(mutableSetOf(), AgentTool::name)
        val extensionTools =
            host.registrations.tools.map { registration ->
                HostedExtensionTool(
                    registration = registration,
                    host = host,
                    context = extensionContextProvider,
                    onActions = { applyExtensionActions(it) },
                )
            }
        val refreshed =
            createSelectedCodingTools(
                cwd = sessionManager.getCwd(),
                noTools = options.noTools,
                noBuiltinTools = options.noBuiltinTools,
                allowedTools = options.tools,
                excludedTools = options.excludeTools,
                extensionTools = extensionTools,
            )
        val newlyRegisteredNames =
            refreshed
                .mapTo(mutableSetOf(), AgentTool::name)
                .apply { removeAll(previousRegistryNames) }
        availableTools = refreshed
        agent.state.tools =
            refreshed.filter { tool ->
                tool.name in previousActiveNames || tool.name in newlyRegisteredNames
            }
        promptResources?.let { resources ->
            baseSystemPrompt = buildCodingSystemPrompt(sessionManager.getCwd(), agent.state.tools, resources)
            agent.state.systemPrompt = baseSystemPrompt
        }
    }

    private fun refreshCurrentModelFromRegistry() {
        val current = agent.state.model
        models.getModel(current.provider, current.id)?.let { refreshed ->
            agent.state.model = refreshed
        }
    }

    private fun applyBootstrapExtensionActions(actions: List<ExtensionAction>) {
        actions.forEach { action ->
            when (action.type) {
                "ui" ->
                    emit(rpcExtensionUiEvent(action.data))

                "append_entry" ->
                    action.data.stringValue("customType")?.let { customType ->
                        val entryId = sessionManager.appendCustomEntry(customType, action.data["data"])
                        emitEntryAppended(entryId)
                    }

                "set_session_name" ->
                    action.data.stringValue("name")?.let(::setSessionName)

                "send_message" -> {
                    val entryId = appendExtensionMessage(sessionManager, action.data["message"])
                    entryId?.let(sessionManager::getEntry)?.let(::emitExtensionRendering)
                }

                "register_provider" -> {
                    val name = action.data.stringValue("name")
                    val config = action.data["config"] as? JsonObject
                    if (name != null && config != null) {
                        runCatching { extensionProviders.register(name, config) }
                            .onFailure { error ->
                                emitExtensionError(
                                    ExtensionDiagnostic(
                                        "<action>",
                                        action.type,
                                        error.message ?: "Failed to register provider $name",
                                    ),
                                )
                            }
                    }
                }

                "unregister_provider" ->
                    action.data.stringValue("name")?.let(extensionProviders::unregister)
            }
        }
    }

    private fun emitExtensionError(diagnostic: ExtensionDiagnostic) {
        emit(
            buildJsonObject {
                put("type", "extension_error")
                put("extensionPath", diagnostic.extensionPath)
                put("event", diagnostic.event)
                put("error", diagnostic.error)
                diagnostic.stack?.let { put("stack", it) }
            },
        )
    }

    private fun emitExtensionRendering(entry: SessionEntry) {
        if (initializingExtensions && listeners.isEmpty()) {
            return
        }
        val optionsProvider = options.extensionRenderOptionsProvider ?: return
        val renderOptions =
            optionsProvider().let {
                it.copy(
                    width = it.width.coerceAtLeast(1),
                    expanded = toolOutputExpandedOverride ?: it.expanded,
                    outputPad = it.outputPad.coerceAtLeast(0),
                )
            }
        val block = renderExtensionEntry(entry, renderOptions) ?: return
        emit(extensionRenderedBlockEvent(block))
    }

    private fun currentToolOutputExpanded(): Boolean =
        toolOutputExpandedOverride
            ?: options.extensionRenderOptionsProvider
                ?.invoke()
                ?.expanded
            ?: false

    private fun renderExtensionEntry(
        entry: SessionEntry,
        renderOptions: ExtensionRenderOptions,
    ): ExtensionRenderedBlock? {
        val value = rendererValue(entry) ?: return null
        val message = customMessage(entry)
        val kind = if (message == null) "entry" else "message"
        val customType =
            when {
                message != null -> message.customType
                else -> value.stringValue("customType") ?: return null
            }
        if (message?.display == false) {
            return null
        }
        val host = extensionHost
        val renderer =
            host?.registrations?.extensions?.let { registrations ->
                findExtensionRenderer(registrations, kind, customType)
            }
        val lines =
            if (kind == "message") {
                renderExtensionMessage(host, renderer, value, message!!, renderOptions)
            } else {
                renderExtensionEntryValue(host, renderer, value, customType, renderOptions)
                    ?: return null
            }
        return ExtensionRenderedBlock(
            entryId = entry.id,
            kind = kind,
            customType = customType,
            lines = lines,
        )
    }

    private fun renderExtensionMessage(
        host: ExtensionHost?,
        renderer: ExtensionRendererRegistration?,
        value: JsonObject,
        message: CustomMessage,
        renderOptions: ExtensionRenderOptions,
    ): List<String> {
        if (host == null || renderer == null) {
            return defaultCustomMessageLines(message, renderOptions.width)
        }
        return runCatching {
            val invocation =
                host.invokeRenderer(
                    kind = "message",
                    rendererId = renderer.id,
                    value = value,
                    width = renderOptions.width,
                    expanded = renderOptions.expanded,
                    outputPad = renderOptions.outputPad,
                    context = extensionContextProvider(),
                )
            applyExtensionActions(invocation.actions)
            parseRendererLines(invocation)
        }.getOrNull() ?: defaultCustomMessageLines(message, renderOptions.width)
    }

    private fun renderExtensionEntryValue(
        host: ExtensionHost?,
        renderer: ExtensionRendererRegistration?,
        value: JsonObject,
        customType: String,
        renderOptions: ExtensionRenderOptions,
    ): List<String>? {
        if (host == null || renderer == null) {
            return null
        }
        return runCatching {
            val invocation =
                host.invokeRenderer(
                    kind = "entry",
                    rendererId = renderer.id,
                    value = value,
                    width = renderOptions.width,
                    expanded = renderOptions.expanded,
                    outputPad = renderOptions.outputPad,
                    context = extensionContextProvider(),
                )
            applyExtensionActions(invocation.actions)
            parseRendererLines(invocation)
        }.getOrElse { error ->
            rendererErrorLines(
                customType = customType,
                message = error.message ?: error::class.simpleName.orEmpty(),
            )
        }
    }

    private fun preRenderHtmlTools(): JsonObject? {
        val host = extensionHost
        val registrations =
            host
                ?.registrations
                ?.tools
                .orEmpty()
                .associateBy(ExtensionToolRegistration::name)
        val rendered = linkedMapOf<String, JsonObject>()

        fun putRendered(
            toolCallId: String,
            key: String,
            html: String,
        ) {
            val value = rendered[toolCallId]?.toMutableMap() ?: linkedMapOf()
            value[key] = JsonPrimitive(html)
            rendered[toolCallId] = JsonObject(value)
        }

        sessionManager.getEntries().filterIsInstance<SessionMessageEntry>().forEach { entry ->
            when (val message = entry.message) {
                is AssistantMessage ->
                    message.content.filterIsInstance<ToolCall>().forEach { call ->
                        if (call.name in HTML_TEMPLATE_RENDERED_TOOLS) {
                            return@forEach
                        }
                        val registration = registrations[call.name]?.takeIf { it.hasRenderCall }
                        val lines =
                            if (host != null && registration != null) {
                                renderHtmlTool(
                                    host = host,
                                    registration = registration,
                                    phase = "call",
                                    toolCallId = call.id,
                                    args = call.arguments,
                                )
                            } else {
                                renderBuiltinHtmlToolCall(call, currentTheme())
                            }
                        lines?.let(::ansiLinesToHtml)
                            ?.takeIf(String::isNotEmpty)
                            ?.let { putRendered(call.id, "callHtml", it) }
                    }

                is ToolResultMessage -> {
                    if (rendered[message.toolCallId] == null && message.toolName in HTML_TEMPLATE_RENDERED_TOOLS) {
                        return@forEach
                    }
                    val registration = registrations[message.toolName]?.takeIf { it.hasRenderResult }
                    val content by lazy {
                        JsonArray(
                            message.content.map { block ->
                                protocolJson.encodeToJsonElement(ContentBlock.serializer(), block)
                            },
                        )
                    }
                    val collapsed =
                        if (host != null && registration != null) {
                            renderHtmlTool(
                                host = host,
                                registration = registration,
                                phase = "result",
                                toolCallId = message.toolCallId,
                                content = content,
                                details = message.details,
                                isError = message.isError,
                                expanded = false,
                            )
                        } else {
                            renderBuiltinHtmlToolResult(
                                message,
                                currentTheme(),
                                expanded = false,
                                expandKey = htmlToolExpandKey(),
                            )
                        }?.let(::trimRenderedResultLines)
                            ?.let(::ansiLinesToHtml)
                    val expanded =
                        if (host != null && registration != null) {
                            renderHtmlTool(
                                host = host,
                                registration = registration,
                                phase = "result",
                                toolCallId = message.toolCallId,
                                content = content,
                                details = message.details,
                                isError = message.isError,
                                expanded = true,
                            )
                        } else {
                            renderBuiltinHtmlToolResult(
                                message,
                                currentTheme(),
                                expanded = true,
                                expandKey = htmlToolExpandKey(),
                            )
                        }?.let(::trimRenderedResultLines)
                            ?.let(::ansiLinesToHtml)
                            ?: return@forEach
                    if (!collapsed.isNullOrEmpty() && collapsed != expanded) {
                        putRendered(message.toolCallId, "resultHtmlCollapsed", collapsed)
                    }
                    putRendered(message.toolCallId, "resultHtmlExpanded", expanded)
                }

                else -> Unit
            }
        }
        return rendered.takeIf { it.isNotEmpty() }?.let(::JsonObject)
    }

    private fun renderHtmlTool(
        host: ExtensionHost,
        registration: ExtensionToolRegistration,
        phase: String,
        toolCallId: String,
        args: JsonObject? = null,
        content: JsonArray? = null,
        details: JsonElement? = null,
        isError: Boolean = false,
        expanded: Boolean = false,
    ): List<String>? =
        runCatching {
            val invocation =
                host.invokeToolRenderer(
                    toolId = registration.id,
                    phase = phase,
                    toolCallId = toolCallId,
                    args = args,
                    content = content,
                    details = details,
                    isError = isError,
                    expanded = expanded,
                    context = extensionContextProvider(),
                )
            applyExtensionActions(invocation.actions)
            parseRendererLines(invocation)
        }.getOrNull()

    private fun htmlToolExpandKey(): String =
        if (options.extensionMode == ExtensionMode.TUI) "ctrl+o" else ""

    private fun resolveModel(
        sessionProvider: String?,
        sessionModel: String?,
        sessionScopedModels: List<ScopedModel>,
    ): Model? {
        if (options.provider != null || options.model != null) {
            runBlocking {
                resolveExactModelReference(models, options.provider, options.model)
            }?.let { return it }
            val reference = parseModelReference(options.provider, options.model)
            val provider = reference.provider
            val modelId = reference.modelId
            val candidates = models.getModels(provider)
            return if (modelId.isNullOrBlank()) {
                candidates.firstOrNull { it.id == provider?.let(::defaultModelId) } ?: candidates.firstOrNull()
            } else {
                candidates.firstOrNull { it.id == modelId }
            }
        }
        if (sessionProvider != null && sessionModel != null) {
            models.getModel(sessionProvider, sessionModel)?.let { return it }
        }
        sessionScopedModels.firstOrNull()?.model?.let { return it }
        val googleModels = models.getModels("google")
        return googleModels.firstOrNull { it.id == defaultModelId("google") }
            ?: googleModels.firstOrNull()
            ?: models.getModels().firstOrNull()
    }

    private fun replaceSession(session: SessionManager) {
        retryDelayJob?.cancel()
        retryDelayJob = null
        retryAttempt = 0
        steeringMessages.clear()
        followUpMessages.clear()
        cancelPendingExtensionUiRequests()
        detachAgent()
        shutdownExtensionSession()
        sessionManager = session
        agent = createAgent()
        activateExtensionSession("new")
    }

    private fun ensureIdle(command: String) {
        check(!agent.state.isStreaming) { "$command is not available while the agent is streaming" }
    }

    private fun abortBashes() {
        activeBashes.toList().forEach(RunningBash::cancel)
    }

    private fun handleHostedUiRequest(
        request: JsonObject,
        respond: (JsonObject) -> Unit,
    ) {
        val requestId = request.string("requestId")
            ?: run {
                respond(buildJsonObject { put("cancelled", true) })
                return
            }
        if (closing.get() ||
            (
                initializingExtensions &&
                    listeners.isEmpty() &&
                    options.extensionUiHandler == null &&
                    options.cancellableExtensionUiHandler == null
            )
        ) {
            respond(buildJsonObject { put("cancelled", true) })
            return
        }
        val outward =
            buildJsonObject {
                put("type", "extension_ui_request")
                put("id", requestId)
                request.forEach { (name, value) ->
                    if (name != "type" && name != "id" && name != "requestId") {
                        put(name, value)
                    }
                }
            }
        val directHandler =
            options.cancellableExtensionUiHandler
                ?: options.extensionUiHandler?.let { handler ->
                    CancellableExtensionUiHandler { directRequest, _ -> handler(directRequest) }
                }
        if (directHandler != null) {
            val pending = PendingDirectExtensionUiRequest()
            pendingDirectExtensionUiRequests.put(requestId, pending)?.let { previous ->
                previous.cancellation.cancel()
            }
            scope.launch(Dispatchers.IO) {
                try {
                    val response =
                        runCatching { directHandler.handle(outward, pending.cancellation) }
                            .getOrElse { buildJsonObject { put("cancelled", true) } }
                    if (pendingDirectExtensionUiRequests.remove(requestId, pending)) {
                        respond(response)
                    }
                } finally {
                    pending.finished.countDown()
                }
            }
            return
        }
        pendingExtensionUiRequests.put(requestId, respond)?.invoke(
            buildJsonObject { put("cancelled", true) },
        )
        emit(outward)
    }

    private fun handleExtensionUiResponse(command: JsonObject) {
        val requestId = command.string("id") ?: return
        pendingExtensionUiRequests.remove(requestId)?.invoke(command)
    }

    private fun handleHostedUiCancellation(requestId: String) {
        pendingExtensionUiRequests.remove(requestId)
        cancelDirectExtensionUiRequest(requestId)
    }

    private fun handleHostedUiControl(request: JsonObject) {
        emit(
            buildJsonObject {
                put("type", "extension_ui_request")
                request.forEach { (name, value) ->
                    if (name != "type" && name != "id") {
                        put(name, value)
                    }
                }
            },
        )
    }

    private fun cancelDirectExtensionUiRequest(requestId: String) {
        val pending = pendingDirectExtensionUiRequests.remove(requestId) ?: return
        pending.cancellation.cancel()
        runCatching {
            pending.finished.await(DIRECT_EXTENSION_UI_CANCEL_WAIT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun cancelPendingExtensionUiRequests() {
        val pending = pendingExtensionUiRequests.values.toList()
        pendingExtensionUiRequests.clear()
        pending.forEach { respond ->
            runCatching {
                respond(buildJsonObject { put("cancelled", true) })
            }
        }
        pendingDirectExtensionUiRequests.keys.toList().forEach(::cancelDirectExtensionUiRequest)
    }

    private fun detachAgent() {
        agentUnsubscribe?.invoke()
        agentUnsubscribe = null
    }

    private fun normalizeBashResult(result: JsonObject): JsonObject =
        buildJsonObject {
            put("output", result.stringValue("output").orEmpty())
            val exitCode = result["exitCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            exitCode?.let { put("exitCode", it) }
            put("cancelled", result["cancelled"]?.jsonPrimitive?.booleanOrNull ?: false)
            put("truncated", result["truncated"]?.jsonPrimitive?.booleanOrNull ?: false)
            result.stringValue("fullOutputPath")?.let { put("fullOutputPath", it) }
        }

    private fun rpcExtensionUiEvent(data: JsonObject): JsonObject =
        buildJsonObject {
            put("type", "extension_ui_request")
            val method = data.stringValue("method")
            data.forEach { (name, value) ->
                when {
                    method == "setStatus" && name == "key" -> put("statusKey", value)
                    method == "setStatus" && name == "text" -> put("statusText", value)
                    else -> put(name, value)
                }
            }
        }

    private fun recordBashResult(
        command: String,
        result: JsonObject,
        excludeFromContext: Boolean?,
    ) {
        val message =
            BashExecutionMessage(
                command = command,
                output = result.stringValue("output").orEmpty(),
                exitCode = result["exitCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                cancelled = result["cancelled"]?.jsonPrimitive?.booleanOrNull ?: false,
                truncated = result["truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
                fullOutputPath = result.stringValue("fullOutputPath"),
                excludeFromContext = excludeFromContext,
            )
        synchronized(pendingBashMessages) {
            if (agent.state.isStreaming) {
                pendingBashMessages += message
                return
            }
        }
        appendBashMessage(message)
    }

    private fun flushPendingBashMessages() {
        val pending =
            synchronized(pendingBashMessages) {
                pendingBashMessages.toList().also { pendingBashMessages.clear() }
            }
        pending.forEach(::appendBashMessage)
    }

    @Synchronized
    private fun appendBashMessage(message: BashExecutionMessage) {
        sessionManager.appendMessage(message)
        agent.state.messages = agent.state.messages + message
    }

    private fun userMessage(command: JsonObject): UserMessage {
        val rawText = command.string("message").orEmpty()
        val resources = promptResources
        val text =
            if (resources == null) {
                rawText
            } else {
                expandResourceCommand(
                    text = rawText,
                    skills = resources.skills,
                    templates = resources.promptTemplates,
                )
            }
        val images =
            command["images"]
                ?.jsonArray
                ?.map { protocolJson.decodeFromJsonElement(ImageContent.serializer(), it) }
                .orEmpty()
        return UserMessage(listOf(TextContent(text)) + images)
    }

    private fun resolvePath(value: String): Path {
        val path = Path.of(value)
        return (if (path.isAbsolute) path else options.cwd.resolve(path)).toAbsolutePath().normalize()
    }

    private fun encodeTreeNode(node: SessionTreeNode): JsonObject =
        buildJsonObject {
            put("entry", encodeEntry(node.entry))
            put("children", JsonArray(node.children.map(::encodeTreeNode)))
            node.label?.let { put("label", it) }
            node.labelTimestamp?.let { put("labelTimestamp", it) }
        }

    private fun availableThinkingLevels(): List<AgentThinkingLevel> =
        if (!agent.state.model.reasoning) {
            listOf(AgentThinkingLevel.OFF)
        } else {
            AgentThinkingLevel.entries.filter { level ->
                val modelLevel = ModelThinkingLevel.valueOf(level.name)
                val mapping = agent.state.model.thinkingLevelMap
                when {
                    mapping.containsKey(modelLevel) && mapping[modelLevel] == null -> false
                    level == AgentThinkingLevel.XHIGH || level == AgentThinkingLevel.MAX ->
                        mapping.containsKey(modelLevel)

                    else -> true
                }
            }
        }

    private fun emit(value: JsonObject) {
        if (initializingExtensions && listeners.isEmpty()) {
            synchronized(pendingEvents) {
                if (initializingExtensions && listeners.isEmpty()) {
                    pendingEvents += value
                    return
                }
            }
        }
        listeners.forEach { listener -> listener(value) }
    }

    private fun sourceInfoJson(sourceInfo: ResourceSourceInfo): JsonObject =
        buildJsonObject {
            put("path", sourceInfo.path.toString())
            put("source", sourceInfo.source)
            put("scope", sourceInfo.scope)
            put("origin", sourceInfo.origin)
            sourceInfo.baseDir?.let { put("baseDir", it.toString()) }
        }
}

private class RunningBash(
    private val cancelAction: () -> Unit,
) {
    val cancelled = AtomicBoolean(false)

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return
        }
        runCatching(cancelAction)
    }
}

suspend fun runRpcJsonLines(
    runtime: RpcRuntime,
    input: BufferedReader,
    output: PrintWriter,
) = coroutineScope {
    val lock = Any()
    val jobs = ConcurrentHashMap.newKeySet<Job>()
    val unsubscribe =
        runtime.subscribe { value ->
            synchronized(lock) {
                output.println(protocolJson.encodeToString(JsonObject.serializer(), value))
                output.flush()
            }
        }
    try {
        while (true) {
            val line = withContext(Dispatchers.IO) { input.readLine() } ?: break
            if (line.isBlank()) {
                continue
            }
            val job =
                launch {
                    val response = runtime.handleLine(line)
                    response?.let {
                        synchronized(lock) {
                            output.println(protocolJson.encodeToString(JsonObject.serializer(), it))
                            output.flush()
                        }
                    }
                }
            jobs += job
            job.invokeOnCompletion { jobs -= job }
        }
    } finally {
        runtime.close()
        jobs.toList().joinAll()
        unsubscribe()
    }
}

private fun successResponse(
    id: String?,
    command: String,
    data: JsonElement? = null,
): JsonObject =
    buildJsonObject {
        id?.let { put("id", it) }
        put("type", "response")
        put("command", command)
        put("success", true)
        if (data != null) {
            put("data", data)
        }
    }

private fun errorResponse(
    id: String?,
    command: String,
    message: String,
): JsonObject =
    buildJsonObject {
        id?.let { put("id", it) }
        put("type", "response")
        put("command", command)
        put("success", false)
        put("error", message)
    }

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun String?.toQueueMode(): QueueMode =
    when (this) {
        "all" -> QueueMode.ALL
        "one-at-a-time" -> QueueMode.ONE_AT_A_TIME
        else -> error("Invalid queue mode: $this")
    }

private fun QueueMode.toProtocolValue(): String =
    when (this) {
        QueueMode.ALL -> "all"
        QueueMode.ONE_AT_A_TIME -> "one-at-a-time"
    }

private fun String.toAgentThinking(): AgentThinkingLevel? =
    when (this) {
        "off" -> AgentThinkingLevel.OFF
        "minimal" -> AgentThinkingLevel.MINIMAL
        "low" -> AgentThinkingLevel.LOW
        "medium" -> AgentThinkingLevel.MEDIUM
        "high" -> AgentThinkingLevel.HIGH
        "xhigh" -> AgentThinkingLevel.XHIGH
        "max" -> AgentThinkingLevel.MAX
        else -> null
    }

private fun AgentThinkingLevel.toProviderThinking(): ThinkingLevel? =
    when (this) {
        AgentThinkingLevel.OFF -> null
        AgentThinkingLevel.MINIMAL -> ThinkingLevel.MINIMAL
        AgentThinkingLevel.LOW -> ThinkingLevel.LOW
        AgentThinkingLevel.MEDIUM -> ThinkingLevel.MEDIUM
        AgentThinkingLevel.HIGH -> ThinkingLevel.HIGH
        AgentThinkingLevel.XHIGH -> ThinkingLevel.XHIGH
        AgentThinkingLevel.MAX -> ThinkingLevel.MAX
    }

private fun works.earendil.pi.codingagent.AgentThinkingLevel.toCoreThinking(): AgentThinkingLevel =
    when (this) {
        works.earendil.pi.codingagent.AgentThinkingLevel.OFF -> AgentThinkingLevel.OFF
        works.earendil.pi.codingagent.AgentThinkingLevel.MINIMAL -> AgentThinkingLevel.MINIMAL
        works.earendil.pi.codingagent.AgentThinkingLevel.LOW -> AgentThinkingLevel.LOW
        works.earendil.pi.codingagent.AgentThinkingLevel.MEDIUM -> AgentThinkingLevel.MEDIUM
        works.earendil.pi.codingagent.AgentThinkingLevel.HIGH -> AgentThinkingLevel.HIGH
        works.earendil.pi.codingagent.AgentThinkingLevel.XHIGH -> AgentThinkingLevel.XHIGH
        works.earendil.pi.codingagent.AgentThinkingLevel.MAX -> AgentThinkingLevel.MAX
    }
