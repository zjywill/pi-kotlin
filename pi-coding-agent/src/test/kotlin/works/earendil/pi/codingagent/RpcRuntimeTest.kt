package works.earendil.pi.codingagent

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.agent.Agent
import works.earendil.pi.ai.FauxModelDefinition
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.FauxResponseStep
import works.earendil.pi.ai.InMemoryCredentialStore
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.ai.fauxAssistantMessage
import works.earendil.pi.ai.fauxToolCall
import works.earendil.pi.ai.providers.builtInModels
import works.earendil.pi.ai.providers.githubCopilotProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RpcRuntimeTest {
    @Test
    fun `TUI extension context includes resolved scoped models`() =
        runTest {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                nodeAvailable(),
                "Node.js 22+ is required for extension runtime tests",
            )
            val root = Files.createTempDirectory("pi-kotlin-rpc-tui-scoped-models")
            val extension =
                root.resolve("scoped-models.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        export default function(pi) {
                          pi.on("session_start", (_event, ctx) => {
                            const scoped = ctx.scopedModels.map(entry =>
                              `${'$'}{entry.model.provider}/${'$'}{entry.model.id}:${'$'}{entry.thinkingLevel ?? "default"}`
                            );
                            ctx.ui.notify(`${'$'}{ctx.mode}|${'$'}{scoped.join(",")}`, "info");
                          });
                        }
                        """.trimIndent(),
                    )
                }
            val provider =
                FauxProvider(
                    definitions =
                        listOf(
                            FauxModelDefinition("faux-1", reasoning = true),
                            FauxModelDefinition("faux-2", reasoning = true),
                        ),
                )
            val runtime =
                RpcRuntime(
                    Models(listOf(provider)),
                    RpcRuntimeOptions(
                        cwd = root,
                        agentDir = Files.createDirectories(root.resolve("agent")),
                        noSession = true,
                        extensionPaths = listOf(extension.toString()),
                        extensionMode = ExtensionMode.TUI,
                        modelPatterns = listOf("faux/faux-2:high"),
                    ),
                )
            val events = mutableListOf<JsonObject>()
            runtime.subscribe(events::add)

            val notification =
                events.single {
                    it.eventType() == "extension_ui_request" &&
                        it["method"]?.jsonPrimitive?.content == "notify"
                }
            val state = requireNotNull(runtime.handle(buildJsonObject { put("type", "get_state") }))

            assertEquals("tui|faux/faux-2:high", notification["message"]?.jsonPrimitive?.content)
            assertEquals("faux-2", state.data()["model"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
            assertEquals("high", state.data()["thinkingLevel"]?.jsonPrimitive?.content)
            runtime.close()
        }

    @Test
    fun `RPC bash is intercepted by user bash and records replacement results`() =
        runTest {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                nodeAvailable(),
                "Node.js 22+ is required for extension runtime tests",
            )
            val root = Files.createTempDirectory("pi-kotlin-rpc-user-bash")
            val extension =
                root.resolve("user-bash.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        export default function(pi) {
                          pi.on("user_bash", event => ({
                            result: {
                              output: `handled:${'$'}{event.command}:${'$'}{event.excludeFromContext}:${'$'}{event.cwd}`,
                              exitCode: 0,
                              cancelled: false,
                              truncated: false,
                            },
                          }));
                        }
                        """.trimIndent(),
                    )
                }
            val runtime =
                RpcRuntime(
                    Models(listOf(FauxProvider())),
                    RpcRuntimeOptions(
                        cwd = root,
                        agentDir = Files.createDirectories(root.resolve("agent")),
                        noSession = true,
                        provider = "faux",
                        model = "faux-1",
                        extensionPaths = listOf(extension.toString()),
                    ),
                )

            val response =
                requireNotNull(
                    runtime.handle(
                        buildJsonObject {
                            put("id", "bash-extension")
                            put("type", "bash")
                            put("command", "do-not-run")
                            put("excludeFromContext", true)
                        },
                    ),
                )
            assertSuccess(
                runtime.handle(
                    buildJsonObject {
                        put("id", "bash-extension-false")
                        put("type", "bash")
                        put("command", "do-not-run-false")
                        put("excludeFromContext", false)
                    },
                ),
            )
            val entries =
                requireNotNull(runtime.handle(buildJsonObject { put("type", "get_entries") }))
                    .data()["entries"]
                    ?.jsonArray
                    .orEmpty()
            val messages = entries.mapNotNull { it.jsonObject["message"] as? JsonObject }

            assertEquals("handled:do-not-run:true:$root", response.data()["output"]?.jsonPrimitive?.content)
            assertEquals(listOf("bashExecution", "bashExecution"), messages.map { it["role"]?.jsonPrimitive?.content })
            assertEquals(
                listOf(true, false),
                messages.map { it["excludeFromContext"]?.jsonPrimitive?.boolean },
            )
            runtime.close()
        }

    @Test
    fun `older bash completion keeps newer execution tracked and cancellable`() =
        runBlocking {
            val root = Files.createTempDirectory("pi-kotlin-rpc-concurrent-bash")
            val runtime =
                RpcRuntime(
                    Models(listOf(FauxProvider())),
                    RpcRuntimeOptions(
                        cwd = root,
                        noSession = true,
                        provider = "faux",
                        model = "faux-1",
                    ),
                )
            val first =
                async {
                    runtime.handle(
                        buildJsonObject {
                            put("id", "first")
                            put("type", "bash")
                            put("command", "sleep 0.2; printf first")
                        },
                    )
                }
            val second =
                async {
                    runtime.handle(
                        buildJsonObject {
                            put("id", "second")
                            put("type", "bash")
                            put("command", "sleep 5; printf second")
                        },
                    )
                }
            withTimeout(5_000) {
                while (runtime.activeBashCount < 2) {
                    delay(10)
                }
            }

            val firstResult = requireNotNull(first.await())
            assertEquals("first", firstResult.data()["output"]?.jsonPrimitive?.content)
            assertEquals(1, runtime.activeBashCount)

            runtime.handle(buildJsonObject { put("type", "abort_bash") })
            val secondResult = requireNotNull(second.await())
            assertTrue(secondResult.data()["cancelled"]?.jsonPrimitive?.boolean ?: false)
            assertEquals(0, runtime.activeBashCount)
            runtime.close()
        }

    @Test
    fun `abort bash cancels every concurrent execution`() =
        runBlocking {
            val root = Files.createTempDirectory("pi-kotlin-rpc-abort-all-bash")
            val runtime =
                RpcRuntime(
                    Models(listOf(FauxProvider())),
                    RpcRuntimeOptions(
                        cwd = root,
                        noSession = true,
                        provider = "faux",
                        model = "faux-1",
                    ),
                )
            val executions =
                listOf("first", "second").map { id ->
                    async {
                        runtime.handle(
                            buildJsonObject {
                                put("id", id)
                                put("type", "bash")
                                put("command", "sleep 5")
                            },
                        )
                    }
                }
            withTimeout(5_000) {
                while (runtime.activeBashCount < executions.size) {
                    delay(10)
                }
            }

            runtime.handle(buildJsonObject { put("type", "abort_bash") })
            val results = executions.map { requireNotNull(it.await()) }

            assertTrue(results.all { it.data()["cancelled"]?.jsonPrimitive?.boolean == true })
            assertEquals(0, runtime.activeBashCount)
            runtime.close()
        }

    @Test
    fun `session replacement detaches the old agent event subscription`() =
        runTest {
            val provider = FauxProvider()
            provider.setResponses(listOf(FauxResponseStep.Message(fauxAssistantMessage("stale"))))
            val runtime =
                RpcRuntime(
                    Models(listOf(provider)),
                    RpcRuntimeOptions(
                        cwd = Files.createTempDirectory("pi-kotlin-rpc-session-subscription"),
                        noSession = true,
                        provider = "faux",
                        model = "faux-1",
                    ),
                )
            val events = mutableListOf<JsonObject>()
            runtime.subscribe(events::add)
            val agentField =
                RpcRuntime::class.java.getDeclaredField("agent").apply {
                    isAccessible = true
                }
            val oldAgent = agentField.get(runtime) as Agent

            assertSuccess(runtime.handle(buildJsonObject { put("type", "new_session") }))
            events.clear()
            val entryCountBefore =
                requireNotNull(runtime.handle(buildJsonObject { put("type", "get_entries") }))
                    .data()["entries"]
                    ?.jsonArray
                    .orEmpty()
                    .size

            oldAgent.prompt(UserMessage("stale event"))

            val entryCountAfter =
                requireNotNull(runtime.handle(buildJsonObject { put("type", "get_entries") }))
                    .data()["entries"]
                    ?.jsonArray
                    .orEmpty()
                    .size
            assertTrue(events.isEmpty())
            assertEquals(entryCountBefore, entryCountAfter)
            runtime.close()
        }

    @Test
    fun `extensions contribute rpc commands tools flags and lifecycle hooks`() =
        runTest {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                nodeAvailable(),
                "Node.js 22+ is required for extension runtime tests",
            )
            val root = Files.createTempDirectory("pi-kotlin-rpc-extension")
            val extension =
                root.resolve("extension.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        import { Type } from "typebox";
                        export default function(pi) {
                          pi.registerFlag("shout", { type: "boolean", default: false });
                          pi.registerTool({
                            name: "extension_echo",
                            label: "Extension echo",
                            description: "Echo text from an extension",
                            parameters: Type.Object({ text: Type.String() }),
                            async execute(_id, params) {
                              return {
                                content: [{ type: "text", text: `echo:${'$'}{params.text}:${'$'}{pi.getFlag("shout")}` }],
                                details: { extension: true },
                              };
                            },
                          });
                          pi.registerCommand("mark", {
                            description: "Mark the session",
                            async handler(args, ctx) {
                              pi.appendEntry("mark", { args });
                              ctx.ui.notify(`marked:${'$'}{args}`, "info");
                            },
                          });
                          pi.on("session_start", (_event, ctx) => {
                            ctx.ui.setStatus("fixture", "started");
                          });
                          pi.on("before_agent_start", event => ({
                            systemPrompt: event.systemPrompt + "\nextension-system-prompt",
                          }));
                        }
                        """.trimIndent(),
                    )
                }
            val provider = FauxProvider()
            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { context, _, _, _ ->
                        assertTrue(context.systemPrompt.orEmpty().endsWith("extension-system-prompt"))
                        assertTrue(context.tools.any { it.name == "extension_echo" })
                        fauxAssistantMessage(
                            content =
                                listOf(
                                    fauxToolCall(
                                        name = "extension_echo",
                                        arguments = buildJsonObject { put("text", "hello") },
                                        id = "extension-call",
                                    ),
                                ),
                            stopReason = StopReason.TOOL_USE,
                        )
                    },
                    FauxResponseStep.Factory { context, _, _, _ ->
                        val result =
                            context.messages
                                .filterIsInstance<ToolResultMessage>()
                                .last()
                        assertEquals("echo:hello:true", works.earendil.pi.ai.contentText(result.content))
                        fauxAssistantMessage("done")
                    },
                ),
            )
            val runtime =
                RpcRuntime(
                    Models(listOf(provider)),
                    RpcRuntimeOptions(
                        cwd = root,
                        agentDir = Files.createDirectories(root.resolve("agent")),
                        noSession = true,
                        provider = "faux",
                        model = "faux-1",
                        extensionPaths = listOf(extension.toString()),
                        extensionFlagValues = mapOf("shout" to true),
                    ),
                )
            val events = mutableListOf<JsonObject>()
            runtime.subscribe(events::add)

            val commands =
                requireNotNull(runtime.handle(buildJsonObject { put("type", "get_commands") }))
                    .data()["commands"]
                    ?.jsonArray
                    .orEmpty()
            assertTrue(
                commands.any {
                    it.jsonObject["name"]?.jsonPrimitive?.content == "mark" &&
                        it.jsonObject["source"]?.jsonPrimitive?.content == "extension"
                },
            )

            assertSuccess(
                runtime.handle(
                    buildJsonObject {
                        put("type", "prompt")
                        put("message", "/mark checkpoint")
                    },
                ),
            )
            assertTrue(
                events.any {
                    it.eventType() == "extension_ui_request" &&
                        it["method"]?.jsonPrimitive?.content == "notify"
                },
            )

            assertSuccess(
                runtime.handle(
                    buildJsonObject {
                        put("type", "prompt")
                        put("message", "run extension tool")
                    },
                ),
            )
            runtime.waitForIdle()
            assertEquals(2, provider.state.callCount)
            assertTrue(events.any { it.eventType() == "tool_execution_start" && it["toolName"]?.jsonPrimitive?.content == "extension_echo" })
            runtime.close()
        }

    @Test
    fun `runtime extension registrations refresh commands and active tools immediately`() =
        runTest {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                nodeAvailable(),
                "Node.js 22+ is required for extension runtime tests",
            )
            val root = Files.createTempDirectory("pi-kotlin-rpc-dynamic-extension")
            val extension =
                root.resolve("dynamic.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        import { Type } from "typebox";
                        export default function(pi) {
                          pi.registerCommand("enable-dynamic", {
                            handler() {
                              pi.registerTool({
                                name: "dynamic_echo",
                                label: "Dynamic echo",
                                description: "Dynamically registered echo tool",
                                parameters: Type.Object({ text: Type.String() }),
                                async execute(_id, params) {
                                  return {
                                    content: [{ type: "text", text: `dynamic:${'$'}{params.text}` }],
                                    details: {},
                                  };
                                },
                              });
                              pi.registerCommand("dynamic-command", {
                                handler(_args, ctx) {
                                  ctx.ui.notify(
                                    `dynamic-tools:${'$'}{pi.getAllTools().map(tool => tool.name).join(",")}`,
                                    "info",
                                  );
                                },
                              });
                              pi.registerFlag("dynamic-flag", { type: "boolean", default: true });
                            },
                          });
                        }
                        """.trimIndent(),
                    )
                }
            val provider = FauxProvider()
            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { context, _, _, _ ->
                        assertTrue(context.tools.any { it.name == "dynamic_echo" })
                        assertTrue(context.systemPrompt.orEmpty().contains("dynamic_echo"))
                        fauxAssistantMessage(
                            content =
                                listOf(
                                    fauxToolCall(
                                        name = "dynamic_echo",
                                        arguments = buildJsonObject { put("text", "ready") },
                                        id = "dynamic-call",
                                    ),
                                ),
                            stopReason = StopReason.TOOL_USE,
                        )
                    },
                    FauxResponseStep.Factory { context, _, _, _ ->
                        val result = context.messages.filterIsInstance<ToolResultMessage>().last()
                        assertEquals("dynamic:ready", works.earendil.pi.ai.contentText(result.content))
                        fauxAssistantMessage("dynamic complete")
                    },
                ),
            )
            val runtime =
                RpcRuntime(
                    Models(listOf(provider)),
                    RpcRuntimeOptions(
                        cwd = root,
                        agentDir = Files.createDirectories(root.resolve("agent")),
                        noSession = true,
                        provider = "faux",
                        model = "faux-1",
                        extensionPaths = listOf(extension.toString()),
                    ),
                )
            val events = mutableListOf<JsonObject>()
            runtime.subscribe(events::add)

            assertSuccess(
                runtime.handle(
                    buildJsonObject {
                        put("type", "prompt")
                        put("message", "/enable-dynamic")
                    },
                ),
            )
            val commands =
                requireNotNull(runtime.handle(buildJsonObject { put("type", "get_commands") }))
                    .data()["commands"]
                    ?.jsonArray
                    .orEmpty()
            assertTrue(commands.any { it.jsonObject["name"]?.jsonPrimitive?.content == "dynamic-command" })
            assertSuccess(
                runtime.handle(
                    buildJsonObject {
                        put("type", "prompt")
                        put("message", "/dynamic-command")
                    },
                ),
            )
            assertTrue(
                events.any {
                    it.eventType() == "extension_ui_request" &&
                        it["message"]?.jsonPrimitive?.content.orEmpty().contains("dynamic_echo")
                },
            )

            assertSuccess(
                runtime.handle(
                    buildJsonObject {
                        put("type", "prompt")
                        put("message", "use the dynamic tool")
                    },
                ),
            )
            runtime.waitForIdle()

            assertEquals(2, provider.state.callCount)
            runtime.close()
        }

    @Test
    fun `prompt emits lifecycle events and updates rpc state`() =
        runTest {
            val provider =
                FauxProvider(
                    definitions =
                        listOf(
                            FauxModelDefinition("faux-1", reasoning = true),
                        ),
                )
            provider.setResponses(listOf(FauxResponseStep.Message(fauxAssistantMessage("done"))))
            val runtime =
                RpcRuntime(
                    Models(listOf(provider)),
                    RpcRuntimeOptions(
                        cwd = Files.createTempDirectory("pi-kotlin-rpc"),
                        noSession = true,
                        provider = "faux",
                        model = "faux-1",
                    ),
                )
            val events = mutableListOf<JsonObject>()
            val settled = CompletableDeferred<Unit>()
            runtime.subscribe { event ->
                events += event
                if (event.eventType() == "agent_settled") {
                    settled.complete(Unit)
                }
            }

            val response =
                runtime.handle(
                    buildJsonObject {
                        put("id", "prompt-1")
                        put("type", "prompt")
                        put("message", "hello")
                    },
                )
            runtime.waitForIdle()
            assertTrue(settled.isCompleted)
            val state = requireNotNull(runtime.handle(buildJsonObject { put("type", "get_state") }))
            val messages = requireNotNull(runtime.handle(buildJsonObject { put("type", "get_messages") }))

            assertEquals(true, response?.get("success")?.jsonPrimitive?.boolean)
            assertEquals(
                listOf("agent_start", "turn_start"),
                events.map { it.eventType() }.take(2),
            )
            assertTrue(events.any { it.eventType() == "message_update" })
            assertEquals("agent_settled", events.last().eventType())
            assertFalse(state.data()["isStreaming"]?.jsonPrimitive?.boolean ?: true)
            assertEquals(2, state.data()["messageCount"]?.jsonPrimitive?.content?.toInt())
            assertEquals(2, messages.data()["messages"]?.jsonArray?.size)
            runtime.close()
        }

    private fun nodeAvailable(): Boolean =
        runCatching {
            val process = ProcessBuilder("node", "--version").start()
            process.waitFor()
            process.exitValue() == 0 &&
                process.inputStream.bufferedReader().readText().trim().removePrefix("v").substringBefore('.').toInt() >= 22
        }.getOrDefault(false)

    @Test
    fun `startup options configure prompt context tools and thinking`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-rpc-startup")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            val cwd = Files.createDirectories(root.resolve("project"))
            Files.writeString(agentDir.resolve("AGENTS.md"), "global context")
            Files.writeString(cwd.resolve("AGENTS.md"), "project context")
            val provider =
                FauxProvider(
                    definitions =
                        listOf(
                            FauxModelDefinition("faux-1", reasoning = true),
                        ),
                )
            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { context, _, _, _ ->
                        val prompt = context.systemPrompt.orEmpty()
                        assertTrue(prompt.startsWith("custom prompt\n\nappend prompt"))
                        assertTrue(prompt.contains("global context"))
                        assertTrue(prompt.contains("project context"))
                        assertTrue(context.tools.isEmpty())
                        fauxAssistantMessage("configured")
                    },
                ),
            )
            val runtime =
                RpcRuntime(
                    Models(listOf(provider)),
                    RpcRuntimeOptions(
                        cwd = cwd,
                        agentDir = agentDir,
                        noSession = true,
                        provider = "faux",
                        model = "faux-1",
                        systemPrompt = "custom prompt",
                        appendSystemPrompt = listOf("append prompt"),
                        noBuiltinTools = true,
                        thinking = AgentThinkingLevel.HIGH,
                    ),
                )

            val initialState = requireNotNull(runtime.handle(buildJsonObject { put("type", "get_state") }))
            assertEquals("high", initialState.data()["thinkingLevel"]?.jsonPrimitive?.content)
            assertSuccess(
                runtime.handle(
                    buildJsonObject {
                        put("type", "prompt")
                        put("message", "hello")
                    },
                ),
            )
            runtime.waitForIdle()

            assertEquals(1, provider.state.callCount)
            runtime.close()
        }

    @Test
    fun `startup model reference preserves slash ids and thinking suffix`() =
        runTest {
            val provider =
                FauxProvider(
                    definitions =
                        listOf(
                            FauxModelDefinition(
                                id = "vendor/model",
                                reasoning = true,
                            ),
                        ),
                )
            val runtime =
                RpcRuntime(
                    Models(listOf(provider)),
                    RpcRuntimeOptions(
                        cwd = Files.createTempDirectory("pi-kotlin-rpc-slash-model"),
                        noSession = true,
                        provider = "faux",
                        model = "vendor/model:xhigh",
                    ),
                )

            val state = requireNotNull(runtime.handle(buildJsonObject { put("type", "get_state") }))

            assertEquals("vendor/model", state.data()["model"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
            assertEquals("xhigh", state.data()["thinkingLevel"]?.jsonPrimitive?.content)
            runtime.close()
        }

    @Test
    fun `rpc model commands respect the authenticated Copilot account catalog`() =
        runTest {
            val catalogModels = builtInModels("github-copilot").take(2)
            val selected = catalogModels.first()
            val excluded = catalogModels.last()
            val models =
                Models(
                    providers = listOf(githubCopilotProvider(catalogModels)),
                    credentials =
                        InMemoryCredentialStore(
                            mapOf(
                                "github-copilot" to
                                    OAuthCredential(
                                        access = "copilot-token",
                                        refresh = "ghu-refresh",
                                        expires = Long.MAX_VALUE,
                                        availableModelIds = listOf(selected.id),
                                    ),
                            ),
                        ),
                )
            val runtime =
                RpcRuntime(
                    models,
                    RpcRuntimeOptions(
                        cwd = Files.createTempDirectory("pi-kotlin-rpc-copilot-models"),
                        noSession = true,
                        provider = "github-copilot",
                        model = selected.id,
                    ),
                )

            val available =
                requireNotNull(
                    runtime.handle(
                        buildJsonObject { put("type", "get_available_models") },
                    ),
                )
            val modelIds =
                available
                    .data()["models"]
                    ?.jsonArray
                    .orEmpty()
                    .map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(listOf(selected.id), modelIds)

            val rejected =
                requireNotNull(
                    runtime.handle(
                        buildJsonObject {
                            put("type", "set_model")
                            put("provider", "github-copilot")
                            put("modelId", excluded.id)
                        },
                    ),
                )
            assertFalse(rejected["success"]?.jsonPrimitive?.boolean ?: true)

            val cycled =
                requireNotNull(
                    runtime.handle(
                        buildJsonObject { put("type", "cycle_model") },
                    ),
                )
            assertTrue(cycled["data"] is JsonNull)
            runtime.close()
        }

    @Test
    fun `model thinking queues session metadata and bash commands are controllable`() =
        runTest {
            val provider =
                FauxProvider(
                    definitions =
                        listOf(
                            FauxModelDefinition("faux-1", reasoning = true),
                            FauxModelDefinition("faux-2", reasoning = true),
                        ),
                )
            val cwd = Files.createTempDirectory("pi-kotlin-rpc-controls")
            val runtime =
                RpcRuntime(
                    Models(listOf(provider)),
                    RpcRuntimeOptions(
                        cwd = cwd,
                        noSession = true,
                        provider = "faux",
                        model = "faux-1",
                    ),
                )
            val bashUpdates = mutableListOf<JsonObject>()
            runtime.subscribe { event ->
                if (event.eventType() == "bash_execution_update") {
                    bashUpdates += event
                }
            }

            assertSuccess(
                runtime.handle(
                    buildJsonObject {
                        put("type", "set_model")
                        put("provider", "faux")
                        put("modelId", "faux-2")
                    },
                ),
            )
            assertSuccess(
                runtime.handle(
                    buildJsonObject {
                        put("type", "set_thinking_level")
                        put("level", "high")
                    },
                ),
            )
            assertSuccess(
                runtime.handle(
                    buildJsonObject {
                        put("type", "set_steering_mode")
                        put("mode", "all")
                    },
                ),
            )
            assertSuccess(
                runtime.handle(
                    buildJsonObject {
                        put("type", "set_follow_up_mode")
                        put("mode", "all")
                    },
                ),
            )
            assertSuccess(
                runtime.handle(
                    buildJsonObject {
                        put("type", "set_session_name")
                        put("name", "  RPC Session  ")
                    },
                ),
            )
            val bash =
                requireNotNull(
                    runtime.handle(
                        buildJsonObject {
                            put("id", "bash-1")
                            put("type", "bash")
                            put("command", "printf rpc-ok")
                        },
                    ),
                )
            val state = requireNotNull(runtime.handle(buildJsonObject { put("type", "get_state") }))
            val entries = requireNotNull(runtime.handle(buildJsonObject { put("type", "get_entries") }))

            assertEquals("rpc-ok", bash.data()["output"]?.jsonPrimitive?.content)
            assertEquals(0, bash.data()["exitCode"]?.jsonPrimitive?.content?.toInt())
            assertEquals("rpc-ok", bashUpdates.joinToString("") { it["delta"]?.jsonPrimitive?.content.orEmpty() })
            assertTrue(bashUpdates.all { it["id"]?.jsonPrimitive?.content == "bash-1" })
            assertEquals("faux-2", state.data()["model"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
            assertEquals("high", state.data()["thinkingLevel"]?.jsonPrimitive?.content)
            assertEquals("all", state.data()["steeringMode"]?.jsonPrimitive?.content)
            assertEquals("all", state.data()["followUpMode"]?.jsonPrimitive?.content)
            assertEquals("RPC Session", state.data()["sessionName"]?.jsonPrimitive?.content)
            assertEquals(4, entries.data()["entries"]?.jsonArray?.size)
            assertEquals(
                "bashExecution",
                entries.data()["entries"]
                    ?.jsonArray
                    ?.last()
                    ?.jsonObject
                    ?.get("message")
                    ?.jsonObject
                    ?.get("role")
                    ?.jsonPrimitive
                    ?.content,
            )
            runtime.close()
        }

    @Test
    fun `invalid json and unsupported commands return protocol errors`() =
        runTest {
            val runtime =
                RpcRuntime(
                    Models(listOf(FauxProvider())),
                    RpcRuntimeOptions(
                        cwd = Files.createTempDirectory("pi-kotlin-rpc-errors"),
                        noSession = true,
                        provider = "faux",
                        model = "faux-1",
                    ),
                )

            val parseError = requireNotNull(runtime.handleLine("{"))
            val unknownError =
                requireNotNull(
                    runtime.handle(
                        buildJsonObject {
                            put("id", "unknown-1")
                            put("type", "unknown")
                        },
                    ),
                )

            assertFalse(parseError["success"]?.jsonPrimitive?.boolean ?: true)
            assertEquals("parse", parseError["command"]?.jsonPrimitive?.content)
            assertFalse(unknownError["success"]?.jsonPrimitive?.boolean ?: true)
            assertTrue(unknownError["error"]?.jsonPrimitive?.content.orEmpty().contains("Unknown command"))
            runtime.close()
        }

    @Test
    fun `compact summarizes old turns and reloads agent context`() =
        runTest {
            val provider = FauxProvider()
            provider.setResponses(
                listOf(
                    FauxResponseStep.Message(fauxAssistantMessage("first response")),
                    FauxResponseStep.Message(fauxAssistantMessage("second response")),
                    FauxResponseStep.Message(fauxAssistantMessage("structured summary")),
                ),
            )
            val runtime =
                RpcRuntime(
                    Models(listOf(provider)),
                    RpcRuntimeOptions(
                        cwd = Files.createTempDirectory("pi-kotlin-rpc-compact"),
                        noSession = true,
                        provider = "faux",
                        model = "faux-1",
                    ),
                )
            repeat(2) { index ->
                assertSuccess(
                    runtime.handle(
                        buildJsonObject {
                            put("type", "prompt")
                            put("message", "turn-$index " + "x".repeat(100_000))
                        },
                    ),
                )
                runtime.waitForIdle()
            }
            val events = mutableListOf<String>()
            runtime.subscribe { event -> events += event.eventType() }

            val response =
                requireNotNull(
                    runtime.handle(
                        buildJsonObject {
                            put("id", "compact-1")
                            put("type", "compact")
                        },
                    ),
                )
            val messages = requireNotNull(runtime.handle(buildJsonObject { put("type", "get_messages") }))

            assertSuccess(response)
            assertEquals("structured summary", response.data()["summary"]?.jsonPrimitive?.content)
            assertTrue(response.data()["tokensBefore"]?.jsonPrimitive?.content?.toInt() ?: 0 > 0)
            assertEquals(listOf("compaction_start", "compaction_end"), events)
            assertEquals(
                "compactionSummary",
                messages.data()["messages"]?.jsonArray?.first()?.jsonObject
                    ?.get("role")?.jsonPrimitive?.content,
            )
            runtime.close()
        }

    private fun JsonObject.eventType(): String = this["type"]?.jsonPrimitive?.content.orEmpty()

    private fun JsonObject.data(): JsonObject = this["data"]?.jsonObject ?: JsonObject(emptyMap())

    private fun assertSuccess(response: JsonObject?) {
        assertEquals(true, response?.get("success")?.jsonPrimitive?.boolean)
    }
}
