package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

fun main() {
    val root = canonicalPath(Files.createTempDirectory("pi-theme-oracle-"))
    val agentDir = Files.createDirectories(root.resolve("agent"))
    val cwd = Files.createDirectories(root.resolve("workspace").resolve("project"))
    Files.createDirectory(cwd.resolve(".git"))
    val packageRoot = root.resolve("packages").resolve("theme-package")
    val extensionRoot = root.resolve("extension-themes")
    val builtinDark = readBuiltinThemeJson("dark")

    fun themeFile(
        path: Path,
        name: String,
        accent: String,
        mutate: (MutableMap<String, Any?>) -> Unit = {},
    ): Path {
        val value = mutableThemeMap(builtinDark)
        value["name"] = name
        @Suppress("UNCHECKED_CAST")
        (value.getValue("colors") as MutableMap<String, Any?>)["accent"] = accent
        mutate(value)
        write(path, oracleJson.encodeToString(JsonObject.serializer(), themeMapJson(value)))
        return path
    }

    try {
        val contractPath =
            themeFile(root.resolve("contract.json"), "contract", "alias") { theme ->
                @Suppress("UNCHECKED_CAST")
                val vars = theme.getValue("vars") as MutableMap<String, Any?>
                vars["primary"] = "#123456"
                vars["alias"] = "primary"
                vars["terminalDefault"] = ""
                vars["palette"] = 244
                @Suppress("UNCHECKED_CAST")
                val colors = theme.getValue("colors") as MutableMap<String, Any?>
                colors["text"] = "terminalDefault"
                colors["selectedBg"] = "#abcdef"
                colors["customMessageBg"] = 17
                colors["thinkingXhigh"] = "#654321"
                colors.remove("thinkingMax")
                theme["export"] =
                    linkedMapOf(
                        "pageBg" to "alias",
                        "cardBg" to "palette",
                        "infoBg" to "terminalDefault",
                    )
            }
        val truecolor = loadThemeFromPath(contractPath, ThemeColorMode.TRUECOLOR)
        val palette = loadThemeFromPath(contractPath, ThemeColorMode.COLOR_256)

        val projectShared =
            themeFile(cwd.resolve(".pi").resolve("themes").resolve("project-shared.json"), "shared", "#220000")
        themeFile(cwd.resolve(".pi").resolve("themes").resolve("project-only.json"), "project-only", "#221100")
        val userShared =
            themeFile(agentDir.resolve("themes").resolve("user-shared.json"), "shared", "#110000")
        themeFile(agentDir.resolve("themes").resolve("renamed-file.json"), "user-only", "#112200")
        themeFile(packageRoot.resolve("themes").resolve("package.json"), "package-only", "#003300")
        write(
            packageRoot.resolve("package.json"),
            """{"name":"theme-package","version":"1.0.0","pi":{"themes":["themes/*.json"]}}""",
        )
        write(
            agentDir.resolve("settings.json"),
            """{"theme":"user-only","packages":[${oracleJson.encodeToString(packageRoot.toString())}]}""",
        )
        write(cwd.resolve(".pi").resolve("settings.json"), """{"theme":"shared"}""")
        val extensionShared =
            themeFile(extensionRoot.resolve("extension-shared.json"), "shared", "#440000")
        themeFile(extensionRoot.resolve("extension-only.json"), "extension-only", "#004400")

        val baseResources =
            resolvePackageResources(
                cwd = cwd,
                agentDir = agentDir,
                projectTrusted = true,
                homeDir = root.resolve("home"),
            )
        val extensionResources =
            ResolvedPackageResources(
                themes =
                    listOf(
                        ResolvedResource(
                            extensionRoot,
                            enabled = true,
                            sourceInfo =
                                ResourceSourceInfo(
                                    extensionRoot,
                                    "extension:theme-oracle",
                                    "temporary",
                                    "top-level",
                                    extensionRoot,
                                ),
                        ),
                    ),
            )
        val loaded =
            loadThemes(
                cwd = cwd,
                agentDir = agentDir,
                projectTrusted = true,
                resolvedPackageResources = baseResources.merge(extensionResources),
            )
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)

        val output =
            buildJsonObject {
                put(
                    "contract",
                    buildJsonObject {
                        put("name", truecolor.name)
                        put("sourcePath", relativeOraclePath(root, truecolor.sourcePath))
                        put(
                            "truecolor",
                            buildJsonObject {
                                put("mode", truecolor.colorMode.wireName)
                                put("accentAnsi", truecolor.getFgAnsi("accent"))
                                put("defaultTextAnsi", truecolor.getFgAnsi("text"))
                                put("selectedBgAnsi", truecolor.getBgAnsi("selectedBg"))
                                put("customBgAnsi", truecolor.getBgAnsi("customMessageBg"))
                                put("accentText", truecolor.fg("accent", "accent"))
                                put("selectedText", truecolor.bg("selectedBg", "selected"))
                            },
                        )
                        put(
                            "palette",
                            buildJsonObject {
                                put("mode", palette.colorMode.wireName)
                                put("accentAnsi", palette.getFgAnsi("accent"))
                                put("selectedBgAnsi", palette.getBgAnsi("selectedBg"))
                            },
                        )
                        put(
                            "thinkingFallback",
                            truecolor.getThinkingBorderColor("max")("border") ==
                                truecolor.getThinkingBorderColor("xhigh")("border"),
                        )
                    },
                )
                put(
                    "autoTheme",
                    buildJsonObject {
                        put("valid", autoThemeJson(parseAutoThemeSetting(" light-custom / dark-custom ")))
                        put(
                            "invalid",
                            JsonArray(
                                listOf(
                                    parseAutoThemeSetting("dark"),
                                    parseAutoThemeSetting("light/dark/extra"),
                                    parseAutoThemeSetting("/dark"),
                                ).map(::autoThemeJson),
                            ),
                        )
                        put(
                            "resolved",
                            buildJsonObject {
                                putNullable("light", resolveThemeSetting("light-custom/dark-custom", TerminalTheme.LIGHT))
                                putNullable("dark", resolveThemeSetting("light-custom/dark-custom", TerminalTheme.DARK))
                                putNullable("fixed", resolveThemeSetting("shared", TerminalTheme.LIGHT))
                                putNullable("invalid", resolveThemeSetting("light/dark/extra", TerminalTheme.DARK))
                            },
                        )
                    },
                )
                put(
                    "resources",
                    buildJsonObject {
                        putNullable("selectedSetting", settings.mergedThemeSetting())
                        put(
                            "themes",
                            buildJsonArray {
                                loaded.themes.forEach { theme ->
                                    add(
                                        buildJsonObject {
                                            put("name", theme.name)
                                            put("path", relativeOraclePath(root, theme.sourcePath))
                                            put(
                                                "accentAnsi",
                                                loadThemeFromPath(
                                                    requireNotNull(theme.sourcePath),
                                                    ThemeColorMode.TRUECOLOR,
                                                )
                                                    .getFgAnsi("accent"),
                                            )
                                            put(
                                                "source",
                                                buildJsonObject {
                                                    put(
                                                        "source",
                                                        theme.sourceInfo
                                                            ?.source
                                                            .orEmpty()
                                                            .replace(root.toString(), "<ROOT>"),
                                                    )
                                                    put("scope", theme.sourceInfo?.scope.orEmpty())
                                                    put("origin", theme.sourceInfo?.origin.orEmpty())
                                                    put(
                                                        "baseDir",
                                                        relativeOraclePath(root, theme.sourceInfo?.baseDir),
                                                    )
                                                },
                                            )
                                        },
                                    )
                                }
                            },
                        )
                        put(
                            "collisions",
                            buildJsonArray {
                                loaded.diagnostics
                                    .mapNotNull(ResourceDiagnostic::collision)
                                    .filter { it.resourceType == "theme" }
                                    .forEach { collision ->
                                        add(
                                            buildJsonObject {
                                                put("name", collision.name)
                                                put("winnerPath", relativeOraclePath(root, collision.winnerPath))
                                                put("loserPath", relativeOraclePath(root, collision.loserPath))
                                            },
                                        )
                                    }
                            },
                        )
                        put(
                            "available",
                            buildJsonArray {
                                availableThemes(loaded.themes).forEach { theme ->
                                    add(
                                        buildJsonObject {
                                            put("name", theme.name)
                                            if (theme.builtin) {
                                                put("path", "<builtin>")
                                            } else {
                                                put("path", relativeOraclePath(root, theme.path))
                                            }
                                        },
                                    )
                                }
                            },
                        )
                        put(
                            "expectedWinnerPaths",
                            buildJsonObject {
                                put("project", relativeOraclePath(root, projectShared))
                                put("userLoser", relativeOraclePath(root, userShared))
                                put("extensionLoser", relativeOraclePath(root, extensionShared))
                            },
                        )
                    },
                )
                put(
                    "invalid",
                    buildJsonArray {
                        add(invalidCase(root, builtinDark, "missing-colors") { colors -> colors.remove("accent") })
                        add(
                            invalidCase(root, builtinDark, "circular-vars") { colors ->
                                colors["accent"] = "a"
                            },
                        )
                        add(invalidCase(root, builtinDark, "missing-var") { colors -> colors["accent"] = "not-defined" })
                        add(
                            invalidCase(root, builtinDark, "invalid-name") { _, theme ->
                                theme["name"] = "light/dark"
                            },
                        )
                        add(invalidCase(root, builtinDark, "invalid-hex") { colors -> colors["accent"] = "#xyz" })
                        add(invalidCase(root, builtinDark, "invalid-index") { colors -> colors["accent"] = 256 })
                    },
                )
            }
        println(oracleJson.encodeToString(JsonObject.serializer(), output))
    } finally {
        root.toFile().deleteRecursively()
    }
}

