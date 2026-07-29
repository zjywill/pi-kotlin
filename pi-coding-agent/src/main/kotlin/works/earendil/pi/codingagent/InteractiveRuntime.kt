package works.earendil.pi.codingagent

import java.awt.Desktop
import java.io.PrintWriter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jline.keymap.KeyMap
import org.jline.reader.Binding
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.MaskingCallback
import org.jline.reader.Reference
import org.jline.reader.UserInterruptException
import org.jline.reader.Widget
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.AuthType
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.ModelsRefreshOptions
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.codingagent.session.SessionManager

data class InteractiveShortcutBinding(
    val id: String,
    val key: String,
)

sealed interface InteractiveReadResult {
    data class Line(
        val value: String?,
    ) : InteractiveReadResult

    data class Shortcut(
        val id: String,
        val buffer: String,
    ) : InteractiveReadResult
}

interface InteractiveConsole : AutoCloseable {
    fun readLine(prompt: String): String?

    fun readLine(
        prompt: String,
        cancellation: ExtensionUiCancellation,
    ): String? = readLine(prompt)

    fun readLineWithShortcuts(
        prompt: String,
        shortcuts: List<InteractiveShortcutBinding>,
        initialBuffer: String = "",
    ): InteractiveReadResult = InteractiveReadResult.Line(readLine(prompt))

    fun print(text: String)

    fun println(text: String = "")

    fun error(text: String)

    override fun close() = Unit
}

