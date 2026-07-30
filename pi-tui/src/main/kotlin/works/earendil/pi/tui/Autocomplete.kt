package works.earendil.pi.tui

import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.isDirectory
import kotlin.io.path.name

data class AutocompleteItem(
    val value: String,
    val label: String = value,
    val description: String? = null,
)

data class AutocompleteSuggestions(
    val items: List<AutocompleteItem>,
    val prefix: String,
)

data class CompletionResult(
    val lines: List<String>,
    val cursorLine: Int,
    val cursorColumn: Int,
)

data class AutocompleteRequest(
    val force: Boolean = false,
)

interface AutocompleteProvider {
    val triggerCharacters: List<String>
        get() = emptyList()

    fun getSuggestions(
        lines: List<String>,
        cursorLine: Int,
        cursorColumn: Int,
        request: AutocompleteRequest = AutocompleteRequest(),
    ): CompletableFuture<AutocompleteSuggestions?>

    fun applyCompletion(
        lines: List<String>,
        cursorLine: Int,
        cursorColumn: Int,
        item: AutocompleteItem,
        prefix: String,
    ): CompletionResult

    fun shouldTriggerFileCompletion(
        lines: List<String>,
        cursorLine: Int,
        cursorColumn: Int,
    ): Boolean = false
}

data class SlashCommand(
    val name: String,
    val description: String? = null,
    val argumentHint: String? = null,
    val getArgumentCompletions: ((String) -> List<AutocompleteItem>?)? = null,
)

