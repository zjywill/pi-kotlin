package works.earendil.pi.tui

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

interface Component {
    fun render(width: Int): List<String>

    fun handleInput(data: String) = Unit

    val wantsKeyRelease: Boolean
        get() = false

    fun invalidate() = Unit
}

interface Focusable {
    var focused: Boolean
}

const val CURSOR_MARKER = "\u001B_pi:c\u0007"

open class Container : Component {
    val children: MutableList<Component> = mutableListOf()

    fun addChild(component: Component) {
        children += component
    }

    fun removeChild(component: Component) {
        children.remove(component)
    }

    fun clear() {
        children.clear()
    }

    override fun invalidate() {
        children.forEach(Component::invalidate)
    }

    override fun render(width: Int): List<String> =
        children.flatMap { component -> component.render(width) }
}

interface Terminal {
    val columns: Int
    val rows: Int

    fun start(
        onInput: (String) -> Unit,
        onResize: () -> Unit,
    )

    fun stop()

    fun write(data: String)

    fun hideCursor() {
        write("\u001B[?25l")
    }

    fun showCursor() {
        write("\u001B[?25h")
    }

    fun setTitle(title: String) {
        write("\u001B]0;$title\u0007")
    }
}

enum class OverlayAnchor {
    CENTER,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    TOP_CENTER,
    BOTTOM_CENTER,
    LEFT_CENTER,
    RIGHT_CENTER,
}

sealed interface SizeValue {
    data class Absolute(
        val value: Int,
    ) : SizeValue

    data class Percent(
        val value: Double,
    ) : SizeValue
}

data class OverlayMargin(
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
) {
    constructor(value: Int) : this(value, value, value, value)
}

data class OverlayOptions(
    val width: SizeValue? = null,
    val minWidth: Int? = null,
    val maxHeight: SizeValue? = null,
    val anchor: OverlayAnchor = OverlayAnchor.CENTER,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val row: SizeValue? = null,
    val col: SizeValue? = null,
    val margin: OverlayMargin = OverlayMargin(),
    val visible: ((width: Int, height: Int) -> Boolean)? = null,
    val nonCapturing: Boolean = false,
)

interface OverlayHandle {
    fun hide()

    fun setHidden(hidden: Boolean)

    fun isHidden(): Boolean

    fun focus()

    fun unfocus(target: Component? = null)

    fun isFocused(): Boolean
}

data class CursorPosition(
    val row: Int,
    val column: Int,
)

private data class OverlayEntry(
    val component: Component,
    val options: OverlayOptions,
    var preFocus: Component?,
    var hidden: Boolean,
    var focusOrder: Long,
)

private data class OverlayLayout(
    val width: Int,
    val row: Int,
    val column: Int,
    val maxHeight: Int?,
)

private data class RenderedOverlay(
    val lines: List<String>,
    val row: Int,
    val column: Int,
    val width: Int,
)

data class InputListenerResult(
    val consume: Boolean = false,
    val data: String? = null,
)

