package works.earendil.pi.tui

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UCharacterCategory
import com.ibm.icu.lang.UProperty
import com.ibm.icu.lang.UCharacter.EastAsianWidth
import com.ibm.icu.text.BreakIterator
import java.util.LinkedHashMap
import java.util.Locale

private const val ESC = '\u001B'
private const val BEL = '\u0007'
private const val TAB_WIDTH = 3
private const val RESET = "\u001B[0m"
private val graphemeIterator =
    ThreadLocal.withInitial {
        BreakIterator.getCharacterInstance(Locale.ROOT)
    }
private val widthCache =
    object : LinkedHashMap<String, Int>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > 512
    }

data class AnsiCode(
    val code: String,
    val length: Int,
)

data class ColumnSlice(
    val text: String,
    val width: Int,
)

data class LineSegments(
    val before: String,
    val beforeWidth: Int,
    val after: String,
    val afterWidth: Int,
)

fun extractAnsiCode(
    value: String,
    position: Int,
): AnsiCode? {
    if (position !in value.indices || value[position] != ESC) {
        return null
    }
    return when (value.getOrNull(position + 1)) {
        '[' -> {
            var index = position + 2
            while (index < value.length && value[index].code !in 0x40..0x7e) {
                index++
            }
            if (index < value.length) {
                AnsiCode(value.substring(position, index + 1), index + 1 - position)
            } else {
                null
            }
        }

        ']', '_' -> {
            var index = position + 2
            while (index < value.length) {
                when {
                    value[index] == BEL ->
                        return AnsiCode(value.substring(position, index + 1), index + 1 - position)

                    value[index] == ESC && value.getOrNull(index + 1) == '\\' ->
                        return AnsiCode(value.substring(position, index + 2), index + 2 - position)
                }
                index++
            }
            null
        }

        else -> null
    }
}

fun visibleWidth(value: String): Int {
    if (value.isEmpty()) {
        return 0
    }
    if (value.all { it.code in 0x20..0x7e }) {
        return value.length
    }
    synchronized(widthCache) {
        widthCache[value]?.let { return it }
    }

    val clean = StringBuilder()
    var index = 0
    while (index < value.length) {
        val ansi = extractAnsiCode(value, index)
        if (ansi != null) {
            index += ansi.length
            continue
        }
        if (value[index] == '\t') {
            clean.append(" ".repeat(TAB_WIDTH))
        } else {
            clean.append(value[index])
        }
        index++
    }
    val width = graphemes(clean.toString()).sumOf(::graphemeWidth)
    synchronized(widthCache) {
        widthCache[value] = width
    }
    return width
}

fun normalizeTerminalOutput(value: String): String {
    val normalized =
        value
            .replace("\u0E33", "\u0E4D\u0E32")
            .replace("\u0EB3", "\u0ECD\u0EB2")
    if ('\t' !in normalized) {
        return normalized
    }
    val result = StringBuilder()
    var index = 0
    while (index < normalized.length) {
        val ansi = extractAnsiCode(normalized, index)
        if (ansi != null) {
            result.append(ansi.code)
            index += ansi.length
        } else {
            result.append(if (normalized[index] == '\t') " ".repeat(TAB_WIDTH) else normalized[index])
            index++
        }
    }
    return result.toString()
}

fun wrapTextWithAnsi(
    text: String,
    width: Int,
): List<String> {
    if (text.isEmpty()) {
        return listOf("")
    }
    if (width <= 0) {
        return listOf("")
    }

    val result = mutableListOf<String>()
    val tracker = AnsiCodeTracker()
    text.split(Regex("\\r\\n|\\r|\\n")).forEach { inputLine ->
        val prefix = if (result.isEmpty()) "" else tracker.activeCodes()
        result += wrapSingleLine(prefix + inputLine, width)
        updateTracker(inputLine, tracker)
    }
    return result.ifEmpty { listOf("") }
}

