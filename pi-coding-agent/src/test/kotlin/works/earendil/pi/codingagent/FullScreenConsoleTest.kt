package works.earendil.pi.codingagent

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import works.earendil.pi.tui.Terminal
import works.earendil.pi.tui.InputListenerResult
import works.earendil.pi.tui.CombinedAutocompleteProvider
import works.earendil.pi.tui.SlashCommand
import kotlinx.coroutines.runBlocking
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.Models

class FullScreenConsoleTest {
    @Test
    fun `full screen console reads raw editor input and renders transcript`() {
        val terminal = ConsoleTerminal(columns = 40, rows = 12)
        val console = FullScreenConsole(terminal, closeTerminal = null)
        console.println("header")
        val result = CompletableFuture.supplyAsync { console.readLine("> ") }
        terminal.awaitOutput("> ")

        terminal.sendText("hello")
        terminal.sendInput("\r")

        assertEquals("hello", result.get(2, TimeUnit.SECONDS))
        assertTrue(terminal.output().contains("header"))
        assertTrue(terminal.output().contains("> "))
        console.close()
    }

    @Test
    fun `secret input is masked and ctrl d returns eof only when empty`() {
        val terminal = ConsoleTerminal()
        val console = FullScreenConsole(terminal, closeTerminal = null)
        val secret = CompletableFuture.supplyAsync { console.readSecret("Key: ") }
        terminal.awaitOutput("Key: ")
        terminal.sendText("topsecret")
        terminal.sendInput("\u0004")
        assertFalse(secret.isDone)
        terminal.sendInput("\r")

        assertEquals("topsecret", secret.get(2, TimeUnit.SECONDS))
        assertFalse(terminal.output().contains("topsecret"))
        assertTrue(terminal.output().contains("*********"))

        val eof = CompletableFuture.supplyAsync { console.readLine("> ") }
        terminal.awaitOutput("> ")
        terminal.sendInput("\u0004")
        assertNull(eof.get(2, TimeUnit.SECONDS))
        console.close()
    }

    @Test
    fun `extension shortcut returns current editor buffer before editor binding`() {
        val terminal = ConsoleTerminal()
        val console = FullScreenConsole(terminal, closeTerminal = null)
        val result =
            CompletableFuture.supplyAsync {
                console.readLineWithShortcuts(
                    prompt = "> ",
                    shortcuts = listOf(InteractiveShortcutBinding("extension:one", "ctrl+k")),
                    initialBuffer = "draft",
                )
            }
        terminal.awaitOutput("> ")

        terminal.sendInput("\u000B")

        assertEquals(
            InteractiveReadResult.Shortcut("extension:one", "draft"),
            result.get(2, TimeUnit.SECONDS),
        )
        console.close()
    }

    @Test
    fun `extension surfaces and custom overlay render in the component tree`() {
        val terminal = ConsoleTerminal(columns = 50, rows = 14)
        val console = FullScreenConsole(terminal, closeTerminal = null)
        console.setHeader(listOf("custom-header"))
        console.setWidget("status", listOf("custom-widget"), "aboveEditor")
        console.setFooter(listOf("custom-footer"))
        terminal.awaitOutput("custom-header")
        terminal.awaitOutput("custom-widget")
        terminal.awaitOutput("custom-footer")

        val result =
            CompletableFuture.supplyAsync {
                console.readExtensionCustom(
                    buildJsonObject {
                        put("componentId", "overlay-one")
                        put("overlay", true)
                        put("lines", JsonArray(listOf(JsonPrimitive("custom-overlay"))))
                        put(
                            "overlayOptions",
                            buildJsonObject {
                                put("anchor", "top-right")
                                put("width", 20)
                            },
                        )
                    },
                    ExtensionUiCancellation(),
                )
            }
        terminal.awaitOutput("custom-overlay")
        terminal.sendInput("\u001B[B")

        assertEquals("\u001B[B", result.get(2, TimeUnit.SECONDS)["input"]?.let { (it as JsonPrimitive).content })
        console.closeExtensionCustom("overlay-one")
        console.close()
    }

    @Test
    fun `extension terminal input may rewrite and consume before the editor`() {
        val terminal = ConsoleTerminal()
        val console = FullScreenConsole(terminal, closeTerminal = null)
        console.setTerminalInputHandler("listener") { data ->
            when (data) {
                "x" -> InputListenerResult(consume = true)
                else -> InputListenerResult(data = data.uppercase())
            }
        }
        val result = CompletableFuture.supplyAsync { console.readLine("> ") }
        terminal.awaitOutput("> ")

        terminal.sendInput("a")
        terminal.sendInput("x")
        terminal.sendInput("\r")

        assertEquals("A", result.get(2, TimeUnit.SECONDS))
        console.close()
    }

    @Test
    fun `remote extension editor renders edits and submits through the main read loop`() {
        val terminal = ConsoleTerminal()
        val console = FullScreenConsole(terminal, closeTerminal = null)
        var remoteText = ""
        console.setEditorComponent(
            componentId = "editor-one",
            lines = listOf("remote-editor:"),
            text = "",
        ) { operation, data, text ->
            when (operation) {
                "set_text" -> remoteText = text.orEmpty()
                "input" ->
                    if (data != "\r") {
                        remoteText += data.orEmpty()
                    }
            }
            buildJsonObject {
                put("text", remoteText)
                put("lines", JsonArray(listOf(JsonPrimitive("remote-editor:$remoteText"))))
                if (operation == "input" && data == "\r") {
                    put("submitted", remoteText)
                }
            }
        }
        val result = CompletableFuture.supplyAsync { console.readLine("> ") }
        terminal.awaitOutput("remote-editor:")

        terminal.sendInput("h")
        terminal.sendInput("i")
        terminal.sendInput("\r")

        assertEquals("hi", result.get(2, TimeUnit.SECONDS))
        terminal.awaitOutput("remote-editor:hi")
        console.setEditorComponent(componentId = null, text = "restored")
        console.close()
    }

