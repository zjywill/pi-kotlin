package works.earendil.pi.tui

import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.Locale

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

private data class SearchMatch(
    val row: Int,
    val startColumn: Int,
    val endColumn: Int,
)

class TranscriptSearchBar : Component, Focusable {
    @Volatile
    var active: Boolean = false

    @Volatile
    var query: String = ""

    @Volatile
    var resultIndex: Int = -1

    @Volatile
    var resultCount: Int = 0

    @Volatile
    override var focused: Boolean = false

    @Volatile
    var onInput: ((String) -> Unit)? = null

    override fun handleInput(data: String) {
        onInput?.invoke(data)
    }

    override fun render(width: Int): List<String> {
        if (!active) {
            return emptyList()
        }
        val safeWidth = width.coerceAtLeast(1)
        val status =
            when {
                query.isEmpty() -> ""
                resultCount == 0 -> "No matches"
                else -> "${resultIndex + 1}/$resultCount"
            }
        val prefix = " Find transcript "
        val gap = " ".repeat((safeWidth - visibleWidth(prefix) - visibleWidth(status)).coerceAtLeast(1))
        val title = truncateToWidth("$prefix$gap$status", safeWidth, "", pad = true)
        val input = truncateToWidth(" $query", safeWidth, "", pad = true)
        return listOf("\u001B[7m$title\u001B[27m", input)
    }
}