fun truncateToWidth(
    text: String,
    maxWidth: Int,
    ellipsis: String = "...",
    pad: Boolean = false,
): String {
    if (maxWidth <= 0) {
        return ""
    }
    if (text.isEmpty()) {
        return if (pad) " ".repeat(maxWidth) else ""
    }

    val textWidth = visibleWidth(text)
    if (textWidth <= maxWidth) {
        return if (pad) text + " ".repeat(maxWidth - textWidth) else text
    }

    val ellipsisWidth = visibleWidth(ellipsis)
    if (ellipsisWidth >= maxWidth) {
        val clipped = truncateFragmentToWidth(ellipsis, maxWidth)
        if (clipped.width == 0) {
            return if (pad) " ".repeat(maxWidth) else ""
        }
        return finalizeTruncatedResult("", 0, clipped.text, clipped.width, maxWidth, pad)
    }

    val targetWidth = maxWidth - ellipsisWidth
    val prefix = StringBuilder()
    var prefixWidth = 0
    var pendingAnsi = ""
    var contiguous = true
    var index = 0
    loop@ while (index < text.length) {
        val ansi = extractAnsiCode(text, index)
        if (ansi != null) {
            pendingAnsi += ansi.code
            index += ansi.length
            continue
        }
        if (text[index] == '\t') {
            if (contiguous && prefixWidth + TAB_WIDTH <= targetWidth) {
                prefix.append(pendingAnsi).append('\t')
                pendingAnsi = ""
                prefixWidth += TAB_WIDTH
            } else {
                contiguous = false
                pendingAnsi = ""
            }
            index++
            continue
        }

        var end = index
        while (end < text.length && text[end] != '\t' && extractAnsiCode(text, end) == null) {
            end++
        }
        for (segment in graphemes(text.substring(index, end))) {
            val segmentWidth = graphemeWidth(segment)
            if (contiguous && prefixWidth + segmentWidth <= targetWidth) {
                prefix.append(pendingAnsi).append(segment)
                pendingAnsi = ""
                prefixWidth += segmentWidth
            } else {
                contiguous = false
                pendingAnsi = ""
            }
        }
        index = end
        if (!contiguous && prefixWidth >= targetWidth) {
            break@loop
        }
    }

    return finalizeTruncatedResult(
        prefix.toString(),
        prefixWidth,
        ellipsis,
        ellipsisWidth,
        maxWidth,
        pad,
    )
}

fun sliceByColumn(
    line: String,
    startColumn: Int,
    length: Int,
    strict: Boolean = false,
): String = sliceWithWidth(line, startColumn, length, strict).text

fun sliceWithWidth(
    line: String,
    startColumn: Int,
    length: Int,
    strict: Boolean = false,
): ColumnSlice {
    if (length <= 0) {
        return ColumnSlice("", 0)
    }
    val endColumn = startColumn + length
    val result = StringBuilder()
    var resultWidth = 0
    var currentColumn = 0
    var index = 0
    var pendingAnsi = ""

    while (index < line.length && currentColumn < endColumn) {
        val ansi = extractAnsiCode(line, index)
        if (ansi != null) {
            when {
                currentColumn in startColumn until endColumn -> result.append(ansi.code)
                currentColumn < startColumn -> pendingAnsi += ansi.code
            }
            index += ansi.length
            continue
        }

        var end = index
        while (end < line.length && extractAnsiCode(line, end) == null) {
            end++
        }
        for (segment in graphemes(line.substring(index, end))) {
            val segmentWidth = graphemeWidth(segment)
            val inRange = currentColumn in startColumn until endColumn
            val fits = !strict || currentColumn + segmentWidth <= endColumn
            if (inRange && fits) {
                result.append(pendingAnsi).append(segment)
                pendingAnsi = ""
                resultWidth += segmentWidth
            }
            currentColumn += segmentWidth
            if (currentColumn >= endColumn) {
                break
            }
        }
        index = end
    }
    return ColumnSlice(result.toString(), resultWidth)
}

fun extractSegments(
    line: String,
    beforeEnd: Int,
    afterStart: Int,
    afterLength: Int,
    strictAfter: Boolean = false,
): LineSegments {
    val before = sliceWithWidth(line, 0, beforeEnd, strict = true)
    val after = sliceWithWidth(line, afterStart, afterLength, strict = strictAfter)
    return LineSegments(before.text, before.width, after.text, after.width)
}

fun applyBackgroundToLine(
    line: String,
    width: Int,
    background: (String) -> String,
): String = background(line + " ".repeat((width - visibleWidth(line)).coerceAtLeast(0)))

fun isWhitespaceChar(value: String): Boolean = value.any(Char::isWhitespace)

fun isPunctuationChar(value: String): Boolean =
    value.any { character ->
        character in "(){}[]<>.,;:'\"!?+-=*/\\|&%^$#@~`"
    }

