package works.earendil.pi.codingagent

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.agent.AgentTool
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.codingagent.session.SessionManager
import works.earendil.pi.codingagent.session.encodeEntry
import works.earendil.pi.codingagent.session.sessionJson
import works.earendil.pi.tui.visibleWidth
import works.earendil.pi.tui.wrapTextWithAnsi

internal data class SessionHtmlExportOptions(
    val theme: Theme = createBuiltinTheme("dark"),
    val systemPrompt: String? = null,
    val tools: List<AgentTool> = emptyList(),
    val renderedTools: JsonObject? = null,
)

fun exportSessionFile(
    inputPath: Path,
    outputPath: Path? = null,
): Path {
    val resolvedInput = inputPath.toAbsolutePath().normalize()
    require(Files.exists(resolvedInput)) { "File not found: $resolvedInput" }
    return exportSession(
        SessionManager.open(resolvedInput),
        outputPath ?: defaultExportPath(resolvedInput),
    )
}

internal fun exportSession(
    sessionManager: SessionManager,
    outputPath: Path? = null,
    options: SessionHtmlExportOptions = SessionHtmlExportOptions(),
): Path {
    val sessionFile = sessionManager.getSessionFile()
        ?: error("Cannot export in-memory session to HTML")
    require(Files.exists(sessionFile)) { "Nothing to export yet - start a conversation first" }
    val resolvedOutput =
        (outputPath ?: defaultExportPath(sessionFile))
            .toAbsolutePath()
            .normalize()
    resolvedOutput.parent?.let(Files::createDirectories)
    Files.writeString(
        resolvedOutput,
        generateSessionHtml(sessionManager, options),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )
    return resolvedOutput
}

internal fun generateSessionHtml(
    sessionManager: SessionManager,
    options: SessionHtmlExportOptions = SessionHtmlExportOptions(),
): String {
    val template = exportResource("template.html")
    val templateCss = exportResource("template.css")
    val templateJs = exportResource("template.js")
    val markedJs = exportResource("vendor/marked.min.js")
    val highlightJs = exportResource("vendor/highlight.min.js")
    val colors = options.theme.cssColors
    val derived = deriveExportColors(colors["userMessageBg"] ?: "#343541")
    val bodyBackground = options.theme.exportColors["pageBg"] ?: derived.pageBackground
    val containerBackground = options.theme.exportColors["cardBg"] ?: derived.cardBackground
    val infoBackground = options.theme.exportColors["infoBg"] ?: derived.infoBackground
    val css =
        templateCss
            .replace("{{THEME_VARS}}", generateThemeVariables(options.theme, derived))
            .replace("{{BODY_BG}}", bodyBackground)
            .replace("{{CONTAINER_BG}}", containerBackground)
            .replace("{{INFO_BG}}", infoBackground)
    val sessionDataBase64 =
        Base64.getEncoder().encodeToString(
            sessionJson
                .encodeToString(
                    JsonObject.serializer(),
                    normalizeJavaScriptNumbers(sessionData(sessionManager, options)) as JsonObject,
                )
                .toByteArray(StandardCharsets.UTF_8),
        )
    var html = template
    html = javaScriptReplaceFirst(html, "{{CSS}}", css)
    html = javaScriptReplaceFirst(html, "{{JS}}", templateJs)
    html = javaScriptReplaceFirst(html, "{{SESSION_DATA}}", sessionDataBase64)
    html = javaScriptReplaceFirst(html, "{{MARKED_JS}}", markedJs)
    html = javaScriptReplaceFirst(html, "{{HIGHLIGHT_JS}}", highlightJs)
    return html
}