class ViewportLayout(
    private val document: Component,
    private val dock: Component,
    scrollbar: ScrollViewScrollbar = ScrollViewScrollbar.AUTO,
    private var scrollbarStyle: (String) -> String = { text -> "\u001B[100m$text\u001B[49m" },
    private val searchBar: TranscriptSearchBar? = null,
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
    private var searchMatches: List<SearchMatch> = emptyList()
    private var selectedSearchMatch = -1
    private var searchMatchStyle: (String) -> String = { text -> "\u001B[4m$text\u001B[24m" }
    private var searchCurrentMatchStyle: (String) -> String = { text -> "\u001B[1;7m$text\u001B[22;27m" }

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

    fun setSearchStyles(
        match: (String) -> String,
        current: (String) -> String,
    ) {
        searchMatchStyle = match
        searchCurrentMatchStyle = current
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
        if (searchBar?.active == true) {
            when {
                keybindings.matches(data, "tui.altScreen.searchClose") -> {
                    if (!isKeyRelease(data)) closeSearch()
                    return true
                }

                keybindings.matches(data, "tui.altScreen.searchNext") -> {
                    if (!isKeyRelease(data)) selectSearchMatch(1)
                    return true
                }

                keybindings.matches(data, "tui.altScreen.searchPrevious") -> {
                    if (!isKeyRelease(data)) selectSearchMatch(-1)
                    return true
                }

                else -> {
                    if (!isKeyRelease(data)) {
                        searchBar.handleInput(data)
                    }
                    return true
                }
            }
        }
        if (keybindings.matches(data, "tui.altScreen.search")) {
            if (!isKeyRelease(data)) openSearch()
            return true
        }
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

            keybindings.matches(data, "tui.altScreen.lineUp") -> {
                if (!isKeyRelease(data)) scrollBy(-1)
                true
            }

            keybindings.matches(data, "tui.altScreen.lineDown") -> {
                if (!isKeyRelease(data)) scrollBy(1)
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
        refreshSearchMatches()
        val maxScrollTop = max(0, contentHeight - viewportHeight)
        scrollTop =
            if (followingEnd) {
                maxScrollTop
            } else {
                scrollTop.coerceIn(0, maxScrollTop)
            }
        followingEnd = followingEnd || scrollTop == maxScrollTop

        val visibleDocument: MutableList<String> =
            if (viewportHeight == 0) {
                mutableListOf<String>()
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
        if (searchBar?.active == true) {
            for (row in visibleDocument.indices) {
                visibleDocument[row] =
                    highlightSearchMatches(
                        line = visibleDocument[row],
                        documentRow = scrollTop + row,
                    )
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
            for (row in visibleDocument.indices) {
                visibleDocument[row] = visibleDocument[row] + " "
            }
        }
        return visibleDocument + dockLines
    }

    override fun bindRenderRequest(callback: () -> Unit) {
        requestRender = callback
        searchBar?.onInput = ::handleSearchInput
    }

    private fun openSearch() {
        val bar = searchBar ?: return
        bar.active = true
        bar.focused = true
        bar.query = ""
        bar.resultIndex = -1
        bar.resultCount = 0
        searchMatches = emptyList()
        selectedSearchMatch = -1
        requestRender?.invoke()
    }

    private fun closeSearch() {
        val bar = searchBar ?: return
        bar.active = false
        bar.focused = false
        bar.query = ""
        bar.resultIndex = -1
        bar.resultCount = 0
        searchMatches = emptyList()
        selectedSearchMatch = -1
        requestRender?.invoke()
    }

    private fun handleSearchInput(data: String) {
        val bar = searchBar ?: return
        val next =
            when {
                data == "\u007F" || data == "\b" -> bar.query.dropLast(1)
                data.startsWith("\u001B[200~") && data.endsWith("\u001B[201~") ->
                    bar.query + data.removePrefix("\u001B[200~").removeSuffix("\u001B[201~")
                data.length == 1 && data[0] >= ' ' -> bar.query + data
                else -> bar.query
            }
        if (next != bar.query) {
            bar.query = next
            refreshSearchMatches(resetSelection = true)
            requestRender?.invoke()
        }
    }

    private fun refreshSearchMatches(resetSelection: Boolean = false) {
        val bar = searchBar ?: return
        if (!bar.active || normalizeSearchQuery(bar.query).isEmpty()) {
            searchMatches = emptyList()
            selectedSearchMatch = -1
        } else {
            searchMatches = findSearchMatches(renderedDocumentLines, bar.query)
            if (resetSelection || selectedSearchMatch !in searchMatches.indices) {
                selectedSearchMatch = if (searchMatches.isEmpty()) -1 else 0
            }
        }
        bar.resultCount = searchMatches.size
        bar.resultIndex = selectedSearchMatch
        if (selectedSearchMatch >= 0) {
            ensureSearchMatchVisible(searchMatches[selectedSearchMatch])
        }
    }

    private fun selectSearchMatch(direction: Int) {
        if (searchMatches.isEmpty()) {
            return
        }
        selectedSearchMatch =
            (selectedSearchMatch + direction).mod(searchMatches.size)
        searchBar?.resultIndex = selectedSearchMatch
        ensureSearchMatchVisible(searchMatches[selectedSearchMatch])
        requestRender?.invoke()
    }

    private fun ensureSearchMatchVisible(match: SearchMatch) {
        val next =
            when {
                match.row < scrollTop -> match.row
                match.row >= scrollTop + viewportHeight -> match.row - viewportHeight + 1
                else -> scrollTop
            }
        scrollTo(next)
    }

    private fun highlightSearchMatches(
        line: String,
        documentRow: Int,
    ): String {
        val matches = searchMatches.filter { it.row == documentRow }
        if (matches.isEmpty()) {
            return line
        }
        val plain = stripTerminalSequencesForSearch(line)
        val output = StringBuilder()
        var cursor = 0
        matches.forEach { match ->
            val start = match.startColumn.coerceIn(0, plain.length)
            val end = match.endColumn.coerceIn(start, plain.length)
            if (start > cursor) {
                output.append(sliceByColumn(line, cursor, start - cursor, strict = true))
            }
            val selected = searchMatches.indexOf(match) == selectedSearchMatch
            output.append(
                if (selected) {
                    searchCurrentMatchStyle(sliceByColumn(line, start, end - start, strict = true))
                } else {
                    searchMatchStyle(sliceByColumn(line, start, end - start, strict = true))
                },
            )
            cursor = end
        }
        if (cursor < visibleWidth(line)) {
            output.append(sliceByColumn(line, cursor, visibleWidth(line) - cursor, strict = true))
        }
        return output.toString()
    }

    private fun findSearchMatches(
        lines: List<String>,
        query: String,
    ): List<SearchMatch> {
        val normalized = normalizeSearchQuery(query).lowercase(Locale.ROOT)
        if (normalized.isEmpty()) {
            return emptyList()
        }
        return lines.flatMapIndexed { row, line ->
            val plain = stripTerminalSequencesForSearch(line)
            buildList {
                var offset = 0
                while (offset <= plain.length - normalized.length) {
                    val index = plain.lowercase(Locale.ROOT).indexOf(normalized, offset)
                    if (index < 0) {
                        break
                    }
                    add(SearchMatch(row, index, index + normalized.length))
                    offset = index + normalized.length
                }
            }
        }
    }

    private fun normalizeSearchQuery(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")

    private fun stripTerminalSequencesForSearch(value: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            val ansi = extractAnsiCode(value, index)
            if (ansi != null) {
                index += ansi.length
            } else {
                result.append(value[index])
                index++
            }
        }
        return result.toString()
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