class InteractiveRuntime(
    private val models: Models,
    private val cwd: Path = Path.of("").toAbsolutePath().normalize(),
    private val agentDir: Path = defaultAgentDirectory(),
    private val consoleFactory: () -> InteractiveConsole = { JLineConsole() },
) {
    suspend fun run(args: Args): Int {
        if (args.diagnostics.any { it.type == Diagnostic.Type.ERROR }) {
            args.diagnostics.forEach { diagnostic ->
                System.err.println("Error: ${diagnostic.message}")
            }
            return 2
        }

        val sessionDirectory = args.sessionDir?.let(::resolvePath)
        val resumedPath =
            if (args.resume) {
                try {
                    consoleFactory().use { console -> selectResumeSession(console, sessionDirectory) }
                } catch (error: IllegalStateException) {
                    System.err.println("Error: ${error.message}")
                    return 1
                } ?: return 0
            } else {
                null
            }
        val sessionPath =
            args.session?.let { value ->
                resolveSessionPath(value, sessionDirectory)
                    ?: run {
                        System.err.println("Error: No session found matching '$value'")
                        return 1
                    }
            } ?: resumedPath
        val forkPath =
            args.fork?.let { value ->
                resolveSessionPath(value, sessionDirectory)
                    ?: run {
                        System.err.println("Error: No session found matching '$value'")
                        return 1
                    }
            }
        val console = consoleFactory()
        val runtime =
            try {
                RpcRuntime(
                    models,
                    RpcRuntimeOptions(
                        cwd = cwd,
                        agentDir = agentDir,
                        sessionDir = sessionDirectory,
                        noSession = args.noSession,
                        sessionId = args.sessionId,
                        sessionPath = sessionPath,
                        forkPath = forkPath,
                        continueRecent = args.continueSession,
                        provider = args.provider,
                        model = args.model,
                        modelPatterns = args.models,
                        apiKey = args.apiKey,
                        systemPrompt = args.systemPrompt,
                        appendSystemPrompt = args.appendSystemPrompt,
                        noContextFiles = args.noContextFiles,
                        skillPaths = args.skills,
                        noSkills = args.noSkills,
                        promptTemplatePaths = args.promptTemplates,
                        noPromptTemplates = args.noPromptTemplates,
                        projectTrusted = args.projectTrustOverride,
                        extensionPaths = args.extensions,
                        noExtensions = args.noExtensions,
                        extensionFlagValues = args.unknownFlags,
                        extensionMode = ExtensionMode.TUI,
                        noTools = args.noTools,
                        noBuiltinTools = args.noBuiltinTools,
                        tools = args.tools,
                        excludeTools = args.excludeTools,
                        thinking = args.thinking,
                        projectTrustPrompt = { projectPath, labels ->
                            selectProjectTrust(projectPath, labels, console)
                        },
                        cancellableExtensionUiHandler = CancellableExtensionUiHandler { request, cancellation ->
                            handleExtensionUiDialog(request, console, cancellation)
                        },
                    ),
                )
            } catch (error: Exception) {
                console.close()
                System.err.println("Error: ${error.message}")
                return 1
            }

        console.use {
            val settled = AtomicReference<CompletableDeferred<Unit>?>(null)
            val streamedText = AtomicBoolean(false)
            val unsubscribe =
                runtime.subscribe { event ->
                    when (event.string("type")) {
                        "message_update" -> {
                            val assistantEvent = event["assistantMessageEvent"] as? JsonObject
                            if (assistantEvent?.string("type") == "text_delta") {
                                assistantEvent.string("delta")?.let { delta ->
                                    console.print(delta)
                                    streamedText.set(true)
                                }
                            }
                        }

                        "tool_execution_start" -> {
                            if (streamedText.getAndSet(false)) {
                                console.println()
                            }
                            console.println("[${event.string("toolName").orEmpty()}]")
                        }

                        "tool_execution_end" -> {
                            if (event["isError"]?.jsonPrimitive?.booleanOrNull == true) {
                                console.error("${event.string("toolName").orEmpty()} failed")
                            }
                        }

                        "extension_ui_request" -> renderExtensionUiRequest(event, console)

                        "extension_error" ->
                            console.error(
                                "Extension ${event.string("extensionPath").orEmpty()} " +
                                    "${event.string("event").orEmpty()}: ${event.string("error").orEmpty()}",
                            )

                        "agent_settled" -> settled.getAndSet(null)?.complete(Unit)
                    }
                }
            try {
                console.println("pi Kotlin ${currentModel(runtime)}")
                console.println("Type /help for commands. Ctrl-D or /exit quits.")
                args.name?.let { name ->
                    val response =
                        runtime.handle(
                            buildJsonObject {
                                put("type", "set_session_name")
                                put("name", name)
                            },
                        )
                    if (response?.success() != true) {
                        console.error(response?.string("error") ?: "Failed to set session name")
                        return 1
                    }
                }
                val initialPrompts =
                    try {
                        buildInitialPrompts(args, runtime.currentCwd())
                    } catch (error: Exception) {
                        console.error(error.message ?: "Failed to build initial prompt")
                        return 1
                    }
                for (message in initialPrompts) {
                    if (!sendPrompt(runtime, message, console, settled, streamedText)) {
                        return 1
                    }
                }
                var editorBuffer = ""
                val reportedShortcutDiagnostics = mutableSetOf<String>()
                while (true) {
                    val shortcutResolution = runtime.extensionShortcuts()
                    shortcutResolution.diagnostics.forEach { diagnostic ->
                        if (reportedShortcutDiagnostics.add(diagnostic.error)) {
                            console.println("Warning: ${diagnostic.error}")
                        }
                    }
                    val read =
                        try {
                            console.readLineWithShortcuts(
                                prompt = "> ",
                                shortcuts =
                                    shortcutResolution.shortcuts.values.map { shortcut ->
                                        InteractiveShortcutBinding(shortcut.id, shortcut.shortcut)
                                    },
                                initialBuffer = editorBuffer,
                            )
                        } catch (_: UserInterruptException) {
                            console.println("^C")
                            continue
                        }
                    if (read is InteractiveReadResult.Shortcut) {
                        editorBuffer = read.buffer
                        runtime.invokeExtensionShortcut(read.id)
                        continue
                    }
                    val line = (read as InteractiveReadResult.Line).value
                    editorBuffer = ""
                    val input = line?.trim() ?: break
                    if (input.isEmpty()) {
                        continue
                    }
                    when {
                        input == "/exit" || input == "/quit" -> break
                        input == "/help" -> printInteractiveHelp(console)
                        input == "/hotkeys" -> printInteractiveHotkeys(console, shortcutResolution)
                        input == "/new" || input == "/clear" ->
                            printCommandResponse(
                                runtime.handle(buildJsonObject { put("type", "new_session") }),
                                console,
                                "Started a new session.",
                            )

                        input == "/session" -> printSession(runtime, console)
                        input == "/stats" -> printStats(runtime, console)
                        input == "/reload" -> {
                            try {
                                runtime.reloadResources()
                                console.println("Reloaded resources.")
                            } catch (error: Exception) {
                                console.error(error.message ?: "Reload failed")
                            }
                        }

                        input.startsWith("/name ") ->
                            printCommandResponse(
                                runtime.handle(
                                    buildJsonObject {
                                        put("type", "set_session_name")
                                        put("name", input.removePrefix("/name ").trim())
                                    },
                                ),
                                console,
                                "Session renamed.",
                            )

                        input == "/model" -> console.println(currentModel(runtime))
                        input.startsWith("/model ") -> setModel(runtime, input.removePrefix("/model ").trim(), console)
                        input == "/login" || input.startsWith("/login ") ->
                            login(
                                input.removePrefix("/login").trim().takeIf(String::isNotEmpty),
                                console,
                            )

                        input == "/logout" || input.startsWith("/logout ") ->
                            logout(
                                input.removePrefix("/logout").trim().takeIf(String::isNotEmpty),
                                console,
                            )

                        input.startsWith("/thinking ") ->
                            printCommandResponse(
                                runtime.handle(
                                    buildJsonObject {
                                        put("type", "set_thinking_level")
                                        put("level", input.removePrefix("/thinking ").trim())
                                    },
                                ),
                                console,
                                "Thinking level updated.",
                            )

                        input.startsWith("!") -> runBash(runtime, input.drop(1), console)
                        input.startsWith("/") ->
                            if (isResourceCommand(runtime, input)) {
                                if (!sendPrompt(runtime, UserMessage(input), console, settled, streamedText)) {
                                    return 1
                                }
                            } else {
                                console.error("Unknown command: ${input.substringBefore(' ')}")
                            }

                        else ->
                            if (!sendPrompt(runtime, UserMessage(input), console, settled, streamedText)) {
                                return 1
                            }
                    }
                }
                return 0
            } finally {
                unsubscribe()
                runtime.close()
            }
        }
    }

    private fun selectProjectTrust(
        projectPath: Path,
        labels: List<String>,
        console: InteractiveConsole,
    ): Int? {
        console.println("Trust project folder?")
        console.println(projectPath.toString())
        console.println()
        console.println(
            "This allows pi to load .pi settings and resources, install missing project packages, " +
                "and execute project extensions.",
        )
        labels.forEachIndexed { index, label ->
            console.println("${index + 1}. $label")
        }
        while (true) {
            val value =
                console.readLine("Select 1-${labels.size} (Enter cancels): ")
                    ?.trim()
                    ?: return null
            if (value.isEmpty()) {
                return null
            }
            val index = value.toIntOrNull()
            if (index != null && index in 1..labels.size) {
                return index - 1
            }
            labels.indexOf(value).takeIf { it >= 0 }?.let { return it }
            console.error("Select a trust option by number or label.")
        }
    }

    private fun renderExtensionUiRequest(
        event: JsonObject,
        console: InteractiveConsole,
    ) {
        when (event.string("method")) {
            "notify" -> {
                val message = event.string("message").orEmpty()
                if (event.string("notifyType") == "error") {
                    console.error(message)
                } else {
                    console.println(message)
                }
            }

            "setStatus" -> {
                val key = event.string("key") ?: event.string("statusKey") ?: return
                val text = event.string("text") ?: event.string("statusText")
                if (!text.isNullOrEmpty()) {
                    console.println("[$key] $text")
                }
            }

            "setTitle" -> event.string("title")?.let { console.println(it) }
        }
    }

    private fun handleExtensionUiDialog(
        request: JsonObject,
        console: InteractiveConsole,
        cancellation: ExtensionUiCancellation,
    ): JsonObject {
        return try {
            when (request.string("method")) {
                "select" -> handleExtensionSelect(request, console, cancellation)
                "confirm" -> handleExtensionConfirm(request, console, cancellation)

                "input" -> {
                    request.string("title")?.let(console::println)
                    val prompt = request.string("placeholder")?.let { "$it: " } ?: "> "
                    val value = console.readLine(prompt, cancellation) ?: return cancelledUiResponse()
                    buildJsonObject { put("value", value) }
                }

                "editor" -> {
                    request.string("title")?.let(console::println)
                    val prefill = request.string("prefill")
                    if (!prefill.isNullOrEmpty()) {
                        console.println(prefill)
                    }
                    val value = console.readLine("Edit: ", cancellation) ?: return cancelledUiResponse()
                    buildJsonObject { put("value", value.ifEmpty { prefill.orEmpty() }) }
                }

                else -> cancelledUiResponse()
            }
        } catch (_: UserInterruptException) {
            cancelledUiResponse()
        } catch (_: EndOfFileException) {
            cancelledUiResponse()
        }
    }

    private fun handleExtensionSelect(
        request: JsonObject,
        console: InteractiveConsole,
        cancellation: ExtensionUiCancellation,
    ): JsonObject {
        val options =
            request["options"]
                ?.jsonArray
                .orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull }
        if (options.isEmpty()) {
            return cancelledUiResponse()
        }
        request.string("title")?.let(console::println)
        options.forEachIndexed { index, option ->
            console.println("${index + 1}. $option")
        }
        while (true) {
            val value =
                console.readLine("Select 1-${options.size} (Enter cancels): ", cancellation)
                    ?.trim()
                    ?: return cancelledUiResponse()
            if (value.isEmpty()) {
                return cancelledUiResponse()
            }
            val selected =
                value.toIntOrNull()
                    ?.takeIf { it in 1..options.size }
                    ?.let { options[it - 1] }
                    ?: options.firstOrNull { it == value }
            if (selected != null) {
                return buildJsonObject { put("value", selected) }
            }
            console.error("Select an option by number or label.")
        }
    }

    private fun handleExtensionConfirm(
        request: JsonObject,
        console: InteractiveConsole,
        cancellation: ExtensionUiCancellation,
    ): JsonObject {
        request.string("title")?.let(console::println)
        request.string("message")?.let(console::println)
        while (true) {
            when (console.readLine("Confirm [y/N]: ", cancellation)?.trim()?.lowercase()) {
                null -> return cancelledUiResponse()
                "", "n", "no" -> return buildJsonObject { put("confirmed", false) }
                "y", "yes" -> return buildJsonObject { put("confirmed", true) }
                else -> console.error("Enter yes or no.")
            }
        }
    }

    private fun cancelledUiResponse(): JsonObject =
        buildJsonObject { put("cancelled", true) }

    private suspend fun sendPrompt(
        runtime: RpcRuntime,
        message: UserMessage,
        console: InteractiveConsole,
        settled: AtomicReference<CompletableDeferred<Unit>?>,
        streamedText: AtomicBoolean,
    ): Boolean {
        val completion = CompletableDeferred<Unit>()
        settled.set(completion)
        streamedText.set(false)
        val response =
            runtime.handle(
                buildJsonObject command@{
                    put("type", "prompt")
                    encodePromptCommand(message).forEach { (name, value) ->
                        this@command.put(name, value)
                    }
                },
            )
        if (response?.success() != true) {
            settled.compareAndSet(completion, null)
            console.error(response?.string("error") ?: "Prompt failed")
            return false
        }
        completion.await()
        if (streamedText.getAndSet(false)) {
            console.println()
        } else {
            val finalText =
                runtime.handle(buildJsonObject { put("type", "get_last_assistant_text") })
                    ?.get("data")
                    ?.jsonObject
                    ?.string("text")
            if (!finalText.isNullOrEmpty()) {
                console.println(finalText)
            }
        }
        return true
    }

    private suspend fun setModel(
        runtime: RpcRuntime,
        value: String,
        console: InteractiveConsole,
    ) {
        val separator = value.indexOf('/')
        if (separator <= 0 || separator == value.lastIndex) {
            console.error("Usage: /model <provider>/<model>")
            return
        }
        printCommandResponse(
            runtime.handle(
                buildJsonObject {
                    put("type", "set_model")
                    put("provider", value.substring(0, separator))
                    put("modelId", value.substring(separator + 1))
                },
            ),
            console,
            "Model set to $value.",
        )
    }

    private suspend fun login(
        providerId: String?,
        console: InteractiveConsole,
    ) {
        val providers = models.getProviders().filter { it.oauth != null }.sortedBy(Provider::id)
        val provider =
            selectProvider(
                requestedId = providerId,
                providers = providers,
                prompt = "Login provider:",
                console = console,
            ) ?: return
        try {
            models.login(
                provider.id,
                AuthType.OAUTH,
                ConsoleAuthInteraction(console),
            )
            models.refresh(ModelsRefreshOptions(allowNetwork = true))
            console.println("Logged in to ${provider.name}.")
        } catch (error: Exception) {
            console.error(error.message ?: "Login failed")
        }
    }

    private suspend fun logout(
        providerId: String?,
        console: InteractiveConsole,
    ) {
        val stored =
            try {
                models.listCredentials()
            } catch (error: Exception) {
                console.error(error.message ?: "Unable to read stored credentials")
                return
            }
        if (stored.isEmpty()) {
            console.error(
                "No stored credentials to remove. /logout only removes credentials saved by /login; " +
                    "environment variables are unchanged.",
            )
            return
        }
        val selectedId =
            if (providerId != null) {
                if (stored.none { it.providerId == providerId }) {
                    console.error("No stored credentials for provider: $providerId")
                    return
                }
                providerId
            } else {
                selectCredential(stored.map { it.providerId }, console) ?: return
            }
        try {
            models.logout(selectedId)
            val name = models.getProvider(selectedId)?.name ?: selectedId
            console.println("Logged out of $name.")
        } catch (error: Exception) {
            console.error(error.message ?: "Logout failed")
        }
    }

    private fun selectProvider(
        requestedId: String?,
        providers: List<Provider>,
        prompt: String,
        console: InteractiveConsole,
    ): Provider? {
        if (requestedId != null) {
            val provider = providers.firstOrNull { it.id == requestedId }
            if (provider == null) {
                console.error("Provider does not support login: $requestedId")
            }
            return provider
        }
        if (providers.isEmpty()) {
            console.error("No providers support interactive login.")
            return null
        }
        providers.forEachIndexed { index, provider ->
            console.println("${index + 1}. ${provider.name} [${provider.id}]")
        }
        while (true) {
            val value = console.readLine("$prompt ")?.trim() ?: return null
            if (value.isEmpty()) {
                return null
            }
            val index = value.toIntOrNull()
            if (index != null && index in 1..providers.size) {
                return providers[index - 1]
            }
            providers.firstOrNull { it.id == value }?.let { return it }
            console.error("Select a provider by number or id.")
        }
    }

    private fun selectCredential(
        providerIds: List<String>,
        console: InteractiveConsole,
    ): String? {
        providerIds.forEachIndexed { index, id ->
            val name = models.getProvider(id)?.name ?: id
            console.println("${index + 1}. $name [$id]")
        }
        while (true) {
            val value = console.readLine("Logout provider: ")?.trim() ?: return null
            if (value.isEmpty()) {
                return null
            }
            val index = value.toIntOrNull()
            if (index != null && index in 1..providerIds.size) {
                return providerIds[index - 1]
            }
            if (value in providerIds) {
                return value
            }
            console.error("Select a provider by number or id.")
        }
    }

    private suspend fun runBash(
        runtime: RpcRuntime,
        command: String,
        console: InteractiveConsole,
    ) {
        if (command.isBlank()) {
            return
        }
        val response =
            runtime.handle(
                buildJsonObject {
                    put("type", "bash")
                    put("command", command)
                },
            )
        if (response?.success() != true) {
            console.error(response?.string("error") ?: "Command failed")
            return
        }
        val data = response["data"]?.jsonObject
        data?.string("output")?.takeIf(String::isNotEmpty)?.let(console::print)
        val exitCode = data?.get("exitCode")?.jsonPrimitive?.contentOrNull
        if (exitCode != "0") {
            console.error("Command exited with code ${exitCode ?: "unknown"}")
        }
    }

    private suspend fun printSession(
        runtime: RpcRuntime,
        console: InteractiveConsole,
    ) {
        val state = runtime.handle(buildJsonObject { put("type", "get_state") })?.get("data")?.jsonObject
        if (state == null) {
            console.error("Unable to read session state.")
            return
        }
        console.println("Session: ${state.string("sessionId").orEmpty()}")
        state.string("sessionName")?.let { console.println("Name: $it") }
        state.string("sessionFile")?.let { console.println("File: $it") }
    }

    private suspend fun printStats(
        runtime: RpcRuntime,
        console: InteractiveConsole,
    ) {
        val stats = runtime.handle(buildJsonObject { put("type", "get_session_stats") })?.get("data")?.jsonObject
        if (stats == null) {
            console.error("Unable to read session statistics.")
            return
        }
        console.println(
            "Messages: ${stats.value("messageCount") ?: "0"}, " +
                "tokens: ${stats.value("totalTokens") ?: "0"}, " +
                "cost: ${stats.value("costTotal") ?: "0"}",
        )
    }

    private suspend fun isResourceCommand(
        runtime: RpcRuntime,
        input: String,
    ): Boolean {
        val name = input.removePrefix("/").substringBefore(' ')
        val commands =
            runtime
                .handle(buildJsonObject { put("type", "get_commands") })
                ?.get("data")
                ?.jsonObject
                ?.get("commands")
                ?.jsonArray
                .orEmpty()
        return commands.any { command ->
            command.jsonObject.string("name") == name
        }
    }

    private suspend fun currentModel(runtime: RpcRuntime): String {
        val model =
            runtime.handle(buildJsonObject { put("type", "get_state") })
                ?.get("data")
                ?.jsonObject
                ?.get("model")
                ?.jsonObject
        return if (model == null) {
            "unknown"
        } else {
            "${model.string("provider").orEmpty()}/${model.string("id").orEmpty()}"
        }
    }

    private fun printCommandResponse(
        response: JsonObject?,
        console: InteractiveConsole,
        successMessage: String,
    ) {
        if (response?.success() == true) {
            console.println(successMessage)
        } else {
            console.error(response?.string("error") ?: "Command failed")
        }
    }

    private fun resolveSessionPath(
        value: String,
        sessionDirectory: Path?,
    ): Path? {
        if ('/' in value || '\\' in value || value.endsWith(".jsonl")) {
            return resolvePath(value).takeIf(Files::exists)
        }
        val local = SessionManager.list(cwd, sessionDirectory)
        val localMatch = local.firstOrNull { it.id == value } ?: local.firstOrNull { it.id.startsWith(value) }
        if (localMatch != null) {
            return localMatch.path
        }
        val global = SessionManager.listAll(sessionDirectory)
        return (global.firstOrNull { it.id == value } ?: global.firstOrNull { it.id.startsWith(value) })?.path
    }

    private fun selectResumeSession(
        console: InteractiveConsole,
        sessionDirectory: Path?,
    ): Path? {
        val local = SessionManager.list(cwd, sessionDirectory)
        val sessions =
            (local + SessionManager.listAll(sessionDirectory))
                .distinctBy { it.path.toAbsolutePath().normalize() }
                .sortedByDescending { it.modified }
                .take(20)
        check(sessions.isNotEmpty()) { "No sessions found." }
        console.println("Resume session:")
        sessions.forEachIndexed { index, session ->
            val label = session.name ?: session.firstMessage
            console.println(
                "${index + 1}. ${label.replace(Regex("[\\r\\n]+"), " ").take(72)} " +
                    "[${session.id}]",
            )
        }
        while (true) {
            val value = console.readLine("Select 1-${sessions.size} (Enter cancels): ")?.trim() ?: return null
            if (value.isEmpty()) {
                return null
            }
            val selected = value.toIntOrNull()
            if (selected != null && selected in 1..sessions.size) {
                return sessions[selected - 1].path
            }
            console.error("Enter a number from 1 to ${sessions.size}.")
        }
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
}

