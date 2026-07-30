package works.earendil.pi.tui

import com.ibm.icu.text.BreakIterator
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class EditorCursor(
    val line: Int,
    val column: Int,
)

data class EditorTheme(
    val borderColor: (String) -> String = { it },
    val selectedItem: (String) -> String = { "\u001B[7m$it\u001B[27m" },
    val description: (String) -> String = { it },
)

data class EditorOptions(
    val paddingX: Int = 0,
    val autocompleteMaxVisible: Int = 5,
)

private data class EditorState(
    val lines: MutableList<String>,
    var cursorLine: Int,
    var cursorColumn: Int,
)

private data class EditorSnapshot(
    val lines: MutableList<String>,
    val cursorLine: Int,
    val cursorColumn: Int,
)

private data class VisualLine(
    val text: String,
    val logicalLine: Int,
    val start: Int,
    val end: Int,
    val hasCursor: Boolean,
    val cursorOffset: Int?,
)

class Editor(
    protected val tui: Tui,
    private val theme: EditorTheme = EditorTheme(),
    options: EditorOptions = EditorOptions(),
    private val keybindings: KeybindingsManager = getKeybindings(),
) : Component,
    Focusable {
    override var focused: Boolean = false
    var onSubmit: ((String) -> Unit)? = null
    var onChange: ((String) -> Unit)? = null
    var disableSubmit: Boolean = false
    var borderColor: (String) -> String = theme.borderColor
    var maskCharacter: String? = null

    private var state = EditorState(mutableListOf(""), 0, 0)
    private var paddingX = options.paddingX.coerceAtLeast(0)
    private var autocompleteMaxVisible = options.autocompleteMaxVisible.coerceIn(3, 20)
    private var autocompleteProvider: AutocompleteProvider? = null
    private var autocompleteSuggestions: AutocompleteSuggestions? = null
    private var autocompleteSelected = 0
    private var autocompleteForced = false
    private val autocompleteRequest = AtomicLong()
    private var pasteBuffer = ""
    private var inPaste = false
    private val history = mutableListOf<String>()
    private var historyIndex = -1
    private var historyDraft: EditorSnapshot? = null
    private val undo = UndoStack<EditorSnapshot>(::cloneSnapshot)
    private val killRing = KillRing()
    private var lastAction: String? = null
    private var lastRenderWidth = 80
    private var scrollOffset = 0

    fun getText(): String = state.lines.joinToString("\n")

    fun getExpandedText(): String = getText()

    fun getLines(): List<String> = state.lines.toList()

    fun getCursor(): EditorCursor = EditorCursor(state.cursorLine, state.cursorColumn)

    fun setText(text: String) {
        exitHistory()
        pushUndo()
        setTextInternal(text, atStart = false)
    }

    fun insertTextAtCursor(text: String) {
        if (text.isEmpty()) {
            return
        }
        pushUndo()
        insertText(text)
        changed(triggerAutocomplete = true)
    }

    fun addToHistory(text: String) {
        val value = text.trim()
        if (value.isEmpty() || history.firstOrNull() == value) {
            return
        }
        history.add(0, value)
        while (history.size > 100) {
            history.removeLast()
        }
    }

    fun setPaddingX(value: Int) {
        paddingX = value.coerceAtLeast(0)
        tui.requestRender()
    }

    fun getPaddingX(): Int = paddingX

    fun setAutocompleteMaxVisible(value: Int) {
        autocompleteMaxVisible = value.coerceIn(3, 20)
        tui.requestRender()
    }

    fun getAutocompleteMaxVisible(): Int = autocompleteMaxVisible

    fun setAutocompleteProvider(provider: AutocompleteProvider?) {
        autocompleteProvider = provider
        cancelAutocomplete()
    }

    fun isShowingAutocomplete(): Boolean = autocompleteSuggestions != null

    override fun render(width: Int): List<String> {
        val safePadding = paddingX.coerceAtMost(max(0, floor((width - 1) / 2.0).toInt()))
        val contentWidth = max(1, width - safePadding * 2)
        val layoutWidth = max(1, contentWidth - if (safePadding == 0) 1 else 0)
        lastRenderWidth = layoutWidth
        val visualLines = buildVisualLines(layoutWidth)
        val cursorIndex = visualLines.indexOfFirst(VisualLine::hasCursor).coerceAtLeast(0)
        val maxVisible = max(5, floor(tui.terminal.rows * 0.3).toInt())
        if (cursorIndex < scrollOffset) {
            scrollOffset = cursorIndex
        } else if (cursorIndex >= scrollOffset + maxVisible) {
            scrollOffset = cursorIndex - maxVisible + 1
        }
        scrollOffset = scrollOffset.coerceIn(0, max(0, visualLines.size - maxVisible))
        val visible = visualLines.drop(scrollOffset).take(maxVisible)
        val left = " ".repeat(safePadding)
        val right = left
        val horizontal = borderColor("─")
        val result = mutableListOf<String>()
        result += horizontal.repeat(width)
        visible.forEach { visual ->
            var rendered = visual.text
            var renderedWidth = visibleWidth(rendered)
            if (visual.hasCursor && visual.cursorOffset != null) {
                val before = rendered.take(visual.cursorOffset)
                val after = rendered.drop(visual.cursorOffset)
                val marker = if (focused) CURSOR_MARKER else ""
                if (after.isEmpty()) {
                    rendered = before + marker + "\u001B[7m \u001B[27m"
                    renderedWidth++
                } else {
                    val end = nextGraphemeBoundary(after, 0)
                    rendered =
                        before +
                            marker +
                            "\u001B[7m" +
                            after.take(end) +
                            "\u001B[27m" +
                            after.drop(end)
                }
            }
            val pad = " ".repeat(max(0, contentWidth - renderedWidth))
            result += left + rendered + pad + right
        }
        result += horizontal.repeat(width)
        autocompleteSuggestions?.let { suggestions ->
            val start =
                autocompleteSelected
                    .minus(autocompleteMaxVisible / 2)
                    .coerceIn(0, max(0, suggestions.items.size - autocompleteMaxVisible))
            suggestions.items
                .drop(start)
                .take(autocompleteMaxVisible)
                .forEachIndexed { index, item ->
                    val absolute = start + index
                    val description = item.description?.let { "  ${theme.description(it)}" }.orEmpty()
                    val label = item.label + description
                    val selected =
                        if (absolute == autocompleteSelected) {
                            theme.selectedItem(label)
                        } else {
                            label
                        }
                    result += left + truncateToWidth(selected, contentWidth, pad = true) + right
                }
        }
        return result
    }

    override fun handleInput(data: String) {
        if (handlePasteInput(data)) {
            return
        }
        if (handleAutocompleteInput(data)) {
            return
        }
        when {
            keybindings.matches(data, "tui.editor.undo") -> undo()
            keybindings.matches(data, "tui.input.tab") -> requestAutocomplete(force = true)
            keybindings.matches(data, "tui.editor.deleteToLineEnd") -> deleteToLineEnd()
            keybindings.matches(data, "tui.editor.deleteToLineStart") -> deleteToLineStart()
            keybindings.matches(data, "tui.editor.deleteWordBackward") -> deleteWordBackward()
            keybindings.matches(data, "tui.editor.deleteWordForward") -> deleteWordForward()
            keybindings.matches(data, "tui.editor.deleteCharBackward") || matchesKey(data, "shift+backspace") ->
                backspace()

            keybindings.matches(data, "tui.editor.deleteCharForward") || matchesKey(data, "shift+delete") ->
                deleteForward()

            keybindings.matches(data, "tui.editor.yank") -> yank()
            keybindings.matches(data, "tui.editor.yankPop") -> yankPop()
            keybindings.matches(data, "tui.editor.cursorLeft") -> moveLeft()
            keybindings.matches(data, "tui.editor.cursorRight") -> moveRight()
            keybindings.matches(data, "tui.editor.cursorUp") -> moveVertical(-1)
            keybindings.matches(data, "tui.editor.cursorDown") -> moveVertical(1)
            keybindings.matches(data, "tui.editor.cursorWordLeft") -> moveWordLeft()
            keybindings.matches(data, "tui.editor.cursorWordRight") -> moveWordRight()
            keybindings.matches(data, "tui.editor.cursorLineStart") -> {
                state.cursorColumn = 0
                lastAction = null
            }

            keybindings.matches(data, "tui.editor.cursorLineEnd") -> {
                state.cursorColumn = currentLine().length
                lastAction = null
            }

            keybindings.matches(data, "tui.input.newLine") -> insertNewline()
            keybindings.matches(data, "tui.input.submit") -> submitOrNewline()
            else -> printableInput(data)?.let(::typeText)
        }
        tui.requestRender()
    }

    private fun handlePasteInput(data: String): Boolean {
        var remaining = data
        if (PASTE_START in remaining) {
            inPaste = true
            pasteBuffer = ""
            remaining = remaining.substringAfter(PASTE_START)
        }
        if (!inPaste) {
            return false
        }
        pasteBuffer += remaining
        if (PASTE_END !in pasteBuffer) {
            return true
        }
        val content = pasteBuffer.substringBefore(PASTE_END)
        val after = pasteBuffer.substringAfter(PASTE_END)
        pasteBuffer = ""
        inPaste = false
        if (content.isNotEmpty()) {
            pushUndo()
            insertText(content)
            changed(triggerAutocomplete = false)
        }
        if (after.isNotEmpty()) {
            handleInput(after)
        }
        return true
    }

    private fun handleAutocompleteInput(data: String): Boolean {
        val suggestions = autocompleteSuggestions ?: return false
        when {
            keybindings.matches(data, "tui.select.cancel") -> cancelAutocomplete()
            keybindings.matches(data, "tui.select.up") -> {
                autocompleteSelected =
                    (autocompleteSelected - 1 + suggestions.items.size) % suggestions.items.size
            }

            keybindings.matches(data, "tui.select.down") -> {
                autocompleteSelected = (autocompleteSelected + 1) % suggestions.items.size
            }

            keybindings.matches(data, "tui.input.tab") -> applySelectedCompletion(submitSlash = false)
            keybindings.matches(data, "tui.select.confirm") -> {
                val selected = suggestions.items.getOrNull(autocompleteSelected)
                val submit =
                    suggestions.prefix.startsWith("/") ||
                        (
                            selected != null &&
                                currentLine().startsWith("/") &&
                                ' ' in currentLine() &&
                                suggestions.prefix == selected.value
                        )
                applySelectedCompletion(submitSlash = submit)
            }

            else -> return false
        }
        tui.requestRender()
        return true
    }

    private fun applySelectedCompletion(submitSlash: Boolean) {
        val provider = autocompleteProvider ?: return
        val suggestions = autocompleteSuggestions ?: return
        val selected = suggestions.items.getOrNull(autocompleteSelected) ?: return
        pushUndo()
        val result =
            provider.applyCompletion(
                state.lines,
                state.cursorLine,
                state.cursorColumn,
                selected,
                suggestions.prefix,
            )
        state =
            EditorState(
                result.lines.toMutableList(),
                result.cursorLine.coerceIn(0, max(0, result.lines.lastIndex)),
                result.cursorColumn,
            )
        state.cursorColumn = state.cursorColumn.coerceIn(0, currentLine().length)
        cancelAutocomplete()
        changed(triggerAutocomplete = false)
        if (submitSlash && !disableSubmit) {
            onSubmit?.invoke(getExpandedText())
        }
    }

    private fun requestAutocomplete(force: Boolean) {
        val provider = autocompleteProvider ?: return
        val id = autocompleteRequest.incrementAndGet()
        autocompleteForced = force
        provider
            .getSuggestions(
                state.lines.toList(),
                state.cursorLine,
                state.cursorColumn,
                AutocompleteRequest(force),
            ).whenComplete { suggestions, _ ->
                if (autocompleteRequest.get() != id) {
                    return@whenComplete
                }
                autocompleteSuggestions =
                    suggestions?.takeIf { result -> result.items.isNotEmpty() }
                autocompleteSelected = 0
                tui.requestRender()
            }
    }

    private fun cancelAutocomplete() {
        autocompleteRequest.incrementAndGet()
        autocompleteSuggestions = null
        autocompleteSelected = 0
        autocompleteForced = false
    }

    private fun submitOrNewline() {
        if (state.cursorColumn > 0 && currentLine().getOrNull(state.cursorColumn - 1) == '\\') {
            pushUndo()
            val line = currentLine()
            state.lines[state.cursorLine] =
                line.removeRange(state.cursorColumn - 1, state.cursorColumn)
            state.cursorColumn--
            insertNewline(pushSnapshot = false)
            return
        }
        if (!disableSubmit) {
            onSubmit?.invoke(getExpandedText())
        }
    }

    private fun typeText(value: String) {
        if (value.isEmpty()) {
            return
        }
        exitHistory()
        pushUndo()
        insertText(value)
        lastAction = if (value.all(Char::isLetterOrDigit)) "type-word" else null
        changed(triggerAutocomplete = true)
    }

    private fun insertText(value: String) {
        val parts = value.split('\n')
        val line = currentLine()
        val before = line.take(state.cursorColumn)
        val after = line.drop(state.cursorColumn)
        if (parts.size == 1) {
            state.lines[state.cursorLine] = before + value + after
            state.cursorColumn += value.length
            return
        }
        state.lines[state.cursorLine] = before + parts.first()
        var insertAt = state.cursorLine + 1
        parts.drop(1).dropLast(1).forEach { middle ->
            state.lines.add(insertAt++, middle)
        }
        state.lines.add(insertAt, parts.last() + after)
        state.cursorLine = insertAt
        state.cursorColumn = parts.last().length
    }

    private fun insertNewline(pushSnapshot: Boolean = true) {
        exitHistory()
        if (pushSnapshot) {
            pushUndo()
        }
        val line = currentLine()
        state.lines[state.cursorLine] = line.take(state.cursorColumn)
        state.lines.add(state.cursorLine + 1, line.drop(state.cursorColumn))
        state.cursorLine++
        state.cursorColumn = 0
        lastAction = null
        changed(triggerAutocomplete = false)
    }

    private fun backspace() {
        if (state.cursorColumn == 0) {
            if (state.cursorLine == 0) {
                return
            }
            pushUndo()
            val current = state.lines.removeAt(state.cursorLine)
            state.cursorLine--
            state.cursorColumn = state.lines[state.cursorLine].length
            state.lines[state.cursorLine] += current
        } else {
            pushUndo()
            val line = currentLine()
            val start = previousGraphemeBoundary(line, state.cursorColumn)
            state.lines[state.cursorLine] = line.removeRange(start, state.cursorColumn)
            state.cursorColumn = start
        }
        lastAction = null
        changed(triggerAutocomplete = true)
    }

    private fun deleteForward() {
        val line = currentLine()
        if (state.cursorColumn == line.length) {
            if (state.cursorLine == state.lines.lastIndex) {
                return
            }
            pushUndo()
            state.lines[state.cursorLine] += state.lines.removeAt(state.cursorLine + 1)
        } else {
            pushUndo()
            val end = nextGraphemeBoundary(line, state.cursorColumn)
            state.lines[state.cursorLine] = line.removeRange(state.cursorColumn, end)
        }
        lastAction = null
        changed(triggerAutocomplete = true)
    }

    private fun deleteWordBackward() {
        if (state.cursorColumn == 0) {
            backspace()
            return
        }
        val line = currentLine()
        val start = findWordBackward(line, state.cursorColumn)
        kill(
            line.substring(start, state.cursorColumn),
            prepend = true,
        )
        pushUndo()
        state.lines[state.cursorLine] = line.removeRange(start, state.cursorColumn)
        state.cursorColumn = start
        changed(triggerAutocomplete = true)
    }

    private fun deleteWordForward() {
        val line = currentLine()
        if (state.cursorColumn == line.length) {
            deleteForward()
            return
        }
        val end = findWordForward(line, state.cursorColumn)
        kill(line.substring(state.cursorColumn, end), prepend = false)
        pushUndo()
        state.lines[state.cursorLine] = line.removeRange(state.cursorColumn, end)
        changed(triggerAutocomplete = true)
    }

    private fun deleteToLineStart() {
        if (state.cursorColumn == 0) {
            backspace()
            return
        }
        val line = currentLine()
        val deleted = line.take(state.cursorColumn)
        kill(deleted, prepend = true)
        pushUndo()
        state.lines[state.cursorLine] = line.drop(state.cursorColumn)
        state.cursorColumn = 0
        changed(triggerAutocomplete = true)
    }

    private fun deleteToLineEnd() {
        val line = currentLine()
        if (state.cursorColumn == line.length) {
            deleteForward()
            return
        }
        val deleted = line.drop(state.cursorColumn)
        kill(deleted, prepend = false)
        pushUndo()
        state.lines[state.cursorLine] = line.take(state.cursorColumn)
        changed(triggerAutocomplete = true)
    }

    private fun kill(
        value: String,
        prepend: Boolean,
    ) {
        killRing.push(value, prepend, accumulate = lastAction == "kill")
        lastAction = "kill"
    }

    private fun yank() {
        val value = killRing.peek() ?: return
        pushUndo()
        insertText(value)
        lastAction = "yank"
        changed(triggerAutocomplete = false)
    }

    private fun yankPop() {
        if (lastAction != "yank" || killRing.length < 2) {
            return
        }
        undo()
        killRing.rotate()
        yank()
    }

    private fun moveLeft() {
        cancelAutocomplete()
        lastAction = null
        if (state.cursorColumn > 0) {
            state.cursorColumn = previousGraphemeBoundary(currentLine(), state.cursorColumn)
        } else if (state.cursorLine > 0) {
            state.cursorLine--
            state.cursorColumn = currentLine().length
        }
    }

    private fun moveRight() {
        cancelAutocomplete()
        lastAction = null
        if (state.cursorColumn < currentLine().length) {
            state.cursorColumn = nextGraphemeBoundary(currentLine(), state.cursorColumn)
        } else if (state.cursorLine < state.lines.lastIndex) {
            state.cursorLine++
            state.cursorColumn = 0
        }
    }

    private fun moveWordLeft() {
        cancelAutocomplete()
        if (state.cursorColumn > 0) {
            state.cursorColumn = findWordBackward(currentLine(), state.cursorColumn)
        } else {
            moveLeft()
        }
        lastAction = null
    }

    private fun moveWordRight() {
        cancelAutocomplete()
        if (state.cursorColumn < currentLine().length) {
            state.cursorColumn = findWordForward(currentLine(), state.cursorColumn)
        } else {
            moveRight()
        }
        lastAction = null
    }

    private fun moveVertical(delta: Int) {
        cancelAutocomplete()
        val target = state.cursorLine + delta
        if (target in state.lines.indices) {
            state.cursorLine = target
            state.cursorColumn = min(state.cursorColumn, currentLine().length)
            lastAction = null
            return
        }
        if (historyIndex >= 0) {
            navigateHistory(delta)
            return
        }
        if (delta < 0 && state.cursorLine == 0 && state.cursorColumn > 0) {
            state.cursorColumn = 0
            return
        }
        if (delta > 0 && state.cursorLine == state.lines.lastIndex && state.cursorColumn < currentLine().length) {
            state.cursorColumn = currentLine().length
            return
        }
        navigateHistory(delta)
    }

    private fun navigateHistory(delta: Int) {
        if (history.isEmpty()) {
            return
        }
        val next = historyIndex - delta
        if (next !in -1 until history.size) {
            return
        }
        if (historyIndex == -1 && next >= 0) {
            historyDraft = snapshot()
        }
        historyIndex = next
        if (next == -1) {
            historyDraft?.let(::restore)
            historyDraft = null
        } else {
            setTextInternal(history[next], atStart = delta < 0)
        }
    }

    private fun exitHistory() {
        historyIndex = -1
        historyDraft = null
    }

    private fun undo() {
        val snapshot = undo.pop() ?: return
        restore(snapshot)
        lastAction = null
        cancelAutocomplete()
        changed(triggerAutocomplete = false)
    }

    private fun pushUndo() {
        undo.push(snapshot())
    }

    private fun snapshot(): EditorSnapshot =
        EditorSnapshot(
            state.lines.toMutableList(),
            state.cursorLine,
            state.cursorColumn,
        )

    private fun restore(snapshot: EditorSnapshot) {
        state =
            EditorState(
                snapshot.lines.toMutableList(),
                snapshot.cursorLine,
                snapshot.cursorColumn,
            )
    }

    private fun setTextInternal(
        text: String,
        atStart: Boolean,
    ) {
        val lines = text.split('\n').toMutableList().ifEmpty { mutableListOf("") }
        state =
            if (atStart) {
                EditorState(lines, 0, 0)
            } else {
                EditorState(lines, lines.lastIndex, lines.last().length)
            }
        scrollOffset = 0
        changed(triggerAutocomplete = false)
    }

    private fun changed(triggerAutocomplete: Boolean) {
        onChange?.invoke(getText())
        if (triggerAutocomplete) {
            val line = currentLine().take(state.cursorColumn)
            val shouldTrigger =
                line.startsWith("/") ||
                    autocompleteProvider
                        ?.triggerCharacters
                        .orEmpty()
                        .any { trigger ->
                            line.substringAfterLast(' ').startsWith(trigger)
                        }
            if (shouldTrigger) {
                requestAutocomplete(force = false)
            } else if (!autocompleteForced) {
                cancelAutocomplete()
            }
        }
        tui.requestRender()
    }

    private fun currentLine(): String = state.lines[state.cursorLine]

    private fun buildVisualLines(width: Int): List<VisualLine> =
        state.lines.flatMapIndexed { lineIndex, line ->
            wrapLine(line, width).map { chunk ->
                val cursor = lineIndex == state.cursorLine && state.cursorColumn in chunk.start..chunk.end
                val source = line.substring(chunk.start, chunk.end)
                val masked = maskCharacter?.let { mask -> mask.repeat(graphemeCount(source)) } ?: source
                val cursorOffset =
                    if (cursor) {
                        maskCharacter?.let {
                            graphemeCount(line.substring(chunk.start, state.cursorColumn))
                        } ?: (state.cursorColumn - chunk.start)
                    } else {
                        null
                    }
                VisualLine(
                    text = masked,
                    logicalLine = lineIndex,
                    start = chunk.start,
                    end = chunk.end,
                    hasCursor = cursor,
                    cursorOffset = cursorOffset,
                )
            }
        }.ifEmpty {
            listOf(VisualLine("", 0, 0, 0, hasCursor = true, cursorOffset = 0))
        }

    private fun wrapLine(
        line: String,
        width: Int,
    ): List<TextChunk> {
        if (line.isEmpty()) {
            return listOf(TextChunk(0, 0))
        }
        val chunks = mutableListOf<TextChunk>()
        var start = 0
        var index = 0
        var currentWidth = 0
        while (index < line.length) {
            val end = nextGraphemeBoundary(line, index)
            val grapheme = line.substring(index, end)
            val graphemeWidth = visibleWidth(grapheme)
            if (currentWidth > 0 && currentWidth + graphemeWidth > width) {
                chunks += TextChunk(start, index)
                start = index
                currentWidth = 0
            }
            currentWidth += graphemeWidth
            index = end
        }
        chunks += TextChunk(start, line.length)
        return chunks
    }
}