class CombinedAutocompleteProvider(
    private val commands: List<SlashCommand> = emptyList(),
    basePath: Path,
    private val maxWalkResults: Int = 100,
) : AutocompleteProvider {
    private val basePath = basePath.toAbsolutePath().normalize()

    override val triggerCharacters: List<String> = listOf("@", "#")

    override fun getSuggestions(
        lines: List<String>,
        cursorLine: Int,
        cursorColumn: Int,
        request: AutocompleteRequest,
    ): CompletableFuture<AutocompleteSuggestions?> {
        val line = lines.getOrElse(cursorLine) { "" }
        val textBeforeCursor = line.take(cursorColumn.coerceIn(0, line.length))
        val result =
            extractAtPrefix(textBeforeCursor)?.let { prefix ->
                val parsed = parsePathPrefix(prefix)
                val suggestions = fuzzyFileSuggestions(parsed.rawPrefix, parsed.quoted, atPrefix = true)
                suggestions.takeIf(List<AutocompleteItem>::isNotEmpty)
                    ?.let { AutocompleteSuggestions(it, prefix) }
            } ?: slashSuggestions(textBeforeCursor, request.force)
        return CompletableFuture.completedFuture(result)
    }

    override fun applyCompletion(
        lines: List<String>,
        cursorLine: Int,
        cursorColumn: Int,
        item: AutocompleteItem,
        prefix: String,
    ): CompletionResult {
        val currentLine = lines.getOrElse(cursorLine) { "" }
        val safeCursor = cursorColumn.coerceIn(0, currentLine.length)
        val beforePrefix = currentLine.take((safeCursor - prefix.length).coerceAtLeast(0))
        val afterCursor = currentLine.drop(safeCursor)
        val quotedPrefix = prefix.startsWith('"') || prefix.startsWith("@\"")
        val adjustedAfter =
            if (quotedPrefix && item.value.endsWith('"') && afterCursor.startsWith('"')) {
                afterCursor.drop(1)
            } else {
                afterCursor
            }
        val slashCommand =
            prefix.startsWith("/") &&
                beforePrefix.isBlank() &&
                '/' !in prefix.drop(1)
        val directory = item.label.endsWith("/")
        val newLine: String
        val newCursor: Int
        when {
            slashCommand -> {
                newLine = "$beforePrefix/${item.value} $adjustedAfter"
                newCursor = beforePrefix.length + item.value.length + 2
            }

            prefix.startsWith("@") -> {
                val suffix = if (directory) "" else " "
                newLine = beforePrefix + item.value + suffix + adjustedAfter
                val offset =
                    if (directory && item.value.endsWith('"')) {
                        item.value.length - 1
                    } else {
                        item.value.length
                    }
                newCursor = beforePrefix.length + offset + suffix.length
            }

            else -> {
                newLine = beforePrefix + item.value + adjustedAfter
                val offset =
                    if (directory && item.value.endsWith('"')) {
                        item.value.length - 1
                    } else {
                        item.value.length
                    }
                newCursor = beforePrefix.length + offset
            }
        }
        val updated = lines.toMutableList()
        while (updated.size <= cursorLine) {
            updated += ""
        }
        updated[cursorLine] = newLine
        return CompletionResult(updated, cursorLine, newCursor)
    }

    override fun shouldTriggerFileCompletion(
        lines: List<String>,
        cursorLine: Int,
        cursorColumn: Int,
    ): Boolean {
        val line = lines.getOrElse(cursorLine) { "" }
        val text = line.take(cursorColumn.coerceIn(0, line.length))
        return extractPathPrefix(text, force = true)?.let { prefix ->
            !(prefix.startsWith("/") && prefix.drop(1).none { it == '/' } && text.startsWith("/"))
        } == true
    }

    private fun slashSuggestions(
        textBeforeCursor: String,
        force: Boolean,
    ): AutocompleteSuggestions? {
        if (!force && textBeforeCursor.startsWith("/")) {
            val space = textBeforeCursor.indexOf(' ')
            if (space < 0) {
                val prefix = textBeforeCursor.drop(1)
                val items =
                    fuzzyFilter(commands, prefix, SlashCommand::name).map { command ->
                        val description =
                            listOfNotNull(command.argumentHint, command.description)
                                .joinToString(" - ")
                                .takeIf(String::isNotEmpty)
                        AutocompleteItem(command.name, command.name, description)
                    }
                return items.takeIf(List<AutocompleteItem>::isNotEmpty)
                    ?.let { AutocompleteSuggestions(it, textBeforeCursor) }
            }
            val commandName = textBeforeCursor.substring(1, space)
            val argument = textBeforeCursor.substring(space + 1)
            val items =
                commands
                    .firstOrNull { command -> command.name == commandName }
                    ?.getArgumentCompletions
                    ?.invoke(argument)
                    .orEmpty()
            return items.takeIf(List<AutocompleteItem>::isNotEmpty)
                ?.let { AutocompleteSuggestions(it, argument) }
        }

        val prefix = extractPathPrefix(textBeforeCursor, force) ?: return null
        if (
            prefix.startsWith("/") &&
            prefix.drop(1).none { it == '/' } &&
            textBeforeCursor.startsWith("/") &&
            ' ' !in textBeforeCursor
        ) {
            return null
        }
        val parsed = parsePathPrefix(prefix)
        val items = directFileSuggestions(parsed.rawPrefix, parsed.quoted, parsed.atPrefix)
        return items.takeIf(List<AutocompleteItem>::isNotEmpty)
            ?.let { AutocompleteSuggestions(it, prefix) }
    }

    private fun directFileSuggestions(
        rawPrefix: String,
        quoted: Boolean,
        atPrefix: Boolean,
    ): List<AutocompleteItem> =
        runCatching {
            val expanded = expandHome(rawPrefix)
            val searchDirectory: Path
            val searchPrefix: String
            when {
                rawPrefix.isEmpty() ||
                    rawPrefix in setOf("./", "../", "~", "~/", "/") ||
                    rawPrefix.endsWith("/") -> {
                    searchDirectory = resolvePath(expanded)
                    searchPrefix = ""
                }

                else -> {
                    val path = Path.of(expanded)
                    searchDirectory =
                        if (path.isAbsolute) {
                            path.parent ?: path
                        } else {
                            basePath.resolve(path.parent ?: Path.of(""))
                        }
                    searchPrefix = path.fileName?.toString().orEmpty()
                }
            }
            Files
                .list(searchDirectory)
                .use { entries ->
                    entries
                        .filter { path -> path.fileName.toString().startsWith(searchPrefix, ignoreCase = true) }
                        .map { path ->
                            val directory = path.isDirectory()
                            val valuePath = directDisplayPath(rawPrefix, path.fileName.toString(), directory)
                            completionItem(valuePath, directory, atPrefix, quoted)
                        }.toList()
                }.sortedWith(compareBy({ !it.label.endsWith("/") }, { it.label.lowercase() }))
        }.getOrDefault(emptyList())

    private fun fuzzyFileSuggestions(
        query: String,
        quoted: Boolean,
        atPrefix: Boolean,
    ): List<AutocompleteItem> {
        val scoped = scopedQuery(query)
        val root = scoped?.root ?: basePath
        val needle = scoped?.query ?: query
        val displayBase = scoped?.displayBase.orEmpty()
        return runCatching {
            Files
                .walk(root, 32, FileVisitOption.FOLLOW_LINKS)
                .use { paths ->
                    paths
                        .filter { path -> path != root && ".git" !in root.relativize(path).map(Path::toString) }
                        .map { path ->
                            val relative = root.relativize(path).toString().replace('\\', '/')
                            val display = displayBase + relative + if (path.isDirectory()) "/" else ""
                            val score = fileScore(relative, needle, path.isDirectory())
                            Triple(display, path.isDirectory(), score)
                        }.filter { (_, _, score) -> score > 0 }
                        .sorted(
                            compareByDescending<Triple<String, Boolean, Int>> { it.third }
                                .thenByDescending { it.second }
                                .thenBy { it.first.lowercase() },
                        ).limit(maxWalkResults.toLong())
                        .map { (path, directory, _) ->
                            completionItem(path, directory, atPrefix, quoted)
                        }.toList()
                }
        }.getOrDefault(emptyList())
    }

    private fun completionItem(
        path: String,
        directory: Boolean,
        atPrefix: Boolean,
        quoted: Boolean,
    ): AutocompleteItem {
        val normalized = path.replace('\\', '/')
        val needsQuotes = quoted || ' ' in normalized
        val prefix = if (atPrefix) "@" else ""
        val value =
            if (needsQuotes) {
                "$prefix\"$normalized\""
            } else {
                prefix + normalized
            }
        val label = normalized.substringAfterLast('/').ifEmpty { normalized } + if (directory) "/" else ""
        return AutocompleteItem(value, label)
    }

    private fun directDisplayPath(
        rawPrefix: String,
        name: String,
        directory: Boolean,
    ): String {
        val base =
            when {
                rawPrefix.endsWith("/") -> rawPrefix + name
                '/' in rawPrefix || '\\' in rawPrefix -> {
                    val normalized = rawPrefix.replace('\\', '/')
                    normalized.substringBeforeLast('/', "") +
                        (if ('/' in normalized) "/" else "") +
                        name
                }

                rawPrefix.startsWith("~") -> "~/$name"
                else -> name
            }
        return base + if (directory) "/" else ""
    }

    private fun scopedQuery(value: String): ScopedQuery? {
        val normalized = value.replace('\\', '/')
        val slash = normalized.lastIndexOf('/')
        if (slash < 0) {
            return null
        }
        val displayBase = normalized.substring(0, slash + 1)
        val query = normalized.substring(slash + 1)
        val root = resolvePath(expandHome(displayBase))
        return root.takeIf(Files::isDirectory)?.let { ScopedQuery(it, query, displayBase) }
    }

    private fun resolvePath(value: String): Path {
        val path = Path.of(value.ifEmpty { "." })
        return (if (path.isAbsolute) path else basePath.resolve(path)).normalize()
    }

    private fun expandHome(value: String): String =
        when {
            value == "~" -> System.getProperty("user.home")
            value.startsWith("~/") -> Path.of(System.getProperty("user.home")).resolve(value.drop(2)).toString()
            else -> value
        }

    private fun fileScore(
        path: String,
        query: String,
        directory: Boolean,
    ): Int {
        if (query.isEmpty()) {
            return 1 + if (directory) 10 else 0
        }
        val name = Path.of(path.removeSuffix("/")).name.lowercase()
        val needle = query.lowercase()
        val score =
            when {
                name == needle -> 100
                name.startsWith(needle) -> 80
                needle in name -> 50
                needle in path.lowercase() -> 30
                fuzzyMatch(needle, path).matches -> 10
                else -> 0
            }
        return score + if (directory && score > 0) 10 else 0
    }

    private fun extractAtPrefix(text: String): String? {
        extractQuotedPrefix(text)?.takeIf { prefix -> prefix.startsWith("@\"") }?.let { return it }
        val start = lastDelimiter(text) + 1
        return text.substring(start).takeIf { token -> token.startsWith("@") }
    }

    private fun extractPathPrefix(
        text: String,
        force: Boolean,
    ): String? {
        extractQuotedPrefix(text)?.let { return it }
        val prefix = text.substring(lastDelimiter(text) + 1)
        if (force) {
            return prefix
        }
        return prefix.takeIf { value ->
            '/' in value ||
                value.startsWith(".") ||
                value.startsWith("~/") ||
                (value.isEmpty() && text.endsWith(' '))
        }
    }
}

