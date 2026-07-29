package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal enum class ThemeColorMode(
    val wireName: String,
) {
    TRUECOLOR("truecolor"),
    COLOR_256("256color"),
}

internal enum class TerminalTheme(
    val wireName: String,
) {
    DARK("dark"),
    LIGHT("light"),
}

internal data class AutoThemeSetting(
    val lightTheme: String,
    val darkTheme: String,
)

internal data class ThemeInfo(
    val name: String,
    val path: Path?,
    val builtin: Boolean,
)

internal data class LoadedThemes(
    val themes: List<Theme>,
    val diagnostics: List<ResourceDiagnostic>,
)

internal data class ThemeResult(
    val success: Boolean,
    val error: String? = null,
)

private sealed interface ColorValue {
    data class Text(
        val value: String,
    ) : ColorValue

    data class Index(
        val value: Int,
    ) : ColorValue
}

private sealed interface ResolvedColor {
    data object TerminalDefault : ResolvedColor

    data class Rgb(
        val red: Int,
        val green: Int,
        val blue: Int,
        val css: String,
    ) : ResolvedColor

    data class Index(
        val value: Int,
    ) : ResolvedColor
}

internal class Theme internal constructor(
    val name: String,
    val sourcePath: Path?,
    val sourceInfo: ResourceSourceInfo?,
    val colorMode: ThemeColorMode,
    private val foreground: Map<String, String>,
    private val background: Map<String, String>,
    val cssColors: Map<String, String>,
    val exportColors: Map<String, String?>,
) {
    fun fg(
        color: String,
        text: String,
    ): String = "${getFgAnsi(color)}$text\u001B[39m"

    fun bg(
        color: String,
        text: String,
    ): String = "${getBgAnsi(color)}$text\u001B[49m"

    fun bold(text: String): String = "\u001B[1m$text\u001B[22m"

    fun italic(text: String): String = "\u001B[3m$text\u001B[23m"

    fun underline(text: String): String = "\u001B[4m$text\u001B[24m"

    fun inverse(text: String): String = "\u001B[7m$text\u001B[27m"

    fun strikethrough(text: String): String = "\u001B[9m$text\u001B[29m"

    fun getFgAnsi(color: String): String =
        foreground[color] ?: error("Unknown theme color: $color")

    fun getBgAnsi(color: String): String =
        background[color] ?: error("Unknown theme background color: $color")

    fun getThinkingBorderColor(level: String): (String) -> String {
        val color =
            when (level) {
                "off" -> "thinkingOff"
                "minimal" -> "thinkingMinimal"
                "low" -> "thinkingLow"
                "medium" -> "thinkingMedium"
                "high" -> "thinkingHigh"
                "xhigh" -> "thinkingXhigh"
                "max" -> "thinkingMax"
                else -> "thinkingOff"
            }
        return { text -> fg(color, text) }
    }

    fun getBashModeBorderColor(): (String) -> String = { text -> fg("bashMode", text) }

    fun extensionJson(): JsonObject =
        buildJsonObject {
            put("name", name)
            sourcePath?.let { put("path", it.toString()) }
            put("colorMode", colorMode.wireName)
            put(
                "fgAnsi",
                JsonObject(foreground.mapValues { JsonPrimitive(it.value) }),
            )
            put(
                "bgAnsi",
                JsonObject(background.mapValues { JsonPrimitive(it.value) }),
            )
        }

    companion object {
        fun fromExtensionJson(value: JsonObject): Theme {
            val name = value["name"]?.jsonPrimitive?.content ?: "<in-memory>"
            val mode =
                when (value["colorMode"]?.jsonPrimitive?.content) {
                    ThemeColorMode.COLOR_256.wireName -> ThemeColorMode.COLOR_256
                    else -> ThemeColorMode.TRUECOLOR
                }
            val foreground =
                value["fgAnsi"]
                    ?.jsonObject
                    ?.mapValues { (_, ansi) -> ansi.jsonPrimitive.content }
                    .orEmpty()
            val background =
                value["bgAnsi"]
                    ?.jsonObject
                    ?.mapValues { (_, ansi) -> ansi.jsonPrimitive.content }
                    .orEmpty()
            require(FOREGROUND_COLOR_TOKENS.all(foreground::containsKey)) {
                "In-memory theme is missing foreground color tokens"
            }
            require(BACKGROUND_COLOR_TOKENS.all(background::containsKey)) {
                "In-memory theme is missing background color tokens"
            }
            val defaultText = if (name == "light") "#000000" else "#e5e5e7"
            val cssColors =
                (foreground + background).mapValues { (_, ansi) ->
                    ansiColorToCss(ansi) ?: defaultText
                }
            return Theme(
                name = name,
                sourcePath = value["path"]?.jsonPrimitive?.content?.let(Path::of),
                sourceInfo = null,
                colorMode = mode,
                foreground = foreground,
                background = background,
                cssColors = cssColors,
                exportColors = emptyMap(),
            )
        }
    }
}