private data class TextChunk(
    val start: Int,
    val end: Int,
)

private val graphemeIterator =
    ThreadLocal.withInitial {
        BreakIterator.getCharacterInstance(Locale.ROOT)
    }

private fun previousGraphemeBoundary(
    text: String,
    offset: Int,
): Int {
    if (offset <= 0) {
        return 0
    }
    val iterator = graphemeIterator.get()
    iterator.setText(text)
    return iterator.preceding(offset).takeIf { it != BreakIterator.DONE } ?: 0
}

private fun nextGraphemeBoundary(
    text: String,
    offset: Int,
): Int {
    if (offset >= text.length) {
        return text.length
    }
    val iterator = graphemeIterator.get()
    iterator.setText(text)
    return iterator.following(offset).takeIf { it != BreakIterator.DONE } ?: text.length
}

private fun graphemeCount(text: String): Int {
    if (text.isEmpty()) {
        return 0
    }
    val iterator = graphemeIterator.get()
    iterator.setText(text)
    var count = 0
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE) {
        val next = iterator.next()
        if (next == BreakIterator.DONE) {
            break
        }
        count++
        boundary = next
    }
    return count
}

private fun cloneSnapshot(snapshot: EditorSnapshot): EditorSnapshot =
    snapshot.copy(lines = snapshot.lines.toMutableList())

private fun printableInput(data: String): String? {
    decodePrintableKey(data)?.let { return it }
    if (data.isEmpty() || '\u001B' in data) {
        return null
    }
    return data.takeIf { value ->
        value.codePoints().allMatch { codepoint ->
            codepoint >= 32 && codepoint != 127
        }
    }
}

private const val PASTE_START = "\u001B[200~"
private const val PASTE_END = "\u001B[201~"