private fun sessionData(
    sessionManager: SessionManager,
    options: SessionHtmlExportOptions,
): JsonObject =
    buildJsonObject {
        val header = sessionManager.getHeader()
        if (header == null) {
            put("header", JsonNull)
        } else {
            put("header", encodeEntry(header))
        }
        put(
            "entries",
            JsonArray(sessionManager.getEntries().map(::encodeEntry)),
        )
        val leafId = sessionManager.getLeafId()
        if (leafId == null) {
            put("leafId", JsonNull)
        } else {
            put("leafId", leafId)
        }
        options.systemPrompt?.let { put("systemPrompt", it) }
        if (options.tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    options.tools.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            },
                        )
                    }
                },
            )
        }
        options.renderedTools?.let { put("renderedTools", it) }
    }

private fun normalizeJavaScriptNumbers(value: JsonElement): JsonElement =
    when (value) {
        is JsonObject -> JsonObject(value.mapValues { (_, element) -> normalizeJavaScriptNumbers(element) })
        is JsonArray -> JsonArray(value.map(::normalizeJavaScriptNumbers))
        is JsonPrimitive -> {
            if (value.isString) {
                value
            } else {
                val number = value.doubleOrNull
                if (number != null && number.isFinite() && number % 1.0 == 0.0) {
                    JsonPrimitive(number.toLong())
                } else {
                    value
                }
            }
        }
    }

internal fun ansiLinesToHtml(lines: List<String>): String =
    lines.joinToString("") { line ->
        "<div class=\"ansi-line\">${ansiToHtml(line).ifEmpty { "&nbsp;" }}</div>"
    }

internal fun trimRenderedResultLines(lines: List<String>): List<String> {
    var start = 0
    var end = lines.size
    while (start < end && ANSI_SEQUENCE.replace(lines[start], "").trim().isEmpty()) {
        start += 1
    }
    while (end > start && ANSI_SEQUENCE.replace(lines[end - 1], "").trim().isEmpty()) {
        end -= 1
    }
    return lines.subList(start, end)
}

internal fun renderBuiltinHtmlToolCall(
    call: ToolCall,
    theme: Theme,
    width: Int = 100,
): List<String>? {
    val name = call.name.takeIf { it == "find" || it == "grep" } ?: return null
    val pattern =
        call.arguments.toolString("pattern")?.let { value ->
            if (name == "grep") "/$value/" else value
        }
    val rawPath = call.arguments.toolString("path")
    val path = rawPath?.let { shortenExportPath(it.ifEmpty { "." }) }
    val invalid = theme.fg("error", "[invalid arg]")
    var text =
        theme.fg("toolTitle", theme.bold(name)) +
            " " +
            (pattern?.let { theme.fg("accent", it) } ?: invalid) +
            theme.fg("toolOutput", " in ${path ?: invalid}")
    if (name == "grep") {
        call.arguments.toolString("glob")?.takeIf(String::isNotEmpty)?.let { glob ->
            text += theme.fg("toolOutput", " ($glob)")
        }
        call.arguments["limit"]?.jsonPrimitive?.contentOrNull?.let { limit ->
            text += theme.fg("toolOutput", " limit $limit")
        }
    } else {
        call.arguments["limit"]?.jsonPrimitive?.contentOrNull?.let { limit ->
            text += theme.fg("toolOutput", " (limit $limit)")
        }
    }
    return renderExportTextComponent(text, width)
}