private fun wrapSingleLine(
    line: String,
    width: Int,
): List<String> {
    if (line.isEmpty() || visibleWidth(line) <= width) {
        return listOf(line)
    }
    val wrapped = mutableListOf<String>()
    val tracker = AnsiCodeTracker()
    var currentLine = ""
    var currentWidth = 0

    for (token in splitTokensWithAnsi(line)) {
        val tokenWidth = visibleWidth(token)
        val whitespace = token.isBlank()
        if (tokenWidth > width && !whitespace) {
            if (currentLine.isNotEmpty()) {
                wrapped += currentLine.trimEnd() + tracker.lineEndReset()
                currentLine = ""
                currentWidth = 0
            }
            val broken = breakLongWord(token, width, tracker)
            wrapped += broken.dropLast(1)
            currentLine = broken.last()
            currentWidth = visibleWidth(currentLine)
            continue
        }

        if (currentWidth + tokenWidth > width && currentWidth > 0) {
            wrapped += currentLine.trimEnd() + tracker.lineEndReset()
            if (whitespace) {
                currentLine = tracker.activeCodes()
                currentWidth = 0
            } else {
                currentLine = tracker.activeCodes() + token
                currentWidth = tokenWidth
            }
        } else {
            currentLine += token
            currentWidth += tokenWidth
        }
        updateTracker(token, tracker)
    }
    if (currentLine.isNotEmpty()) {
        wrapped += currentLine
    }
    return wrapped.ifEmpty { listOf("") }.map(String::trimEnd)
}

private fun splitTokensWithAnsi(text: String): List<String> {
    val tokens = mutableListOf<String>()
    var current = ""
    var pendingAnsi = ""
    var currentKind: Boolean? = null
    var index = 0

    fun flush() {
        if (current.isNotEmpty()) {
            tokens += current
            current = ""
            currentKind = null
        }
    }

    while (index < text.length) {
        val ansi = extractAnsiCode(text, index)
        if (ansi != null) {
            pendingAnsi += ansi.code
            index += ansi.length
            continue
        }
        var end = index
        while (end < text.length && extractAnsiCode(text, end) == null) {
            end++
        }
        for (segment in graphemes(text.substring(index, end))) {
            val isSpace = segment == " "
            if (!isSpace && segment.codePoints().anyMatch(::isCjkBreakCodePoint)) {
                flush()
                tokens += pendingAnsi + segment
                pendingAnsi = ""
                continue
            }
            if (current.isNotEmpty() && currentKind != isSpace) {
                flush()
            }
            current += pendingAnsi + segment
            pendingAnsi = ""
            currentKind = isSpace
        }
        index = end
    }
    if (pendingAnsi.isNotEmpty()) {
        current =
            when {
                current.isNotEmpty() -> current + pendingAnsi
                tokens.isNotEmpty() -> {
                    tokens[tokens.lastIndex] += pendingAnsi
                    ""
                }

                else -> pendingAnsi
            }
    }
    flush()
    return tokens
}

private fun breakLongWord(
    word: String,
    width: Int,
    tracker: AnsiCodeTracker,
): List<String> {
    val lines = mutableListOf<String>()
    var currentLine = tracker.activeCodes()
    var currentWidth = 0
    var index = 0
    while (index < word.length) {
        val ansi = extractAnsiCode(word, index)
        if (ansi != null) {
            currentLine += ansi.code
            tracker.process(ansi.code)
            index += ansi.length
            continue
        }
        var end = index
        while (end < word.length && extractAnsiCode(word, end) == null) {
            end++
        }
        for (segment in graphemes(word.substring(index, end))) {
            val segmentWidth = graphemeWidth(segment)
            if (currentWidth + segmentWidth > width) {
                lines += currentLine + tracker.lineEndReset()
                currentLine = tracker.activeCodes()
                currentWidth = 0
            }
            currentLine += segment
            currentWidth += segmentWidth
        }
        index = end
    }
    if (currentLine.isNotEmpty()) {
        lines += currentLine
    }
    return lines.ifEmpty { listOf("") }
}

private fun truncateFragmentToWidth(
    text: String,
    maxWidth: Int,
): ColumnSlice {
    if (maxWidth <= 0 || text.isEmpty()) {
        return ColumnSlice("", 0)
    }
    val result = StringBuilder()
    var width = 0
    var index = 0
    var pendingAnsi = ""
    while (index < text.length) {
        val ansi = extractAnsiCode(text, index)
        if (ansi != null) {
            pendingAnsi += ansi.code
            index += ansi.length
            continue
        }
        var end = index
        while (end < text.length && extractAnsiCode(text, end) == null) {
            end++
        }
        for (segment in graphemes(text.substring(index, end))) {
            val segmentWidth = graphemeWidth(segment)
            if (width + segmentWidth > maxWidth) {
                return ColumnSlice(result.toString(), width)
            }
            result.append(pendingAnsi).append(segment)
            pendingAnsi = ""
            width += segmentWidth
        }
        index = end
    }
    return ColumnSlice(result.toString(), width)
}

