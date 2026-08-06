package works.earendil.pi.tui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorTest {
    @Test
    fun `edits unicode by grapheme and supports multiline submission`() {
        val terminal = EditorTerminal()
        val tui = Tui(terminal)
        val editor = Editor(tui)
        tui.addChild(editor)
        tui.setFocus(editor)
        var submitted: String? = null
        editor.onSubmit = { submitted = it }

        editor.handleInput("😀")
        editor.handleInput("👍")
        editor.handleInput("\u001B[D")
        editor.handleInput("x")
        editor.handleInput("\u001B[C")
        editor.handleInput("\n")
        editor.handleInput("next")
        editor.handleInput("\r")

        assertEquals("😀x👍\nnext", editor.getText())
        assertEquals("😀x👍\nnext", submitted)

        editor.handleInput("\u007F")
        assertEquals("😀x👍\nnex", editor.getText())
    }

    @Test
    fun `history keeps draft and caps consecutive duplicates`() {
        val editor = Editor(Tui(EditorTerminal()))
        editor.addToHistory("first")
        editor.addToHistory("second")
        editor.addToHistory("second")
        editor.setText("draft")

        editor.handleInput("\u001B[A")
        editor.handleInput("\u001B[A")
        assertEquals("second", editor.getText())
        editor.handleInput("\u001B[A")
        assertEquals("first", editor.getText())
        editor.handleInput("\u001B[B")
        editor.handleInput("\u001B[B")
        assertEquals("draft", editor.getText())
    }

    @Test
    fun `dedicated history bindings browse without moving the cursor first`() {
        val keybindings =
            KeybindingsManager(
                TUI_KEYBINDINGS,
                mapOf(
                    "tui.editor.historyPrevious" to listOf("ctrl+p"),
                    "tui.editor.historyNext" to listOf("ctrl+n"),
                ),
            )
        val editor = Editor(Tui(EditorTerminal()), keybindings = keybindings)
        editor.addToHistory("older prompt")
        editor.addToHistory("newer\nmultiline prompt")
        editor.setText("draft")
        editor.handleInput("\u001B[D")
        editor.handleInput("\u001B[D")

        editor.handleInput("\u0010")
        assertEquals("newer\nmultiline prompt", editor.getText())
        assertEquals(EditorCursor(0, 0), editor.getCursor())

        editor.handleInput("\u0010")
        assertEquals("older prompt", editor.getText())

        editor.handleInput("\u000E")
        assertEquals("newer\nmultiline prompt", editor.getText())
        assertEquals(EditorCursor(1, 16), editor.getCursor())

        editor.handleInput("\u000E")
        assertEquals("draft", editor.getText())
        assertEquals(EditorCursor(0, 3), editor.getCursor())
    }

    @Test
    fun `bracketed paste undo kill and yank preserve text`() {
        val editor = Editor(Tui(EditorTerminal()))
        editor.handleInput("\u001B[200~hello\nworld\u001B[201~")
        assertEquals("hello\nworld", editor.getText())
        editor.handleInput("\u001B[27;5;117~")
        assertEquals("hello\n", editor.getText())
        editor.handleInput("\u0019")
        assertEquals("hello\nworld", editor.getText())
        editor.handleInput("\u001F")
        assertEquals("hello\n", editor.getText())
    }

    @Test
    fun `renders cursor marker and accepts asynchronous autocomplete`() {
        val root = Files.createTempDirectory("pi-kotlin-editor-autocomplete")
        Files.writeString(root.resolve("README.md"), "")
        val terminal = EditorTerminal(columns = 30, rows = 12)
        val tui = Tui(terminal)
        val editor = Editor(tui)
        editor.setAutocompleteProvider(
            CombinedAutocompleteProvider(
                commands = listOf(SlashCommand("model"), SlashCommand("help")),
                basePath = root,
            ),
        )
        tui.addChild(editor)
        tui.setFocus(editor)
        tui.start()

        editor.handleInput("/")
        editor.handleInput("m")
        waitFor { editor.isShowingAutocomplete() }
        assertTrue(editor.render(30).any { "model" in it })

        editor.handleInput("\t")
        assertEquals("/model ", editor.getText())
        assertFalse(editor.isShowingAutocomplete())
        assertEquals(CursorPosition(1, 7), tui.cursorPosition)
    }

    @Test
    fun `enter submits an exact slash command argument completion`() {
        val editor = Editor(Tui(EditorTerminal()))
        editor.setAutocompleteProvider(
            CombinedAutocompleteProvider(
                commands =
                    listOf(
                        SlashCommand(
                            name = "login",
                            getArgumentCompletions = {
                                listOf(AutocompleteItem("rpc-fixture"))
                            },
                        ),
                    ),
                basePath = Files.createTempDirectory("pi-kotlin-editor-command-argument"),
            ),
        )
        var submitted: String? = null
        editor.onSubmit = { submitted = it }

        "/login rpc-fixture".forEach { character ->
            editor.handleInput(character.toString())
        }
        waitFor { editor.isShowingAutocomplete() }
        editor.handleInput("\r")

        assertEquals("/login rpc-fixture", submitted)
        assertFalse(editor.isShowingAutocomplete())
    }

    private fun waitFor(predicate: () -> Boolean) {
        repeat(100) {
            if (predicate()) {
                return
            }
            Thread.sleep(5)
        }
        assertTrue(predicate())
    }
}

private class EditorTerminal(
    override var columns: Int = 80,
    override var rows: Int = 24,
) : Terminal {
    override fun start(
        onInput: (String) -> Unit,
        onResize: () -> Unit,
    ) = Unit

    override fun stop() = Unit

    override fun write(data: String) = Unit
}
