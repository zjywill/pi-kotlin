package works.earendil.pi.codingagent

import java.awt.Desktop
import java.io.PrintWriter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
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
import works.earendil.pi.tui.AutocompleteItem
import works.earendil.pi.tui.AutocompleteProvider
import works.earendil.pi.tui.CombinedAutocompleteProvider
import works.earendil.pi.tui.KeybindingsManager
import works.earendil.pi.tui.SlashCommand
import works.earendil.pi.tui.TUI_KEYBINDINGS
import works.earendil.pi.tui.fuzzyFilter
import works.earendil.pi.tui.renderTerminalImageLines

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

    fun readSecret(prompt: String): String? = readLine(prompt)

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

    fun printlnAbove(text: String) = println(text)

    fun error(text: String)

    fun width(): Int = 80

    fun supportsAnsi(): Boolean = false

    override fun close() = Unit
}

class InteractiveRuntime(
    private val models: Models,
    private val cwd: Path = Path.of("").toAbsolutePath().normalize(),
    private val agentDir: Path = defaultAgentDirectory(),
    private val consoleFactory: (() -> InteractiveConsole)? = null,
    private val packageUpdateChecker: (Path, Path, Boolean) -> List<String> = ::checkPackageUpdates,
    private val modelRefreshTimeoutMs: Long = MODEL_REFRESH_TIMEOUT_MS,
) {
    private val extensionSurfaceLock = Any()
    private val extensionWidgetsAbove = linkedMapOf<String, List<String>>()
    private val extensionWidgetsBelow = linkedMapOf<String, List<String>>()
    private var extensionHeader: List<String>? = null
    private var extensionFooter: List<String>? = null
    @Volatile
    private var renderLiveExtensionSurfaces = false
    private var defaultInteractiveHeaderText = "pi Kotlin"

    suspend fun run(args: Args): Int {
        if (args.diagnostics.any { it.type == Diagnostic.Type.ERROR }) {
            args.diagnostics.forEach { diagnostic ->
                System.err.println("Error: ${diagnostic.message}")
            }
            return 2
        }
        val offline = args.offline || offlineEnvironmentEnabled()

        val sessionDirectory = args.sessionDir?.let(::resolvePath)
        val resumedPath =
            if (args.resume) {
                try {
                    createConsole(args).use { console -> selectResumeSession(console, sessionDirectory) }
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
        val console = createConsole(args)
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
                        themePaths = args.themes,
                        initialThemeSetting = args.useTheme,
                        noThemes = args.noThemes,
                        projectTrusted = args.projectTrustOverride,
                        extensionPaths = args.extensions,
                        noExtensions = args.noExtensions,
                        offline = offline,
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
                        extensionRenderOptionsProvider = {
                            ExtensionRenderOptions(
                                width = console.width(),
                                expanded = false,
                                outputPad = 1,
                            )
                        },
                    ),
                )
            } catch (error: Exception) {
                console.close()
                System.err.println("Error: ${error.message}")
                return 1
            }

        console.use {
            synchronized(extensionSurfaceLock) {
                extensionWidgetsAbove.clear()
                extensionWidgetsBelow.clear()
                extensionHeader = null
                extensionFooter = null
            }
            renderLiveExtensionSurfaces = false
            defaultInteractiveHeaderText = "pi Kotlin ${currentModel(runtime)}"
            configureFullScreenConsole(console, runtime)
            val settled = AtomicReference<CompletableDeferred<Unit>?>(null)
            val streamedText = AtomicBoolean(false)
            val streamedMarkdown = AtomicReference("")
            val unsubscribe =
                runtime.subscribe { event ->
                    when (event.string("type")) {
                        "message_update" -> {
                            val assistantEvent = event["assistantMessageEvent"] as? JsonObject
                            when (assistantEvent?.string("type")) {
                                "text_start" -> {
                                    if (runtime.extensionMarkdownTransformerCount() > 0) {
                                        streamedMarkdown.set("")
                                        (console as? FullScreenConsoleControl)?.setStreamingText("")
                                    }
                                }

                                "text_delta" -> {
                                    assistantEvent.string("delta")?.let { delta ->
                                        if (runtime.extensionMarkdownTransformerCount() == 0) {
                                            console.print(themeForeground(runtime, console, "text", delta))
                                        } else {
                                            val source =
                                                streamedMarkdown.updateAndGet { current ->
                                                    current + delta
                                                }
                                            val transformed =
                                                runCatching {
                                                    runtime.transformMarkdown(
                                                        markdown = source,
                                                        messageType = "assistant",
                                                        isStreaming = true,
                                                        availableWidth = (console.width() - 2).coerceAtLeast(1),
                                                    )
                                                }.getOrDefault(source)
                                            (console as? FullScreenConsoleControl)?.setStreamingText(
                                                themeForeground(runtime, console, "text", transformed),
                                            )
                                        }
                                        streamedText.set(true)
                                    }
                                }

                                "text_end" -> {
                                    if (runtime.extensionMarkdownTransformerCount() > 0) {
                                        val source = assistantEvent.string("content") ?: streamedMarkdown.get()
                                        val transformed =
                                            runCatching {
                                                runtime.transformMarkdown(
                                                    markdown = source,
                                                    messageType = "assistant",
                                                    isStreaming = false,
                                                    availableWidth = (console.width() - 2).coerceAtLeast(1),
                                                )
                                            }.getOrDefault(source)
                                        val styled = themeForeground(runtime, console, "text", transformed)
                                        val fullScreen = console as? FullScreenConsoleControl
                                        if (fullScreen == null) {
                                            console.println(styled)
                                        } else {
                                            fullScreen.setStreamingText(styled)
                                            fullScreen.commitStreamingText()
                                        }
                                        streamedMarkdown.set("")
                                        streamedText.set(false)
                                    }
                                }
                            }
                        }

                        "tool_execution_start" -> {
                            if (streamedText.getAndSet(false)) {
                                console.println()
                            }
                            val label = "[${event.string("toolName").orEmpty()}]"
                            console.println(
                                themeStyle(runtime, console, label) { theme, text ->
                                    theme.bold(theme.fg("toolTitle", text))
                                },
                            )
                        }

                        "tool_execution_end" -> {
                            renderToolResultImages(event, console)
                            if (event["isError"]?.jsonPrimitive?.booleanOrNull == true) {
                                console.error("${event.string("toolName").orEmpty()} failed")
                            }
                        }

                        "extension_ui_request" -> renderExtensionUiRequest(event, console, runtime)

                        "extension_render" -> {
                            if (streamedText.get()) {
                                console.println()
                            }
                            event["lines"]
                                ?.jsonArray
                                .orEmpty()
                                .mapNotNull { it.jsonPrimitive.contentOrNull }
                                .forEach(console::println)
                        }

                        "extension_error" ->
                            console.error(
                                "Extension ${event.string("extensionPath").orEmpty()} " +
                                    "${event.string("event").orEmpty()}: ${event.string("error").orEmpty()}",
                            )

                        "agent_settled" -> settled.getAndSet(null)?.complete(Unit)
                    }
            }
            val packageUpdateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val quietStartup =
                    SettingsStore(
                        cwd = runtime.currentCwd(),
                        agentDir = agentDir,
                        projectTrusted = runtime.currentProjectTrusted(),
                    ).let { settings ->
                        settings.project().quietStartup ?: settings.global().quietStartup ?: false
                    }
                if (args.verbose || !quietStartup) {
                    renderInteractiveHeader(console, runtime)
                    renderStartupContext(console, runtime)
                    console.println(
                        themeForeground(
                            runtime,
                            console,
                            "dim",
                            "Type /help for commands. Ctrl-D or /exit quits.",
                        ),
                    )
                }
                runtime.renderExtensionTranscript()
                renderInitialExtensionSurfaces(console)
                renderLiveExtensionSurfaces = true
                if (!offline) {
                    packageUpdateScope.launch {
                        val updates =
                            try {
                                packageUpdateChecker(
                                    runtime.currentCwd(),
                                    agentDir,
                                    runtime.currentProjectTrusted(),
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                emptyList()
                            }
                        if (updates.isNotEmpty()) {
                            renderPackageUpdateNotification(console, runtime, updates)
                        }
                    }
                }
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
                    configureFullScreenConsole(console, runtime)
                    val shortcutResolution = runtime.extensionShortcuts()
                    shortcutResolution.diagnostics.forEach { diagnostic ->
                        if (reportedShortcutDiagnostics.add(diagnostic.error)) {
                            console.println(
                                themeForeground(runtime, console, "warning", "Warning: ${diagnostic.error}"),
                            )
                        }
                    }
                    val read =
                        try {
                            console.readLineWithShortcuts(
                                prompt = themeForeground(runtime, console, "accent", "> "),
                                shortcuts =
                                    buildList {
                                        loadExtensionShortcutKeybindings(agentDir)
                                            .getValue("app.message.copy")
                                            .forEach { shortcut ->
                                                add(
                                                    InteractiveShortcutBinding(
                                                        COPY_LAST_MESSAGE_SHORTCUT_ID,
                                                        shortcut,
                                                    ),
                                                )
                                            }
                                        shortcutResolution.shortcuts.values.forEach { shortcut ->
                                            add(InteractiveShortcutBinding(shortcut.id, shortcut.shortcut))
                                        }
                                    },
                                initialBuffer = editorBuffer,
                            )
                        } catch (_: UserInterruptException) {
                            console.println("^C")
                            continue
                        }
                    if (read is InteractiveReadResult.Shortcut) {
                        editorBuffer = read.buffer
                        if (read.id == COPY_LAST_MESSAGE_SHORTCUT_ID) {
                            copyLastAssistantMessage(runtime, console)
                            continue
                        }
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
                                console.println(
                                    themeForeground(runtime, console, "success", "Reloaded resources."),
                                )
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
                        input == "/ui-mode" ->
                            console.println(
                                "TUI mode: " +
                                    ((console as? FullScreenConsoleControl)?.currentTuiMode()?.wireValue ?: "regular"),
                            )

                        input.startsWith("/ui-mode ") ->
                            switchTuiMode(
                                value = input.removePrefix("/ui-mode ").trim(),
                                console = console,
                                runtime = runtime,
                            )

                        input == "/login" || input.startsWith("/login ") ->
                            login(
                                input.removePrefix("/login").trim().takeIf(String::isNotEmpty),
                                console,
                                allowNetwork = !offline,
                                backgroundScope = packageUpdateScope,
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
                packageUpdateScope.cancel()
                unsubscribe()
                runtime.close()
            }
        }
    }

    private fun renderPackageUpdateNotification(
        console: InteractiveConsole,
        runtime: RpcRuntime,
        updates: List<String>,
    ) {
        val lines =
            buildList {
                add(themeForeground(runtime, console, "warning", "Package Updates Available"))
                add(
                    themeForeground(
                        runtime,
                        console,
                        "dim",
                        "Package updates are available. Run pi update --extensions",
                    ),
                )
                add(themeForeground(runtime, console, "dim", "Packages:"))
                updates.forEach { update -> add("- $update") }
            }
        console.printlnAbove(lines.joinToString("\n"))
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
        runtime: RpcRuntime,
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

            "setTitle" ->
                event.string("title")?.let { title ->
                    (console as? FullScreenConsoleControl)?.setTitle(title)
                        ?: console.println(title)
                }

            "setWidget" -> updateExtensionWidget(event, console)
            "setHeader" -> updateExtensionHeader(event, console, runtime)
            "setFooter" -> updateExtensionFooter(event, console)
            "custom_close" ->
                event.string("componentId")
                    ?.let { componentId ->
                        (console as? FullScreenConsoleControl)?.closeExtensionCustom(componentId)
                    }

            "terminal_input_add" ->
                event.string("listenerId")
                    ?.let { listenerId ->
                        (console as? FullScreenConsoleControl)?.setTerminalInputHandler(listenerId) { data ->
                            runtime.invokeExtensionTerminalInput(listenerId, data)
                        }
                    }

            "terminal_input_remove" ->
                event.string("listenerId")
                    ?.let { listenerId ->
                        (console as? FullScreenConsoleControl)?.setTerminalInputHandler(listenerId, null)
                    }

            "setEditorComponent" -> {
                val fullScreen = console as? FullScreenConsoleControl ?: return
                val componentId = event.string("componentId")
                fullScreen.setEditorComponent(
                    componentId = componentId,
                    lines = event.stringLines("lines").orEmpty(),
                    text = event.string("text"),
                    bridge =
                        componentId?.let { id ->
                            { operation, data, text ->
                                runtime.invokeExtensionEditorComponent(
                                    componentId = id,
                                    operation = operation,
                                    width = console.width(),
                                    data = data,
                                    text = text,
                                )
                            }
                        },
                )
            }

            "set_editor_text" ->
                event.string("text")
                    ?.let { text ->
                        (console as? FullScreenConsoleControl)?.setEditorText(text)
                    }

            "paste_to_editor" ->
                event.string("text")
                    ?.let { text ->
                        (console as? FullScreenConsoleControl)?.setEditorText(text, paste = true)
                    }

            "custom_overlay_handle" -> {
                val componentId = event.string("componentId") ?: return
                val operation = event.string("operation") ?: return
                (console as? FullScreenConsoleControl)?.controlExtensionCustom(
                    componentId = componentId,
                    operation = operation,
                    hidden = event["hidden"]?.jsonPrimitive?.booleanOrNull,
                    targetNull = event["targetNull"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
        }
    }

    private fun updateExtensionWidget(
        event: JsonObject,
        console: InteractiveConsole,
    ) {
        val key = event.string("widgetKey") ?: event.string("key") ?: return
        val lines =
            event.stringLines("widgetLines")
                ?: event.stringLines("content")
        val placement =
            event.string("widgetPlacement")
                ?: event["options"]?.jsonObject?.string("placement")
                ?: "aboveEditor"
        synchronized(extensionSurfaceLock) {
            extensionWidgetsAbove.remove(key)
            extensionWidgetsBelow.remove(key)
            if (lines != null) {
                val target =
                    if (placement == "belowEditor") extensionWidgetsBelow else extensionWidgetsAbove
                target[key] = lines
            }
        }
        (console as? FullScreenConsoleControl)?.let { fullScreen ->
            fullScreen.setWidget(key, lines, placement)
            return
        }
        if (renderLiveExtensionSurfaces && lines != null) {
            renderExtensionLines(lines, console)
        }
    }

    private fun updateExtensionHeader(
        event: JsonObject,
        console: InteractiveConsole,
        runtime: RpcRuntime,
    ) {
        val lines = event.stringLines("headerLines") ?: event.stringLines("lines")
        synchronized(extensionSurfaceLock) {
            extensionHeader = lines
        }
        (console as? FullScreenConsoleControl)?.let { fullScreen ->
            if (lines == null) {
                fullScreen.setHeader(listOf(renderDefaultInteractiveHeaderText(console, runtime)))
            } else {
                fullScreen.setHeader(lines)
            }
            return
        }
        if (renderLiveExtensionSurfaces) {
            if (lines == null) {
                renderDefaultInteractiveHeader(console, runtime)
            } else {
                renderExtensionLines(lines, console)
            }
        }
    }

    private fun updateExtensionFooter(
        event: JsonObject,
        console: InteractiveConsole,
    ) {
        val lines = event.stringLines("footerLines") ?: event.stringLines("lines")
        synchronized(extensionSurfaceLock) {
            extensionFooter = lines
        }
        (console as? FullScreenConsoleControl)?.let { fullScreen ->
            fullScreen.setFooter(lines)
            return
        }
        if (renderLiveExtensionSurfaces && lines != null) {
            renderExtensionLines(lines, console)
        }
    }

    private fun renderInteractiveHeader(
        console: InteractiveConsole,
        runtime: RpcRuntime,
    ) {
        val lines = synchronized(extensionSurfaceLock) { extensionHeader }
        (console as? FullScreenConsoleControl)?.let { fullScreen ->
            fullScreen.setHeader(lines ?: listOf(renderDefaultInteractiveHeaderText(console, runtime)))
            return
        }
        if (lines == null) {
            renderDefaultInteractiveHeader(console, runtime)
        } else {
            renderExtensionLines(lines, console)
        }
    }

    private fun renderDefaultInteractiveHeader(
        console: InteractiveConsole,
        runtime: RpcRuntime,
    ) {
        console.println(renderDefaultInteractiveHeaderText(console, runtime))
    }

    private fun renderDefaultInteractiveHeaderText(
        console: InteractiveConsole,
        runtime: RpcRuntime,
    ): String =
        if (!console.supportsAnsi()) {
            defaultInteractiveHeaderText
        } else {
            val theme = runtime.currentTheme()
            val prefix = "pi Kotlin"
            val suffix = defaultInteractiveHeaderText.removePrefix(prefix)
            theme.bold(theme.fg("accent", prefix)) + theme.fg("dim", suffix)
        }

    private fun renderStartupContext(
        console: InteractiveConsole,
        runtime: RpcRuntime,
    ) {
        val resources = runtime.currentPromptResources()
        val paths =
            buildList {
                resources.systemPromptSourcePath?.let(::add)
                addAll(resources.appendPromptSourcePaths)
                resources.contextFiles.mapTo(this, ProjectContextFile::path)
            }
        if (paths.isEmpty()) {
            return
        }
        console.println()
        console.println(themeForeground(runtime, console, "mdHeading", "[Context]"))
        console.println(
            themeForeground(
                runtime,
                console,
                "dim",
                "  " + paths.joinToString(", ") { formatStartupContextPath(runtime.currentCwd(), it) },
            ),
        )
    }

    private fun themeForeground(
        runtime: RpcRuntime,
        console: InteractiveConsole,
        color: String,
        text: String,
    ): String =
        themeStyle(runtime, console, text) { theme, value ->
            theme.fg(color, value)
        }

    private fun themeStyle(
        runtime: RpcRuntime,
        console: InteractiveConsole,
        text: String,
        style: (Theme, String) -> String,
    ): String = if (console.supportsAnsi()) style(runtime.currentTheme(), text) else text

    private fun renderInitialExtensionSurfaces(console: InteractiveConsole) {
        if (console is FullScreenConsoleControl) {
            return
        }
        val surfaces =
            synchronized(extensionSurfaceLock) {
                buildList {
                    extensionWidgetsAbove.values.forEach(::add)
                    extensionWidgetsBelow.values.forEach(::add)
                    extensionFooter?.let(::add)
                }
            }
        surfaces.forEach { renderExtensionLines(it, console) }
    }

    private fun renderExtensionLines(
        lines: List<String>,
        console: InteractiveConsole,
    ) {
        lines.forEach(console::println)
    }

    private fun JsonObject.stringLines(name: String): List<String>? =
        (this[name] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

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

                "custom" -> handleExtensionCustom(request, console, cancellation)
                else -> cancelledUiResponse()
            }
        } catch (_: UserInterruptException) {
            cancelledUiResponse()
        } catch (_: EndOfFileException) {
            cancelledUiResponse()
        }
    }

    private fun handleExtensionCustom(
        request: JsonObject,
        console: InteractiveConsole,
        cancellation: ExtensionUiCancellation,
    ): JsonObject {
        (console as? FullScreenConsoleControl)?.let { fullScreen ->
            return fullScreen.readExtensionCustom(request, cancellation)
        }
        request.stringLines("lines").orEmpty().forEach(console::println)
        val value = console.readLine("Custom input: ", cancellation)
            ?: return cancelledUiResponse()
        return buildJsonObject { put("input", extensionCustomInputSequence(value)) }
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
        if (runtime.extensionMarkdownTransformerCount() > 0) {
            val source = works.earendil.pi.ai.contentText(message.content)
            if (source.isNotEmpty()) {
                val transformed =
                    runCatching {
                        runtime.transformMarkdown(
                            markdown = source,
                            messageType = "user",
                            isStreaming = false,
                            availableWidth = (console.width() - 2).coerceAtLeast(1),
                        )
                    }.getOrDefault(source)
                console.println(transformed)
            }
        }
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
        if (runtime.extensionMarkdownTransformerCount() > 0) {
            val pending = streamedText.getAndSet(false)
            if (pending) {
                val source =
                    runtime.handle(buildJsonObject { put("type", "get_last_assistant_text") })
                        ?.get("data")
                        ?.jsonObject
                        ?.string("text")
                        .orEmpty()
                if (source.isNotEmpty()) {
                    val transformed =
                        runCatching {
                            runtime.transformMarkdown(
                                markdown = source,
                                messageType = "assistant",
                                isStreaming = false,
                                availableWidth = (console.width() - 2).coerceAtLeast(1),
                            )
                        }.getOrDefault(source)
                    val styled = themeForeground(runtime, console, "text", transformed)
                    val fullScreen = console as? FullScreenConsoleControl
                    if (fullScreen == null) {
                        console.println(styled)
                    } else {
                        fullScreen.setStreamingText(styled)
                        fullScreen.commitStreamingText()
                    }
                }
            }
            return true
        }
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

    private fun switchTuiMode(
        value: String,
        console: InteractiveConsole,
        runtime: RpcRuntime,
    ) {
        val mode =
            when (value) {
                TuiMode.REGULAR.wireValue -> TuiMode.REGULAR
                TuiMode.FULLSCREEN.wireValue -> TuiMode.FULLSCREEN
                else -> {
                    console.error("Usage: /ui-mode <regular|fullscreen>")
                    return
                }
            }
        val fullScreen = console as? FullScreenConsoleControl
        if (fullScreen == null) {
            console.error("TUI mode switching is unavailable for this console.")
            return
        }
        if (!fullScreen.switchTuiMode(mode)) {
            console.error("Close active overlays before changing TUI mode.")
            return
        }
        SettingsStore(
            cwd = runtime.currentCwd(),
            agentDir = agentDir,
            projectTrusted = runtime.currentProjectTrusted(),
        ).setTuiMode(mode)
        console.println("TUI mode: ${mode.wireValue}")
    }

    private suspend fun copyLastAssistantMessage(
        runtime: RpcRuntime,
        console: InteractiveConsole,
    ) {
        val text =
            runtime.handle(buildJsonObject { put("type", "get_last_assistant_text") })
                ?.get("data")
                ?.jsonObject
                ?.string("text")
        if (text.isNullOrEmpty()) {
            console.error("No agent messages to copy yet.")
            return
        }
        val fullScreen = console as? FullScreenConsoleControl
        val copied = fullScreen?.copyTextToClipboard(text) ?: writeClipboardText(text)
        if (!copied) {
            console.error("Failed to copy to clipboard.")
            return
        }
        if (fullScreen?.currentTuiMode() == TuiMode.FULLSCREEN) {
            fullScreen.flash("Copied!")
        } else {
            console.println("Copied last agent message to clipboard.")
        }
    }

    private suspend fun login(
        providerId: String?,
        console: InteractiveConsole,
        allowNetwork: Boolean,
        backgroundScope: CoroutineScope,
    ) {
        val options =
            models
                .getProviders()
                .flatMap { provider ->
                    buildList {
                        provider.oauth?.let {
                            add(LoginOption(provider, AuthType.OAUTH))
                        }
                        provider.apiKey
                            ?.takeIf { it.supportsLogin }
                            ?.let {
                                add(LoginOption(provider, AuthType.API_KEY))
                            }
                    }
                }.sortedWith(compareBy({ it.provider.name }, { it.authType.name }))
        val matching =
            providerId
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?.let { requested ->
                    options.filter { option ->
                        option.provider.id.equals(requested, ignoreCase = true) ||
                            option.provider.name.equals(requested, ignoreCase = true)
                    }
                }
        val option =
            when {
                matching == null -> selectLoginOption(options, "Login provider:", console)
                matching.isEmpty() -> {
                    console.error("Provider does not support login: $providerId")
                    null
                }

                matching.size == 1 -> matching.single()
                else -> selectLoginOption(matching, "Authentication method:", console)
            } ?: return
        try {
            models.login(
                option.provider.id,
                option.authType,
                ConsoleAuthInteraction(console),
            )
            val localRefresh =
                models.refresh(
                    ModelsRefreshOptions(
                        allowNetwork = false,
                        providers = setOf(option.provider.id),
                    ),
                )
            localRefresh.errors[option.provider.id]?.let { throw it }
            console.println("Logged in to ${option.provider.name}.")
            if (allowNetwork) {
                backgroundScope.launch {
                    val refresh =
                        withTimeoutOrNull(modelRefreshTimeoutMs) {
                            models.refresh(
                                ModelsRefreshOptions(
                                    allowNetwork = true,
                                    providers = setOf(option.provider.id),
                                ),
                            )
                        }
                    when {
                        refresh == null ->
                            console.println(
                                "Warning: Model catalog refresh timed out for ${option.provider.id}; " +
                                    "showing cached models.",
                            )

                        refresh.errors.isNotEmpty() ->
                            console.println(
                                "Warning: Could not refresh ${option.provider.id}; showing cached models.",
                            )
                    }
                }
            }
        } catch (error: Exception) {
            console.error(error.message ?: "Login failed")
        }
    }

    private fun selectLoginOption(
        options: List<LoginOption>,
        prompt: String,
        console: InteractiveConsole,
    ): LoginOption? {
        if (options.isEmpty()) {
            console.error("No providers support interactive login.")
            return null
        }
        options.forEachIndexed { index, option ->
            val type = if (option.authType == AuthType.OAUTH) "subscription" else "API key"
            console.println("${index + 1}. ${option.provider.name} [$type; ${option.provider.id}]")
        }
        while (true) {
            val value = console.readLine("$prompt ")?.trim() ?: return null
            if (value.isEmpty()) {
                return null
            }
            val index = value.toIntOrNull()
            if (index != null && index in 1..options.size) {
                return options[index - 1]
            }
            options.firstOrNull { option ->
                option.provider.id.equals(value, ignoreCase = true) ||
                    option.provider.name.equals(value, ignoreCase = true)
            }?.let { return it }
            console.error("Select a provider by number or id.")
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
            val localRefresh =
                models.refresh(
                    ModelsRefreshOptions(
                        allowNetwork = false,
                        providers = setOf(selectedId),
                    ),
                )
            localRefresh.errors[selectedId]?.let { throw it }
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

    private suspend fun configureFullScreenConsole(
        console: InteractiveConsole,
        runtime: RpcRuntime,
    ) {
        val fullScreen = console as? FullScreenConsoleControl ?: return
        fullScreen.setTitle(defaultInteractiveHeaderText)
        fullScreen.setScrollbarStyle { text -> runtime.currentTheme().bg("scrollbarThumb", text) }
        fullScreen.setSearchStyles(
            match = { text ->
                runtime.currentTheme().underline(
                    runtime.currentTheme().bg(
                        "searchMatchBg",
                        runtime.currentTheme().fg("searchMatchText", text),
                    ),
                )
            },
            current = { text ->
                runtime.currentTheme().bold(
                    runtime.currentTheme().inverse(
                        runtime.currentTheme().bg(
                            "searchMatchBg",
                            runtime.currentTheme().fg("searchMatchText", text),
                        ),
                    ),
                )
            },
        )
        fullScreen.setAutocompleteProvider(createAutocompleteProvider(runtime))
    }

    private fun createConsole(args: Args): InteractiveConsole {
        consoleFactory?.let { return it() }
        val settings = SettingsStore(cwd, agentDir, projectTrusted = false)
        val resolvedKeybindings = loadExtensionShortcutKeybindings(agentDir)
        return FullScreenConsole(
            tuiMode = args.tuiMode ?: settings.mergedTuiMode(),
            fullscreenScrollbar = settings.mergedFullscreenScrollbar(),
            autocompleteMaxVisible = settings.mergedAutocompleteMaxVisible(),
            keybindings =
                KeybindingsManager(
                    TUI_KEYBINDINGS,
                    resolvedKeybindings.filterKeys(TUI_KEYBINDINGS::containsKey),
                ),
        )
    }

    private suspend fun createAutocompleteProvider(runtime: RpcRuntime): AutocompleteProvider {
        val availableModels =
            try {
                models.getAvailable()
            } catch (_: Exception) {
                emptyList()
            }
        val loginProviders =
            models
                .getProviders()
                .filter { provider ->
                    provider.oauth != null || provider.apiKey?.supportsLogin == true
                }
        val logoutProviders =
            try {
                models.listCredentials().map { credential -> credential.providerId }
            } catch (_: Exception) {
                emptyList()
            }
        val builtIns =
            interactiveSlashCommands(
                modelCompletions = { prefix ->
                    fuzzyFilter(availableModels, prefix) { model ->
                        "${model.provider}/${model.id} ${model.name}"
                    }.map { model ->
                        AutocompleteItem(
                            value = "${model.provider}/${model.id}",
                            label = model.id,
                            description = model.provider,
                        )
                    }
                },
                loginCompletions = { prefix ->
                    fuzzyFilter(loginProviders, prefix) { provider ->
                        "${provider.id} ${provider.name}"
                    }.map { provider ->
                        AutocompleteItem(
                            value = provider.id,
                            label = provider.id,
                            description = provider.name,
                        )
                    }
                },
                logoutCompletions = { prefix ->
                    fuzzyFilter(logoutProviders, prefix, String::toString)
                        .map(::AutocompleteItem)
                },
            )
        val builtInNames = builtIns.mapTo(mutableSetOf(), SlashCommand::name)
        val resourceCommands =
            runtime
                .handle(buildJsonObject { put("type", "get_commands") })
                ?.get("data")
                ?.jsonObject
                ?.get("commands")
                ?.jsonArray
                .orEmpty()
                .mapNotNull { element ->
                    val command = element as? JsonObject ?: return@mapNotNull null
                    val name = command.string("name") ?: return@mapNotNull null
                    if (name in builtInNames) {
                        return@mapNotNull null
                    }
                    SlashCommand(
                        name = name,
                        description = command.string("description"),
                    )
                }
                .distinctBy(SlashCommand::name)
        val base =
            CombinedAutocompleteProvider(
            commands = builtIns + resourceCommands,
            basePath = runtime.currentCwd(),
        )
        return if (runtime.extensionAutocompleteProviderCount() > 0) {
            HostedAutocompleteProvider(base, runtime)
        } else {
            base
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

private data class LoginOption(
    val provider: Provider,
    val authType: AuthType,
)

private const val MODEL_REFRESH_TIMEOUT_MS = 15_000L
private const val COPY_LAST_MESSAGE_SHORTCUT_ID = "app.message.copy"

private fun interactiveSlashCommands(
    modelCompletions: (String) -> List<AutocompleteItem>,
    loginCompletions: (String) -> List<AutocompleteItem>,
    logoutCompletions: (String) -> List<AutocompleteItem>,
): List<SlashCommand> =
    listOf(
        SlashCommand("help", "Show commands"),
        SlashCommand("hotkeys", "Show keyboard shortcuts"),
        SlashCommand("new", "Start a new session"),
        SlashCommand("clear", "Start a new session"),
        SlashCommand("session", "Show session information"),
        SlashCommand("stats", "Show token and cost totals"),
        SlashCommand("reload", "Reload skills, prompt templates, extensions, themes, and context files"),
        SlashCommand("name", "Set the session name", "<name>"),
        SlashCommand("model", "Show or change the model", "<provider/model>", modelCompletions),
        SlashCommand(
            "ui-mode",
            "Show or change the TUI mode",
            "<regular|fullscreen>",
        ) { prefix ->
            fuzzyFilter(
                listOf(TuiMode.REGULAR.wireValue, TuiMode.FULLSCREEN.wireValue),
                prefix,
                String::toString,
            ).map(::AutocompleteItem)
        },
        SlashCommand("login", "Sign in to a provider", "<provider>", loginCompletions),
        SlashCommand("logout", "Remove stored provider credentials", "<provider>", logoutCompletions),
        SlashCommand(
            "thinking",
            "Set the thinking level",
            "<level>",
        ) { prefix ->
            fuzzyFilter(
                listOf("off", "minimal", "low", "medium", "high", "xhigh", "max"),
                prefix,
                String::toString,
            ).map(::AutocompleteItem)
        },
        SlashCommand("exit", "Exit"),
        SlashCommand("quit", "Exit"),
    )

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
                    if (prompt.secret) {
                        console.readSecret("${prompt.message} ")
                    } else {
                        console.readLine("${prompt.message} ")
                    }
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

internal fun extensionCustomInputSequence(value: String): String =
    when (value.trim().lowercase()) {
        "", "enter", "return" -> "\r"
        "escape", "esc" -> "\u001b"
        "tab" -> "\t"
        "backspace" -> "\u007f"
        "delete" -> "\u001b[3~"
        "home" -> "\u001b[H"
        "end" -> "\u001b[F"
        "pageup", "page-up" -> "\u001b[5~"
        "pagedown", "page-down" -> "\u001b[6~"
        "up" -> "\u001b[A"
        "down" -> "\u001b[B"
        "right" -> "\u001b[C"
        "left" -> "\u001b[D"
        else -> value
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

    override fun readSecret(prompt: String): String? =
        try {
            reader.readLine(prompt, '*')
        } catch (_: EndOfFileException) {
            null
        }

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

    override fun printlnAbove(text: String) {
        reader.printAbove(text)
    }

    override fun error(text: String) {
        output.println("Error: $text")
        output.flush()
    }

    override fun width(): Int = normalizeTerminalWidth(terminal.width)

    override fun supportsAnsi(): Boolean = true

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

private fun checkPackageUpdates(
    cwd: Path,
    agentDir: Path,
    projectTrusted: Boolean,
): List<String> {
    val settings = SettingsStore(cwd, agentDir, projectTrusted)
    return PackageManager(
        cwd = cwd,
        agentDir = agentDir,
        settings = settings,
        projectTrusted = projectTrusted,
    ).checkForAvailableUpdates().map(PackageUpdate::displayName)
}

internal fun normalizeTerminalWidth(width: Int): Int = width.takeIf { it > 0 } ?: 80

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

private fun formatStartupContextPath(
    cwd: Path,
    path: Path,
): String {
    val normalizedPath = path.toAbsolutePath().normalize()
    val normalizedCwd = cwd.toAbsolutePath().normalize()
    if (normalizedPath.startsWith(normalizedCwd)) {
        return normalizedCwd.relativize(normalizedPath).toString().replace('\\', '/')
    }
    val home = defaultHomeDirectory()
    if (normalizedPath.startsWith(home)) {
        return "~/" + home.relativize(normalizedPath).toString().replace('\\', '/')
    }
    return normalizedPath.toString().replace('\\', '/')
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
        /ui-mode [mode]               Show or change regular|fullscreen mode
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
    console.println("Ctrl-X                         Copy the last agent message")
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

private fun renderToolResultImages(
    event: JsonObject,
    console: InteractiveConsole,
) {
    val content =
        (event["result"] as? JsonObject)
            ?.get("content")
            ?.let { it as? kotlinx.serialization.json.JsonArray }
            .orEmpty()
    content.forEach { value ->
        val block = value as? JsonObject ?: return@forEach
        if (block.string("type") != "image") return@forEach
        val data = block.string("data") ?: return@forEach
        val mimeType = block.string("mimeType") ?: return@forEach
        renderTerminalImageLines(
            base64Data = data,
            mimeType = mimeType,
            width = console.width(),
        ).forEach(console::println)
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.value(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.success(): Boolean = this["success"]?.jsonPrimitive?.booleanOrNull == true
