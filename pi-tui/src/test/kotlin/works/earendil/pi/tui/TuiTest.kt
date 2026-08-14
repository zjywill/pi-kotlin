package works.earendil.pi.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiTest {
    @Test
    fun `alternate screen reuses kitty uploads when an image moves`() {
        val terminal = TestTerminal(columns = 20, rows = 4)
        val imageId = 4242L
        registerKittyImageMetadata(
            KittyImageMetadata(
                imageId = imageId,
                columns = 2,
                rows = 2,
                widthPx = 100,
                heightPx = 100,
            ),
        )
        val image =
            encodeKitty(
                base64Data = "A".repeat(8_192),
                columns = 2,
                rows = 2,
                imageId = imageId,
                moveCursor = false,
            )
        val content = MutableLinesComponent(listOf("header", image, "", "dock"))
        val tui =
            Tui(
                terminal = terminal,
                screenMode = TuiScreenMode.ALTERNATE,
                imageProtocol = TerminalImageProtocol.KITTY,
            )
        tui.addChild(content)
        tui.start()
        assertTrue(terminal.writes().contains("\u001B_Ga=T"))

        terminal.clearWrites()
        content.lines = listOf("header", "second", image, "")
        tui.requestRender()
        val redraw = terminal.writes()

        assertTrue(redraw.contains(deleteAllKittyPlacements()))
        assertTrue(redraw.contains("\u001B_Ga=p,q=2"))
        assertFalse(redraw.contains("\u001B_Ga=T"))
    }

    @Test
    fun `alternate screen retains a recently offscreen kitty image`() {
        val terminal = TestTerminal(columns = 20, rows = 4)
        val imageId = 4343L
        registerKittyImageMetadata(
            KittyImageMetadata(
                imageId = imageId,
                columns = 2,
                rows = 2,
                widthPx = 100,
                heightPx = 100,
            ),
        )
        val image =
            encodeKitty(
                base64Data = "B".repeat(8_192),
                columns = 2,
                rows = 2,
                imageId = imageId,
                moveCursor = false,
            )
        val content = MutableLinesComponent(listOf(image))
        val tui =
            Tui(
                terminal = terminal,
                screenMode = TuiScreenMode.ALTERNATE,
                imageProtocol = TerminalImageProtocol.KITTY,
            )
        tui.addChild(content)
        tui.start()

        terminal.clearWrites()
        content.lines = listOf("offscreen")
        tui.requestRender()
        assertFalse(terminal.writes().contains(deleteKittyImage(imageId)))

        terminal.clearWrites()
        content.lines = listOf(image)
        tui.requestRender()
        assertTrue(terminal.writes().contains("\u001B_Ga=p,q=2"))
        assertFalse(terminal.writes().contains("\u001B_Ga=T"))
    }

    @Test
    fun `screen mode switching preserves the renderer and rejects active overlays`() {
        val terminal = TestTerminal(columns = 20, rows = 4)
        val tui = Tui(terminal)
        tui.addChild(LinesComponent(listOf("main")))
        tui.start()
        terminal.clearWrites()

        assertTrue(tui.switchScreenMode(TuiScreenMode.ALTERNATE))
        assertEquals(TuiScreenMode.ALTERNATE, tui.currentScreenMode())
        assertTrue(terminal.writes().contains("\u001B[?1049h"))

        val overlay = tui.showOverlay(LinesComponent(listOf("overlay")))
        assertFalse(tui.switchScreenMode(TuiScreenMode.MAIN))
        overlay.hide()

        terminal.clearWrites()
        assertTrue(tui.switchScreenMode(TuiScreenMode.MAIN))
        tui.requestRender()
        assertEquals(TuiScreenMode.MAIN, tui.currentScreenMode())
        assertTrue(terminal.writes().contains("\u001B[?1049l"))
        assertEquals(listOf("main"), tui.renderFrame())
    }

    @Test
    fun `alternate screen keeps dock fixed while scrolling the document`() {
        val terminal = TestTerminal(columns = 20, rows = 6)
        val document = MutableLinesComponent((0..9).map { "line-$it" })
        val dock = LinesComponent(listOf("editor"))
        val tui = Tui(terminal, screenMode = TuiScreenMode.ALTERNATE)
        tui.addChild(
            ViewportLayout(
                document = document,
                dock = dock,
                scrollbar = ScrollViewScrollbar.HIDDEN,
            ),
        )

        tui.start()

        assertTrue(terminal.writes().contains("\u001B[?1049h"))
        assertEquals(listOf("line-5", "line-6", "line-7", "line-8", "line-9", "editor"), tui.renderFrame())

        terminal.sendInput("\u001B[5~")
        assertEquals(listOf("line-4", "line-5", "line-6", "line-7", "line-8", "editor"), tui.renderFrame())

        terminal.sendInput("\u001B[F")
        assertEquals(listOf("line-5", "line-6", "line-7", "line-8", "line-9", "editor"), tui.renderFrame())

        tui.stop()
        assertTrue(terminal.writes().contains("\u001B[?1049l"))
    }

    @Test
    fun `alternate screen jumps between prompt markers`() {
        val terminal = TestTerminal(columns = 20, rows = 6)
        val prompt = "\u001B]133;A\u0007"
        val document =
            MutableLinesComponent(
                listOf(
                    "${prompt}prompt-0",
                    "line-1",
                    "line-2",
                    "${prompt}prompt-3",
                    "line-4",
                    "line-5",
                    "line-6",
                    "${prompt}prompt-7",
                    "line-8",
                    "line-9",
                ),
            )
        val viewport =
            ViewportLayout(
                document = document,
                dock = LinesComponent(listOf("editor")),
                scrollbar = ScrollViewScrollbar.HIDDEN,
            )
        val tui = Tui(terminal, screenMode = TuiScreenMode.ALTERNATE)
        tui.addChild(viewport)
        tui.start()

        assertEquals(5, viewport.viewportTop)
        terminal.sendInput("\u001B[1;6A")
        assertEquals(3, viewport.viewportTop)
        terminal.sendInput("\u001B[1;6B")
        assertEquals(5, viewport.viewportTop)
    }

    @Test
    fun `alternate screen supports configurable single-line transcript scrolling`() {
        val previous = getKeybindings()
        setKeybindings(
            KeybindingsManager(
                TUI_KEYBINDINGS,
                mapOf(
                    "tui.altScreen.lineUp" to listOf("u"),
                    "tui.altScreen.lineDown" to listOf("d"),
                ),
            ),
        )
        try {
            val terminal = TestTerminal(columns = 20, rows = 6)
            val viewport =
                ViewportLayout(
                    document = MutableLinesComponent((0..19).map { "line-$it" }),
                    dock = LinesComponent(listOf("editor")),
                    scrollbar = ScrollViewScrollbar.HIDDEN,
                )
            val tui = Tui(terminal, screenMode = TuiScreenMode.ALTERNATE)
            tui.addChild(viewport)
            tui.start()

            assertEquals(15, viewport.viewportTop)
            terminal.sendInput("u")
            assertEquals(14, viewport.viewportTop)
            terminal.sendInput("d")
            assertEquals(15, viewport.viewportTop)
        } finally {
            setKeybindings(previous)
        }
    }

    @Test
    fun `focused fullscreen overlay receives wheel input instead of transcript viewport`() {
        val terminal = TestTerminal(columns = 20, rows = 6)
        val viewport =
            ViewportLayout(
                document = MutableLinesComponent((0..19).map { "line-$it" }),
                dock = LinesComponent(listOf("editor")),
                scrollbar = ScrollViewScrollbar.HIDDEN,
            )
        val tui = Tui(terminal, screenMode = TuiScreenMode.ALTERNATE)
        tui.addChild(viewport)
        tui.start()
        val overlay = FocusableComponent("overlay")
        tui.showOverlay(overlay)

        terminal.sendInput("\u001B[<64;1;1M")

        assertEquals(15, viewport.viewportTop)
        assertEquals(listOf("\u001B[<64;1;1M"), overlay.inputs)
    }

    @Test
    fun `alternate screen copies only an active mouse selection`() {
        val terminal = TestTerminal(columns = 20, rows = 3)
        val tui = Tui(terminal, screenMode = TuiScreenMode.ALTERNATE)
        tui.addChild(LinesComponent(listOf("hello world")))
        tui.start()
        terminal.clearWrites()

        terminal.sendInput("\u001B[<0;5;3m")
        assertFalse(terminal.writes().contains("\u001B]52;c;"))

        terminal.sendInput("\u001B[<0;1;3M")
        terminal.sendInput("\u001B[<32;5;3M")
        terminal.sendInput("\u001B[<3;5;3m")

        assertTrue(terminal.writes().contains("\u001B]52;c;aGVsbG8=\u0007"))
        assertTrue(terminal.writes().contains("Copied!"))
    }

    @Test
    fun `alternate screen routes generic release selection through host clipboard`() {
        val terminal = TestTerminal(columns = 20, rows = 3)
        var copied: String? = null
        val tui =
            Tui(
                terminal = terminal,
                screenMode = TuiScreenMode.ALTERNATE,
                copySelectionToClipboard = { text ->
                    copied = text
                    true
                },
            )
        tui.addChild(LinesComponent(listOf("hello world")))
        tui.start()
        terminal.clearWrites()

        terminal.sendInput("\u001B[<0;1;3M")
        terminal.sendInput("\u001B[<32;5;3M")
        terminal.sendInput("\u001B[<3;5;3m")

        assertEquals("hello", copied)
        assertFalse(terminal.writes().contains("\u001B]52;c;"))
        assertTrue(terminal.writes().contains("Copied!"))
    }

    @Test
    fun `alternate screen reports host clipboard failure`() {
        val terminal = TestTerminal(columns = 20, rows = 3)
        val tui =
            Tui(
                terminal = terminal,
                screenMode = TuiScreenMode.ALTERNATE,
                copySelectionToClipboard = { false },
            )
        tui.addChild(LinesComponent(listOf("hello world")))
        tui.start()
        terminal.clearWrites()

        terminal.sendInput("\u001B[<0;1;3M")
        terminal.sendInput("\u001B[<32;5;3M")
        terminal.sendInput("\u001B[<3;5;3m")

        assertFalse(terminal.writes().contains("\u001B]52;c;"))
        assertTrue(terminal.writes().contains("Copy failed"))
    }

    @Test
    fun `alternate screen scrollbar drag changes viewport position`() {
        val terminal = TestTerminal(columns = 20, rows = 6)
        val viewport =
            ViewportLayout(
                document = MutableLinesComponent((0..19).map { "line-$it" }),
                dock = LinesComponent(listOf("editor")),
                scrollbar = ScrollViewScrollbar.ALWAYS,
            )
        val tui = Tui(terminal, screenMode = TuiScreenMode.ALTERNATE)
        tui.addChild(viewport)
        tui.start()

        assertEquals(15, viewport.viewportTop)
        terminal.sendInput("\u001B[H")
        assertEquals(0, viewport.viewportTop)
        terminal.sendInput("\u001B[<0;20;1M")
        terminal.sendInput("\u001B[<32;20;5M")
        terminal.sendInput("\u001B[<0;20;5m")

        assertTrue(viewport.viewportTop > 0)
    }

    @Test
    fun `centers overlay against terminal when base content is short`() {
        val terminal = TestTerminal(columns = 40, rows = 10)
        val tui = Tui(terminal)
        tui.addChild(LinesComponent(listOf("one", "two")))
        tui.showOverlay(LinesComponent(listOf("OVERLAY")), OverlayOptions(width = SizeValue.Absolute(10)))

        val frame = tui.renderFrame()

        assertEquals(10, frame.size)
        assertTrue(frame[4].contains("OVERLAY"))
        assertTrue(frame.all { visibleWidth(it) <= 40 })
    }

    @Test
    fun `supports anchors percentage width margins and clipping`() {
        val terminal = TestTerminal(columns = 100, rows = 20)
        val tui = Tui(terminal)
        tui.showOverlay(
            RecordingComponent("X".repeat(80)),
            OverlayOptions(
                width = SizeValue.Percent(50.0),
                minWidth = 30,
                anchor = OverlayAnchor.TOP_LEFT,
                margin = OverlayMargin(2),
            ),
        )

        val frame = tui.renderFrame()

        assertEquals("X", sliceByColumn(frame[2], 2, 1, strict = true).takeLast(1))
        assertEquals(100, visibleWidth(frame[2]))
    }

    @Test
    fun `non capturing overlay preserves focus and explicit focus restores it`() {
        val terminal = TestTerminal()
        val tui = Tui(terminal)
        val editor = FocusableComponent("editor")
        val overlay = FocusableComponent("overlay")
        tui.addChild(editor)
        tui.setFocus(editor)

        val handle = tui.showOverlay(overlay, OverlayOptions(nonCapturing = true))
        assertTrue(editor.focused)
        assertFalse(overlay.focused)

        handle.focus()
        assertFalse(editor.focused)
        assertTrue(overlay.focused)
        assertTrue(handle.isFocused())

        handle.unfocus()
        assertTrue(editor.focused)
        assertFalse(overlay.focused)
    }

    @Test
    fun `deferred overlay render waits until controls are applied`() {
        val terminal = TestTerminal()
        val tui = Tui(terminal)
        tui.start()
        terminal.clearWrites()

        tui.showOverlay(
            LinesComponent(listOf("deferred-overlay")),
            renderImmediately = false,
        )

        assertFalse(terminal.writes().contains("deferred-overlay"))
        tui.requestRender()
        assertTrue(terminal.writes().contains("deferred-overlay"))
    }

    @Test
    fun `terminal listeners may rewrite or consume raw input before focused component`() {
        val terminal = TestTerminal()
        val tui = Tui(terminal)
        val component = FocusableComponent("input")
        tui.addChild(component)
        tui.setFocus(component)
        tui.addInputListener { data ->
            if (data == "skip") {
                InputListenerResult(consume = true)
            } else {
                InputListenerResult(data = data.uppercase())
            }
        }
        tui.start()

        terminal.sendInput("a")
        terminal.sendInput("skip")

        assertEquals(listOf("A"), component.inputs)
    }

    @Test
    fun `line level renderer clears the screen when content shrinks`() {
        val terminal = TestTerminal()
        val tui = Tui(terminal)
        val lines = MutableLinesComponent(listOf("one", "two", "three"))
        tui.addChild(lines)
        tui.start()
        terminal.clearWrites()

        lines.lines = listOf("one")
        tui.requestRender()

        assertTrue(terminal.writes().contains("\u001B[2J"))
        assertTrue(tui.fullRedraws >= 2)
    }

    @Test
    fun `cursor marker is removed and positioned by terminal columns`() {
        val terminal = TestTerminal(columns = 20, rows = 5)
        val tui = Tui(terminal, showHardwareCursor = true)
        tui.addChild(LinesComponent(listOf("abc${CURSOR_MARKER}def")))
        tui.start()

        assertEquals(CursorPosition(0, 3), tui.cursorPosition)
        assertTrue(terminal.writes().contains("\u001B[4G"))
        assertFalse(terminal.writes().contains(CURSOR_MARKER))
    }
}

private class LinesComponent(
    private val lines: List<String>,
) : Component {
    override fun render(width: Int): List<String> = lines
}

private class MutableLinesComponent(
    var lines: List<String>,
) : Component {
    override fun render(width: Int): List<String> = lines
}

private class RecordingComponent(
    private val line: String,
) : Component {
    override fun render(width: Int): List<String> = listOf(line)
}

private class FocusableComponent(
    private val line: String,
) : Component,
    Focusable {
    override var focused: Boolean = false
    val inputs = mutableListOf<String>()

    override fun render(width: Int): List<String> = listOf(line)

    override fun handleInput(data: String) {
        inputs += data
    }
}

private class TestTerminal(
    override var columns: Int = 80,
    override var rows: Int = 24,
) : Terminal {
    private var inputHandler: ((String) -> Unit)? = null
    private var resizeHandler: (() -> Unit)? = null
    private val output = StringBuilder()

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

    override fun write(data: String) {
        output.append(data)
    }

    fun sendInput(data: String) {
        inputHandler?.invoke(data)
    }

    fun writes(): String = output.toString()

    fun clearWrites() {
        output.clear()
    }
}
