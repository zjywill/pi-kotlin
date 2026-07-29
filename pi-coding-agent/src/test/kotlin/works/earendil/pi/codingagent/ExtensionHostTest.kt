package works.earendil.pi.codingagent

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import works.earendil.pi.ai.FauxModelDefinition
import works.earendil.pi.ai.FauxProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtensionHostTest {
    @Test
    fun `extension host invokes registered message and entry renderers`() =
        runTest {
            assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
            val root = Files.createTempDirectory("pi-kotlin-extension-renderers")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            val extension =
                root.resolve("renderers.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        import { Box, Text } from "@earendil-works/pi-tui";

                        export default function(pi) {
                          pi.registerMessageRenderer("status", (message, { expanded, outputPad }, theme) => {
                            const box = new Box(outputPad, 1, text => theme.bg("customMessageBg", text));
                            box.addChild(new Text(
                              `${'$'}{theme.fg("accent", message.content)}|${'$'}{expanded}|${'$'}{outputPad}|${'$'}{message.details.source}`,
                              0,
                              0,
                            ));
                            return box;
                          });
                          pi.registerMessageRenderer("hidden-renderer", () => undefined);
                          pi.registerEntryRenderer("card", (entry, { expanded }) => ({
                            render(width) {
                              return [`entry|${'$'}{width}|${'$'}{expanded}|${'$'}{entry.id}|${'$'}{entry.data.value}`];
                            },
                          }));
                        }
                        """.trimIndent(),
                    )
                }
            val host =
                assertNotNull(
                    ExtensionHost.start(
                        sources =
                            listOf(
                                ExtensionSource(
                                    extension,
                                    ResourceSourceInfo(extension, "local", baseDir = root),
                                ),
                            ),
                        agentDir = agentDir,
                        cwd = root,
                        mode = ExtensionMode.TUI,
                        projectTrusted = true,
                        flagValues = emptyMap(),
                        context = extensionTestContext(root),
                    ),
                )

            host.use {
                val registration = host.registrations.extensions.single()
                assertEquals(
                    "0:message-renderer:status",
                    registration.messageRenderers.first { it.customType == "status" }.id,
                )
                assertEquals(
                    "0:entry-renderer:card",
                    registration.entryRenderers.single().id,
                )

                val message =
                    host.invokeRenderer(
                        kind = "message",
                        rendererId = registration.messageRenderers.first { it.customType == "status" }.id,
                        value =
                            buildJsonObject {
                                put("role", "custom")
                                put("customType", "status")
                                put("content", "ready")
                                put("display", true)
                                put("details", buildJsonObject { put("source", "host") })
                                put("timestamp", 123)
                            },
                        width = 30,
                        expanded = true,
                        outputPad = 2,
                    )
                val entry =
                    host.invokeRenderer(
                        kind = "entry",
                        rendererId = registration.entryRenderers.single().id,
                        value =
                            buildJsonObject {
                                put("type", "custom")
                                put("id", "entry-1")
                                put("timestamp", "2026-07-29T00:00:00Z")
                                put("customType", "card")
                                put("data", buildJsonObject { put("value", "saved") })
                            },
                        width = 41,
                        expanded = false,
                        outputPad = 9,
                    )
                val undefined =
                    host.invokeRenderer(
                        kind = "message",
                        rendererId =
                            registration.messageRenderers
                                .first { it.customType == "hidden-renderer" }
                                .id,
                        value =
                            buildJsonObject {
                                put("role", "custom")
                                put("customType", "hidden-renderer")
                                put("content", "ignored")
                                put("display", true)
                                put("timestamp", 123)
                            },
                        width = 20,
                        expanded = false,
                        outputPad = 1,
                    )

                assertTrue(
                    parseRendererLines(message)
                        .orEmpty()
                        .any { it.contains("ready|true|2|host") },
                )
                assertEquals(listOf("entry|41|false|entry-1|saved"), parseRendererLines(entry))
                assertEquals(null, parseRendererLines(undefined))
            }
        }

    @Test
    fun `final extension host responses require the active request id`() {
        val error =
            assertFailsWith<IllegalStateException> {
                requireExtensionHostFinalResponse(
                    buildJsonObject {
                        put("id", "other")
                        put("ok", true)
                    },
                    expectedId = "active",
                )
            }

        assertEquals(
            "Extension host response id mismatch: expected active, received other",
            error.message,
        )
    }

    @Test
    fun `awaited extension UI requests receive correlated responses`() =
        runTest {
            assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
            val root = Files.createTempDirectory("pi-kotlin-extension-ui")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            val extension =
                root.resolve("dialogs.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        export default function(pi) {
                          pi.registerCommand("dialogs", {
                            async handler(_args, ctx) {
                              const choice = await ctx.ui.select("Choose", ["alpha", "beta"]);
                              const confirmed = await ctx.ui.confirm("Confirm", "Continue?");
                              const name = await ctx.ui.input("Name", "enter name");
                              const edited = await ctx.ui.editor("Edit", "draft");
                              pi.appendEntry("answers", { choice, confirmed, name, edited });
                            },
                          });
                        }
                        """.trimIndent(),
                    )
                }
            val requests = mutableListOf<JsonObject>()
            val host =
                assertNotNull(
                    ExtensionHost.start(
                        sources =
                            listOf(
                                ExtensionSource(
                                    extension,
                                    ResourceSourceInfo(extension, "local", baseDir = root),
                                ),
                            ),
                        agentDir = agentDir,
                        cwd = root,
                        mode = ExtensionMode.RPC,
                        projectTrusted = true,
                        flagValues = emptyMap(),
                        context = extensionTestContext(root),
                        onUiRequest = { request, respond ->
                            requests += request
                            respond(
                                when (request["method"]?.jsonPrimitive?.contentOrNull) {
                                    "select" -> buildJsonObject { put("value", "beta") }
                                    "confirm" -> buildJsonObject { put("confirmed", true) }
                                    "input" -> buildJsonObject { put("value", "Ada") }
                                    "editor" -> buildJsonObject { put("value", "edited") }
                                    else -> buildJsonObject { put("cancelled", true) }
                                },
                            )
                        },
                    ),
                )

            host.use {
                val invocation =
                    host.invokeCommand(
                        name = "dialogs",
                        args = "",
                        context = extensionTestContext(root),
                    )
                val answers =
                    invocation.actions
                        .single { it.type == "append_entry" }
                        .data["data"]
                        ?.jsonObject

                assertEquals(
                    listOf("select", "confirm", "input", "editor"),
                    requests.map { it["method"]?.jsonPrimitive?.contentOrNull },
                )
                assertEquals(
                    4,
                    requests.mapNotNull { it["requestId"]?.jsonPrimitive?.contentOrNull }.distinct().size,
                )
                assertEquals("beta", answers?.get("choice")?.jsonPrimitive?.content)
                assertEquals("true", answers?.get("confirmed")?.jsonPrimitive?.content)
                assertEquals("Ada", answers?.get("name")?.jsonPrimitive?.content)
                assertEquals("edited", answers?.get("edited")?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `extension UI timeout cancels the pending host request`() =
        runTest {
            assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
            val root = Files.createTempDirectory("pi-kotlin-extension-ui-timeout")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            val extension =
                root.resolve("timeout.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        export default function(pi) {
                          pi.registerCommand("timeout", {
                            async handler(_args, ctx) {
                              const value = await ctx.ui.input("Wait", undefined, { timeout: 20 });
                              pi.appendEntry("timeout", { value: value ?? "cancelled" });
                            },
                          });
                        }
                        """.trimIndent(),
                    )
                }
            val cancelled = mutableListOf<String>()
            val host =
                assertNotNull(
                    ExtensionHost.start(
                        sources =
                            listOf(
                                ExtensionSource(
                                    extension,
                                    ResourceSourceInfo(extension, "local", baseDir = root),
                                ),
                            ),
                        agentDir = agentDir,
                        cwd = root,
                        mode = ExtensionMode.RPC,
                        projectTrusted = true,
                        flagValues = emptyMap(),
                        context = extensionTestContext(root),
                        onUiRequest = { _, _ -> },
                        onUiCancelled = cancelled::add,
                    ),
                )

            host.use {
                val invocation =
                    host.invokeCommand(
                        name = "timeout",
                        args = "",
                        context = extensionTestContext(root),
                    )
                val value =
                    invocation.actions
                        .single { it.type == "append_entry" }
                        .data["data"]
                        ?.jsonObject
                        ?.get("value")
                        ?.jsonPrimitive
                        ?.content

                assertEquals("cancelled", value)
                assertEquals(1, cancelled.size)
            }
        }

    @Test
    fun `virtual TypeBox compiler accepts nullable arrays with items`() =
        runTest {
            assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
            val root = Files.createTempDirectory("pi-kotlin-extension-typebox-nullable")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            val extension =
                root.resolve("nullable.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        import { Compile } from "typebox/compile";
                        export default function(pi) {
                          const generated = new Function(
                            Compile({ type: ["array", "null"], items: { type: "string" } }).Code()
                          )();
                          pi.on("session_start", () => ({
                            nullAccepted: generated(null),
                            valuesAccepted: generated(["a", "b"]),
                            numbersRejected: generated([1]),
                          }));
                        }
                        """.trimIndent(),
                    )
                }
            val host =
                assertNotNull(
                    ExtensionHost.start(
                        sources =
                            listOf(
                                ExtensionSource(
                                    extension,
                                    ResourceSourceInfo(extension, "local", baseDir = root),
                                ),
                            ),
                        agentDir = agentDir,
                        cwd = root,
                        mode = ExtensionMode.PRINT,
                        projectTrusted = true,
                        flagValues = emptyMap(),
                        context = extensionTestContext(root),
                    ),
                )

            host.use {
                val result =
                    host.emit(
                        buildJsonObject { put("type", "session_start") },
                        extensionTestContext(root),
                    ).result?.jsonObject

                assertEquals("true", result?.get("nullAccepted")?.jsonPrimitive?.content)
                assertEquals("true", result?.get("valuesAccepted")?.jsonPrimitive?.content)
                assertEquals("false", result?.get("numbersRejected")?.jsonPrimitive?.content)
            }
        }

    @Test
    fun `extension context exposes an empty then live scoped model list`() =
        runTest {
            assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
            val root = Files.createTempDirectory("pi-kotlin-extension-scoped-models")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            val extension =
                root.resolve("scoped-models.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        export default function(pi) {
                          pi.on("session_start", (_event, ctx) => ({
                            mode: ctx.mode,
                            scopedModels: ctx.scopedModels.map(entry => ({
                              provider: entry.model.provider,
                              id: entry.model.id,
                              thinkingLevel: entry.thinkingLevel,
                            })),
                          }));
                        }
                        """.trimIndent(),
                    )
                }
            val host =
                assertNotNull(
                    ExtensionHost.start(
                        sources =
                            listOf(
                                ExtensionSource(
                                    extension,
                                    ResourceSourceInfo(extension, "local", baseDir = root),
                                ),
                            ),
                        agentDir = agentDir,
                        cwd = root,
                        mode = ExtensionMode.PRINT,
                        projectTrusted = true,
                        flagValues = emptyMap(),
                        context = extensionTestContext(root),
                    ),
                )
            val model =
                FauxProvider(
                    definitions = listOf(FauxModelDefinition("faux-scoped", reasoning = true)),
                ).getModels().single()

            host.use {
                val empty =
                    host.emit(
                        buildJsonObject { put("type", "session_start") },
                        extensionTestContext(root),
                    )
                val populated =
                    host.emit(
                        buildJsonObject { put("type", "session_start") },
                        extensionContextJson(
                            cwd = root,
                            mode = ExtensionMode.PRINT,
                            projectTrusted = true,
                            model = model,
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
                            scopedModels = listOf(ScopedModel(model, AgentThinkingLevel.HIGH)),
                        ),
                    )

                assertTrue(empty.result?.jsonObject?.get("scopedModels")?.jsonArray.orEmpty().isEmpty())
                val entry =
                    populated.result
                        ?.jsonObject
                        ?.get("scopedModels")
                        ?.jsonArray
                        ?.single()
                        ?.jsonObject
                assertEquals("faux", entry?.get("provider")?.jsonPrimitive?.content)
                assertEquals("faux-scoped", entry?.get("id")?.jsonPrimitive?.content)
                assertEquals("high", entry?.get("thinkingLevel")?.jsonPrimitive?.content)
            }
        }

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
                          pi.registerShortcut("ctrl+y", {
                            description: "Runs a greeting shortcut",
                            async handler(ctx) {
                              ctx.ui.notify("shortcut:hello", "info");
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
            val shortcut = host.registrations.extensions.single().shortcuts.single()
            assertEquals("ctrl+y", shortcut.shortcut)
            assertEquals("Runs a greeting shortcut", shortcut.description)

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

            val shortcutInvocation =
                host.invokeShortcut(
                    id = shortcut.id,
                    context = extensionTestContext(root),
                )
            assertTrue(
                shortcutInvocation.actions.any {
                    it.type == "ui" && it.data.stringValue("message") == "shortcut:hello"
                },
            )

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

    @Test
    fun `delivers registrations created after a command response`() =
        runTest {
            assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
            val root = Files.createTempDirectory("pi-kotlin-extension-background-registrations")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            val extension =
                root.resolve("background.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        import { Type } from "typebox";
                        export default function(pi) {
                          pi.registerCommand("schedule", {
                            handler() {
                              setTimeout(() => {
                                pi.registerTool({
                                  name: "background_echo",
                                  label: "Background echo",
                                  description: "Registered after the command response",
                                  parameters: Type.Object({ text: Type.String() }),
                                  async execute(_id, params) {
                                    return {
                                      content: [{ type: "text", text: `background:${'$'}{params.text}` }],
                                      details: {},
                                    };
                                  },
                                });
                                pi.registerCommand("background-command", { handler() {} });
                                pi.registerFlag("background-flag", { type: "boolean", default: true });
                                pi.registerProvider("background-provider", {
                                  name: "Background Provider",
                                  baseUrl: "https://background.invalid/v1",
                                  apiKey: "background-key",
                                  api: "openai-completions",
                                  models: [{
                                    id: "background-model",
                                    name: "Background Model",
                                    reasoning: false,
                                    input: ["text"],
                                    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
                                    contextWindow: 8192,
                                    maxTokens: 1024,
                                  }],
                                });
                              }, 0);
                            },
                          });
                        }
                        """.trimIndent(),
                    )
                }
            val diagnostics = CopyOnWriteArrayList<ExtensionDiagnostic>()
            val backgroundActions = CopyOnWriteArrayList<ExtensionAction>()
            val registrationsReady = CountDownLatch(1)
            val host =
                assertNotNull(
                    ExtensionHost.start(
                        sources =
                            listOf(
                                ExtensionSource(
                                    extension,
                                    ResourceSourceInfo(extension, "local", baseDir = root),
                                ),
                            ),
                        agentDir = agentDir,
                        cwd = root,
                        mode = ExtensionMode.RPC,
                        projectTrusted = true,
                        flagValues = emptyMap(),
                        context = extensionTestContext(root),
                        onDiagnostic = diagnostics::add,
                    ),
                )
            try {
                host.invokeCommand("schedule", "", extensionTestContext(root))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (host.registrations.tools.none { it.name == "background_echo" }) {
                    check(System.nanoTime() < deadline) {
                        "Timed out waiting for the host to receive background registrations"
                    }
                    Thread.sleep(10)
                }
                host.bindBackgroundActions { actions ->
                    backgroundActions += actions
                    if (actions.any { it.type == "registrations_changed" }) {
                        registrationsReady.countDown()
                    }
                }
                assertTrue(
                    registrationsReady.await(2, TimeUnit.SECONDS),
                    "Timed out waiting for background registrations",
                )
                assertEquals(
                    listOf("background_echo"),
                    host.registrations.tools.map { it.name },
                )
                assertTrue(host.registrations.commands.any { it.name == "background-command" })
                assertTrue(host.registrations.flags.any { it.name == "background-flag" })
                assertTrue(
                    host.registrations.providers.any {
                        it["name"]?.jsonPrimitive?.content == "background-provider"
                    },
                )
                assertTrue(backgroundActions.any { it.type == "register_provider" })

                val result =
                    host.invokeTool(
                        toolId = host.registrations.tools.single().id,
                        toolCallId = "background-call",
                        params = buildJsonObject { put("text", "ready") },
                        context = extensionTestContext(root),
                    )
                assertEquals(
                    "background:ready",
                    result.result
                        ?.jsonObject
                        ?.get("content")
                        ?.jsonArray
                        ?.single()
                        ?.jsonObject
                        ?.get("text")
                        ?.jsonPrimitive
                        ?.content,
                )
                assertTrue(diagnostics.isEmpty(), diagnostics.joinToString())
            } finally {
                host.close()
            }
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
