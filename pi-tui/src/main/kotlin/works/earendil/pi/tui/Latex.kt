package works.earendil.pi.tui

private val latexSymbols =
    mapOf(
        "alpha" to "α",
        "beta" to "β",
        "gamma" to "γ",
        "delta" to "δ",
        "epsilon" to "ϵ",
        "varepsilon" to "ε",
        "theta" to "θ",
        "lambda" to "λ",
        "mu" to "μ",
        "pi" to "π",
        "rho" to "ρ",
        "sigma" to "σ",
        "phi" to "ϕ",
        "varphi" to "φ",
        "omega" to "ω",
        "Gamma" to "Γ",
        "Delta" to "Δ",
        "Theta" to "Θ",
        "Lambda" to "Λ",
        "Pi" to "Π",
        "Sigma" to "Σ",
        "Phi" to "Φ",
        "Omega" to "Ω",
        "pm" to "±",
        "mp" to "∓",
        "times" to "×",
        "div" to "÷",
        "cdot" to "·",
        "le" to "≤",
        "leq" to "≤",
        "ge" to "≥",
        "geq" to "≥",
        "ne" to "≠",
        "neq" to "≠",
        "equiv" to "≡",
        "approx" to "≈",
        "sim" to "∼",
        "in" to "∈",
        "notin" to "∉",
        "subset" to "⊂",
        "supset" to "⊃",
        "subseteq" to "⊆",
        "supseteq" to "⊇",
        "cup" to "∪",
        "cap" to "∩",
        "forall" to "∀",
        "exists" to "∃",
        "nexists" to "∄",
        "neg" to "¬",
        "land" to "∧",
        "wedge" to "∧",
        "lor" to "∨",
        "vee" to "∨",
        "to" to "→",
        "rightarrow" to "→",
        "leftarrow" to "←",
        "leftrightarrow" to "↔",
        "Rightarrow" to "⇒",
        "Leftarrow" to "⇐",
        "Leftrightarrow" to "⇔",
        "implies" to "⇒",
        "iff" to "⇔",
        "mapsto" to "↦",
        "uparrow" to "↑",
        "downarrow" to "↓",
        "partial" to "∂",
        "nabla" to "∇",
        "int" to "∫",
        "iint" to "∬",
        "iiint" to "∭",
        "oint" to "∮",
        "sum" to "∑",
        "prod" to "∏",
        "infty" to "∞",
        "emptyset" to "∅",
        "sqrt" to "√",
        "ldots" to "…",
        "cdots" to "⋯",
        "langle" to "⟨",
        "rangle" to "⟩",
        "lfloor" to "⌊",
        "rfloor" to "⌋",
        "lceil" to "⌈",
        "rceil" to "⌉",
    )

private val blackboardSymbols =
    mapOf(
        'A' to "𝔸",
        'B' to "𝔹",
        'C' to "ℂ",
        'H' to "ℍ",
        'N' to "ℕ",
        'P' to "ℙ",
        'Q' to "ℚ",
        'R' to "ℝ",
        'Z' to "ℤ",
    )

private val superscripts =
    mapOf(
        '0' to '⁰',
        '1' to '¹',
        '2' to '²',
        '3' to '³',
        '4' to '⁴',
        '5' to '⁵',
        '6' to '⁶',
        '7' to '⁷',
        '8' to '⁸',
        '9' to '⁹',
        '+' to '⁺',
        '-' to '⁻',
        '=' to '⁼',
        '(' to '⁽',
        ')' to '⁾',
        'n' to 'ⁿ',
        'i' to 'ⁱ',
    )

private val subscripts =
    mapOf(
        '0' to '₀',
        '1' to '₁',
        '2' to '₂',
        '3' to '₃',
        '4' to '₄',
        '5' to '₅',
        '6' to '₆',
        '7' to '₇',
        '8' to '₈',
        '9' to '₉',
        '+' to '₊',
        '-' to '₋',
        '=' to '₌',
        '(' to '₍',
        ')' to '₎',
        'a' to 'ₐ',
        'e' to 'ₑ',
        'h' to 'ₕ',
        'i' to 'ᵢ',
        'j' to 'ⱼ',
        'k' to 'ₖ',
        'l' to 'ₗ',
        'm' to 'ₘ',
        'n' to 'ₙ',
        'o' to 'ₒ',
        'p' to 'ₚ',
        'r' to 'ᵣ',
        's' to 'ₛ',
        't' to 'ₜ',
        'u' to 'ᵤ',
        'v' to 'ᵥ',
        'x' to 'ₓ',
    )