internal class ThemeRegistry internal constructor(
    val loaded: LoadedThemes,
    private val builtinThemes: Map<String, Theme>,
    private val settings: SettingsStore,
    initialThemeName: String,
) {
    private val customThemes = loaded.themes.associateBy(Theme::name)
    var activeTheme: Theme = theme(initialThemeName) ?: builtinThemes.getValue("dark")
        private set

    fun theme(name: String): Theme? = customThemes[name] ?: builtinThemes[name]

    fun setTheme(
        name: String,
        persist: Boolean = true,
    ): ThemeResult {
        val selected =
            theme(name)
                ?: run {
                    activeTheme = builtinThemes.getValue("dark")
                    return ThemeResult(false, "Theme not found: $name")
                }
        activeTheme = selected
        if (persist) {
            settings.setTheme(name)
        }
        return ThemeResult(true)
    }

    fun setThemeInstance(theme: Theme): ThemeResult {
        activeTheme = theme
        return ThemeResult(true)
    }

    fun available(): List<ThemeInfo> = availableThemes(loaded.themes)

    fun extensionJson(): JsonObject =
        buildJsonObject {
            put("theme", activeTheme.extensionJson())
            put(
                "themes",
                buildJsonArray {
                    available().forEach { info ->
                        val theme = theme(info.name) ?: return@forEach
                        add(
                            buildJsonObject {
                                put("name", info.name)
                                if (info.path == null) put("path", JsonNull) else put("path", info.path.toString())
                                put("theme", theme.extensionJson())
                            },
                        )
                    }
                },
            )
        }
}

internal fun parseAutoThemeSetting(themeSetting: String?): AutoThemeSetting? {
    if (themeSetting == null) return null
    val slashIndex = themeSetting.indexOf('/')
    if (slashIndex < 0 || themeSetting.indexOf('/', slashIndex + 1) >= 0) {
        return null
    }
    val lightTheme = themeSetting.substring(0, slashIndex).trim()
    val darkTheme = themeSetting.substring(slashIndex + 1).trim()
    if (lightTheme.isEmpty() || darkTheme.isEmpty()) {
        return null
    }
    return AutoThemeSetting(lightTheme, darkTheme)
}

internal fun resolveThemeSetting(
    themeSetting: String?,
    terminalTheme: TerminalTheme,
): String? {
    parseAutoThemeSetting(themeSetting)?.let { setting ->
        return if (terminalTheme == TerminalTheme.LIGHT) setting.lightTheme else setting.darkTheme
    }
    if (themeSetting?.contains('/') == true) return null
    return themeSetting
}

internal fun loadThemeFromPath(
    path: Path,
    mode: ThemeColorMode = detectThemeColorMode(),
    sourceInfo: ResourceSourceInfo? = null,
): Theme {
    val normalized = path.toAbsolutePath().normalize()
    val value =
        try {
            themeJson.parseToJsonElement(Files.readString(normalized)).jsonObject
        } catch (error: Exception) {
            error("Failed to parse theme $normalized: $error")
        }
    return createTheme(
        value = value,
        label = normalized.toString(),
        mode = mode,
        sourcePath = normalized,
        sourceInfo = sourceInfo?.copy(path = normalized),
    )
}

