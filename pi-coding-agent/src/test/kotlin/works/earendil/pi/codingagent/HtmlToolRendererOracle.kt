package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main(args: Array<String>) {
    val fixture =
        Path
            .of(args.firstOrNull() ?: "migration/fixtures/html-export/extension-tool.ts")
            .toAbsolutePath()
            .normalize()
    val root = Files.createTempDirectory("pi-html-tool-renderer-oracle")
    val agentDir = Files.createDirectories(root.resolve("agent"))
    val cwd = Files.createDirectories(root.resolve("project"))
    val registry =
        createThemeRegistry(
            cwd = cwd,
            agentDir = agentDir,
            projectTrusted = true,
            noThemes = true,
            terminalTheme = TerminalTheme.DARK,
            colorMode = ThemeColorMode.TRUECOLOR,
        )
    val context = htmlToolRendererContext(cwd, registry)
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
        val registration = host.registrations.tools.single { tool -> tool.name == "html_probe" }
        val call =
            renderTool(
                host = host,
                registration = registration,
                phase = "call",
                context = context,
                args = buildJsonObject { put("text", "hello") },
            )
        val content =
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "result:hello")
                    },
                ),
            )
        val details = buildJsonObject { put("source", "html-probe") }
        val collapsed =
            renderTool(
                host = host,
                registration = registration,
                phase = "result",
                context = context,
                content = content,
                details = details,
                expanded = false,
            )
        val expanded =
            renderTool(
                host = host,
                registration = registration,
                phase = "result",
                context = context,
                content = content,
                details = details,
                expanded = true,
            )
        val result =
            buildJsonObject {
                put("callHtml", ansiLinesToHtml(call))
                put(
                    "result",
                    buildJsonObject {
                        val collapsedHtml = ansiLinesToHtml(trimRenderedResultLines(collapsed))
                        val expandedHtml = ansiLinesToHtml(trimRenderedResultLines(expanded))
                        if (collapsedHtml.isNotEmpty() && collapsedHtml != expandedHtml) {
                            put("collapsed", collapsedHtml)
                        }
                        put("expanded", expandedHtml)
                    },
                )
            }
        println(protocolJson.encodeToString(JsonObject.serializer(), result))
    }
    root.toFile().deleteRecursively()
}

private fun renderTool(
    host: ExtensionHost,
    registration: ExtensionToolRegistration,
    phase: String,
    context: JsonObject,
    args: JsonObject? = null,
    content: JsonArray? = null,
    details: JsonObject? = null,
    expanded: Boolean = false,
): List<String> {
    val invocation =
        host.invokeToolRenderer(
            toolId = registration.id,
            phase = phase,
            toolCallId = "html-call",
            args = args,
            content = content,
            details = details,
            expanded = expanded,
            width = 100,
            context = context,
        )
    return requireNotNull(parseRendererLines(invocation))
}

private fun htmlToolRendererContext(
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
        activeTools = listOf("html_probe"),
        allTools = emptyList(),
        sessionName = null,
        sessionId = null,
        sessionFile = null,
        isIdle = true,
        hasPendingMessages = false,
        flagValues = emptyMap(),
        themeRegistry = registry,
    )
