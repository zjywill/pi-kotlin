package works.earendil.pi.tui

import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ScrollViewScrollbar(
    val wireValue: String,
) {
    HIDDEN("hidden"),
    AUTO("auto"),
    ALWAYS("always"),
}

interface ViewportComponent : Component {
    fun renderViewport(
        width: Int,
        height: Int,
    ): List<String>

    fun renderDocument(width: Int): List<String> = render(width)

    fun handleViewportInput(data: String): Boolean = false

    fun bindRenderRequest(callback: () -> Unit) = Unit
}

class ViewportLayout(
    private val document: Component,
    private val dock: Component,
    scrollbar: ScrollViewScrollbar = ScrollViewScrollbar.AUTO,
    private var scrollbarStyle: (String) -> String = { text -> "\u001B[100m$text\u001B[49m" },
) : ViewportComponent {
    private var scrollbar = scrollbar
    private var scrollTop = 0
    private var contentHeight = 0
    private var viewportHeight = 0
    private var followingEnd = true
    private var scrollbarActivity = 0L
    private var requestRender: (() -> Unit)? = null
    private var renderedDocumentLines: List<String> = emptyList()
    private var renderedWidth = 0
    private var scrollbarDragOffset: Int? = null

    val isFollowingOutput: Boolean
        get() = followingEnd

    val viewportTop: Int
        get() = scrollTop

    fun setScrollbar(value: ScrollViewScrollbar) {
        if (scrollbar == value) {
            return
        }
        scrollbar = value
        markScrollbarActivity()
        requestRender?.invoke()
    }

    fun setScrollbarStyle(style: (String) -> String) {
        scrollbarStyle = style
        requestRender?.invoke()
    }

    fun scrollBy(lines: Int) {
        if (lines == 0) {
            return
        }
        val maxScrollTop = max(0, contentHeight - viewportHeight)
        val start = if (followingEnd) maxScrollTop else scrollTop
        val next = (start + lines).coerceIn(0, maxScrollTop)
        if (next == start) {
            return
        }
        scrollTop = next
        followingEnd = next == maxScrollTop
        markScrollbarActivity()
        requestRender?.invoke()
    }

    fun scrollToTop() {
        if (scrollTop == 0 && !followingEnd) {
            return
        }
        scrollTop = 0
        followingEnd = contentHeight <= viewportHeight
        markScrollbarActivity()
        requestRender?.invoke()
    }

    fun scrollToBottom() {
        val next = max(0, contentHeight - viewportHeight)
        if (scrollTop == next && followingEnd) {
            return
        }
        scrollTop = next
        followingEnd = true
        markScrollbarActivity()
        requestRender?.invoke()
    }

    override fun handleViewportInput(data: String): Boolean {
        val keybindings = getKeybindings()
        val page = max(1, viewportHeight - PAGE_SCROLL_OVERLAP)
        return when {
            keybindings.matches(data, "tui.altScreen.pageUp") -> {
                if (!isKeyRelease(data)) scrollBy(-page)
                true
            }

            keybindings.matches(data, "tui.altScreen.pageDown") -> {
                if (!isKeyRelease(data)) scrollBy(page)
                true
            }

            keybindings.matches(data, "tui.altScreen.previousPrompt") -> {
                if (!isKeyRelease(data)) scrollToPrompt(-1)
                true
            }

            keybindings.matches(data, "tui.altScreen.nextPrompt") -> {
                if (!isKeyRelease(data)) scrollToPrompt(1)
                true
            }

            keybindings.matches(data, "tui.altScreen.top") -> {
                if (!isKeyRelease(data)) scrollToTop()
                true
            }

            keybindings.matches(data, "tui.altScreen.bottom") -> {
                if (!isKeyRelease(data)) scrollToBottom()
                true
            }

            else -> handleMouseInput(data)
        }
    }

    override fun render(width: Int): List<String> =
        document.render(width) + dock.render(width)

    override fun renderDocument(width: Int): List<String> = render(width)

    override fun renderViewport(
        width: Int,
        height: Int,
    ): List<String> {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val dockLines = dock.render(safeWidth).takeLast(safeHeight)
        val nextViewportHeight = max(0, safeHeight - dockLines.size)
        val contentWidth =
            if (scrollbar == ScrollViewScrollbar.ALWAYS && safeWidth > 1) {
                safeWidth - 1
            } else {
                safeWidth
            }
        val documentLines = document.render(contentWidth)
        renderedDocumentLines = documentLines
        renderedWidth = safeWidth
        contentHeight = documentLines.size
        viewportHeight = nextViewportHeight
        val maxScrollTop = max(0, contentHeight - viewportHeight)
        scrollTop =
            if (followingEnd) {
                maxScrollTop
            } else {
                scrollTop.coerceIn(0, maxScrollTop)
            }
        followingEnd = followingEnd || scrollTop == maxScrollTop

        val visibleDocument =
            if (viewportHeight == 0) {
                mutableListOf()
            } else {
                documentLines
                    .drop(scrollTop)
                    .take(viewportHeight)
                    .toMutableList()
                    .also { lines ->
                        while (lines.size < viewportHeight) {
                            lines += ""
                        }
                    }
            }
        for (row in visibleDocument.indices) {
            val line = visibleDocument[row]
            val metadata = getKittyImageMetadata(line) ?: continue
            visibleDocument[row] =
                cropKittyImageLine(
                    line = line,
                    hiddenRows = 0,
                    visibleRows = min(metadata.rows, viewportHeight - row),
                )
        }
        if (shouldShowScrollbar()) {
            paintScrollbar(visibleDocument, safeWidth)
        } else if (contentWidth < safeWidth) {
            visibleDocument.replaceAll { line -> line + " " }
        }
        return visibleDocument + dockLines
    }

    override fun bindRenderRequest(callback: () -> Unit) {
        requestRender = callback
    }

    private fun handleMouseInput(data: String): Boolean {
        val match = SGR_MOUSE.matchEntire(data) ?: return false
        val button = match.groupValues[1].toIntOrNull() ?: return true
        val x = match.groupValues[2].toIntOrNull()?.minus(1) ?: return true
        val y = match.groupValues[3].toIntOrNull()?.minus(1) ?: return true
        val release = match.groupValues[4] == "m"
        if (button and 64 != 0) {
            when (button and 3) {
                0 -> scrollBy(-1)
                1 -> scrollBy(1)
            }
            return true
        }
        if (release && scrollbarDragOffset != null) {
            scrollbarDragOffset = null
            markScrollbarActivity()
            return true
        }
        if (scrollbarDragOffset != null && button and 32 != 0) {
            dragScrollbar(y)
            return true
        }
        if (
            !release &&
            button and 3 == 0 &&
            x == renderedWidth - 1 &&
            y in 0 until viewportHeight &&
            contentHeight > viewportHeight &&
            scrollbar != ScrollViewScrollbar.HIDDEN
        ) {
            markScrollbarActivity()
            val geometry = scrollbarGeometry() ?: return true
            scrollbarDragOffset =
                if (y in geometry.thumbTop until geometry.thumbTop + geometry.thumbHeight) {
                    y - geometry.thumbTop
                } else {
                    geometry.thumbHeight / 2
                }
            dragScrollbar(y)
            return true
        }
        return false
    }

    private fun shouldShowScrollbar(): Boolean =
        when (scrollbar) {
            ScrollViewScrollbar.HIDDEN -> false
            ScrollViewScrollbar.ALWAYS -> viewportHeight > 0
            ScrollViewScrollbar.AUTO ->
                contentHeight > viewportHeight &&
                    System.nanoTime() < scrollbarActivity
        }

    private fun markScrollbarActivity() {
        if (scrollbar != ScrollViewScrollbar.AUTO) {
            return
        }
        val deadline = System.nanoTime() + SCROLLBAR_HIDE_DELAY_NS
        scrollbarActivity = deadline
        thread(name = "pi-scrollbar-hide", isDaemon = true) {
            Thread.sleep(SCROLLBAR_HIDE_DELAY_MS)
            if (scrollbarActivity == deadline) {
                requestRender?.invoke()
            }
        }
    }

    private fun paintScrollbar(
        lines: MutableList<String>,
        width: Int,
    ) {
        if (lines.isEmpty() || width <= 0) {
            return
        }
        val geometry = scrollbarGeometry() ?: return
        val thumbHeight = geometry.thumbHeight
        val thumbTop = geometry.thumbTop
        for (row in thumbTop until min(lines.size, thumbTop + thumbHeight)) {
            val line = lines[row]
            val before = sliceByColumn(line, 0, max(0, width - 1), strict = true)
            val padding = " ".repeat(max(0, width - 1 - visibleWidth(before)))
            lines[row] = before + padding + scrollbarStyle(" ")
        }
    }

    private fun scrollToPrompt(direction: Int) {
        if (direction == 0 || renderedDocumentLines.isEmpty()) {
            return
        }
        var row = scrollTop + direction
        while (row in renderedDocumentLines.indices) {
            if (renderedDocumentLines[row].contains(OSC133_PROMPT_START)) {
                scrollTo(row)
                return
            }
            row += direction
        }
    }

    private fun scrollTo(value: Int) {
        val maxScrollTop = max(0, contentHeight - viewportHeight)
        val next = value.coerceIn(0, maxScrollTop)
        if (next == scrollTop && followingEnd == (next == maxScrollTop)) {
            return
        }
        scrollTop = next
        followingEnd = next == maxScrollTop
        markScrollbarActivity()
        requestRender?.invoke()
    }

    private fun dragScrollbar(pointerRow: Int) {
        val geometry = scrollbarGeometry() ?: return
        val maxThumbTop = max(0, viewportHeight - geometry.thumbHeight)
        val thumbTop =
            (pointerRow - requireNotNull(scrollbarDragOffset))
                .coerceIn(0, maxThumbTop)
        val next =
            if (maxThumbTop == 0) {
                0
            } else {
                ((thumbTop.toDouble() / maxThumbTop) * geometry.maxScrollTop).roundToInt()
            }
        scrollTo(next)
    }

    private fun scrollbarGeometry(): ScrollbarGeometry? {
        if (viewportHeight <= 0 || contentHeight <= viewportHeight) {
            return null
        }
        val thumbHeight =
            max(
                min(2, viewportHeight),
                min(
                    viewportHeight,
                    ((viewportHeight.toDouble() * viewportHeight) / contentHeight).roundToInt(),
                ),
            )
        val maxScrollTop = max(0, contentHeight - viewportHeight)
        val maxThumbTop = max(0, viewportHeight - thumbHeight)
        val thumbTop =
            if (maxScrollTop == 0) {
                0
            } else {
                ((scrollTop.toDouble() / maxScrollTop) * maxThumbTop).roundToInt()
            }
        return ScrollbarGeometry(thumbTop, thumbHeight, maxScrollTop)
    }
}

private data class ScrollbarGeometry(
    val thumbTop: Int,
    val thumbHeight: Int,
    val maxScrollTop: Int,
)

private val SGR_MOUSE = Regex("""^\u001B\[<(\d+);(\d+);(\d+)([Mm])$""")
private const val OSC133_PROMPT_START = "\u001B]133;A\u0007"
private const val PAGE_SCROLL_OVERLAP = 4
private const val SCROLLBAR_HIDE_DELAY_MS = 1_000L
private const val SCROLLBAR_HIDE_DELAY_NS = SCROLLBAR_HIDE_DELAY_MS * 1_000_000L