private fun invalidCase(
    root: Path,
    builtin: JsonObject,
    name: String,
    mutate: (MutableMap<String, Any?>, MutableMap<String, Any?>) -> Unit,
): JsonObject {
    val theme = mutableThemeMap(builtin)
    theme["name"] = name
    @Suppress("UNCHECKED_CAST")
    val colors = theme.getValue("colors") as MutableMap<String, Any?>
    if (name == "circular-vars") {
        theme["vars"] = linkedMapOf("a" to "b", "b" to "a")
    }
    mutate(colors, theme)
    val path = root.resolve("invalid").resolve("$name.json")
    write(path, oracleJson.encodeToString(JsonObject.serializer(), themeMapJson(theme)))
    val error =
        runCatching { loadThemeFromPath(path, ThemeColorMode.TRUECOLOR) }
            .fold(
                onSuccess = { "accepted" },
                onFailure = ::classifyThemeError,
            )
    return buildJsonObject {
        put("name", name)
        put("error", error)
    }
}

private fun invalidCase(
    root: Path,
    builtin: JsonObject,
    name: String,
    mutate: (MutableMap<String, Any?>) -> Unit,
): JsonObject = invalidCase(root, builtin, name) { colors, _ -> mutate(colors) }

private fun classifyThemeError(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        "Missing required color tokens" in message -> "missing-required-colors"
        "Circular variable reference" in message -> "circular-variable"
        "Variable reference not found" in message -> "missing-variable"
        "cannot contain" in message -> "invalid-name"
        "Invalid hex color" in message -> "invalid-color"
        "0..255" in message || "maximum" in message -> "invalid-index"
        else -> "other"
    }
}

