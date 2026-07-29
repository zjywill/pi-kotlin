package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun main(args: Array<String>) {
    val fixtureRoot =
        Path
            .of(args.firstOrNull() ?: "migration/fixtures/extension-shortcuts")
            .toAbsolutePath()
            .normalize()
    val paths =
        Files
            .list(fixtureRoot)
            .use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".ts") }
                    .sorted()
                    .toList()
            }
    val agentDir = Files.createTempDirectory("pi-extension-shortcuts-oracle")
    val context =
        buildJsonObject {
            put("cwd", fixtureRoot.toString())
            put("mode", "tui")
            put("hasUI", true)
            put("projectTrusted", true)
            put("thinkingLevel", "off")
            put("systemPrompt", "")
        }
    val hostDiagnostics = mutableListOf<ExtensionDiagnostic>()
    val host =
        checkNotNull(
            ExtensionHost.start(
                sources =
                    paths.map { path ->
                        ExtensionSource(
                            path,
                            ResourceSourceInfo(path, "local", baseDir = path.parent),
                        )
                    },
                agentDir = agentDir,
                cwd = fixtureRoot,
                mode = ExtensionMode.TUI,
                projectTrusted = true,
                flagValues = emptyMap(),
                context = context,
                onDiagnostic = hostDiagnostics::add,
            ),
        )
    check(hostDiagnostics.isEmpty()) { hostDiagnostics.joinToString() }
    val defaults = loadExtensionShortcutKeybindings(agentDir)
    val custom =
        defaults.toMutableMap().apply {
            this["app.interrupt"] = listOf("ctrl+q")
            this["app.model.cycleForward"] = listOf("ctrl+n")
        }
    val output =
        buildJsonObject {
            put("default", shortcutScenario(host, defaults, context, paths))
            put("custom", shortcutScenario(host, custom, context, paths))
        }
    host.close()
    println(Json.encodeToString(output))
}

private fun shortcutScenario(
    host: ExtensionHost,
    keybindings: Map<String, List<String>>,
    context: JsonObject,
    paths: List<Path>,
): JsonObject {
    val resolution = resolveExtensionShortcuts(host.registrations.extensions, keybindings)
    val actions = mutableListOf<JsonObject>()
    resolution.shortcuts.forEach { (key, shortcut) ->
        host
            .invokeShortcut(shortcut.id, context)
            .actions
            .filter { action -> action.type == "ui" && action.data.stringValue("method") == "notify" }
            .forEach { action ->
                actions +=
                    buildJsonObject {
                        put("key", key)
                        put("message", action.data.stringValue("message").orEmpty())
                    }
            }
    }
    return buildJsonObject {
        put(
            "shortcuts",
            JsonArray(
                resolution.shortcuts
                    .map { (key, shortcut) ->
                        buildJsonObject {
                            put("key", key)
                            if (shortcut.description == null) {
                                put("description", JsonNull)
                            } else {
                                put("description", shortcut.description)
                            }
                            put("path", shortcut.extensionPath.fileName.toString())
                        }
                    }.sortedBy { it.getValue("key").jsonPrimitive.content },
            ),
        )
        put(
            "diagnostics",
            JsonArray(
                resolution.diagnostics
                    .map { diagnostic ->
                        paths.fold(diagnostic.error) { message, path ->
                            message.replace(path.toString(), path.fileName.toString())
                        }
                    }.sorted()
                    .map(::JsonPrimitive),
            ),
        )
        put(
            "actions",
            JsonArray(actions.sortedBy { it.getValue("key").jsonPrimitive.content }),
        )
    }
}
