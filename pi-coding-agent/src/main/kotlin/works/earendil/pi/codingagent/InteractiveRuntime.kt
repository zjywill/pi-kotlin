package works.earendil.pi.codingagent

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.codingagent.session.SessionManager

interface InteractiveConsole : AutoCloseable {
    fun readLine(prompt: String): String?

    fun print(text: String)

    fun println(text: String = "")

    fun error(text: String)

    override fun close() = Unit
}

class InteractiveRuntime(
    private val models: Models,
    private val cwd: Path = Path.of("").toAbsolutePath().normalize(),
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
        val runtime =
            try {
                RpcRuntime(
                    models,
                    RpcRuntimeOptions(
                        cwd = cwd,
                        sessionDir = sessionDirectory,
                        noSession = args.noSession,
                        sessionId = args.sessionId,
                        sessionPath = sessionPath,
                        forkPath = forkPath,
                        continueRecent = args.continueSession,
                        provider = args.provider,
                        model = args.model,
                        apiKey = args.apiKey,
                        systemPrompt = args.systemPrompt,
                        appendSystemPrompt = args.appendSystemPrompt,
                        noContextFiles = args.noContextFiles,
                        projectTrusted = args.projectTrustOverride == true,
                        noTools = args.noTools,
                        noBuiltinTools = args.noBuiltinTools,
                        tools = args.tools,
                        excludeTools = args.excludeTools,
                        thinking = args.thinking,
                    ),
                )
            } catch (error: Exception) {
                System.err.println("Error: ${error.message}")
                return 1
            }

        consoleFactory().use { console ->
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
                while (true) {
                    val line =
                        try {
                            console.readLine("> ")
                        } catch (_: UserInterruptException) {
                            console.println("^C")
                            continue
                        }
                    val input = line?.trim() ?: break
                    if (input.isEmpty()) {
                        continue
                    }
                    when {
                        input == "/exit" || input == "/quit" -> break
                        input == "/help" -> printInteractiveHelp(console)
                        input == "/new" || input == "/clear" ->
                            printCommandResponse(
                                runtime.handle(buildJsonObject { put("type", "new_session") }),
                                console,
                                "Started a new session.",
                            )

                        input == "/session" -> printSession(runtime, console)
                        input == "/stats" -> printStats(runtime, console)
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
                        input.startsWith("/") -> console.error("Unknown command: ${input.substringBefore(' ')}")
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

private class JLineConsole(
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

    override fun readLine(prompt: String): String? =
        try {
            reader.readLine(prompt)
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

private fun printInteractiveHelp(console: InteractiveConsole) {
    console.println(
        """
        /help                         Show commands
        /new, /clear                  Start a new session
        /session                      Show session information
        /stats                        Show token and cost totals
        /name <name>                  Set the session name
        /model [provider/model]       Show or change the model
        /thinking <level>             Set off|minimal|low|medium|high|xhigh|max
        !<command>                    Run a shell command
        /exit, /quit                  Exit
        """.trimIndent(),
    )
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.value(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.success(): Boolean = this["success"]?.jsonPrimitive?.booleanOrNull == true