private class ConsoleAuthInteraction(
    private val console: InteractiveConsole,
) : AuthInteraction {
    override suspend fun prompt(prompt: AuthPrompt): String =
        runInterruptible(Dispatchers.IO) {
            when (prompt) {
                is AuthPrompt.Select -> select(prompt)
                is AuthPrompt.ManualCode ->
                    console.readLine("${prompt.message} ")
                        ?: error("Login cancelled")

                is AuthPrompt.Text ->
                    console.readLine("${prompt.message} ")
                        ?: error("Login cancelled")
            }
        }

    override fun notify(event: AuthEvent) {
        when (event) {
            is AuthEvent.AuthUrl -> {
                event.instructions?.let(console::println)
                console.println(event.url)
                openBrowser(event.url)
            }

            is AuthEvent.DeviceCode -> {
                console.println("Open ${event.verificationUri}")
                console.println("Enter code: ${event.userCode}")
            }

            is AuthEvent.Info -> {
                console.println(event.message)
                event.links.forEach { link ->
                    console.println(link.label?.let { "$it: ${link.url}" } ?: link.url)
                }
            }

            is AuthEvent.Progress -> console.println(event.message)
        }
    }

    private fun select(prompt: AuthPrompt.Select): String {
        console.println(prompt.message)
        prompt.options.forEachIndexed { index, option ->
            val description = option.description?.let { " - $it" }.orEmpty()
            console.println("${index + 1}. ${option.label}$description")
        }
        while (true) {
            val value = console.readLine("Select 1-${prompt.options.size}: ")?.trim()
                ?: error("Login cancelled")
            if (value.isEmpty()) {
                return prompt.options.first().id
            }
            val index = value.toIntOrNull()
            if (index != null && index in 1..prompt.options.size) {
                return prompt.options[index - 1].id
            }
            prompt.options.firstOrNull { it.id == value }?.let { return it.id }
            console.error("Enter a number from 1 to ${prompt.options.size}.")
        }
    }
}

