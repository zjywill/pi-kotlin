package works.earendil.pi.codingagent

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import works.earendil.pi.agent.Agent
import works.earendil.pi.agent.AgentEvent
import works.earendil.pi.agent.AgentInitialState
import works.earendil.pi.agent.AgentOptions
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
        val model =
            resolveModel(args, initialContext.model)
                ?: run {
                    stderr.println("Error: No model matched the requested provider/model.")
                    return 1
                }
        val runtimeCwd = sessionManager.getCwd()
        val tools =
            createSelectedCodingTools(
                cwd = runtimeCwd,
                noTools = args.noTools,
                noBuiltinTools = args.noBuiltinTools,
                allowedTools = args.tools,
                excludedTools = args.excludeTools,
            )
        val promptResources =
            loadPromptResources(
                cwd = runtimeCwd,
                agentDir = agentDir,
                systemPromptSource = args.systemPrompt,
                appendPromptSources = args.appendSystemPrompt,
                noContextFiles = args.noContextFiles,
                projectTrusted = args.projectTrustOverride == true,
                onWarning = { stderr.println("Warning: $it") },
            )
        val requestedThinking =
            args.thinking ?: parseModelReference(args.provider, args.model).thinking
        val agent =
            Agent(
                AgentOptions(
                    streamFunction =
                        StreamFunction { requestModel, context: Context, options: SimpleStreamOptions ->
                            models.streamSimple(requestModel, context, options)
                        },
                    convertToLlm = { messages -> convertCodingMessagesToLlm(messages) },
                    initialState =
                        AgentInitialState(
                            systemPrompt = buildCodingSystemPrompt(runtimeCwd, tools, promptResources),
                            model = model,
                            thinkingLevel =
                                (requestedThinking ?: initialContext.thinkingLevel.toCliThinkingLevel())
                                    .toAgentThinkingLevel(),
                            tools = tools,
                            messages = initialContext.messages,
                        ),
                    streamOptions =
                        SimpleStreamOptions(
                            stream =
                                StreamOptions(
                                    apiKey = args.apiKey,
                                    sessionId = sessionManager.getSessionId(),
                                ),
                            reasoning =
                                (requestedThinking ?: initialContext.thinkingLevel.toCliThinkingLevel())
                                    .toProviderThinkingLevel(),
                        ),
                ),
            )
        if (args.mode == OutputMode.JSON) {
            sessionManager.getHeader()?.let { header ->
                stdout.println(protocolJson.encodeToString(JsonObject.serializer(), encodeEntry(header)))
            }
        }
        agent.subscribe { event ->
            if (args.mode == OutputMode.JSON) {
                stdout.println(protocolJson.encodeToString(JsonObject.serializer(), encodeAgentEvent(event)))
            }
            if (event is AgentEvent.MessageEnd) {
                sessionManager.appendMessage(event.message)
            }
        }

        val prompts =
            try {
                buildInitialPrompts(args, runtimeCwd, stdinContent)
            } catch (error: Exception) {
                stderr.println("Error: ${error.message}")
                return 1
            }
        if (prompts.isEmpty()) {
            stderr.println("Error: A prompt is required in print mode.")
            return 1
        }
        prompts.forEach { prompt -> agent.prompt(prompt) }
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
    ): Model? {
        if (args.provider == null && args.model == null && sessionModel != null) {
            return models
                .getAvailable(sessionModel.provider)
                .firstOrNull { it.id == sessionModel.modelId }
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
