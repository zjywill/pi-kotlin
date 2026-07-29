package works.earendil.pi.codingagent

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import works.earendil.pi.agent.Agent
import works.earendil.pi.agent.AgentEvent
import works.earendil.pi.agent.AgentInitialState
import works.earendil.pi.agent.AgentOptions
import works.earendil.pi.agent.AgentTool
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StreamFunction
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.codingagent.session.NewSessionOptions
import works.earendil.pi.codingagent.session.SessionHeader
import works.earendil.pi.codingagent.session.SessionManager
import works.earendil.pi.codingagent.session.SessionModel
import works.earendil.pi.codingagent.session.assertValidSessionId
import works.earendil.pi.codingagent.session.encodeEntry
import works.earendil.pi.tui.fuzzyFilter

class CliRuntime(
    private val models: Models,
    private val cwd: Path = Path.of("").toAbsolutePath().normalize(),
    private val agentDir: Path = defaultAgentDirectory(),
    private val stdinContent: String? = null,
    private val stdout: PrintWriter = PrintWriter(System.out, true),
    private val stderr: PrintWriter = PrintWriter(System.err, true),
) {
    suspend fun run(args: Args): Int {
        args.diagnostics.forEach { diagnostic ->
            val prefix = if (diagnostic.type == Diagnostic.Type.ERROR) "Error: " else "Warning: "
            stderr.println(prefix + diagnostic.message)
        }
        if (args.diagnostics.any { it.type == Diagnostic.Type.ERROR }) {
            return 2
        }
        if (args.listModelsRequested) {
            listModels(args.listModels)
            return 0
        }
        if (args.mode == OutputMode.RPC) {
            stderr.println("Error: RPC mode has not been migrated yet.")
            return 2
        }

        val sessionManager =
            try {
                createSessionManager(args)
            } catch (error: IllegalArgumentException) {
                stderr.println("Error: ${error.message}")
                return 1
            } catch (error: IllegalStateException) {
                stderr.println("Error: ${error.message}")
                return 1
            }
        if (args.name != null) {
            val name = args.name.orEmpty().trim()
            if (name.isEmpty()) {
                stderr.println("Error: --name requires a non-empty value")
                return 1
            }
            sessionManager.appendSessionInfo(name)
        }
        val initialContext = sessionManager.buildSessionContext()
        val runtimeCwd = sessionManager.getCwd()
        val requestedThinking =
            args.thinking ?: parseModelReference(args.provider, args.model).thinking
        var initialThinking =
            (requestedThinking ?: initialContext.thinkingLevel.toCliThinkingLevel())
                .toAgentThinkingLevel()
        val initialBuiltInTools =
            createSelectedCodingTools(
                cwd = runtimeCwd,
                noTools = args.noTools,
                noBuiltinTools = args.noBuiltinTools,
                allowedTools = args.tools,
                excludedTools = args.excludeTools,
            )
        var agentRef: Agent? = null
        var selectedTools: List<AgentTool> = initialBuiltInTools
        var extensionHost: ExtensionHost? = null
        val providerRegistry = ExtensionProviderRegistry(models, extensionHost = { extensionHost })
        val extensionActionLock = Any()
        var promptResourcesRef: PromptResources? = null
        var baseSystemPrompt = ""
        var refreshExtensionRegistrations: () -> Unit = {}
        var projectTrusted = false
        var scopedModels: List<ScopedModel> = emptyList()
        var modelRef: Model? =
            initialContext.model?.let { models.getModel(it.provider, it.modelId) }
                ?: models.getModels().firstOrNull()

        fun currentExtensionContext(): JsonObject {
            val state = agentRef?.state
            return extensionContextJson(
                cwd = runtimeCwd,
                mode = ExtensionMode.PRINT,
                projectTrusted = projectTrusted,
                model = state?.model ?: modelRef,
                thinkingLevel = (state?.thinkingLevel ?: initialThinking).toProtocolValue(),
                systemPrompt = state?.systemPrompt.orEmpty(),
                activeTools = (state?.tools ?: selectedTools).map(AgentTool::name),
                allTools = selectedTools,
                sessionName = sessionManager.getSessionName(),
                sessionId = sessionManager.getSessionId(),
                sessionFile = sessionManager.getSessionFile(),
                isIdle = state?.isStreaming != true,
                hasPendingMessages = agentRef?.hasQueuedMessages() == true,
                flagValues = args.unknownFlags,
                scopedModels = scopedModels,
            )
        }

        fun applyExtensionActions(actions: List<ExtensionAction>) {
            synchronized(extensionActionLock) {
                actions.forEach { action ->
                    when (action.type) {
                    "ui" -> {
                        if (action.data.stringValue("method") == "notify") {
                            stderr.println(action.data.stringValue("message").orEmpty())
                        }
                    }

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
                                stderr.println("Warning: Extension set_label failed: ${error.message}")
                            }
                        }
                    }

                    "set_active_tools" -> {
                        val names =
                            action.data["toolNames"]
                                ?.let { value -> value as? kotlinx.serialization.json.JsonArray }
                                .orEmpty()
                                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        agentRef?.state?.tools = selectedTools.filter { it.name in names }
                    }

                    "set_thinking_level" ->
                        action.data.stringValue("level")
                            ?.toCoreThinkingLevel()
                            ?.let { level ->
                                agentRef?.state?.thinkingLevel = level
                                sessionManager.appendThinkingLevelChange(level.toProtocolValue())
                            }

                    "send_message" ->
                        appendExtensionMessage(sessionManager, action.data["message"])

                    "send_user_message" ->
                        queueExtensionUserMessage(agentRef, action.data)

                    "register_provider" -> {
                        val name = action.data.stringValue("name")
                        val config = action.data["config"] as? JsonObject
                        if (name == null || config == null) {
                            stderr.println("Warning: Extension provider registration is invalid.")
                        } else {
                            runCatching { providerRegistry.register(name, config) }
                                .onSuccess {
                                    val current = agentRef?.state?.model
                                    if (current != null) {
                                        models.getModel(current.provider, current.id)?.let { refreshed ->
                                            agentRef?.state?.model = refreshed
                                        }
                                    }
                                }
                                .onFailure { error ->
                                    stderr.println("Warning: ${error.message}")
                                }
                        }
                    }

                    "unregister_provider" ->
                        action.data.stringValue("name")?.let { name ->
                            providerRegistry.unregister(name)
                            val current = agentRef?.state?.model
                            if (current != null) {
                                models.getModel(current.provider, current.id)?.let { refreshed ->
                                    agentRef?.state?.model = refreshed
                                }
                            }
                        }

                    "registrations_changed" -> refreshExtensionRegistrations()

                    "unsupported" ->
                        stderr.println(
                            "Warning: Extension UI method ${action.data.stringValue("method").orEmpty()} " +
                                "is not available in print mode.",
                        )
                    }
                }
            }
        }

        val bootstrap =
            try {
                bootstrapExtensions(
                    cwd = runtimeCwd,
                    agentDir = agentDir,
                    trustOverride = args.projectTrustOverride,
                    explicitPaths = args.extensions,
                    noExtensions = args.noExtensions,
                    mode = ExtensionMode.PRINT,
                    flagValues = args.unknownFlags,
                    context = { trusted ->
                        extensionContextJson(
                            cwd = runtimeCwd,
                            mode = ExtensionMode.PRINT,
                            projectTrusted = trusted,
                            model = modelRef,
                            thinkingLevel = initialThinking.toProtocolValue(),
                            systemPrompt = "",
                            activeTools = initialBuiltInTools.map(AgentTool::name),
                            allTools = initialBuiltInTools,
                            sessionName = sessionManager.getSessionName(),
                            sessionId = sessionManager.getSessionId(),
                            sessionFile = sessionManager.getSessionFile(),
                            isIdle = true,
                            hasPendingMessages = false,
                            flagValues = args.unknownFlags,
                            scopedModels =
                                resolveConfiguredModelScope(
                                    explicitPatterns = args.models,
                                    availableModels = models.getModels(),
                                    cwd = runtimeCwd,
                                    agentDir = agentDir,
                                    projectTrusted = trusted,
                                ).scopedModels,
                        )
                    },
                    onWarning = { stderr.println("Warning: $it") },
                    onDiagnostic = { diagnostic ->
                        stderr.println(
                            "Warning: Extension ${diagnostic.extensionPath} ${diagnostic.event}: ${diagnostic.error}",
                        )
                    },
                    onLog = stderr::println,
                    onBootstrapActions = ::applyExtensionActions,
                )
            } catch (error: Exception) {
                stderr.println("Error: ${error.message}")
                return 1
            }
        projectTrusted = bootstrap.projectTrusted
        extensionHost = bootstrap.host
        applyExtensionActions(extensionHost?.drainStartupActions().orEmpty())
        val scopeResolution =
            resolveConfiguredModelScope(
                explicitPatterns = args.models,
                availableModels = models.getAvailable(),
                cwd = runtimeCwd,
                agentDir = agentDir,
                projectTrusted = projectTrusted,
            )
        scopedModels = scopeResolution.scopedModels
        scopeResolution.diagnostics.forEach { diagnostic ->
            stderr.println("Warning: ${diagnostic.message}")
        }
        if (
            args.thinking == null &&
            args.model == null &&
            initialContext.model == null
        ) {
            scopedModels.firstOrNull()?.thinkingLevel?.let { scopedThinking ->
                initialThinking = scopedThinking.toAgentThinkingLevel()
            }
        }
        val model =
            resolveModel(args, initialContext.model, scopedModels)
                ?: run {
                    stderr.println("Error: No model matched the requested provider/model.")
                    providerRegistry.reset()
                    extensionHost?.close()
                    return 1
                }
        modelRef = model
        var promptResources =
            loadPromptResources(
                cwd = runtimeCwd,
                agentDir = agentDir,
                systemPromptSource = args.systemPrompt,
                appendPromptSources = args.appendSystemPrompt,
                noContextFiles = args.noContextFiles,
                skillPaths = args.skills,
                noSkills = args.noSkills,
                promptTemplatePaths = args.promptTemplates,
                noPromptTemplates = args.noPromptTemplates,
                projectTrusted = projectTrusted,
                resolvedPackageResources = bootstrap.packageResources,
                onWarning = { stderr.println("Warning: $it") },
            )
        promptResourcesRef = promptResources
        val extensionTools =
            extensionHost
                ?.registrations
                ?.tools
                .orEmpty()
                .map { registration ->
                    HostedExtensionTool(
                        registration = registration,
                        host = requireNotNull(extensionHost),
                        context = ::currentExtensionContext,
                        onActions = ::applyExtensionActions,
                    )
                }
        selectedTools =
            createSelectedCodingTools(
                cwd = runtimeCwd,
                noTools = args.noTools,
                noBuiltinTools = args.noBuiltinTools,
                allowedTools = args.tools,
                excludedTools = args.excludeTools,
                extensionTools = extensionTools,
            )
        baseSystemPrompt = buildCodingSystemPrompt(runtimeCwd, selectedTools, promptResources)
        val agent =
            Agent(
                AgentOptions(
                    streamFunction =
                        StreamFunction { requestModel, context: Context, options: SimpleStreamOptions ->
                            models.streamSimple(requestModel, context, options)
                        },
                    convertToLlm = { messages -> convertCodingMessagesToLlm(messages) },
                    beforeToolCall = { call ->
                        emitExtensionBeforeToolCall(
                            host = extensionHost,
                            context = ::currentExtensionContext,
                            onActions = ::applyExtensionActions,
                            call = call,
                        )
                    },
                    afterToolCall = { call ->
                        emitExtensionAfterToolCall(
                            host = extensionHost,
                            context = ::currentExtensionContext,
                            onActions = ::applyExtensionActions,
                            call = call,
                        )
                    },
                    initialState =
                        AgentInitialState(
                            systemPrompt = baseSystemPrompt,
                            model = model,
                            thinkingLevel = initialThinking,
                            tools = selectedTools,
                            messages = initialContext.messages,
                        ),
                    streamOptions =
                        SimpleStreamOptions(
                            stream =
                                StreamOptions(
                                apiKey = args.apiKey,
                                sessionId = sessionManager.getSessionId(),
                            ),
                            reasoning = initialThinking.toProviderThinkingLevel(),
                        ),
                ),
            )
        agentRef = agent
        refreshExtensionRegistrations = refresh@{
            val host = extensionHost ?: return@refresh
            val previousRegistryNames = selectedTools.mapTo(mutableSetOf(), AgentTool::name)
            val previousActiveNames = agent.state.tools.mapTo(mutableSetOf(), AgentTool::name)
            val refreshedExtensionTools =
                host.registrations.tools.map { registration ->
                    HostedExtensionTool(
                        registration = registration,
                        host = host,
                        context = ::currentExtensionContext,
                        onActions = ::applyExtensionActions,
                    )
                }
            val refreshedTools =
                createSelectedCodingTools(
                    cwd = runtimeCwd,
                    noTools = args.noTools,
                    noBuiltinTools = args.noBuiltinTools,
                    allowedTools = args.tools,
                    excludedTools = args.excludeTools,
                    extensionTools = refreshedExtensionTools,
                )
            val newlyRegisteredNames =
                refreshedTools
                    .mapTo(mutableSetOf(), AgentTool::name)
                    .apply { removeAll(previousRegistryNames) }
            selectedTools = refreshedTools
            agent.state.tools =
                refreshedTools.filter { tool ->
                    tool.name in previousActiveNames || tool.name in newlyRegisteredNames
                }
            promptResourcesRef?.let { resources ->
                baseSystemPrompt = buildCodingSystemPrompt(runtimeCwd, agent.state.tools, resources)
                agent.state.systemPrompt = baseSystemPrompt
            }
        }
        extensionHost?.bindBackgroundActions(::applyExtensionActions)
        if (args.mode == OutputMode.JSON) {
            sessionManager.getHeader()?.let { header ->
                stdout.println(protocolJson.encodeToString(JsonObject.serializer(), encodeEntry(header)))
            }
        }
        agent.subscribe { event ->
            emitExtensionAgentEvent(
                host = extensionHost,
                event = event,
                context = ::currentExtensionContext,
                onActions = ::applyExtensionActions,
            )
            if (args.mode == OutputMode.JSON) {
                stdout.println(protocolJson.encodeToString(JsonObject.serializer(), encodeAgentEvent(event)))
            }
            if (event is AgentEvent.MessageEnd) {
                sessionManager.appendMessage(event.message)
            }
        }

        try {
            emitExtensionEvent(
                host = extensionHost,
                event =
                    buildJsonObject {
                        put("type", "session_start")
                        put("reason", if (initialContext.messages.isEmpty()) "startup" else "resume")
                    },
                context = ::currentExtensionContext,
                onActions = ::applyExtensionActions,
            )
            val extensionResources =
                discoverExtensionResources(
                    host = extensionHost,
                    cwd = runtimeCwd,
                    reason = "startup",
                    context = currentExtensionContext(),
                    onActions = ::applyExtensionActions,
                )
            if (
                extensionResources.skills.isNotEmpty() ||
                extensionResources.prompts.isNotEmpty() ||
                extensionResources.themes.isNotEmpty()
            ) {
                promptResources =
                    loadPromptResources(
                        cwd = runtimeCwd,
                        agentDir = agentDir,
                        systemPromptSource = args.systemPrompt,
                        appendPromptSources = args.appendSystemPrompt,
                        noContextFiles = args.noContextFiles,
                        skillPaths = args.skills,
                        noSkills = args.noSkills,
                        promptTemplatePaths = args.promptTemplates,
                        noPromptTemplates = args.noPromptTemplates,
                        projectTrusted = projectTrusted,
                        resolvedPackageResources = bootstrap.packageResources.merge(extensionResources),
                        onWarning = { stderr.println("Warning: $it") },
                    )
                promptResourcesRef = promptResources
                baseSystemPrompt = buildCodingSystemPrompt(runtimeCwd, selectedTools, promptResources)
                agent.state.systemPrompt = baseSystemPrompt
            }
            val prompts =
                try {
                    buildInitialPrompts(
                        args = args,
                        cwd = runtimeCwd,
                        stdinContent = stdinContent,
                        resources = promptResources,
                        onWarning = { stderr.println("Warning: $it") },
                    )
                } catch (error: Exception) {
                    stderr.println("Error: ${error.message}")
                    return 1
                }
            if (prompts.isEmpty()) {
                stderr.println("Error: A prompt is required in print mode.")
                return 1
            }
            prompts.forEach { prompt ->
                val promptText = works.earendil.pi.ai.contentText(prompt.content)
                val extensionCommand = findExtensionCommand(extensionHost, promptText)
                if (extensionCommand != null) {
                    val (name, commandArgs) = extensionCommand
                    val invocation =
                        requireNotNull(extensionHost).invokeCommand(
                            name = name,
                            args = commandArgs,
                            context = currentExtensionContext(),
                        )
                    applyExtensionActions(invocation.actions)
                    return@forEach
                }
                val before =
                    emitExtensionBeforeAgentStart(
                        host = extensionHost,
                        prompt = promptText,
                        systemPrompt = baseSystemPrompt,
                        context = ::currentExtensionContext,
                        onActions = ::applyExtensionActions,
                    )
                agent.state.systemPrompt = before?.systemPrompt ?: baseSystemPrompt
                agent.prompt(prompt)
            }
            val finalMessage = agent.state.messages.filterIsInstance<AssistantMessage>().lastOrNull()
            if (args.mode != OutputMode.JSON && finalMessage != null) {
                if (finalMessage.errorMessage != null) {
                    stderr.println(finalMessage.errorMessage)
                    return 1
                }
                val text = finalMessage.content.filterIsInstance<TextContent>().joinToString("") { it.text }
                if (text.isNotEmpty()) {
                    stdout.println(text)
                }
            }
            return if (finalMessage?.errorMessage == null) 0 else 1
        } finally {
            runCatching {
                emitExtensionEvent(
                    host = extensionHost,
                    event = buildJsonObject { put("type", "session_shutdown") },
                    context = ::currentExtensionContext,
                    onActions = ::applyExtensionActions,
                )
            }
            extensionHost?.close()
            providerRegistry.reset()
        }
    }

    private suspend fun listModels(query: String?) {
        val all = models.getAvailable()
        val filtered =
            if (query.isNullOrBlank()) {
                all
            } else {
                fuzzyFilter(all, query) { "${it.id} ${it.provider}" }
            }
        filtered
            .sortedWith(compareBy<Model> { it.provider }.thenBy { it.id })
            .forEach { model ->
                stdout.println("${model.provider}/${model.id}\t${model.name}")
            }
    }

    private suspend fun resolveModel(
        args: Args,
        sessionModel: SessionModel?,
        scopedModels: List<ScopedModel>,
    ): Model? {
        if (args.provider == null && args.model == null && sessionModel != null) {
            return models
                .getAvailable(sessionModel.provider)
                .firstOrNull { it.id == sessionModel.modelId }
        }
        if (args.provider == null && args.model == null && scopedModels.isNotEmpty()) {
            return scopedModels.first().model
        }
        val reference = parseModelReference(args.provider, args.model)
        val providerName = reference.provider ?: "google"
        val candidates = models.getAvailable(providerName)
        val pattern = reference.modelId
        if (pattern.isNullOrBlank()) {
            val defaultId = defaultModelId(providerName)
            return candidates.firstOrNull { it.id == defaultId } ?: candidates.firstOrNull()
        }
        return candidates.firstOrNull { it.id == pattern }
            ?: fuzzyFilter(candidates, pattern) { it.id }.firstOrNull()
    }

    private fun createSessionManager(args: Args): SessionManager {
        val sessionDirectory = args.sessionDir?.let(::resolvePath)
        validateSessionFlags(args)
        args.sessionId?.let(::assertValidSessionId)
        if (args.noSession) {
            return SessionManager.inMemory(cwd, args.sessionId?.let { NewSessionOptions(id = it) })
        }
        if (args.fork != null) {
            val targetId = args.sessionId
            if (targetId != null && SessionManager.list(cwd, sessionDirectory).any { it.id == targetId }) {
                error("Session already exists with id '$targetId'")
            }
            val source =
                resolveSessionPath(args.fork.orEmpty(), sessionDirectory)
                    ?: error("No session found matching '${args.fork}'")
            return SessionManager.forkFrom(
                source,
                cwd,
                sessionDirectory,
                targetId?.let { NewSessionOptions(id = it) },
            )
        }
        if (args.session != null) {
            val path =
                resolveSessionPath(args.session.orEmpty(), sessionDirectory)
                    ?: error("No session found matching '${args.session}'")
            return SessionManager.open(path, sessionDirectory)
        }
        if (args.resume) {
            error("--resume requires an interactive terminal")
        }
        if (args.continueSession) {
            return SessionManager.continueRecent(cwd, sessionDirectory)
        }
        if (args.sessionId != null) {
            val existing = SessionManager.list(cwd, sessionDirectory).firstOrNull { it.id == args.sessionId }
            if (existing != null) {
                return SessionManager.open(existing.path, sessionDirectory)
            }
            stderr.println(
                "Warning: No project session found with id '${args.sessionId}'; " +
                    "creating a new session with that id.",
            )
        }
        return SessionManager.create(
            cwd,
            sessionDirectory,
            args.sessionId?.let { NewSessionOptions(id = it) },
        )
    }

    private fun validateSessionFlags(args: Args) {
        if (args.fork != null) {
            val conflicts =
                buildList {
                    if (args.session != null) add("--session")
                    if (args.continueSession) add("--continue")
                    if (args.resume) add("--resume")
                    if (args.noSession) add("--no-session")
                }
            require(conflicts.isEmpty()) {
                "--fork cannot be combined with ${conflicts.joinToString(", ")}"
            }
        }
        if (args.sessionId != null) {
            val conflicts =
                buildList {
                    if (args.session != null) add("--session")
                    if (args.continueSession) add("--continue")
                    if (args.resume) add("--resume")
                }
            require(conflicts.isEmpty()) {
                "--session-id cannot be combined with ${conflicts.joinToString(", ")}"
            }
        }
    }

    private fun resolveSessionPath(
        value: String,
        sessionDirectory: Path?,
    ): Path? {
        if ('/' in value || '\\' in value || value.endsWith(".jsonl")) {
            val path = resolvePath(value)
            return path.takeIf(Files::exists)
        }
        val local = SessionManager.list(cwd, sessionDirectory)
        val localMatch = local.firstOrNull { it.id == value } ?: local.firstOrNull { it.id.startsWith(value) }
        if (localMatch != null) {
            return localMatch.path
        }
        val global = SessionManager.listAll(sessionDirectory)
        return (global.firstOrNull { it.id == value } ?: global.firstOrNull { it.id.startsWith(value) })?.path
    }

    private fun resolvePath(value: String): Path {
        val expanded =
            if (value == "~" || value.startsWith("~/")) {
                Path.of(System.getProperty("user.home")).resolve(value.removePrefix("~/"))
            } else {
                Path.of(value)
            }
        return (if (expanded.isAbsolute) expanded else cwd.resolve(expanded)).toAbsolutePath().normalize()
    }

    private fun AgentThinkingLevel?.toProviderThinkingLevel(): ThinkingLevel? =
        when (this) {
            AgentThinkingLevel.MINIMAL -> ThinkingLevel.MINIMAL
            AgentThinkingLevel.LOW -> ThinkingLevel.LOW
            AgentThinkingLevel.MEDIUM -> ThinkingLevel.MEDIUM
            AgentThinkingLevel.HIGH -> ThinkingLevel.HIGH
            AgentThinkingLevel.XHIGH -> ThinkingLevel.XHIGH
            AgentThinkingLevel.MAX -> ThinkingLevel.MAX
            AgentThinkingLevel.OFF,
            null,
            -> null
        }

    private fun AgentThinkingLevel?.toAgentThinkingLevel(): works.earendil.pi.agent.AgentThinkingLevel =
        when (this) {
            AgentThinkingLevel.MINIMAL -> works.earendil.pi.agent.AgentThinkingLevel.MINIMAL
            AgentThinkingLevel.LOW -> works.earendil.pi.agent.AgentThinkingLevel.LOW
            AgentThinkingLevel.MEDIUM -> works.earendil.pi.agent.AgentThinkingLevel.MEDIUM
            AgentThinkingLevel.HIGH -> works.earendil.pi.agent.AgentThinkingLevel.HIGH
            AgentThinkingLevel.XHIGH -> works.earendil.pi.agent.AgentThinkingLevel.XHIGH
            AgentThinkingLevel.MAX -> works.earendil.pi.agent.AgentThinkingLevel.MAX
            AgentThinkingLevel.OFF,
            null,
            -> works.earendil.pi.agent.AgentThinkingLevel.OFF
        }

    private fun String.toCliThinkingLevel(): AgentThinkingLevel? =
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

}
