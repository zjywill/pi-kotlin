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
    val fixtureRoot =
        Path
            .of(args.firstOrNull() ?: "migration/fixtures/extension-renderers")
            .toAbsolutePath()
            .normalize()
    val fixturePaths =
        Files
            .list(fixtureRoot)
            .use { paths ->
                paths
                    .filter { it.fileName.toString().endsWith(".ts") }
                    .sorted()
                    .toList()
            }
    val root = Files.createTempDirectory("pi-extension-renderers-oracle")
    val host =
        checkNotNull(
            ExtensionHost.start(
                sources =
                    fixturePaths.map { path ->
                        ExtensionSource(
                            path,
                            ResourceSourceInfo(path, "local", baseDir = fixtureRoot),
                        )
                    },
                agentDir = Files.createDirectories(root.resolve("agent")),
                cwd = fixtureRoot,
                mode = ExtensionMode.TUI,
                projectTrusted = true,
                flagValues = emptyMap(),
                context = rendererOracleContext(fixtureRoot),
            ),
        )
    host.use {
        val message =
            buildJsonObject {
                put("role", "custom")
                put("customType", "oracle-message")
                put("content", "hello")
                put("display", true)
                put("details", buildJsonObject { put("source", "oracle") })
                put("timestamp", 123)
            }
        val entry =
            buildJsonObject {
                put("type", "custom")
                put("id", "entry-1")
                put("parentId", "parent-1")
                put("timestamp", "2026-07-29T00:00:00Z")
                put("customType", "oracle-entry")
                put("data", buildJsonObject { put("value", "saved") })
            }
        val output =
            buildJsonObject {
                put("message", renderOracleValue(host, "message", "oracle-message", message))
                put("entry", renderOracleValue(host, "entry", "oracle-entry", entry))
                put(
                    "messageUndefined",
                    renderOracleValue(
                        host,
                        "message",
                        "undefined-message",
                        JsonObject(message + ("customType" to JsonPrimitive("undefined-message"))),
                    ),
                )
                put(
                    "entryUndefined",
                    renderOracleValue(
                        host,
                        "entry",
                        "undefined-entry",
                        JsonObject(entry + ("customType" to JsonPrimitive("undefined-entry"))),
                    ),
                )
                put(
                    "messageThrow",
                    renderOracleValue(
                        host,
                        "message",
                        "throw-message",
                        JsonObject(message + ("customType" to JsonPrimitive("throw-message"))),
                    ),
                )
                put(
                    "entryThrow",
                    renderOracleValue(
                        host,
                        "entry",
                        "throw-entry",
                        JsonObject(entry + ("customType" to JsonPrimitive("throw-entry"))),
                    ),
                )
            }
        println(protocolJson.encodeToString(JsonObject.serializer(), output))
    }
}

private fun renderOracleValue(
    host: ExtensionHost,
    kind: String,
    customType: String,
    value: JsonObject,
): JsonObject {
    val renderer =
        findExtensionRenderer(
            registrations = host.registrations.extensions,
            kind = kind,
            customType = customType,
        ) ?: return buildJsonObject { put("missing", true) }
    return runCatching {
        val invocation =
            host.invokeRenderer(
                kind = kind,
                rendererId = renderer.id,
                value = value,
                width = 44,
                expanded = true,
                outputPad = 2,
            )
        val result = invocation.result?.jsonObject ?: JsonObject(emptyMap())
        buildJsonObject {
            put("rendered", result["rendered"]?.jsonPrimitive?.content == "true")
            put(
                "lines",
                JsonArray(
                    result["lines"]
                        ?.jsonArray
                        .orEmpty()
                        .map { JsonPrimitive(it.jsonPrimitive.content) },
                ),
            )
        }
    }.getOrElse {
        buildJsonObject { put("threw", true) }
    }
}

private fun rendererOracleContext(cwd: Path): JsonObject =
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
    }