    @Test
    fun `ctrl d exits after restoring the default editor`() {
        val terminal = ConsoleTerminal()
        val console = FullScreenConsole(terminal, closeTerminal = null)
        console.setEditorComponent(
            componentId = "editor-one",
            lines = listOf("remote-editor"),
            text = "draft",
        ) { _, _, _ ->
            buildJsonObject {
                put("text", "draft")
                put("lines", JsonArray(listOf(JsonPrimitive("remote-editor"))))
            }
        }
        val result = CompletableFuture.supplyAsync { console.readLine("> ") }
        terminal.awaitOutput("remote-editor")

        console.setEditorComponent(componentId = null, text = "draft")
        console.setEditorText("")
        terminal.awaitOutput("> ")
        terminal.sendInput("\u0004")

        assertNull(result.get(2, TimeUnit.SECONDS))
        console.close()
    }

    @Test
    fun `overlay handle controls queue before mount and restore input after show`() {
        val terminal = ConsoleTerminal(columns = 40, rows = 12)
        val console = FullScreenConsole(terminal, closeTerminal = null)
        console.controlExtensionCustom("queued-overlay", "setHidden", hidden = true)
        val result =
            CompletableFuture.supplyAsync {
                console.readExtensionCustom(
                    buildJsonObject {
                        put("componentId", "queued-overlay")
                        put("overlay", true)
                        put("lines", JsonArray(listOf(JsonPrimitive("queued"))))
                    },
                    ExtensionUiCancellation(),
                )
            }
        Thread.sleep(30)
        terminal.sendInput("x")
        assertFalse(result.isDone)

        console.controlExtensionCustom("queued-overlay", "setHidden", hidden = false)
        terminal.awaitOutput("queued")
        terminal.sendInput("\r")

        assertEquals("\r", result.get(2, TimeUnit.SECONDS)["input"]?.let { (it as JsonPrimitive).content })
        console.closeExtensionCustom("queued-overlay")
        console.close()
    }

    @Test
    fun `slash autocomplete submits the completed command to the runtime`() {
        val terminal = ConsoleTerminal()
        val console = FullScreenConsole(terminal, closeTerminal = null)
        console.setAutocompleteProvider(
            CombinedAutocompleteProvider(
                commands = listOf(SlashCommand("help", "Show commands")),
                basePath = Files.createTempDirectory("pi-kotlin-console-slash"),
            ),
        )
        val result = CompletableFuture.supplyAsync { console.readLine("> ") }
        terminal.awaitOutput("> ")

        terminal.sendText("/help")
        terminal.awaitOutput("Show commands")
        terminal.sendInput("\r")

        assertEquals("/help ", result.get(2, TimeUnit.SECONDS))
        console.close()
    }

    @Test
    fun `full screen runtime executes slash commands and exits`() {
        val terminal = ConsoleTerminal(columns = 40, rows = 18)
        val console = FullScreenConsole(terminal, closeTerminal = null)
        val root = Files.createTempDirectory("pi-kotlin-full-screen-runtime")
        val runtime =
            InteractiveRuntime(
                Models(listOf(FauxProvider())),
                cwd = root,
                agentDir = Files.createDirectories(root.resolve("agent")),
                consoleFactory = { console },
            )
        val result =
            CompletableFuture.supplyAsync {
                runBlocking {
                    runtime.run(
                        parseArgs(
                            listOf(
                                "--provider",
                                "faux",
                                "--model",
                                "faux-1",
                                "--no-session",
                                "--offline",
                            ),
                        ),
                    )
                }
            }
        terminal.awaitOutput("Type /help for commands.")

        terminal.sendText("/help")
        terminal.awaitOutput("Show commands")
        terminal.sendInput("\r")
        terminal.awaitOutput("!<command>")
        terminal.sendText("/exit")
        terminal.sendInput("\r")

        assertEquals(0, result.get(2, TimeUnit.SECONDS))
    }
}

private class ConsoleTerminal(
    override var columns: Int = 80,
    override var rows: Int = 24,
) : Terminal {
    private var inputHandler: ((String) -> Unit)? = null
    private var resizeHandler: (() -> Unit)? = null
    private val writes = StringBuilder()

    override fun start(
        onInput: (String) -> Unit,
        onResize: () -> Unit,
    ) {
        inputHandler = onInput
        resizeHandler = onResize
    }

    override fun stop() {
        inputHandler = null
        resizeHandler = null
    }

    @Synchronized
    override fun write(data: String) {
        writes.append(data)
    }

    fun sendText(value: String) {
        value.forEach { character ->
            sendInput(character.toString())
        }
    }

    fun sendInput(value: String) {
        inputHandler?.invoke(value)
    }

    @Synchronized
    fun output(): String = writes.toString()

    fun awaitOutput(value: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (value !in output() && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(value in output(), "Terminal output did not contain $value")
    }
}