class Tui(
    val terminal: Terminal,
    private val showHardwareCursor: Boolean = false,
) : Container() {
    private val inputListeners = linkedSetOf<(String) -> InputListenerResult?>()
    private val overlayStack = mutableListOf<OverlayEntry>()
    private var focusOrder = 0L
    private var focusedComponent: Component? = null
    private var previousLines: List<String> = emptyList()
    private var previousWidth = 0
    private var previousHeight = 0
    private var hardwareCursorRow = 0
    private var rendering = false
    private var renderPending = false
    private var stopped = true

    var fullRedraws: Int = 0
        private set

    var cursorPosition: CursorPosition? = null
        private set

    fun setFocus(component: Component?) {
        if (focusedComponent === component) {
            return
        }
        (focusedComponent as? Focusable)?.focused = false
        focusedComponent = component
        (component as? Focusable)?.focused = true
    }

    fun focusedComponent(): Component? = focusedComponent

    fun addInputListener(listener: (String) -> InputListenerResult?): () -> Unit {
        inputListeners += listener
        return { inputListeners -= listener }
    }

    fun removeInputListener(listener: (String) -> InputListenerResult?) {
        inputListeners -= listener
    }

    fun showOverlay(
        component: Component,
        options: OverlayOptions = OverlayOptions(),
        renderImmediately: Boolean = true,
    ): OverlayHandle {
        val entry =
            OverlayEntry(
                component = component,
                options = options,
                preFocus = focusedComponent,
                hidden = false,
                focusOrder = ++focusOrder,
            )
        overlayStack += entry
        if (!options.nonCapturing && isVisible(entry)) {
            setFocus(component)
        }
        terminal.hideCursor()
        if (renderImmediately) {
            requestRender()
        }
        return object : OverlayHandle {
            override fun hide() {
                val index = overlayStack.indexOf(entry)
                if (index < 0) {
                    return
                }
                retargetPreFocus(entry)
                overlayStack.removeAt(index)
                if (focusedComponent === component) {
                    setFocus(topCapturingOverlay()?.component ?: entry.preFocus)
                }
                requestRender()
            }

            override fun setHidden(hidden: Boolean) {
                if (entry.hidden == hidden) {
                    return
                }
                entry.hidden = hidden
                if (hidden && focusedComponent === component) {
                    setFocus(topCapturingOverlay(excluding = entry)?.component ?: entry.preFocus)
                } else if (!hidden && !entry.options.nonCapturing && isVisible(entry)) {
                    entry.focusOrder = ++focusOrder
                    setFocus(component)
                }
                requestRender()
            }

            override fun isHidden(): Boolean = entry.hidden

            override fun focus() {
                if (entry !in overlayStack || !isVisible(entry)) {
                    return
                }
                entry.focusOrder = ++focusOrder
                setFocus(component)
                requestRender()
            }

            override fun unfocus(target: Component?) {
                if (focusedComponent !== component) {
                    return
                }
                val fallback =
                    target
                        ?: topCapturingOverlay(excluding = entry)?.component
                        ?: entry.preFocus
                setFocus(fallback)
                requestRender()
            }

            override fun isFocused(): Boolean = focusedComponent === component
        }
    }

    fun hideOverlay() {
        val entry = overlayStack.lastOrNull() ?: return
        retargetPreFocus(entry)
        overlayStack.removeLast()
        if (focusedComponent === entry.component) {
            setFocus(topCapturingOverlay()?.component ?: entry.preFocus)
        }
        requestRender()
    }

    fun hasOverlay(): Boolean = overlayStack.any(::isVisible)

    override fun invalidate() {
        super.invalidate()
        overlayStack.forEach { entry -> entry.component.invalidate() }
    }

    fun start() {
        stopped = false
        terminal.start(::dispatchInput, ::requestRender)
        terminal.hideCursor()
        requestRender(force = true)
    }

    fun stop() {
        if (stopped) {
            return
        }
        stopped = true
        terminal.showCursor()
        terminal.stop()
    }

    fun requestRender(force: Boolean = false) {
        if (stopped) {
            return
        }
        synchronized(this) {
            if (force) {
                previousLines = emptyList()
                previousWidth = -1
                previousHeight = -1
                hardwareCursorRow = 0
            }
            if (rendering) {
                renderPending = true
                return
            }
            rendering = true
        }
        try {
            while (true) {
                synchronized(this) {
                    renderPending = false
                }
                doRender()
                val rerender =
                    synchronized(this) {
                        if (renderPending) {
                            true
                        } else {
                            rendering = false
                            false
                        }
                    }
                if (!rerender) {
                    return
                }
            }
        } catch (error: Throwable) {
            synchronized(this) {
                rendering = false
            }
            throw error
        }
    }

    fun renderFrame(): List<String> {
        val width = terminal.columns.coerceAtLeast(1)
        val height = terminal.rows.coerceAtLeast(1)
        return compositeOverlays(render(width), width, height)
    }

    private fun dispatchInput(initialData: String) {
        var data = initialData
        inputListeners.toList().forEach { listener ->
            val result = listener(data)
            if (result?.consume == true) {
                return
            }
            result?.data?.let { replacement ->
                data = replacement
            }
            if (data.isEmpty()) {
                return
            }
        }
        val focused = focusedComponent ?: return
        if (isKeyRelease(data) && !focused.wantsKeyRelease) {
            return
        }
        focused.handleInput(data)
        requestRender()
    }

    private fun doRender() {
        val width = terminal.columns.coerceAtLeast(1)
        val height = terminal.rows.coerceAtLeast(1)
        val rawLines = renderFrame().toMutableList()
        cursorPosition = extractCursorPosition(rawLines, height)
        val lines =
            rawLines.map { line ->
                normalizeTerminalOutput(line) + SEGMENT_RESET
            }
        val widthChanged = previousWidth > 0 && previousWidth != width
        val heightChanged = previousHeight > 0 && previousHeight != height
        if (previousLines.isEmpty()) {
            fullRender(lines, clear = previousWidth == -1 || previousHeight == -1)
            remember(lines, width, height)
            return
        }
        if (widthChanged || heightChanged || lines.size < previousLines.size) {
            fullRender(lines, clear = true)
            remember(lines, width, height)
            return
        }

        val maxLines = max(lines.size, previousLines.size)
        var firstChanged = -1
        var lastChanged = -1
        for (index in 0 until maxLines) {
            val old = previousLines.getOrElse(index) { "" }
            val new = lines.getOrElse(index) { "" }
            if (old != new) {
                if (firstChanged < 0) {
                    firstChanged = index
                }
                lastChanged = index
            }
        }
        if (firstChanged < 0) {
            positionHardwareCursor(lines.size)
            remember(lines, width, height)
            return
        }

        val buffer = StringBuilder(SYNC_START)
        val target = if (firstChanged == previousLines.size && firstChanged > 0) firstChanged - 1 else firstChanged
        val rowDelta = target - hardwareCursorRow
        when {
            rowDelta > 0 -> buffer.append("\u001B[").append(rowDelta).append('B')
            rowDelta < 0 -> buffer.append("\u001B[").append(-rowDelta).append('A')
        }
        buffer.append(if (target < firstChanged) "\r\n" else "\r")
        for (index in firstChanged..min(lastChanged, lines.lastIndex)) {
            if (index > firstChanged) {
                buffer.append("\r\n")
            }
            buffer.append("\u001B[2K").append(lines[index])
        }
        buffer.append(SYNC_END)
        terminal.write(buffer.toString())
        hardwareCursorRow = min(lastChanged, lines.lastIndex).coerceAtLeast(0)
        positionHardwareCursor(lines.size)
        remember(lines, width, height)
    }

    private fun fullRender(
        lines: List<String>,
        clear: Boolean,
    ) {
        fullRedraws++
        val buffer = StringBuilder(SYNC_START)
        if (clear) {
            buffer.append("\u001B[2J\u001B[H\u001B[3J")
        }
        lines.forEachIndexed { index, line ->
            if (index > 0) {
                buffer.append("\r\n")
            }
            buffer.append(line)
        }
        buffer.append(SYNC_END)
        terminal.write(buffer.toString())
        hardwareCursorRow = (lines.size - 1).coerceAtLeast(0)
        positionHardwareCursor(lines.size)
    }

    private fun positionHardwareCursor(totalLines: Int) {
        val position = cursorPosition
        if (position == null || totalLines == 0) {
            terminal.hideCursor()
            return
        }
        val targetRow = position.row.coerceIn(0, totalLines - 1)
        val targetColumn = position.column.coerceAtLeast(0)
        val buffer = StringBuilder()
        val rowDelta = targetRow - hardwareCursorRow
        when {
            rowDelta > 0 -> buffer.append("\u001B[").append(rowDelta).append('B')
            rowDelta < 0 -> buffer.append("\u001B[").append(-rowDelta).append('A')
        }
        buffer.append("\u001B[").append(targetColumn + 1).append('G')
        terminal.write(buffer.toString())
        hardwareCursorRow = targetRow
        if (showHardwareCursor) {
            terminal.showCursor()
        } else {
            terminal.hideCursor()
        }
    }

    private fun remember(
        lines: List<String>,
        width: Int,
        height: Int,
    ) {
        previousLines = lines
        previousWidth = width
        previousHeight = height
    }

    private fun extractCursorPosition(
        lines: MutableList<String>,
        height: Int,
    ): CursorPosition? {
        val viewportTop = max(0, lines.size - height)
        for (row in lines.lastIndex downTo viewportTop) {
            val marker = lines[row].indexOf(CURSOR_MARKER)
            if (marker < 0) {
                continue
            }
            val column = visibleWidth(lines[row].substring(0, marker))
            lines[row] =
                lines[row].removeRange(
                    marker,
                    marker + CURSOR_MARKER.length,
                )
            return CursorPosition(row, column)
        }
        return null
    }

    private fun compositeOverlays(
        baseLines: List<String>,
        width: Int,
        height: Int,
    ): List<String> {
        if (overlayStack.none(::isVisible)) {
            return baseLines
        }
        val result = baseLines.toMutableList()
        val rendered =
            overlayStack
                .filter(::isVisible)
                .sortedBy(OverlayEntry::focusOrder)
                .map { entry ->
                    val initial = resolveOverlayLayout(entry.options, 0, width, height)
                    var lines = entry.component.render(initial.width)
                    if (initial.maxHeight != null && lines.size > initial.maxHeight) {
                        lines = lines.take(initial.maxHeight)
                    }
                    val layout = resolveOverlayLayout(entry.options, lines.size, width, height)
                    RenderedOverlay(lines, layout.row, layout.column, layout.width)
                }
        val minLines = rendered.maxOfOrNull { overlay -> overlay.row + overlay.lines.size } ?: 0
        val workingHeight = max(max(result.size, height), minLines)
        while (result.size < workingHeight) {
            result += ""
        }
        val viewportStart = max(0, workingHeight - height)
        rendered.forEach { overlay ->
            overlay.lines.forEachIndexed { lineIndex, line ->
                val target = viewportStart + overlay.row + lineIndex
                if (target !in result.indices) {
                    return@forEachIndexed
                }
                val clipped =
                    if (visibleWidth(line) > overlay.width) {
                        sliceByColumn(line, 0, overlay.width, strict = true)
                    } else {
                        line
                    }
                result[target] =
                    compositeLineAt(
                        baseLine = result[target],
                        overlayLine = clipped,
                        startColumn = overlay.column,
                        overlayWidth = overlay.width,
                        totalWidth = width,
                    )
            }
        }
        return result
    }

    private fun resolveOverlayLayout(
        options: OverlayOptions,
        overlayHeight: Int,
        terminalWidth: Int,
        terminalHeight: Int,
    ): OverlayLayout {
        val marginTop = options.margin.top.coerceAtLeast(0)
        val marginRight = options.margin.right.coerceAtLeast(0)
        val marginBottom = options.margin.bottom.coerceAtLeast(0)
        val marginLeft = options.margin.left.coerceAtLeast(0)
        val availableWidth = max(1, terminalWidth - marginLeft - marginRight)
        val availableHeight = max(1, terminalHeight - marginTop - marginBottom)

        var width = resolveSize(options.width, terminalWidth) ?: min(80, availableWidth)
        options.minWidth?.let { minimum ->
            width = max(width, minimum)
        }
        width = width.coerceIn(1, availableWidth)
        val maxHeight =
            resolveSize(options.maxHeight, terminalHeight)
                ?.coerceIn(1, availableHeight)
        val effectiveHeight =
            if (maxHeight == null) {
                overlayHeight
            } else {
                min(overlayHeight, maxHeight)
            }

        var row =
            resolvePosition(
                value = options.row,
                reference = terminalHeight,
                available = availableHeight,
                itemSize = effectiveHeight,
                margin = marginTop,
            ) ?: resolveAnchorRow(options.anchor, effectiveHeight, availableHeight, marginTop)
        var column =
            resolvePosition(
                value = options.col,
                reference = terminalWidth,
                available = availableWidth,
                itemSize = width,
                margin = marginLeft,
            ) ?: resolveAnchorColumn(options.anchor, width, availableWidth, marginLeft)
        row += options.offsetY
        column += options.offsetX
        row = row.coerceIn(marginTop, max(marginTop, terminalHeight - marginBottom - effectiveHeight))
        column = column.coerceIn(marginLeft, max(marginLeft, terminalWidth - marginRight - width))
        return OverlayLayout(width, row, column, maxHeight)
    }

    private fun compositeLineAt(
        baseLine: String,
        overlayLine: String,
        startColumn: Int,
        overlayWidth: Int,
        totalWidth: Int,
    ): String {
        val afterStart = startColumn + overlayWidth
        val base =
            extractSegments(
                line = baseLine,
                beforeEnd = startColumn,
                afterStart = afterStart,
                afterLength = totalWidth - afterStart,
                strictAfter = true,
            )
        val overlay = sliceWithWidth(overlayLine, 0, overlayWidth, strict = true)
        val beforePad = max(0, startColumn - base.beforeWidth)
        val overlayPad = max(0, overlayWidth - overlay.width)
        val actualBeforeWidth = max(startColumn, base.beforeWidth)
        val actualOverlayWidth = max(overlayWidth, overlay.width)
        val afterTarget = max(0, totalWidth - actualBeforeWidth - actualOverlayWidth)
        val afterPad = max(0, afterTarget - base.afterWidth)
        val result =
            base.before +
                " ".repeat(beforePad) +
                SEGMENT_RESET +
                overlay.text +
                " ".repeat(overlayPad) +
                SEGMENT_RESET +
                base.after +
                " ".repeat(afterPad)
        return if (visibleWidth(result) <= totalWidth) {
            result
        } else {
            sliceByColumn(result, 0, totalWidth, strict = true)
        }
    }

    private fun isVisible(entry: OverlayEntry): Boolean =
        !entry.hidden &&
            (
                entry.options.visible?.invoke(
                    terminal.columns,
                    terminal.rows,
                ) != false
            )

    private fun topCapturingOverlay(excluding: OverlayEntry? = null): OverlayEntry? =
        overlayStack
            .asSequence()
            .filter { entry ->
                entry !== excluding &&
                    !entry.options.nonCapturing &&
                    isVisible(entry)
            }.maxByOrNull(OverlayEntry::focusOrder)

    private fun retargetPreFocus(removed: OverlayEntry) {
        overlayStack.forEach { entry ->
            if (entry !== removed && entry.preFocus === removed.component) {
                entry.preFocus = removed.preFocus
            }
        }
    }
}