internal fun readBuiltinThemeJson(name: String): JsonObject {
    require(name == "dark" || name == "light") { "Unknown built-in theme: $name" }
    val resource = "/works/earendil/pi/codingagent/theme/$name.json"
    return checkNotNull(Theme::class.java.getResourceAsStream(resource)) {
        "Bundled theme is missing: $resource"
    }.bufferedReader().use { reader ->
        themeJson.parseToJsonElement(reader.readText()).jsonObject
    }
}

internal fun loadThemes(
    cwd: Path,
    agentDir: Path = defaultAgentDirectory(),
    projectTrusted: Boolean,
    themePaths: List<String> = emptyList(),
    noThemes: Boolean = false,
    homeDir: Path = defaultHomeDirectory(),
    resolvedPackageResources: ResolvedPackageResources? = null,
): LoadedThemes {
    val normalizedCwd = cwd.toAbsolutePath().normalize()
    val packageResources =
        resolvedPackageResources
            ?: resolvePackageResources(
                cwd = normalizedCwd,
                agentDir = agentDir,
                projectTrusted = projectTrusted,
                homeDir = homeDir,
            )
    val resources =
        buildList {
            if (!noThemes) {
                addAll(packageResources.themes.filter(ResolvedResource::enabled))
            }
            themePaths.forEach { raw ->
                val path = resolveThemePath(normalizedCwd, raw)
                add(
                    ResolvedResource(
                        path = path,
                        enabled = true,
                        sourceInfo =
                            ResourceSourceInfo(
                                path = path,
                                source = "cli",
                                scope = "temporary",
                                origin = "top-level",
                                baseDir = if (Files.isDirectory(path)) path else path.parent,
                            ),
                    ),
                )
            }
        }
    val themes = mutableListOf<Theme>()
    val diagnostics = mutableListOf<ResourceDiagnostic>()
    resources
        .distinctBy { canonicalPath(it.path) }
        .forEach { resource ->
            loadThemeResource(resource, themes, diagnostics)
        }

    val winners = linkedMapOf<String, Theme>()
    themes.forEach { theme ->
        val existing = winners[theme.name]
        if (existing == null) {
            winners[theme.name] = theme
        } else {
            val loserPath = requireNotNull(theme.sourcePath)
            diagnostics +=
                ResourceDiagnostic(
                    type = ResourceDiagnosticType.COLLISION,
                    message = "name \"${theme.name}\" collision",
                    path = loserPath,
                    collision =
                        ResourceCollision(
                            resourceType = "theme",
                            name = theme.name,
                            winnerPath = requireNotNull(existing.sourcePath),
                            loserPath = loserPath,
                        ),
                )
        }
    }
    return LoadedThemes(winners.values.toList(), diagnostics)
}

internal fun createThemeRegistry(
    cwd: Path,
    agentDir: Path = defaultAgentDirectory(),
    projectTrusted: Boolean,
    themePaths: List<String> = emptyList(),
    noThemes: Boolean = false,
    terminalTheme: TerminalTheme = detectTerminalTheme(),
    colorMode: ThemeColorMode = detectThemeColorMode(),
    homeDir: Path = defaultHomeDirectory(),
    resolvedPackageResources: ResolvedPackageResources? = null,
): ThemeRegistry {
    val settings = SettingsStore(cwd, agentDir, projectTrusted)
    val loaded =
        loadThemes(
            cwd = cwd,
            agentDir = agentDir,
            projectTrusted = projectTrusted,
            themePaths = themePaths,
            noThemes = noThemes,
            homeDir = homeDir,
            resolvedPackageResources = resolvedPackageResources,
        )
    val builtins =
        listOf("dark", "light").associateWith { name ->
            createTheme(
                value = readBuiltinThemeJson(name),
                label = name,
                mode = colorMode,
                sourcePath = null,
                sourceInfo = null,
            )
        }
    val requested =
        resolveThemeSetting(settings.mergedThemeSetting(), terminalTheme)
            ?: terminalTheme.wireName
    return ThemeRegistry(loaded, builtins, settings, requested)
}

internal fun createBuiltinTheme(
    name: String,
    colorMode: ThemeColorMode = ThemeColorMode.TRUECOLOR,
): Theme =
    createTheme(
        value = readBuiltinThemeJson(name),
        label = name,
        mode = colorMode,
        sourcePath = null,
        sourceInfo = null,
    )