private fun openBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI.create(url))
        }
    }
}

internal class JLineConsole(
    private val terminal: Terminal =
        TerminalBuilder
            .builder()
            .system(true)
            .build(),
) : InteractiveConsole {
    private val output = PrintWriter(terminal.writer(), true)
    private val reader: LineReader =
        LineReaderBuilder
            .builder()
            .terminal(terminal)
            .variable(LineReader.HISTORY_FILE, historyFile())
            .build()

    override fun readLine(
        prompt: String,
    ): String? = readLineInternal(prompt, cancellation = null)

    override fun readLine(
        prompt: String,
        cancellation: ExtensionUiCancellation,
    ): String? = readLineInternal(prompt, cancellation)

    override fun readLineWithShortcuts(
        prompt: String,
        shortcuts: List<InteractiveShortcutBinding>,
        initialBuffer: String,
    ): InteractiveReadResult {
        if (shortcuts.isEmpty()) {
            return InteractiveReadResult.Line(readLineWithInitialBuffer(prompt, initialBuffer))
        }
        val triggered = AtomicReference<String?>(null)
        val keyMaps: Map<String, KeyMap<Binding>> = reader.keyMaps
        val activeKeyMaps = keyMaps.values.distinct()
        check(activeKeyMaps.isNotEmpty()) { "JLine editor keymaps are unavailable" }
        val previousBindings = mutableListOf<InstalledJLineBinding>()
        val widgetNames = mutableListOf<String>()
        shortcuts.forEachIndexed { index, shortcut ->
            val widgetName = "pi-extension-shortcut-$index"
            widgetNames += widgetName
            reader.widgets[widgetName] =
                Widget {
                    triggered.compareAndSet(null, shortcut.id)
                    reader.callWidget(LineReader.ACCEPT_LINE)
                    true
                }
            jlineSequencesForKeyId(shortcut.key).forEach { sequence ->
                activeKeyMaps.forEach { keyMap ->
                    previousBindings += InstalledJLineBinding(keyMap, sequence, keyMap.getBound(sequence))
                    keyMap.bind(Reference(widgetName), sequence)
                }
            }
        }
        return try {
            val line = readLineWithInitialBuffer(prompt, initialBuffer)
            triggered.get()?.let { id ->
                InteractiveReadResult.Shortcut(id, line.orEmpty())
            } ?: InteractiveReadResult.Line(line)
        } finally {
            previousBindings.forEach { installed ->
                if (installed.previous == null) {
                    installed.keyMap.unbind(installed.sequence)
                } else {
                    installed.keyMap.bind(installed.previous, installed.sequence)
                }
            }
            widgetNames.forEach(reader.widgets::remove)
        }
    }

    private fun readLineInternal(
        prompt: String,
        cancellation: ExtensionUiCancellation?,
    ): String? {
        if (cancellation?.isCancelled == true) {
            return null
        }
        val readingThread = Thread.currentThread()
        val registration = cancellation?.onCancellation(readingThread::interrupt)
        return try {
            reader.readLine(prompt)
        } catch (_: EndOfFileException) {
            null
        } finally {
            registration?.close()
            if (cancellation?.isCancelled == true) {
                Thread.interrupted()
            }
        }
    }

    private fun readLineWithInitialBuffer(
        prompt: String,
        initialBuffer: String,
    ): String? =
        try {
            reader.readLine(prompt, null, null as MaskingCallback?, initialBuffer)
        } catch (_: EndOfFileException) {
            null
        }

    override fun print(text: String) {
        output.print(text)
        output.flush()
    }

    override fun println(text: String) {
        output.println(text)
        output.flush()
    }

    override fun error(text: String) {
        output.println("Error: $text")
        output.flush()
    }

    override fun close() {
        terminal.close()
    }

    companion object {
        private fun historyFile(): Path {
            val path = Path.of(System.getProperty("user.home"), ".pi", "agent", "history")
            Files.createDirectories(path.parent)
            return path
        }
    }
}