internal fun renderBuiltinHtmlToolResult(
    message: ToolResultMessage,
    theme: Theme,
    expanded: Boolean,
    width: Int = 100,
    expandKey: String = "",
): List<String>? {
    val maxLines =
        when (message.toolName) {
            "find" -> if (expanded) Int.MAX_VALUE else 20
            "grep" -> if (expanded) Int.MAX_VALUE else 15
            else -> return null
        }
    val output =
        message.content
            .filterIsInstance<TextContent>()
            .joinToString("\n", transform = TextContent::text)
            .let { ANSI_SEQUENCE.replace(it, "") }
            .replace("\r", "")
            .trim()
    var text = ""
    if (output.isNotEmpty()) {
        val lines = output.split('\n')
        val displayLines = lines.take(maxLines)
        val remaining = lines.size - displayLines.size
        text += "\n" + displayLines.joinToString("\n") { line -> theme.fg("toolOutput", line) }
        if (remaining > 0) {
            text += theme.fg("muted", "\n... ($remaining more lines,")
            text += " "
            text += theme.fg("dim", expandKey)
            text += theme.fg("muted", " to expand")
            text += theme.fg("muted", ")")
        }
    }
    val details = message.details as? JsonObject
    val limit =
        if (message.toolName == "find") {
            details?.get("resultLimitReached")?.jsonPrimitive?.intOrNull
        } else {
            details?.get("matchLimitReached")?.jsonPrimitive?.intOrNull
        }
    val truncation = details?.get("truncation") as? JsonObject
    val truncated = truncation?.get("truncated")?.jsonPrimitive?.booleanOrNull == true
    val linesTruncated =
        message.toolName == "grep" &&
            details?.get("linesTruncated")?.jsonPrimitive?.booleanOrNull == true
    if (limit != null || truncated || linesTruncated) {
        val warnings =
            buildList {
                if (limit != null) {
                    add("$limit ${if (message.toolName == "find") "results" else "matches"} limit")
                }
                if (truncated) {
                    val maxBytes = truncation["maxBytes"]?.jsonPrimitive?.intOrNull ?: 50 * 1024
                    add("${formatExportSize(maxBytes)} limit")
                }
                if (linesTruncated) {
                    add("some lines truncated")
                }
            }
        text += "\n" + theme.fg("warning", "[Truncated: ${warnings.joinToString(", ")}]")
    }
    return renderExportTextComponent(text, width)
}

internal fun ansiToHtml(text: String): String {
    val style = HtmlTextStyle()
    val output = StringBuilder()
    var lastIndex = 0
    var spanOpen = false
    ANSI_SEQUENCE.findAll(text).forEach { match ->
        if (match.range.first > lastIndex) {
            output.append(escapeExportHtml(text.substring(lastIndex, match.range.first)))
        }
        if (spanOpen) {
            output.append("</span>")
            spanOpen = false
        }
        val parameters =
            match.groupValues[1]
                .takeIf(String::isNotEmpty)
                ?.split(';')
                ?.map { it.toIntOrNull() ?: 0 }
                ?: listOf(0)
        applyAnsiStyle(parameters, style)
        style.inlineCss().takeIf(String::isNotEmpty)?.let { css ->
            output.append("<span style=\"").append(css).append("\">")
            spanOpen = true
        }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < text.length) {
        output.append(escapeExportHtml(text.substring(lastIndex)))
    }
    if (spanOpen) {
        output.append("</span>")
    }
    return output.toString()
}

private data class HtmlTextStyle(
    var foreground: String? = null,
    var background: String? = null,
    var bold: Boolean = false,
    var dim: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
) {
    fun inlineCss(): String =
        buildList {
            foreground?.let { add("color:$it") }
            background?.let { add("background-color:$it") }
            if (bold) add("font-weight:bold")
            if (dim) add("opacity:0.6")
            if (italic) add("font-style:italic")
            if (underline) add("text-decoration:underline")
        }.joinToString(";")
}