internal fun availableThemes(customThemes: List<Theme>): List<ThemeInfo> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<ThemeInfo>()
    listOf("dark", "light").forEach { name ->
        if (seen.add(name)) {
            result += ThemeInfo(name, null, builtin = true)
        }
    }
    customThemes.forEach { theme ->
        if (seen.add(theme.name)) {
            result += ThemeInfo(theme.name, theme.sourcePath, builtin = false)
        }
    }
    return result.sortedBy(ThemeInfo::name)
}

internal fun detectThemeColorMode(environment: Map<String, String> = System.getenv()): ThemeColorMode {
    val colorTerm = environment["COLORTERM"].orEmpty().lowercase()
    val term = environment["TERM"].orEmpty().lowercase()
    return if ("truecolor" in colorTerm || "24bit" in colorTerm || "truecolor" in term) {
        ThemeColorMode.TRUECOLOR
    } else {
        ThemeColorMode.COLOR_256
    }
}

internal fun detectTerminalTheme(environment: Map<String, String> = System.getenv()): TerminalTheme {
    val background =
        environment["COLORFGBG"]
            ?.split(';')
            ?.asReversed()
            ?.firstNotNullOfOrNull { value ->
                value.trim().toIntOrNull()?.takeIf { it in 0..255 }
            }
            ?: return TerminalTheme.DARK
    val rgb = ansi256ToRgb(background)
    val luminance =
        0.2126 * linearChannel(rgb.first) +
            0.7152 * linearChannel(rgb.second) +
            0.0722 * linearChannel(rgb.third)
    return if (luminance >= 0.5) TerminalTheme.LIGHT else TerminalTheme.DARK
}

private fun loadThemeResource(
    resource: ResolvedResource,
    themes: MutableList<Theme>,
    diagnostics: MutableList<ResourceDiagnostic>,
) {
    val path = resource.path.toAbsolutePath().normalize()
    if (!Files.exists(path)) {
        diagnostics +=
            ResourceDiagnostic(
                ResourceDiagnosticType.WARNING,
                "theme path does not exist",
                path,
            )
        return
    }
    when {
        Files.isDirectory(path) ->
            Files.list(path).use { entries ->
                entries
                    .filter { entry -> Files.isRegularFile(entry) && entry.fileName.toString().endsWith(".json") }
                    .sorted()
                    .forEach { entry ->
                        loadThemeFile(entry, resource.sourceInfo.copy(path = entry), themes, diagnostics)
                    }
            }

        Files.isRegularFile(path) && path.fileName.toString().endsWith(".json") ->
            loadThemeFile(path, resource.sourceInfo.copy(path = path), themes, diagnostics)

        else ->
            diagnostics +=
                ResourceDiagnostic(
                    ResourceDiagnosticType.WARNING,
                    "theme path is not a json file",
                    path,
                )
    }
}

private fun loadThemeFile(
    path: Path,
    sourceInfo: ResourceSourceInfo,
    themes: MutableList<Theme>,
    diagnostics: MutableList<ResourceDiagnostic>,
) {
    runCatching {
        loadThemeFromPath(path, sourceInfo = sourceInfo)
    }.onSuccess(themes::add)
        .onFailure { error ->
            diagnostics +=
                ResourceDiagnostic(
                    ResourceDiagnosticType.WARNING,
                    error.message ?: "failed to load theme",
                    path,
                )
        }
}