private data class InstalledJLineBinding(
    val keyMap: KeyMap<Binding>,
    val sequence: String,
    val previous: Binding?,
)

private fun jlineSequencesForKeyId(keyId: String): List<String> {
    val parts = keyId.lowercase().split('+')
    val rawKey = parts.lastOrNull()?.takeIf(String::isNotEmpty) ?: return emptyList()
    val key =
        when (rawKey) {
            "esc" -> "escape"
            "return" -> "enter"
            else -> rawKey
        }
    val modifiers = parts.dropLast(1).toSet()
    val sequences = linkedSetOf<String>()
    val codepoint =
        when (key) {
            "escape" -> 27
            "enter" -> 13
            "tab" -> 9
            "space" -> 32
            "backspace" -> 127
            else -> key.singleOrNull()?.code
        }

    when {
        modifiers.isEmpty() ->
            when (key) {
                "escape" -> sequences += "\u001B"
                "enter" -> sequences += "\r"
                "tab" -> sequences += "\t"
                "space" -> sequences += " "
                "backspace" -> sequences += "\u007F"
                "delete" -> sequences += "\u001B[3~"
                "insert" -> sequences += "\u001B[2~"
                "home" -> sequences += "\u001B[H"
                "end" -> sequences += "\u001B[F"
                "pageup" -> sequences += "\u001B[5~"
                "pagedown" -> sequences += "\u001B[6~"
                "up" -> sequences += "\u001B[A"
                "down" -> sequences += "\u001B[B"
                "right" -> sequences += "\u001B[C"
                "left" -> sequences += "\u001B[D"
                else -> key.singleOrNull()?.let { sequences += it.toString() }
            }

        modifiers == setOf("ctrl") && key.singleOrNull() != null -> {
            val character = key.single().lowercaseChar()
            when {
                character in 'a'..'z' || character in setOf('[', '\\', ']', '_') ->
                    sequences += (character.code and 0x1F).toChar().toString()

                character == '-' -> sequences += "\u001F"
            }
        }

        modifiers == setOf("alt") && key.singleOrNull() != null ->
            sequences += KeyMap.alt(key.single())

        modifiers == setOf("alt") && key == "enter" ->
            sequences += "\u001B\r"

        modifiers == setOf("shift") && key == "tab" ->
            sequences += "\u001B[Z"

        modifiers == setOf("shift") && key.singleOrNull()?.isLetter() == true ->
            sequences += key.uppercase()
    }

    val modifierMask =
        (if ("shift" in modifiers) 1 else 0) +
            (if ("alt" in modifiers) 2 else 0) +
            (if ("ctrl" in modifiers) 4 else 0) +
            (if ("super" in modifiers) 8 else 0)
    if (modifierMask != 0) {
        val encodedModifier = modifierMask + 1
        if (codepoint != null) {
            sequences += "\u001B[$codepoint;${encodedModifier}u"
        }
        when (key) {
            "up" -> sequences += "\u001B[1;${encodedModifier}A"
            "down" -> sequences += "\u001B[1;${encodedModifier}B"
            "right" -> sequences += "\u001B[1;${encodedModifier}C"
            "left" -> sequences += "\u001B[1;${encodedModifier}D"
            "home" -> sequences += "\u001B[1;${encodedModifier}H"
            "end" -> sequences += "\u001B[1;${encodedModifier}F"
            "insert" -> sequences += "\u001B[2;${encodedModifier}~"
            "delete" -> sequences += "\u001B[3;${encodedModifier}~"
            "pageup" -> sequences += "\u001B[5;${encodedModifier}~"
            "pagedown" -> sequences += "\u001B[6;${encodedModifier}~"
        }
    }
    return sequences.toList()
}