private fun applyAnsiStyle(
    parameters: List<Int>,
    style: HtmlTextStyle,
) {
    var index = 0
    while (index < parameters.size) {
        when (val code = parameters[index]) {
            0 -> {
                style.foreground = null
                style.background = null
                style.bold = false
                style.dim = false
                style.italic = false
                style.underline = false
            }

            1 -> style.bold = true
            2 -> style.dim = true
            3 -> style.italic = true
            4 -> style.underline = true
            22 -> {
                style.bold = false
                style.dim = false
            }

            23 -> style.italic = false
            24 -> style.underline = false
            in 30..37 -> style.foreground = ANSI_COLORS[code - 30]
            38 -> {
                when (parameters.getOrNull(index + 1)) {
                    5 -> {
                        parameters.getOrNull(index + 2)?.let { style.foreground = exportAnsi256ToHex(it) }
                        index += 2
                    }

                    2 -> {
                        val red = parameters.getOrNull(index + 2)
                        val green = parameters.getOrNull(index + 3)
                        val blue = parameters.getOrNull(index + 4)
                        if (red != null && green != null && blue != null) {
                            style.foreground = "rgb($red,$green,$blue)"
                        }
                        index += 4
                    }
                }
            }

            39 -> style.foreground = null
            in 40..47 -> style.background = ANSI_COLORS[code - 40]
            48 -> {
                when (parameters.getOrNull(index + 1)) {
                    5 -> {
                        parameters.getOrNull(index + 2)?.let { style.background = exportAnsi256ToHex(it) }
                        index += 2
                    }

                    2 -> {
                        val red = parameters.getOrNull(index + 2)
                        val green = parameters.getOrNull(index + 3)
                        val blue = parameters.getOrNull(index + 4)
                        if (red != null && green != null && blue != null) {
                            style.background = "rgb($red,$green,$blue)"
                        }
                        index += 4
                    }
                }
            }

            49 -> style.background = null
            in 90..97 -> style.foreground = ANSI_COLORS[code - 90 + 8]
            in 100..107 -> style.background = ANSI_COLORS[code - 100 + 8]
        }
        index += 1
    }
}

private fun exportAnsi256ToHex(index: Int): String {
    if (index < 16) {
        return ANSI_COLORS[index]
    }
    if (index < 232) {
        val cube = index - 16
        fun component(value: Int): Int = if (value == 0) 0 else 55 + value * 40
        return "#%02x%02x%02x".format(
            component(cube / 36),
            component((cube % 36) / 6),
            component(cube % 6),
        )
    }
    val gray = 8 + (index - 232) * 10
    return "#%02x%02x%02x".format(gray, gray, gray)
}

private fun escapeExportHtml(value: String): String =
    buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#039;"
                    else -> character
                },
            )
        }
    }

private fun JsonObject.toolString(name: String): String? {
    val value = this[name] ?: return ""
    val primitive = value as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}

private fun shortenExportPath(value: String): String {
    val home = defaultHomeDirectory().toString()
    return if (value.startsWith(home)) "~" + value.removePrefix(home) else value
}

private fun formatExportSize(bytes: Int): String =
    when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
        else -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
    }

private fun renderExportTextComponent(
    text: String,
    width: Int,
): List<String> =
    wrapTextWithAnsi(text, width).map { line ->
        line + " ".repeat((width - visibleWidth(line)).coerceAtLeast(0))
    }

private fun generateThemeVariables(
    theme: Theme,
    derived: ExportColors,
): String {
    val lines = theme.cssColors.map { (key, value) -> "--$key: $value;" }.toMutableList()
    lines += "--exportPageBg: ${theme.exportColors["pageBg"] ?: derived.pageBackground};"
    lines += "--exportCardBg: ${theme.exportColors["cardBg"] ?: derived.cardBackground};"
    lines += "--exportInfoBg: ${theme.exportColors["infoBg"] ?: derived.infoBackground};"
    return lines.joinToString("\n      ")
}

private data class ExportColors(
    val pageBackground: String,
    val cardBackground: String,
    val infoBackground: String,
)

private data class Rgb(
    val red: Int,
    val green: Int,
    val blue: Int,
)