fun renderLatex(
    source: String,
    display: Boolean = false,
): String? {
    val parser = LatexParser(source)
    val rendered = parser.parseSequence() ?: return null
    if (!parser.atEnd()) {
        return null
    }
    val compact = rendered.replace(Regex("\\s+"), " ").trim()
    return compact.takeIf(String::isNotEmpty)?.let { value ->
        if (display) value else value
    }
}

fun renderMarkdownLatex(markdown: String): String {
    val lines = markdown.split('\n')
    val rendered = mutableListOf<String>()
    var index = 0
    var fence: String? = null
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()
        val nextFence = Regex("^(`{3,}|~{3,})").find(trimmed)?.value
        if (nextFence != null) {
            if (fence == null) {
                fence = nextFence
            } else if (trimmed.startsWith(fence)) {
                fence = null
            }
            rendered += line
            index++
            continue
        }
        if (fence != null) {
            rendered += line
            index++
            continue
        }

        val block = readLatexBlock(lines, index)
        if (block != null) {
            val value = renderLatex(block.source, display = true)
            if (value == null) {
                rendered += block.rawLines
            } else {
                rendered += value.split('\n')
            }
            index = block.nextIndex
            continue
        }

        rendered += renderInlineLatex(line)
        index++
    }
    return rendered.joinToString("\n")
}

private data class LatexBlock(
    val source: String,
    val rawLines: List<String>,
    val nextIndex: Int,
)

private fun readLatexBlock(
    lines: List<String>,
    start: Int,
): LatexBlock? {
    val trimmed = lines[start].trim()
    val delimiter =
        when {
            trimmed.startsWith("$$") -> "$$"
            trimmed.startsWith("\\[") -> "\\["
            else -> return null
        }
    val closing = if (delimiter == "$$") "$$" else "\\]"
    val firstContent = trimmed.removePrefix(delimiter)
    if (firstContent.endsWith(closing) && firstContent.length > closing.length) {
        return LatexBlock(
            source = firstContent.removeSuffix(closing).trim(),
            rawLines = listOf(lines[start]),
            nextIndex = start + 1,
        )
    }
    val raw = mutableListOf(lines[start])
    val content = mutableListOf(firstContent)
    for (index in start + 1 until lines.size) {
        val line = lines[index]
        raw += line
        val closeIndex = findUnescaped(line, closing)
        if (closeIndex >= 0) {
            content += line.take(closeIndex)
            return LatexBlock(
                source = content.joinToString("\n").trim(),
                rawLines = raw,
                nextIndex = index + 1,
            )
        }
        content += line
    }
    return LatexBlock(
        source = "",
        rawLines = raw,
        nextIndex = lines.size,
    )
}

private fun renderInlineLatex(line: String): String {
    val result = StringBuilder()
    var index = 0
    var codeFenceLength = 0
    while (index < line.length) {
        if (line[index] == '`') {
            val run = line.drop(index).takeWhile { it == '`' }.length
            if (codeFenceLength == 0) {
                codeFenceLength = run
            } else if (run == codeFenceLength) {
                codeFenceLength = 0
            }
            result.append(line, index, index + run)
            index += run
            continue
        }
        if (codeFenceLength > 0) {
            result.append(line[index++])
            continue
        }

        val delimiter =
            when {
                line.startsWith("\\(", index) -> "\\("
                line.startsWith("\\[", index) -> "\\["
                line[index] == '$' &&
                    !isEscaped(line, index) &&
                    line.getOrNull(index + 1)?.isWhitespace() != true -> "$"

                else -> null
            }
        if (delimiter == null) {
            result.append(line[index++])
            continue
        }
        val closing =
            when (delimiter) {
                "\\(" -> "\\)"
                "\\[" -> "\\]"
                else -> "$"
            }
        val closingIndex = findUnescaped(line, closing, index + delimiter.length)
        if (closingIndex < 0) {
            result.append(line.substring(index))
            break
        }
        val source = line.substring(index + delimiter.length, closingIndex)
        if (
            delimiter == "$" &&
            (
                source.isEmpty() ||
                    source.last().isWhitespace() ||
                    source.firstOrNull()?.isDigit() == true ||
                    '`' in source
            )
        ) {
            result.append(line[index++])
            continue
        }
        val value = renderLatex(source, display = delimiter == "\\[")
        if (value == null) {
            result.append(line, index, closingIndex + closing.length)
        } else {
            result.append(value)
        }
        index = closingIndex + closing.length
    }
    return result.toString()
}