private fun autoThemeJson(value: AutoThemeSetting?): kotlinx.serialization.json.JsonElement =
    value?.let {
        buildJsonObject {
            put("lightTheme", it.lightTheme)
            put("darkTheme", it.darkTheme)
        }
    } ?: JsonNull

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    name: String,
    value: String?,
) {
    if (value == null) put(name, JsonNull) else put(name, value)
}

private fun relativeOraclePath(
    root: Path,
    path: Path?,
): kotlinx.serialization.json.JsonElement {
    if (path == null) return JsonNull
    val normalized = canonicalPath(path)
    return if (normalized.startsWith(root)) {
        JsonPrimitive(root.relativize(normalized).toString().replace('\\', '/'))
    } else {
        JsonPrimitive(normalized.toString().replace('\\', '/'))
    }
}

private fun mutableThemeMap(value: JsonObject): MutableMap<String, Any?> =
    value.mapValuesTo(linkedMapOf()) { (_, element) ->
        when (element) {
            is JsonObject -> mutableThemeMap(element)
            is JsonPrimitive ->
                if (element.isString) {
                    element.content
                } else {
                    element.content.toIntOrNull() ?: element.content
                }

            else -> error("Unsupported theme fixture value: $element")
        }
    }

private fun themeMapJson(value: Map<String, Any?>): JsonObject =
    buildJsonObject {
        value.forEach { (name, entry) ->
            put(
                name,
                when (entry) {
                    is Map<*, *> ->
                        themeMapJson(
                            entry.entries.associate { (key, nested) -> key.toString() to nested },
                        )

                    is Number -> JsonPrimitive(entry.toInt())
                    null -> JsonNull
                    else -> JsonPrimitive(entry.toString())
                },
            )
        }
    }

private fun write(
    path: Path,
    content: String,
) {
    Files.createDirectories(path.parent)
    Files.writeString(path, content)
}

private val oracleJson =
    kotlinx.serialization.json.Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