private data class ParsedPathPrefix(
    val rawPrefix: String,
    val atPrefix: Boolean,
    val quoted: Boolean,
)

private data class ScopedQuery(
    val root: Path,
    val query: String,
    val displayBase: String,
)

private fun parsePathPrefix(prefix: String): ParsedPathPrefix =
    when {
        prefix.startsWith("@\"") -> ParsedPathPrefix(prefix.drop(2), atPrefix = true, quoted = true)
        prefix.startsWith('"') -> ParsedPathPrefix(prefix.drop(1), atPrefix = false, quoted = true)
        prefix.startsWith("@") -> ParsedPathPrefix(prefix.drop(1), atPrefix = true, quoted = false)
        else -> ParsedPathPrefix(prefix, atPrefix = false, quoted = false)
    }

private fun extractQuotedPrefix(text: String): String? {
    var open = -1
    var quoted = false
    text.forEachIndexed { index, character ->
        if (character == '"') {
            quoted = !quoted
            if (quoted) {
                open = index
            }
        }
    }
    if (!quoted || open < 0) {
        return null
    }
    val start = if (open > 0 && text[open - 1] == '@') open - 1 else open
    return text.substring(start)
}

private fun lastDelimiter(text: String): Int {
    for (index in text.lastIndex downTo 0) {
        if (text[index] in setOf(' ', '\t', '"', '\'', '=')) {
            return index
        }
    }
    return -1
}
