package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun main(args: Array<String>) {
    val fixture =
        Path
            .of(args.firstOrNull() ?: "migration/fixtures/extension-custom-ui.ts")
            .toAbsolutePath()
            .normalize()
    val root = Files.createTempDirectory("pi-extension-custom-ui-oracle")
    val diagnostics = mutableListOf<ExtensionDiagnostic>()
    val customFrames = mutableListOf<JsonObject>()
    val customInputs = ArrayDeque(listOf("\u001b[B", "\r"))
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
                agentDir = Files.createDirectories(root.resolve("agent")),
                cwd = fixture.parent,
                mode = ExtensionMode.TUI,
                projectTrusted = true,
                flagValues = emptyMap(),
                context = customUiOracleContext(fixture.parent, width = 32),
                onDiagnostic = diagnostics::add,
                onUiRequest = { request, respond ->
                    customFrames +=
                        buildJsonObject {
                            put("method", "custom")
                            put(
                                "lines",
                                JsonArray(
                                    request["lines"]
                                        ?.jsonArray
                                        .orEmpty()
                                        .map { JsonPrimitive(it.jsonPrimitive.content) },
                                ),
                            )
                        }
                    respond(
                        buildJsonObject {
                            val input = customInputs.removeFirstOrNull()
                            if (input == null) {
                                put("cancelled", true)
                            } else {
                                put("input", input)
                            }
                        },
                    )
                },
            ),
        )

    host.use {
        val startup =
            host.emit(
                event =
                    buildJsonObject {
                        put("type", "session_start")
                        put("reason", "startup")
                    },
                context = customUiOracleContext(fixture.parent, width = 32),
            )
        val refresh =
            host.invokeCommand(
                name = "refresh-clear",
                args = "",
                context = customUiOracleContext(fixture.parent, width = 40),
            )
        val custom =
            host.invokeCommand(
                name = "choose",
                args = "",
                context = customUiOracleContext(fixture.parent, width = 40),
            )
        val customActions = JsonArray(customFrames + normalizeCustomUiActions(custom.actions))
        customFrames.clear()
        customInputs.addLast("Ada")
        customInputs.addLast("\r")
        val editor =
            host.invokeCommand(
                name = "edit",
                args = "",
                context = customUiOracleContext(fixture.parent, width = 40),
            )
        check(diagnostics.isEmpty()) { diagnostics.joinToString() }
        val output =
            buildJsonObject {
                put("startup", normalizeCustomUiActions(startup.actions))
                put("refresh", normalizeCustomUiActions(refresh.actions))
                put("custom", customActions)
                put(
                    "editor",
                    JsonArray(customFrames + normalizeCustomUiActions(editor.actions)),
                )
            }
        println(protocolJson.encodeToString(JsonObject.serializer(), output))
    }
}

private fun normalizeCustomUiActions(actions: List<ExtensionAction>): JsonArray =
    JsonArray(
        actions
            .filter { it.type == "ui" }
            .map { action ->
                val method = action.data.oracleString("method").orEmpty()
                buildJsonObject {
                    put("method", method)
                    when (method) {
                        "setStatus" -> {
                            (
                                action.data.oracleString("statusKey")
                                    ?: action.data.oracleString("key")
                            )
                                ?.let { put("key", it) }
                            (
                                action.data.oracleString("statusText")
                                    ?: action.data.oracleString("text")
                            )
                                ?.let { put("text", it) }
                        }

                        "setWidget" -> {
                            action.data.oracleString("widgetKey")
                                ?.let { put("key", it) }
                            action.data["widgetLines"]
                                ?.jsonArray
                                ?.let { lines ->
                                    action.data.oracleString("widgetPlacement")
                                        ?.let { put("placement", it) }
                                    put(
                                        "lines",
                                        JsonArray(lines.map { JsonPrimitive(it.jsonPrimitive.content) }),
                                    )
                                }
                        }

                        "setHeader" ->
                            action.data["headerLines"]
                                ?.jsonArray
                                ?.let { lines ->
                                    put(
                                        "lines",
                                        JsonArray(lines.map { JsonPrimitive(it.jsonPrimitive.content) }),
                                    )
                                }

                        "setFooter" ->
                            action.data["footerLines"]
                                ?.jsonArray
                                ?.let { lines ->
                                    put(
                                        "lines",
                                        JsonArray(lines.map { JsonPrimitive(it.jsonPrimitive.content) }),
                                    )
                                }

                        "notify" -> {
                            action.data.oracleString("message")
                                ?.let { put("message", it) }
                            put("notifyType", action.data.oracleString("notifyType") ?: "info")
                        }
                    }
                }
            },
    )

private fun customUiOracleContext(
    cwd: Path,
    width: Int,
): JsonObject =
    buildJsonObject {
        put("cwd", cwd.toString())
        put("mode", "tui")
        put("hasUI", true)
        put("projectTrusted", true)
        put("thinkingLevel", "off")
        put("systemPrompt", "")
        put("activeTools", JsonArray(emptyList()))
        put("allTools", JsonArray(emptyList()))
        put("isIdle", true)
        put("hasPendingMessages", false)
        put("uiWidth", width)
    }

private fun JsonObject.oracleString(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull
