package works.earendil.pi.codingagent

import java.io.BufferedReader
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StreamFunction
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.ai.contentText
import works.earendil.pi.codingagent.session.NewSessionOptions
import works.earendil.pi.codingagent.session.SessionManager
import works.earendil.pi.codingagent.session.SessionMessageEntry
import works.earendil.pi.codingagent.session.SessionTreeNode
import works.earendil.pi.codingagent.session.encodeEntry
import works.earendil.pi.codingagent.tools.truncateTail
import kotlin.math.max

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
    val apiKey: String? = null,
    val systemPrompt: String? = null,
    val appendSystemPrompt: List<String> = emptyList(),
    val noContextFiles: Boolean = false,
    val skillPaths: List<String> = emptyList(),
    val noSkills: Boolean = false,
    val promptTemplatePaths: List<String> = emptyList(),
    val noPromptTemplates: Boolean = false,
    val projectTrusted: Boolean? = null,
    val extensionPaths: List<String> = emptyList(),
    val noExtensions: Boolean = false,
    val extensionFlagValues: Map<String, Any> = emptyMap(),
    val extensionMode: ExtensionMode = ExtensionMode.RPC,
    val noTools: Boolean = false,
    val noBuiltinTools: Boolean = false,
    val tools: List<String>? = null,
    val excludeTools: List<String>? = null,
    val thinking: works.earendil.pi.codingagent.AgentThinkingLevel? = null,
    val projectTrustPrompt: ((Path, List<String>) -> Int?)? = null,
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
    private var autoRetryEnabled = true
    private var promptJob: Job? = null
    private var bashProcess: Process? = null
    private var promptResources: PromptResources? = null
    private var extensionHost: ExtensionHost? = null
    private val extensionProviders = ExtensionProviderRegistry(models)
    private var extensionContextProvider: () -> JsonObject = { JsonObject(emptyMap()) }
    private var baseSystemPrompt: String = ""
    private var availableTools: List<AgentTool> = emptyList()
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
        val id = command.string("id")
        val type = command.string("type") ?: return errorResponse(id, "unknown", "Command type is required")
        return try {
            when (type) {
                "prompt" -> handlePrompt(command, id)
                "steer" -> {
                    agent.steer(userMessage(command))
                    successResponse(id, type)
                }

                "follow_up" -> {
                    agent.followUp(userMessage(command))
                    successResponse(id, type)
                }

                "abort" -> {
                    promptJob?.cancel()
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
                    agent.state.thinkingLevel = level
                    sessionManager.appendThinkingLevelChange(level.toProtocolValue())
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
                    successResponse(id, type)
                }

                "set_follow_up_mode" -> {
                    followUpMode = command.string("mode").toQueueMode()
                    agent.setFollowUpMode(followUpMode)
                    successResponse(id, type)
                }

                "compact" -> handleCompact(command, id)
                "set_auto_compaction" -> {
                    autoCompactionEnabled = command["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                    successResponse(id, type)
                }

                "set_auto_retry" -> {
                    autoRetryEnabled = command["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                    successResponse(id, type)
                }

                "abort_retry" -> successResponse(id, type)
                "bash" -> handleBash(command, id)
                "abort_bash" -> {
                    bashProcess?.destroyForcibly()
                    successResponse(id, type)
                }

                "get_session_stats" -> successResponse(id, type, sessionStatsJson())
                "export_html" -> {
                    val outputPath = command.string("outputPath")?.let(::resolvePath)
                    val path = exportSession(sessionManager, outputPath)
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
                            sessionManager.getLeafId()?.let { put("leafId", it) } ?: put("leafId", JsonNull)
                        },
                    )

                "get_last_assistant_text" ->
                    successResponse(
                        id,
                        type,
                        buildJsonObject {
                            lastAssistantText()?.let { put("text", it) } ?: put("text", JsonNull)
                        },
                    )

                "set_session_name" -> {
                    val name = command.string("name").orEmpty().trim()
                    if (name.isEmpty()) {
                        errorResponse(id, type, "Session name cannot be empty")
                    } else {
                        sessionManager.appendSessionInfo(name)
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
                                JsonArray(
                                    agent.state.messages.map {
                                        protocolJson.encodeToJsonElement(Message.serializer(), it)
                                    },
                                ),
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
        promptJob?.cancel()
        bashProcess?.destroyForcibly()
        runCatching {
            emitExtensionEvent(
                host = extensionHost,
                event = buildJsonObject { put("type", "session_shutdown") },
                context = extensionContextProvider,
                onActions = { applyExtensionActions(it) },
            )
        }
        extensionHost?.close()
        extensionHost = null
        extensionProviders.reset()
        scope.cancel()
    }

    suspend fun waitForIdle() {
        promptJob?.join()
        agent.waitForIdle()
    }

    fun reloadResources() {
        ensureIdle("reload")
        shutdownExtensionSession()
        agent = createAgent()
        activateExtensionSession("reload")
    }

    private suspend fun handlePrompt(
        command: JsonObject,
        id: String?,
    ): JsonObject {
        findExtensionCommand(extensionHost, command.string("message").orEmpty())?.let { (name, args) ->
            val host = extensionHost
                ?: return@let
            val invocation =
                withContext(Dispatchers.IO) {
                    host.invokeCommand(name, args, extensionContextProvider())
                }
            applyExtensionActions(invocation.actions)
            if (!agent.state.isStreaming) {
                emit(buildJsonObject { put("type", "agent_settled") })
            }
            return successResponse(id, "prompt")
        }
        if (agent.state.isStreaming) {
            return when (command.string("streamingBehavior")) {
                "steer" -> {
                    agent.steer(userMessage(command))
                    successResponse(id, "prompt")
                }

                "followUp" -> {
                    agent.followUp(userMessage(command))
                    successResponse(id, "prompt")
                }

                else -> errorResponse(id, "prompt", "Agent is already processing a prompt")
            }
        }
        val prompt = userMessage(command)
        promptJob =
            scope.launch {
                try {
                    val before =
                        emitExtensionBeforeAgentStart(
                            host = extensionHost,
                            prompt = contentText(prompt.content),
                            systemPrompt = baseSystemPrompt,
                            context = extensionContextProvider,
                            onActions = { applyExtensionActions(it) },
                        )
                    agent.state.systemPrompt = before?.systemPrompt ?: baseSystemPrompt
                    agent.prompt(prompt)
                } finally {
                    emit(buildJsonObject { put("type", "agent_settled") })
                    promptJob = null
                }
            }
        return successResponse(id, "prompt")
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
        return successResponse(
            id,
            "set_model",
            protocolJson.encodeToJsonElement(Model.serializer(), model).jsonObject,
        )
    }

    private suspend fun handleCycleModel(id: String?): JsonObject {
        val available =
            models
                .getAvailable()
                .sortedWith(compareBy<Model> { it.provider }.thenBy { it.id })
        if (available.size <= 1) {
            return successResponse(id, "cycle_model", JsonNull)
        }
        val currentIndex = available.indexOfFirst { it.provider == agent.state.model.provider && it.id == agent.state.model.id }
        val next = available[(currentIndex + 1).mod(available.size)]
        agent.state.model = next
        sessionManager.appendModelChange(next.provider, next.id)
        return successResponse(
            id,
            "cycle_model",
            buildJsonObject {
                put("model", protocolJson.encodeToJsonElement(Model.serializer(), next))
                put("thinkingLevel", agent.state.thinkingLevel.toProtocolValue())
                put("isScoped", false)
            },
        )
    }

    private fun handleCycleThinking(id: String?): JsonObject {
        val levels = availableThinkingLevels()
        if (levels.isEmpty()) {
            return successResponse(id, "cycle_thinking_level", JsonNull)
        }
        val current = levels.indexOf(agent.state.thinkingLevel)
        val next = levels[(current + 1).mod(levels.size)]
        agent.state.thinkingLevel = next
        sessionManager.appendThinkingLevelChange(next.toProtocolValue())
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
        ensureIdle("compact")
        val entries = sessionManager.getBranch()
        val preparation =
            prepareCompaction(entries)
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
            compact(
                preparation = preparation,
                models = models,
                model = agent.state.model,
                apiKey = options.apiKey,
                customInstructions = command.string("customInstructions"),
                thinkingLevel = agent.state.thinkingLevel.toProviderThinking(),
            )
        sessionManager.appendCompaction(
            result.summary,
            result.firstKeptEntryId,
            result.tokensBefore,
            result.details,
            usage = result.usage,
        )
        agent.state.messages = sessionManager.buildSessionContext().messages
        val data =
            buildJsonObject {
                put("summary", result.summary)
                put("firstKeptEntryId", result.firstKeptEntryId)
                put("tokensBefore", result.tokensBefore)
                put("estimatedTokensAfter", estimateContextTokens(agent.state.messages).tokens)
                put("usage", protocolJson.encodeToJsonElement(Usage.serializer(), result.usage))
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
    }

    private suspend fun handleBash(
        command: JsonObject,
        id: String?,
    ): JsonObject {
        val shellCommand = command.string("command")
            ?: return errorResponse(id, "bash", "Command is required")
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
                        .redirectErrorStream(true)
                        .start()
                bashProcess = process
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
                            emit(
                                buildJsonObject {
                                    put("type", "bash_execution_update")
                                    id?.let { put("id", it) }
                                    put("delta", delta)
                                },
                            )
                        }
                    }
                    val exitCode = process.waitFor()
                    val truncated = truncateTail(output.toString())
                    buildJsonObject {
                        put("output", truncated.content)
                        put("exitCode", exitCode)
                        put("cancelled", false)
                        put("truncated", truncated.truncated)
                    }
                } finally {
                    bashProcess = null
                }
            }
        return successResponse(id, "bash", result)
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
        if (!clone) {
            val entryId = command.string("entryId")
                ?: return errorResponse(id, "fork", "Entry id is required")
            if (forked.getEntry(entryId) == null) {
                return errorResponse(id, "fork", "Entry $entryId not found")
            }
            forked.branch(entryId)
        }
        replaceSession(forked)
        return if (clone) {
            successResponse(id, "clone", buildJsonObject { put("cancelled", false) })
        } else {
            successResponse(
                id,
                "fork",
                buildJsonObject {
                    put("text", lastAssistantText().orEmpty())
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
                sessionManager.getLeafId()?.let { put("leafId", it) } ?: put("leafId", JsonNull)
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
        var cachedTokens = 0
        var uncachedTokens = 0
        var totalTokens = 0
        var costTotal = 0.0
        val messages = sessionManager.getEntries().filterIsInstance<SessionMessageEntry>()
        messages.forEach { entry ->
            val usage = (entry.message as? AssistantMessage)?.usage ?: return@forEach
            cachedTokens += usage.cacheRead
            uncachedTokens += usage.input + usage.cacheWrite
            totalTokens += usage.input + usage.output + usage.cacheRead + usage.cacheWrite
            costTotal += usage.cost.total
        }
        return buildJsonObject {
            put("messageCount", messages.size)
            put("cachedTokens", cachedTokens)
            put("uncachedTokens", uncachedTokens)
            put("totalTokens", totalTokens)
            put("costTotal", costTotal)
        }
    }

    private fun stateJson(): JsonObject =
        buildJsonObject {
            put("model", protocolJson.encodeToJsonElement(Model.serializer(), agent.state.model))
            put("thinkingLevel", agent.state.thinkingLevel.toProtocolValue())
            put("isStreaming", agent.state.isStreaming)
            put("isCompacting", false)
            put("steeringMode", steeringMode.toProtocolValue())
            put("followUpMode", followUpMode.toProtocolValue())
            sessionManager.getSessionFile()?.let { put("sessionFile", it.toString()) }
            put("sessionId", sessionManager.getSessionId())
            sessionManager.getSessionName()?.let { put("sessionName", it) }
            put("autoCompactionEnabled", autoCompactionEnabled)
            put("messageCount", agent.state.messages.size)
            put("pendingMessageCount", if (agent.hasQueuedMessages()) 1 else 0)
        }

    private fun lastAssistantText(): String? =
        agent.state.messages
            .filterIsInstance<AssistantMessage>()
            .lastOrNull()
            ?.content
            ?.filterIsInstance<TextContent>()
            ?.joinToString("") { it.text }
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
        extensionHost?.close()
        extensionHost = null
        extensionProviders.reset()
        val context = sessionManager.buildSessionContext()
        val thinking =
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
        var modelRef: Model? =
            context.model?.let { models.getModel(it.provider, it.modelId) }
                ?: models.getModels().firstOrNull()

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
                onProjectTrustPrompt =
                    options.projectTrustPrompt?.let { prompt ->
                        { path, choices ->
                            prompt(path, choices.map(ProjectTrustOption::label))
                                ?.let(choices::getOrNull)
                        }
                    },
            )
        projectTrusted = bootstrap.projectTrusted
        val host = bootstrap.host
        extensionHost = host
        extensionContextProvider = ::currentExtensionContext
        applyBootstrapExtensionActions(host?.drainStartupActions().orEmpty())
        val model =
            resolveModel(context.model?.provider, context.model?.modelId)
                ?: error("No model is available")
        modelRef = model
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
                        emitExtensionAfterToolCall(
                            host = host,
                            context = ::currentExtensionContext,
                            onActions = { applyExtensionActions(it) },
                            call = call,
                        )
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
        created.subscribe { event ->
            emitExtensionAgentEvent(
                host = host,
                event = event,
                context = ::currentExtensionContext,
                onActions = { applyExtensionActions(it) },
            )
            emit(encodeAgentEvent(event))
            if (event is AgentEvent.MessageEnd) {
                sessionManager.appendMessage(event.message)
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
        extensionHost = null
        extensionProviders.reset()
    }

    private fun applyExtensionActions(actions: List<ExtensionAction>) {
        actions.forEach { action ->
            when (action.type) {
                "ui" ->
                    emit(
                        JsonObject(
                            mapOf("type" to JsonPrimitive("extension_ui_request")) + action.data,
                        ),
                    )

                "append_entry" ->
                    action.data.stringValue("customType")?.let { customType ->
                        sessionManager.appendCustomEntry(customType, action.data["data"])
                    }

                "set_session_name" ->
                    action.data.stringValue("name")?.let(sessionManager::appendSessionInfo)

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

                "set_thinking_level" ->
                    action.data.stringValue("level")
                        ?.toCoreThinkingLevel()
                        ?.let { level ->
                            agent.state.thinkingLevel = level
                            sessionManager.appendThinkingLevelChange(level.toProtocolValue())
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

                "send_message" ->
                    appendExtensionMessage(sessionManager, action.data["message"])

                "send_user_message" -> {
                    if (!queueExtensionUserMessage(agent, action.data)) {
                        emitExtensionError(
                            ExtensionDiagnostic(
                                extensionPath = "<action>",
                                event = "send_user_message",
                                error = "Idle extension-triggered turns are not migrated yet",
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
                    emit(
                        JsonObject(
                            mapOf("type" to JsonPrimitive("extension_ui_request")) + action.data,
                        ),
                    )

                "append_entry" ->
                    action.data.stringValue("customType")?.let { customType ->
                        sessionManager.appendCustomEntry(customType, action.data["data"])
                    }

                "set_session_name" ->
                    action.data.stringValue("name")?.let(sessionManager::appendSessionInfo)

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

    private fun resolveModel(
        sessionProvider: String?,
        sessionModel: String?,
    ): Model? {
        if (options.provider != null || options.model != null) {
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
        val googleModels = models.getModels("google")
        return googleModels.firstOrNull { it.id == defaultModelId("google") }
            ?: googleModels.firstOrNull()
            ?: models.getModels().firstOrNull()
    }

    private fun replaceSession(session: SessionManager) {
        shutdownExtensionSession()
        sessionManager = session
        agent = createAgent()
        activateExtensionSession("new")
    }

    private fun ensureIdle(command: String) {
        check(!agent.state.isStreaming) { "$command is not available while the agent is streaming" }
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
        return if (images.isEmpty()) {
            UserMessage(text)
        } else {
            UserMessage(listOf(TextContent(text)) + images)
        }
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
        if (agent.state.model.reasoning) {
            AgentThinkingLevel.entries
        } else {
            listOf(AgentThinkingLevel.OFF)
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

suspend fun runRpcJsonLines(
    runtime: RpcRuntime,
    input: BufferedReader,
    output: PrintWriter,
) {
    val lock = Any()
    val unsubscribe =
        runtime.subscribe { value ->
            synchronized(lock) {
                output.println(protocolJson.encodeToString(JsonObject.serializer(), value))
                output.flush()
            }
        }
    try {
        input.lineSequence().forEach { line ->
            if (line.isBlank()) {
                return@forEach
            }
            runtime.handleLine(line)?.let { response ->
                synchronized(lock) {
                    output.println(protocolJson.encodeToString(JsonObject.serializer(), response))
                    output.flush()
                }
            }
        }
    } finally {
        unsubscribe()
        runtime.close()
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