private fun createTheme(
    value: JsonObject,
    label: String,
    mode: ThemeColorMode,
    sourcePath: Path?,
    sourceInfo: ResourceSourceInfo?,
): Theme {
    validateKeys(value, TOP_LEVEL_KEYS, "theme \"$label\"")
    val name = value["name"]?.jsonPrimitive?.takeIf(JsonPrimitive::isString)?.content
        ?: error("Invalid theme \"$label\": /name must be string")
    if ('/' in name) {
        error(
            "Invalid theme name \"$name\": theme names cannot contain \"/\" because it is reserved " +
                "for automatic light/dark theme settings.",
        )
    }
    val vars =
        value["vars"]?.jsonObject?.mapValues { (key, color) ->
            parseColorValue(color, "/vars/$key")
        }.orEmpty()
    val colorsObject = value["colors"]?.jsonObject
        ?: error("Invalid theme \"$label\": Missing required color tokens:\n${REQUIRED_COLOR_TOKENS.sorted().joinToString("\n")}")
    validateKeys(colorsObject, ALL_COLOR_TOKENS, "theme \"$label\" colors")
    val missing = REQUIRED_COLOR_TOKENS - colorsObject.keys
    if (missing.isNotEmpty()) {
        error(
            "Invalid theme \"$label\":\n\nMissing required color tokens:\n" +
                missing.sorted().joinToString("\n") { "  - $it" },
        )
    }
    val parsedExport =
        value["export"]?.jsonObject?.mapValues { (key, color) ->
            parseColorValue(color, "/export/$key")
        }.orEmpty()
    value["export"]?.jsonObject?.let { export ->
        validateKeys(export, EXPORT_COLOR_TOKENS, "theme \"$label\" export")
    }
    val parsed =
        colorsObject.mapValues { (key, color) ->
            parseColorValue(color, "/colors/$key")
        }.toMutableMap()
    parsed.putIfAbsent("thinkingMax", parsed.getValue("thinkingXhigh"))
    val resolved =
        parsed.mapValues { (_, color) ->
            resolveColor(color, vars)
        }
    val foreground = linkedMapOf<String, String>()
    val background = linkedMapOf<String, String>()
    val defaultText = if (name == "light") "#000000" else "#e5e5e7"
    val cssColors =
        resolved.mapValues { (_, color) ->
            resolvedColorToCss(color, defaultText)
        }
    val exportColors =
        parsedExport.mapValues { (_, color) ->
            resolvedExportColorToCss(resolveColor(color, vars))
        }
    resolved.forEach { (key, color) ->
        if (key in BACKGROUND_COLOR_TOKENS) {
            background[key] = backgroundAnsi(color, mode)
        } else {
            foreground[key] = foregroundAnsi(color, mode)
        }
    }
    return Theme(
        name = name,
        sourcePath = sourcePath,
        sourceInfo = sourceInfo,
        colorMode = mode,
        foreground = foreground,
        background = background,
        cssColors = cssColors,
        exportColors = exportColors,
    )
}

private fun validateKeys(
    value: JsonObject,
    allowed: Set<String>,
    label: String,
) {
    val extras = value.keys - allowed
    require(extras.isEmpty()) {
        "Invalid $label: unsupported properties: ${extras.sorted().joinToString()}"
    }
}

private fun parseColorValue(
    value: JsonElement,
    path: String,
): ColorValue {
    val primitive = value as? JsonPrimitive
        ?: error("Invalid theme color $path: expected string or integer")
    if (primitive.isString) {
        return ColorValue.Text(primitive.content)
    }
    val index = primitive.intOrNull
        ?: error("Invalid theme color $path: expected string or integer")
    require(index in 0..255) {
        "Invalid theme color $path: integer must be in 0..255"
    }
    return ColorValue.Index(index)
}

private fun resolveColor(
    value: ColorValue,
    vars: Map<String, ColorValue>,
    visited: Set<String> = emptySet(),
): ResolvedColor =
    when (value) {
        is ColorValue.Index -> ResolvedColor.Index(value.value)
        is ColorValue.Text -> {
            when {
                value.value.isEmpty() -> ResolvedColor.TerminalDefault
                value.value.startsWith('#') -> parseHex(value.value)
                value.value in visited ->
                    error("Circular variable reference detected: ${value.value}")

                value.value !in vars ->
                    error("Variable reference not found: ${value.value}")

                else ->
                    resolveColor(
                        vars.getValue(value.value),
                        vars,
                        visited + value.value,
                    )
            }
        }
    }

private fun parseHex(value: String): ResolvedColor.Rgb {
    val match = HEX_COLOR.matchEntire(value)
        ?: error("Invalid hex color: $value")
    val hex = match.groupValues[1]
    return ResolvedColor.Rgb(
        hex.substring(0, 2).toInt(16),
        hex.substring(2, 4).toInt(16),
        hex.substring(4, 6).toInt(16),
        value,
    )
}