private fun printInteractiveHelp(console: InteractiveConsole) {
    console.println(
        """
        /help                         Show commands
        /hotkeys                      Show keyboard shortcuts
        /new, /clear                  Start a new session
        /session                      Show session information
        /stats                        Show token and cost totals
        /reload                       Reload skills, prompt templates, and context files
        /name <name>                  Set the session name
        /model [provider/model]       Show or change the model
        /login [provider]             Sign in to a provider
        /logout [provider]            Remove stored provider credentials
        /thinking <level>             Set off|minimal|low|medium|high|xhigh|max
        !<command>                    Run a shell command
        /exit, /quit                  Exit
        """.trimIndent(),
    )
}

private fun printInteractiveHotkeys(
    console: InteractiveConsole,
    resolution: ExtensionShortcutResolution,
) {
    console.println("Keyboard Shortcuts")
    console.println("Ctrl-D                         Exit when the editor is empty")
    console.println("Ctrl-C                         Clear or interrupt input")
    if (resolution.shortcuts.isNotEmpty()) {
        console.println()
        console.println("Extensions")
        resolution.shortcuts.forEach { (key, shortcut) ->
            console.println(
                "${formatShortcutKey(key).padEnd(30)} ${shortcut.description ?: shortcut.extensionPath}",
            )
        }
    }
}

private fun formatShortcutKey(key: String): String =
    key
        .split('+')
        .joinToString("+") { part ->
            when (part.lowercase()) {
                "ctrl" -> "Ctrl"
                "alt" -> "Alt"
                "shift" -> "Shift"
                "super" -> "Super"
                "pageup" -> "PageUp"
                "pagedown" -> "PageDown"
                else -> part.replaceFirstChar(Char::uppercase)
            }
        }

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.value(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.success(): Boolean = this["success"]?.jsonPrimitive?.booleanOrNull == true
