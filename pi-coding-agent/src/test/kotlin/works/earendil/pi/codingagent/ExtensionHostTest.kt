package works.earendil.pi.codingagent

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtensionHostTest {
    @Test
    fun `loads TypeScript extensions and executes registrations and hooks`() =
        runTest {
            assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
            val root = Files.createTempDirectory("pi-kotlin-extension-host")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            val extension =
                root.resolve("extension.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        import { Type } from "typebox";
                        import { defineTool, type ExtensionAPI } from "@earendil-works/pi-coding-agent";

                        export default function(pi: ExtensionAPI) {
                          pi.registerFlag("plan", {
                            type: "boolean",
                            description: "Plan mode",
                            default: false,
                          });
                          pi.registerTool(defineTool({
                            name: "hello",
                            label: "Hello",
                            description: "Greets a name",
                            parameters: Type.Object({
                              name: Type.String({ description: "Name to greet" }),
                            }),
                            async execute(_id, params, _signal, onUpdate) {
                              onUpdate?.({ content: [{ type: "text", text: "working" }] });
                              return {
                                content: [{ type: "text", text: `Hello, ${'$'}{params.name}!` }],
                                details: { plan: pi.getFlag("plan") },
                              };
                            },
                          }));
                          pi.registerCommand("hello-command", {
                            description: "Runs a greeting command",
                            async handler(args, ctx) {
                              ctx.ui.notify(`command:${'$'}{args}`, "info");
                              pi.appendEntry("command", { args });
                            },
                          });
                          pi.on("session_start", (_event, ctx) => {
                            ctx.ui.setStatus("fixture", "started");
                          });
                          pi.on("tool_call", event => {
                            if (event.input.block === true) {
                              return { block: true, reason: "blocked by fixture" };
                            }
                          });
                          pi.on("before_agent_start", event => ({
                            systemPrompt: event.systemPrompt + "\nextension prompt",
                          }));
                        }
                        """.trimIndent(),
                    )
                }
            val diagnostics = mutableListOf<ExtensionDiagnostic>()
            val host =
                assertNotNull(
                    ExtensionHost.start(
                        sources =
                            listOf(
                                ExtensionSource(
                                    extension,
                                    ResourceSourceInfo(
                                        path = extension,
                                        source = "local",
                                        scope = "temporary",
                                        origin = "top-level",
                                        baseDir = root,
                                    ),
                                ),
                            ),
                        agentDir = agentDir,
                        cwd = root,
                        mode = ExtensionMode.PRINT,
                        projectTrusted = true,
                        flagValues = mapOf("plan" to true),
                        context = extensionTestContext(root),
                        onDiagnostic = diagnostics::add,
                    ),
                )

            assertTrue(diagnostics.isEmpty())
            assertEquals(listOf("hello"), host.registrations.tools.map { it.name })
            assertEquals(listOf("hello-command"), host.registrations.commands.map { it.invocationName })
            assertEquals("Runs a greeting command", host.registrations.commands.single().description)
            assertEquals("Plan mode", host.registrations.flags.single().description)
            assertEquals(false, host.registrations.flags.single().defaultValue?.jsonPrimitive?.content?.toBoolean())
            assertEquals(setOf("session_start", "tool_call", "before_agent_start"), host.registrations.extensions.single().events)

            val tool =
                host.invokeTool(
                    toolId = host.registrations.tools.single().id,
                    toolCallId = "call-1",
                    params = buildJsonObject { put("name", "Kotlin") },
                    context = extensionTestContext(root),
                )
            assertEquals(
                "Hello, Kotlin!",
                tool.result
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonArray
                    ?.single()
                    ?.jsonObject
                    ?.get("text")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(true, tool.result?.jsonObject?.get("details")?.jsonObject?.get("plan")?.jsonPrimitive?.content?.toBoolean())
            assertTrue(tool.actions.any { it.type == "tool_update" })

            val command =
                host.invokeCommand(
                    name = "hello-command",
                    args = "world",
                    context = extensionTestContext(root),
                )
            assertTrue(command.actions.any { it.type == "ui" && it.data["method"]?.jsonPrimitive?.content == "notify" })
            assertTrue(command.actions.any { it.type == "append_entry" })

            val blocked =
                host.emit(
                    event =
                        buildJsonObject {
                            put("type", "tool_call")
                            put("toolName", "bash")
                            put("toolCallId", "call-2")
                            put("input", buildJsonObject { put("block", true) })
                        },
                    context = extensionTestContext(root),
                )
            val blockedResult = blocked.result?.jsonObject
            assertTrue(blockedResult?.get("block")?.jsonPrimitive?.content?.toBoolean() == true)
            assertEquals(
                "blocked by fixture",
                blockedResult.getValue("reason").jsonPrimitive.content,
            )

            val prompt =
                host.emit(
                    event =
                        buildJsonObject {
                            put("type", "before_agent_start")
                            put("prompt", "hello")
                            put("systemPrompt", "base")
                        },
                    context =
                        JsonObject(
                            extensionTestContext(root) +
                                ("systemPrompt" to kotlinx.serialization.json.JsonPrimitive("base")),
                        ),
                )
            assertEquals(
                "base\nextension prompt",
                prompt.result?.jsonObject?.get("systemPrompt")?.jsonPrimitive?.content,
            )
            val repeatedPrompt =
                host.emit(
                    event =
                        buildJsonObject {
                            put("type", "before_agent_start")
                            put("prompt", "again")
                            put("systemPrompt", "base")
                        },
                    context =
                        JsonObject(
                            extensionTestContext(root) +
                                ("systemPrompt" to kotlinx.serialization.json.JsonPrimitive("base\nextension prompt")),
                        ),
                )
            assertEquals(
                "base\nextension prompt",
                repeatedPrompt.result?.jsonObject?.get("systemPrompt")?.jsonPrimitive?.content,
            )
            assertFalse(host.registrations.providers.isNotEmpty())
            host.close()
        }

    private fun extensionTestContext(root: java.nio.file.Path): JsonObject =
        buildJsonObject {
            put("cwd", root.toString())
            put("mode", "print")
            put("hasUI", false)
            put("projectTrusted", true)
            put("thinkingLevel", "off")
            put("systemPrompt", "base")
            put("activeTools", kotlinx.serialization.json.JsonArray(emptyList()))
            put("allTools", kotlinx.serialization.json.JsonArray(emptyList()))
            put("isIdle", true)
            put("hasPendingMessages", false)
            put("flags", buildJsonObject { put("plan", true) })
        }

    private fun nodeAvailable(): Boolean =
        runCatching {
            val process = ProcessBuilder("node", "--version").start()
            process.waitFor()
            process.exitValue() == 0 &&
                process.inputStream.bufferedReader().readText().trim().removePrefix("v").substringBefore('.').toInt() >= 22
        }.getOrDefault(false)
}
