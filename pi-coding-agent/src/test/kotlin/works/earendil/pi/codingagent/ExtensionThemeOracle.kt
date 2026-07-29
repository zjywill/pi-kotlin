package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun main(args: Array<String>) {
    val fixture =
        Path
            .of(args.firstOrNull() ?: "migration/fixtures/extension-theme.ts")
            .toAbsolutePath()
            .normalize()
    val root = Files.createTempDirectory("pi-extension-theme-oracle")
    val agentDir = Files.createDirectories(root.resolve("agent"))
    val cwd = Files.createDirectories(root.resolve("project"))
    val custom = readBuiltinThemeJson("dark").toMutableMap()
    custom["name"] = JsonPrimitive("oracle")
    val colors = custom.getValue("colors").jsonObject.toMutableMap()
    colors["accent"] = JsonPrimitive("#123456")
    custom["colors"] = JsonObject(colors)
    val customPath = root.resolve("oracle.json")
    Files.writeString(
        customPath,
        protocolJson.encodeToString(JsonObject.serializer(), JsonObject(custom)),
    )
    Files.writeString(agentDir.resolve("settings.json"), """{"theme":"oracle"}""")
    val registry =
        createThemeRegistry(
            cwd = cwd,
            agentDir = agentDir,
            projectTrusted = true,
            themePaths = listOf(customPath.toString()),
            noThemes = true,
        )
    val context = extensionThemeContext(cwd, registry)
    val host =
        checkNotNull(
            ExtensionHost.start(
                sources =
                    listOf(
                        ExtensionSource(
                            fixture,
                            ResourceSourceInfo(fixture, "local", baseDir = fixture.parent),
                        ),
                    ),
                agentDir = agentDir,
                cwd = cwd,
                mode = ExtensionMode.TUI,
                projectTrusted = true,
                flagValues = emptyMap(),
                context = context,
            ),
        )
    host.use {
        val invocation = host.invokeCommand("theme-probe", "", context)
        val actions =
            JsonArray(
                invocation.actions
                    .filter { action -> action.type == "ui" }
                    .mapNotNull { action ->
                        when (action.data["method"]?.jsonPrimitive?.content) {
                            "setWidget" ->
                                buildJsonObject {
                                    put("method", "setWidget")
                                    put("key", action.data["widgetKey"]?.jsonPrimitive?.content.orEmpty())
                                    put(
                                        "lines",
                                        JsonArray(
                                            action.data["widgetLines"]
                                                ?.jsonArray
                                                .orEmpty()
                                                .map { JsonPrimitive(it.jsonPrimitive.content) },
                                        ),
                                    )
                                }

                            "notify" ->
                                buildJsonObject {
                                    put("method", "notify")
                                    put("message", action.data["message"]?.jsonPrimitive?.content.orEmpty())
                                    put(
                                        "notifyType",
                                        action.data["notifyType"]?.jsonPrimitive?.content ?: "info",
                                    )
                                }

                            else -> null
                        }
                    },
            )
        println(protocolJson.encodeToString(JsonArray.serializer(), actions))
    }
    root.toFile().deleteRecursively()
}

private fun extensionThemeContext(
    cwd: Path,
    registry: ThemeRegistry,
): JsonObject =
    extensionContextJson(
        cwd = cwd,
        mode = ExtensionMode.TUI,
        projectTrusted = true,
        model = null,
        thinkingLevel = "off",
        systemPrompt = "",
        activeTools = emptyList(),
        allTools = emptyList(),
        sessionName = null,
        sessionId = null,
        sessionFile = null,
        isIdle = true,
        hasPendingMessages = false,
        flagValues = emptyMap(),
        themeRegistry = registry,
    )