private fun finalizeTruncatedResult(
    prefix: String,
    prefixWidth: Int,
    ellipsis: String,
    ellipsisWidth: Int,
    maxWidth: Int,
    pad: Boolean,
): String {
    val result =
        if (ellipsis.isEmpty()) {
            prefix + RESET
        } else {
            prefix + RESET + ellipsis + RESET
        }
    val visibleWidth = prefixWidth + ellipsisWidth
    return if (pad) result + " ".repeat((maxWidth - visibleWidth).coerceAtLeast(0)) else result
}

private fun graphemes(value: String): List<String> {
    if (value.isEmpty()) {
        return emptyList()
    }
    val iterator = graphemeIterator.get()
    iterator.setText(value)
    val result = mutableListOf<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result += value.substring(start, end)
        start = end
        end = iterator.next()
    }
    return result
}

private fun graphemeWidth(segment: String): Int {
    if (segment == "\t") {
        return TAB_WIDTH
    }
    val codePoints = segment.codePoints().toArray()
    if (codePoints.isEmpty()) {
        return 0
    }
    if (codePoints.all(::isTerminalSpacingMark)) {
        return codePoints.size
    }
    if (codePoints.all(::isZeroWidth)) {
        return 0
    }
    if (
        codePoints.any { it == 0xFE0F || it == 0x200D } ||
        codePoints.first() in 0x1F1E6..0x1F1FF ||
        codePoints.any { UCharacter.hasBinaryProperty(it, UProperty.EMOJI_PRESENTATION) }
    ) {
        return 2
    }

    val base = codePoints.firstOrNull { !isZeroWidth(it) } ?: return 0
    var width =
        when (UCharacter.getIntPropertyValue(base, UProperty.EAST_ASIAN_WIDTH)) {
            EastAsianWidth.FULLWIDTH, EastAsianWidth.WIDE -> 2
            else -> 1
        }
    var followsMark = false
    codePoints.drop(1).forEach { codePoint ->
        when {
            isTerminalSpacingMark(codePoint) -> {
                width++
                followsMark = false
            }

            isMark(codePoint) -> followsMark = true
            !isNonPrinting(codePoint) -> {
                val eastAsianWidth = UCharacter.getIntPropertyValue(codePoint, UProperty.EAST_ASIAN_WIDTH)
                if (followsMark) {
                    width += if (eastAsianWidth == EastAsianWidth.FULLWIDTH || eastAsianWidth == EastAsianWidth.WIDE) 2 else 1
                } else if (eastAsianWidth == EastAsianWidth.FULLWIDTH || eastAsianWidth == EastAsianWidth.WIDE) {
                    width += 2
                } else if (codePoint == 0x0E33 || codePoint == 0x0EB3) {
                    width++
                }
                followsMark = false
            }
        }
    }
    return width
}

private fun isTerminalSpacingMark(codePoint: Int): Boolean =
    (
        UCharacter.getType(codePoint) == UCharacterCategory.COMBINING_SPACING_MARK.toInt() &&
            codePoint !in setOf(0x1734, 0x302E, 0x302F)
    ) ||
        codePoint == 0x065F ||
        codePoint == 0x0F7F ||
        codePoint == 0x102B ||
        codePoint == 0x102C ||
        codePoint == 0x1031 ||
        codePoint in 0x1033..0x1035 ||
        codePoint == 0x1038 ||
        codePoint in 0x103A..0x103E

private fun isMark(codePoint: Int): Boolean =
    when (UCharacter.getType(codePoint)) {
        UCharacterCategory.NON_SPACING_MARK.toInt(),
        UCharacterCategory.COMBINING_SPACING_MARK.toInt(),
        UCharacterCategory.ENCLOSING_MARK.toInt(),
        -> true

        else -> false
    }

private fun isNonPrinting(codePoint: Int): Boolean =
    isZeroWidth(codePoint) ||
        UCharacter.getType(codePoint) == UCharacterCategory.FORMAT.toInt()

