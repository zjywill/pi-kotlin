package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main(args: Array<String>) {
    val fixture =
        Path
            .of(args.firstOrNull() ?: "migration/fixtures/extension-runtime/basic.ts")
            .toAbsolutePath()
            .normalize()
    val root = Files.createTempDirectory("pi-extension-runtime-oracle")
    val agentDir = Files.createDirectories(root.resolve("agent"))
    val diagnostics = mutableListOf<ExtensionDiagnostic>()
    val context = oracleExtensionContext(fixture.parent)
    val host =
        checkNotNull(
            ExtensionHost.start(
                sources =
                    listOf(
                        ExtensionSource(
                            fixture,
                            ResourceSourceInfo(
                                path = fixture,
                                source = "local",
                                scope = "temporary",
                                origin = "top-level",
                                baseDir = fixture.parent,
                            ),
                        ),
                    ),
                agentDir = agentDir,
                cwd = fixture.parent,
                mode = ExtensionMode.PRINT,
                projectTrusted = true,
                flagValues = mapOf("loud" to true),
                context = context,
                onDiagnostic = diagnostics::add,
            ),
        )
    try {
        val registration = host.registrations
        val toolRegistration = registration.tools.single { it.name == "extension_echo" }
        val toolInvocation =
            host.invokeTool(
                toolId = toolRegistration.id,
                toolCallId = "call-1",
                params =
                    buildJsonObject {
                        put("text", "hello")
                        put("suffix", "!")
                    },
                context = context,
            )
        val command =
            host.invokeCommand(
                name = "record",
                args = "checkpoint",
                context = context,
            )
        val session =
            host.emit(
                event =
                    buildJsonObject {
                        put("type", "session_start")
                        put("reason", "startup")
                    },
                context = context,
            )
        val before =
            host.emit(
                event =
                    buildJsonObject {
                        put("type", "before_agent_start")
                        put("prompt", "hello")
                        put("systemPrompt", "base")
                        put("systemPromptOptions", buildJsonObject { put("cwd", fixture.parent.toString()) })
                    },
                context = JsonObject(context + ("systemPrompt" to JsonPrimitive("base"))),
            )
        val toolCall =
            host.emit(
                event =
                    buildJsonObject {
                        put("type", "tool_call")
                        put("toolName", "bash")
                        put("toolCallId", "call-2")
                        put("input", buildJsonObject { put("block", true) })
                    },
                context = context,
            )
        val toolResult =
            host.emit(
                event =
                    buildJsonObject {
                        put("type", "tool_result")
                        put("toolName", "extension_echo")
                        put("toolCallId", "call-1")
                        put("input", buildJsonObject { put("text", "hello") })
                        put(
                            "content",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", "hello")
                                    },
                                ),
                            ),
                        )
                        put("details", JsonObject(emptyMap()))
                        put("isError", false)
                    },
                context = context,
            )
        val resources =
            host.emit(
                event =
                    buildJsonObject {
                        put("type", "resources_discover")
                        put("cwd", fixture.parent.toString())
                        put("reason", "startup")
                    },
                context = context,
            )

        val output =
            buildJsonObject {
                put(
                    "errors",
                    JsonArray(
                        diagnostics.map { diagnostic ->
                            buildJsonObject {
                                put("path", diagnostic.extensionPath)
                                put("error", diagnostic.error)
                            }
                        },
                    ),
                )
                put(
                    "registrations",
                    buildJsonObject {
                        put(
                            "tools",
                            JsonArray(
                                registration.tools.map { tool ->
                                    buildJsonObject {
                                        put("name", tool.name)
                                        put("label", tool.label)
                                        put("description", tool.description)
                                        put("parameters", tool.parameters)
                                        if (tool.executionMode == null) {
                                            put("executionMode", JsonNull)
                                        } else {
                                            put("executionMode", tool.executionMode.name.lowercase())
                                        }
                                    }
                                },
                            ),
                        )
                        put(
                            "commands",
                            JsonArray(
                                registration.commands.map { commandRegistration ->
                                    buildJsonObject {
                                        put("name", commandRegistration.name)
                                        if (commandRegistration.description == null) {
                                            put("description", JsonNull)
                                        } else {
                                            put("description", commandRegistration.description)
                                        }
                                    }
                                },
                            ),
                        )
                        put(
                            "flags",
                            JsonArray(
                                registration.flags.map { flag ->
                                    buildJsonObject {
                                        put("name", flag.name)
                                        if (flag.description == null) {
                                            put("description", JsonNull)
                                        } else {
                                            put("description", flag.description)
                                        }
                                        put("type", flag.type)
                                        put("default", flag.defaultValue ?: JsonNull)
                                        put("value", true)
                                    }
                                },
                            ),
                        )
                        put(
                            "providers",
                            JsonArray(
                                registration.providers.map { provider ->
                                    buildJsonObject {
                                        put("name", provider.getValue("name"))
                                        put("config", provider.getValue("config"))
                                    }
                                },
                            ),
                        )
                        put(
                            "events",
                            JsonArray(
                                registration.extensions
                                    .flatMap { it.events }
                                    .distinct()
                                    .sorted()
                                    .map(::JsonPrimitive),
                            ),
                        )
                    },
                )
                put(
                    "tool",
                    buildJsonObject {
                        put("result", requireNotNull(toolInvocation.result))
                        put(
                            "updates",
                            JsonArray(
                                toolInvocation.actions
                                    .filter { it.type == "tool_update" }
                                    .mapNotNull { it.data["result"] },
                            ),
                        )
                    },
                )
                put("commandActions", normalizedActions(command.actions))
                put("sessionActions", normalizedActions(session.actions))
                put("beforeAgentStart", requireNotNull(before.result))
                put("toolCall", requireNotNull(toolCall.result))
                put("toolResult", requireNotNull(toolResult.result))
                put("resourcesDiscover", requireNotNull(resources.result))
            }
        println(protocolJson.encodeToString(JsonObject.serializer(), output))
    } finally {
        host.close()
        root.toFile().deleteRecursively()
    }
}

private fun oracleExtensionContext(cwd: Path): JsonObject =
    buildJsonObject {
        put("cwd", cwd.toString())
        put("mode", "print")
        put("hasUI", false)
        put("projectTrusted", true)
        put("thinkingLevel", "off")
        put("systemPrompt", "base")
        put("activeTools", JsonArray(listOf(JsonPrimitive("extension_echo"))))
        put("allTools", JsonArray(emptyList()))
        put("isIdle", true)
        put("hasPendingMessages", false)
        put("flags", buildJsonObject { put("loud", true) })
    }

private fun normalizedActions(actions: List<ExtensionAction>): JsonArray =
    JsonArray(
        actions.map { action ->
            buildJsonObject {
                put("type", action.type)
                action.data
                    .filterKeys { it != "id" }
                    .forEach { (name, value) -> put(name, value) }
            }
        },
    )