private fun resolvedColorToCss(
    color: ResolvedColor,
    defaultText: String,
): String =
    when (color) {
        ResolvedColor.TerminalDefault -> defaultText
        is ResolvedColor.Index -> ansi256ToHex(color.value)
        is ResolvedColor.Rgb -> color.css
    }

private fun resolvedExportColorToCss(color: ResolvedColor): String? =
    when (color) {
        ResolvedColor.TerminalDefault -> null
        is ResolvedColor.Index -> ansi256ToHex(color.value)
        is ResolvedColor.Rgb -> color.css
    }

private fun ansiColorToCss(value: String): String? {
    val rgb = ANSI_RGB.matchEntire(value)
    if (rgb != null) {
        return rgbToHex(
            rgb.groupValues[1].toInt(),
            rgb.groupValues[2].toInt(),
            rgb.groupValues[3].toInt(),
        )
    }
    val index = ANSI_INDEX.matchEntire(value)
    if (index != null) {
        return ansi256ToHex(index.groupValues[1].toInt())
    }
    return null
}

private fun ansi256ToHex(index: Int): String {
    val (red, green, blue) = ansi256ToRgb(index)
    return rgbToHex(red, green, blue)
}

private fun rgbToHex(
    red: Int,
    green: Int,
    blue: Int,
): String = "#%02x%02x%02x".format(red, green, blue)

private fun foregroundAnsi(
    color: ResolvedColor,
    mode: ThemeColorMode,
): String =
    when (color) {
        ResolvedColor.TerminalDefault -> "\u001B[39m"
        is ResolvedColor.Index -> "\u001B[38;5;${color.value}m"
        is ResolvedColor.Rgb ->
            if (mode == ThemeColorMode.TRUECOLOR) {
                "\u001B[38;2;${color.red};${color.green};${color.blue}m"
            } else {
                "\u001B[38;5;${rgbTo256(color.red, color.green, color.blue)}m"
            }
    }

private fun backgroundAnsi(
    color: ResolvedColor,
    mode: ThemeColorMode,
): String =
    when (color) {
        ResolvedColor.TerminalDefault -> "\u001B[49m"
        is ResolvedColor.Index -> "\u001B[48;5;${color.value}m"
        is ResolvedColor.Rgb ->
            if (mode == ThemeColorMode.TRUECOLOR) {
                "\u001B[48;2;${color.red};${color.green};${color.blue}m"
            } else {
                "\u001B[48;5;${rgbTo256(color.red, color.green, color.blue)}m"
            }
    }

private fun rgbTo256(
    red: Int,
    green: Int,
    blue: Int,
): Int {
    val redIndex = closestIndex(red, COLOR_CUBE_VALUES)
    val greenIndex = closestIndex(green, COLOR_CUBE_VALUES)
    val blueIndex = closestIndex(blue, COLOR_CUBE_VALUES)
    val cubeRed = COLOR_CUBE_VALUES[redIndex]
    val cubeGreen = COLOR_CUBE_VALUES[greenIndex]
    val cubeBlue = COLOR_CUBE_VALUES[blueIndex]
    val cubeIndex = 16 + 36 * redIndex + 6 * greenIndex + blueIndex
    val cubeDistance = colorDistance(red, green, blue, cubeRed, cubeGreen, cubeBlue)

    val gray = (0.299 * red + 0.587 * green + 0.114 * blue).roundToInt()
    val grayIndex = closestIndex(gray, GRAY_VALUES)
    val grayValue = GRAY_VALUES[grayIndex]
    val grayDistance = colorDistance(red, green, blue, grayValue, grayValue, grayValue)
    val spread = maxOf(red, green, blue) - minOf(red, green, blue)
    return if (spread < 10 && grayDistance < cubeDistance) 232 + grayIndex else cubeIndex
}

private fun closestIndex(
    value: Int,
    choices: List<Int>,
): Int =
    choices.indices.minBy { index -> abs(value - choices[index]) }

private fun colorDistance(
    redA: Int,
    greenA: Int,
    blueA: Int,
    redB: Int,
    greenB: Int,
    blueB: Int,
): Double {
    val red = redA - redB
    val green = greenA - greenB
    val blue = blueA - blueB
    return red * red * 0.299 + green * green * 0.587 + blue * blue * 0.114
}