private fun isZeroWidth(codePoint: Int): Boolean {
    if (UCharacter.hasBinaryProperty(codePoint, UProperty.DEFAULT_IGNORABLE_CODE_POINT)) {
        return true
    }
    return when (UCharacter.getType(codePoint)) {
        UCharacterCategory.CONTROL.toInt(),
        UCharacterCategory.FORMAT.toInt(),
        UCharacterCategory.NON_SPACING_MARK.toInt(),
        UCharacterCategory.COMBINING_SPACING_MARK.toInt(),
        UCharacterCategory.ENCLOSING_MARK.toInt(),
        UCharacterCategory.SURROGATE.toInt(),
        -> true

        else -> false
    }
}

private fun isCjkBreakCodePoint(codePoint: Int): Boolean =
    codePoint in 0x3040..0x30FF ||
        codePoint in 0x3100..0x312F ||
        codePoint in 0x31A0..0x31BF ||
        codePoint in 0x3400..0x4DBF ||
        codePoint in 0x4E00..0x9FFF ||
        codePoint in 0xAC00..0xD7AF ||
        codePoint in 0xF900..0xFAFF ||
        codePoint in 0x20000..0x3134F

private fun updateTracker(
    text: String,
    tracker: AnsiCodeTracker,
) {
    var index = 0
    while (index < text.length) {
        val ansi = extractAnsiCode(text, index)
        if (ansi != null) {
            tracker.process(ansi.code)
            index += ansi.length
        } else {
            index++
        }
    }
}

private class AnsiCodeTracker {
    private val attributes = linkedSetOf<Int>()
    private var foreground: String? = null
    private var background: String? = null
    private var hyperlink: Hyperlink? = null

    fun process(code: String) {
        parseHyperlink(code)?.let {
            hyperlink = it.takeIf(Hyperlink::isOpen)
            return
        }
        if (!code.endsWith('m')) {
            return
        }
        val parameters = code.removePrefix("\u001B[").removeSuffix("m")
        if (parameters.isEmpty() || parameters == "0") {
            reset()
            return
        }
        val parts = parameters.split(';')
        var index = 0
        while (index < parts.size) {
            val value = parts[index].toIntOrNull()
            if (value == 38 || value == 48) {
                val count =
                    when (parts.getOrNull(index + 1)) {
                        "5" -> 3
                        "2" -> 5
                        else -> 1
                    }
                val color = parts.drop(index).take(count).joinToString(";")
                if (value == 38) foreground = color else background = color
                index += count
                continue
            }
            when (value) {
                0 -> reset()
                in setOf(1, 2, 3, 4, 5, 7, 8, 9) -> attributes += requireNotNull(value)
                21 -> attributes -= 1
                22 -> {
                    attributes -= 1
                    attributes -= 2
                }

                23 -> attributes -= 3
                24 -> attributes -= 4
                25 -> attributes -= 5
                27 -> attributes -= 7
                28 -> attributes -= 8
                29 -> attributes -= 9
                39 -> foreground = null
                49 -> background = null
                in 30..37, in 90..97 -> foreground = value.toString()
                in 40..47, in 100..107 -> background = value.toString()
            }
            index++
        }
    }

    fun activeCodes(): String {
        val codes = attributes.map(Int::toString).toMutableList()
        foreground?.let(codes::add)
        background?.let(codes::add)
        val sgr = if (codes.isEmpty()) "" else "\u001B[${codes.joinToString(";")}m"
        return sgr + (hyperlink?.openCode().orEmpty())
    }

    fun lineEndReset(): String =
        buildString {
            if (4 in attributes) append("\u001B[24m")
            hyperlink?.let { append(it.closeCode()) }
        }

    private fun reset() {
        attributes.clear()
        foreground = null
        background = null
    }
}

private data class Hyperlink(
    val parameters: String,
    val url: String,
    val terminator: String,
) {
    val isOpen: Boolean
        get() = url.isNotEmpty()

    fun openCode(): String = "\u001B]8;$parameters;$url$terminator"

    fun closeCode(): String = "\u001B]8;;$terminator"
}

private fun parseHyperlink(code: String): Hyperlink? {
    if (!code.startsWith("\u001B]8;")) {
        return null
    }
    val terminator = if (code.endsWith(BEL)) BEL.toString() else "\u001B\\"
    val body = code.substring(4, code.length - terminator.length)
    val separator = body.indexOf(';')
    if (separator < 0) {
        return null
    }
    return Hyperlink(body.substring(0, separator), body.substring(separator + 1), terminator)
}