private fun findUnescaped(
    source: String,
    delimiter: String,
    from: Int = 0,
): Int {
    var index = source.indexOf(delimiter, from)
    while (index >= 0 && isEscaped(source, index)) {
        index = source.indexOf(delimiter, index + delimiter.length)
    }
    return index
}

private fun isEscaped(
    source: String,
    index: Int,
): Boolean {
    var slashes = 0
    var cursor = index - 1
    while (cursor >= 0 && source[cursor] == '\\') {
        slashes++
        cursor--
    }
    return slashes % 2 == 1
}

private class LatexParser(
    private val source: String,
) {
    private var index = 0

    fun atEnd(): Boolean = index == source.length

    fun parseSequence(until: Char? = null): String? {
        val result = StringBuilder()
        while (index < source.length && source[index] != until) {
            when (val character = source[index]) {
                '\\' -> result.append(parseCommand() ?: return null)
                '^', '_' -> {
                    index++
                    val argument = parseArgument() ?: return null
                    val converted =
                        argument.map { value ->
                            if (character == '^') superscripts[value] else subscripts[value]
                        }
                    if (converted.any { it == null }) {
                        result.append(if (character == '^') "^(" else "_(")
                        result.append(argument)
                        result.append(')')
                    } else {
                        converted.forEach { result.append(it) }
                    }
                }

                '{' -> {
                    index++
                    result.append(parseSequence('}') ?: return null)
                    if (index >= source.length || source[index] != '}') {
                        return null
                    }
                    index++
                }

                '}' -> return if (until == '}') result.toString() else null
                '~' -> {
                    result.append(' ')
                    index++
                }

                else -> {
                    result.append(character)
                    index++
                }
            }
        }
        return result.toString()
    }

    private fun parseCommand(): String? {
        index++
        if (index >= source.length) {
            return null
        }
        if (!source[index].isLetter()) {
            return source[index++].toString()
        }
        val start = index
        while (index < source.length && source[index].isLetter()) {
            index++
        }
        val command = source.substring(start, index)
        val whitespaceStart = index
        while (index < source.length && source[index].isWhitespace()) {
            index++
        }
        val separated = index > whitespaceStart
        return when (command) {
            "frac" -> {
                val numerator = parseArgument() ?: return null
                val denominator = parseArgument() ?: return null
                "$numerator⁄$denominator"
            }

            "sqrt" -> {
                val value = parseArgument() ?: return null
                if (value.length == 1) "√$value" else "√($value)"
            }

            "mathbb" -> {
                val value = parseRawArgument() ?: return null
                value.map { blackboardSymbols[it] ?: it.toString() }.joinToString("")
            }

            "mathrm", "mathbf", "mathit", "operatorname", "text" ->
                parseArgument() ?: return null

            "left", "right" -> ""
            else ->
                latexSymbols[command]
                    ?.let { symbol -> symbol + if (separated) " " else "" }
                    ?: return null
        }
    }

    private fun parseArgument(): String? {
        if (index >= source.length) {
            return null
        }
        if (source[index] != '{') {
            val start = index
            return when (source[index]) {
                '\\' -> parseCommand()
                else -> source[index++].toString()
            } ?: source.substring(start, index)
        }
        index++
        val value = parseSequence('}') ?: return null
        if (index >= source.length || source[index] != '}') {
            return null
        }
        index++
        return value
    }

    private fun parseRawArgument(): String? {
        if (index >= source.length || source[index] != '{') {
            return null
        }
        val end = source.indexOf('}', index + 1)
        if (end < 0) {
            return null
        }
        return source.substring(index + 1, end).also { index = end + 1 }
    }
}