private fun ansi256ToRgb(index: Int): Triple<Int, Int, Int> {
    val basic =
        listOf(
            Triple(0, 0, 0),
            Triple(128, 0, 0),
            Triple(0, 128, 0),
            Triple(128, 128, 0),
            Triple(0, 0, 128),
            Triple(128, 0, 128),
            Triple(0, 128, 128),
            Triple(192, 192, 192),
            Triple(128, 128, 128),
            Triple(255, 0, 0),
            Triple(0, 255, 0),
            Triple(255, 255, 0),
            Triple(0, 0, 255),
            Triple(255, 0, 255),
            Triple(0, 255, 255),
            Triple(255, 255, 255),
        )
    if (index < 16) return basic[index]
    if (index < 232) {
        val cube = index - 16
        return Triple(
            COLOR_CUBE_VALUES[cube / 36],
            COLOR_CUBE_VALUES[(cube % 36) / 6],
            COLOR_CUBE_VALUES[cube % 6],
        )
    }
    val gray = 8 + (index - 232) * 10
    return Triple(gray, gray, gray)
}

private fun linearChannel(channel: Int): Double {
    val value = channel / 255.0
    return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
}

private fun resolveThemePath(
    cwd: Path,
    raw: String,
): Path {
    val expanded =
        if (raw == "~" || raw.startsWith("~/")) {
            defaultHomeDirectory().resolve(raw.removePrefix("~/"))
        } else {
            Path.of(raw)
        }
    return (if (expanded.isAbsolute) expanded else cwd.resolve(expanded)).toAbsolutePath().normalize()
}

private val themeJson =
    Json {
        ignoreUnknownKeys = false
    }

private val TOP_LEVEL_KEYS = setOf("\$schema", "name", "vars", "colors", "export")
private val BACKGROUND_COLOR_TOKENS =
    setOf(
        "selectedBg",
        "userMessageBg",
        "customMessageBg",
        "toolPendingBg",
        "toolSuccessBg",
        "toolErrorBg",
    )
private val FOREGROUND_COLOR_TOKENS =
    setOf(
        "accent",
        "border",
        "borderAccent",
        "borderMuted",
        "success",
        "error",
        "warning",
        "muted",
        "dim",
        "text",
        "thinkingText",
        "userMessageText",
        "customMessageText",
        "customMessageLabel",
        "toolTitle",
        "toolOutput",
        "mdHeading",
        "mdLink",
        "mdLinkUrl",
        "mdCode",
        "mdCodeBlock",
        "mdCodeBlockBorder",
        "mdQuote",
        "mdQuoteBorder",
        "mdHr",
        "mdListBullet",
        "toolDiffAdded",
        "toolDiffRemoved",
        "toolDiffContext",
        "syntaxComment",
        "syntaxKeyword",
        "syntaxFunction",
        "syntaxVariable",
        "syntaxString",
        "syntaxNumber",
        "syntaxType",
        "syntaxOperator",
        "syntaxPunctuation",
        "thinkingOff",
        "thinkingMinimal",
        "thinkingLow",
        "thinkingMedium",
        "thinkingHigh",
        "thinkingXhigh",
        "thinkingMax",
        "bashMode",
    )
private val ALL_COLOR_TOKENS = FOREGROUND_COLOR_TOKENS + BACKGROUND_COLOR_TOKENS
private val REQUIRED_COLOR_TOKENS = ALL_COLOR_TOKENS - "thinkingMax"
private val EXPORT_COLOR_TOKENS = setOf("pageBg", "cardBg", "infoBg")
private val HEX_COLOR = Regex("^#([0-9a-fA-F]{6})$")
private val ANSI_RGB = Regex("^\\u001B\\[(?:38|48);2;(\\d+);(\\d+);(\\d+)m$")
private val ANSI_INDEX = Regex("^\\u001B\\[(?:38|48);5;(\\d+)m$")
private val COLOR_CUBE_VALUES = listOf(0, 95, 135, 175, 215, 255)
private val GRAY_VALUES = List(24) { index -> 8 + index * 10 }