fun parseSizeValue(value: String): SizeValue? {
    val match = PERCENT_PATTERN.matchEntire(value.trim()) ?: return null
    return SizeValue.Percent(match.groupValues[1].toDouble())
}

private fun resolveSize(
    value: SizeValue?,
    reference: Int,
): Int? =
    when (value) {
        null -> null
        is SizeValue.Absolute -> value.value
        is SizeValue.Percent -> floor(reference * value.value / 100.0).toInt()
    }

private fun resolvePosition(
    value: SizeValue?,
    reference: Int,
    available: Int,
    itemSize: Int,
    margin: Int,
): Int? =
    when (value) {
        null -> null
        is SizeValue.Absolute -> value.value
        is SizeValue.Percent -> {
            val maxPosition = max(0, available - itemSize)
            margin + floor(maxPosition * value.value / 100.0).toInt()
        }
    }

private fun resolveAnchorRow(
    anchor: OverlayAnchor,
    height: Int,
    availableHeight: Int,
    marginTop: Int,
): Int =
    when (anchor) {
        OverlayAnchor.TOP_LEFT,
        OverlayAnchor.TOP_CENTER,
        OverlayAnchor.TOP_RIGHT,
        -> marginTop

        OverlayAnchor.BOTTOM_LEFT,
        OverlayAnchor.BOTTOM_CENTER,
        OverlayAnchor.BOTTOM_RIGHT,
        -> marginTop + availableHeight - height

        OverlayAnchor.LEFT_CENTER,
        OverlayAnchor.CENTER,
        OverlayAnchor.RIGHT_CENTER,
        -> marginTop + floor((availableHeight - height) / 2.0).toInt()
    }

private fun resolveAnchorColumn(
    anchor: OverlayAnchor,
    width: Int,
    availableWidth: Int,
    marginLeft: Int,
): Int =
    when (anchor) {
        OverlayAnchor.TOP_LEFT,
        OverlayAnchor.LEFT_CENTER,
        OverlayAnchor.BOTTOM_LEFT,
        -> marginLeft

        OverlayAnchor.TOP_RIGHT,
        OverlayAnchor.RIGHT_CENTER,
        OverlayAnchor.BOTTOM_RIGHT,
        -> marginLeft + availableWidth - width

        OverlayAnchor.TOP_CENTER,
        OverlayAnchor.CENTER,
        OverlayAnchor.BOTTOM_CENTER,
        -> marginLeft + floor((availableWidth - width) / 2.0).toInt()
    }

private val PERCENT_PATTERN = Regex("""^(\d+(?:\.\d+)?)%$""")
private const val SYNC_START = "\u001B[?2026h"
private const val SYNC_END = "\u001B[?2026l"
private const val SEGMENT_RESET = "\u001B[0m\u001B]8;;\u0007"