private fun deriveExportColors(baseColor: String): ExportColors {
    val parsed =
        parseCssColor(baseColor)
            ?: return ExportColors(
                pageBackground = "rgb(24, 24, 30)",
                cardBackground = "rgb(30, 30, 36)",
                infoBackground = "rgb(60, 55, 40)",
            )
    return if (relativeLuminance(parsed) > 0.5) {
        ExportColors(
            pageBackground = adjustBrightness(baseColor, 0.96),
            cardBackground = baseColor,
            infoBackground =
                "rgb(${min(255, parsed.red + 10)}, ${min(255, parsed.green + 5)}, " +
                    "${max(0, parsed.blue - 20)})",
        )
    } else {
        ExportColors(
            pageBackground = adjustBrightness(baseColor, 0.7),
            cardBackground = adjustBrightness(baseColor, 0.85),
            infoBackground =
                "rgb(${min(255, parsed.red + 20)}, ${min(255, parsed.green + 15)}, ${parsed.blue})",
        )
    }
}

private fun parseCssColor(value: String): Rgb? {
    val hex = HEX_COLOR.matchEntire(value)
    if (hex != null) {
        return Rgb(
            hex.groupValues[1].toInt(16),
            hex.groupValues[2].toInt(16),
            hex.groupValues[3].toInt(16),
        )
    }
    val rgb = RGB_COLOR.matchEntire(value)
    return rgb?.let {
        Rgb(
            it.groupValues[1].toInt(),
            it.groupValues[2].toInt(),
            it.groupValues[3].toInt(),
        )
    }
}

private fun relativeLuminance(color: Rgb): Double =
    0.2126 * linearChannel(color.red) +
        0.7152 * linearChannel(color.green) +
        0.0722 * linearChannel(color.blue)

private fun linearChannel(channel: Int): Double {
    val value = channel / 255.0
    return if (value <= 0.03928) {
        value / 12.92
    } else {
        ((value + 0.055) / 1.055).pow(2.4)
    }
}

private fun adjustBrightness(
    color: String,
    factor: Double,
): String {
    val parsed = parseCssColor(color) ?: return color
    fun adjust(channel: Int): Int = (channel * factor).roundToInt().coerceIn(0, 255)
    return "rgb(${adjust(parsed.red)}, ${adjust(parsed.green)}, ${adjust(parsed.blue)})"
}

private fun javaScriptReplaceFirst(
    input: String,
    search: String,
    replacement: String,
): String {
    val index = input.indexOf(search)
    if (index < 0) {
        return input
    }
    val prefix = input.substring(0, index)
    val suffix = input.substring(index + search.length)
    val expanded =
        buildString(replacement.length) {
            var cursor = 0
            while (cursor < replacement.length) {
                val character = replacement[cursor]
                if (character != '$' || cursor + 1 >= replacement.length) {
                    append(character)
                    cursor += 1
                    continue
                }
                when (replacement[cursor + 1]) {
                    '$' -> append('$')
                    '&' -> append(search)
                    '`' -> append(prefix)
                    '\'' -> append(suffix)
                    else -> {
                        append('$')
                        cursor += 1
                        continue
                    }
                }
                cursor += 2
            }
        }
    return prefix + expanded + suffix
}

private fun exportResource(name: String): String {
    val path = "/works/earendil/pi/codingagent/export-html/$name"
    return checkNotNull(SessionHtmlExportOptions::class.java.getResourceAsStream(path)) {
        "Bundled HTML export resource is missing: $path"
    }.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
}

private fun defaultExportPath(sessionFile: Path): Path {
    val name = sessionFile.fileName.toString().removeSuffix(".jsonl")
    return Path.of("pi-session-$name.html")
}

private val HEX_COLOR = Regex("^#([0-9a-fA-F]{2})([0-9a-fA-F]{2})([0-9a-fA-F]{2})$")
private val RGB_COLOR = Regex("^rgb\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)$")
private val ANSI_SEQUENCE = Regex("\\u001B\\[([\\d;]*)m")
private val ANSI_COLORS =
    listOf(
        "#000000",
        "#800000",
        "#008000",
        "#808000",
        "#000080",
        "#800080",
        "#008080",
        "#c0c0c0",
        "#808080",
        "#ff0000",
        "#00ff00",
        "#ffff00",
        "#0000ff",
        "#ff00ff",
        "#00ffff",
        "#ffffff",
    )
