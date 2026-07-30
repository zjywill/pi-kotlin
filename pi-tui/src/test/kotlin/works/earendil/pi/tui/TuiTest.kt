package works.earendil.pi.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiTest {
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
