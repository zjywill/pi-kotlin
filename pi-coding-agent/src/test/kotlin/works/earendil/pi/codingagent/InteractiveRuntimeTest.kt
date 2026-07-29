package works.earendil.pi.codingagent

import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import works.earendil.pi.ai.ApiKeyAuth
import works.earendil.pi.ai.ApiKeyCredential
import works.earendil.pi.ai.AuthContext
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthOption
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.AuthResult
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.FauxResponseStep
import works.earendil.pi.ai.InMemoryCredentialStore
import works.earendil.pi.ai.InMemoryModelsStore
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.RefreshModelsContext
import works.earendil.pi.ai.fauxAssistantMessage
import works.earendil.pi.codingagent.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InteractiveRuntimeTest {
    @Test
    fun `JLine console uses terminal width and falls back when unavailable`() {
        assertEquals(80, normalizeTerminalWidth(0))
        assertEquals(80, normalizeTerminalWidth(-1))
        assertEquals(72, normalizeTerminalWidth(72))
    }

    @Test
    fun `interactive mode passes terminal width to extension renderers`() =
        runTest {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                nodeAvailable(),
                "Node.js 22+ is required for extension runtime tests",
            )
            val root = Files.createTempDirectory("pi-kotlin-interactive-renderer")
            val extension =
                root.resolve("renderer.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        export default function(pi) {
                          pi.registerMessageRenderer("width", message => ({
                            render(width) { return [`render-width:${'$'}{width}:${'$'}{message.content}`]; },
                          }));
                          pi.registerCommand("render-width", {
                            handler() {
                              pi.sendMessage({ customType: "width", content: "visible", display: true });
                            },
                          });
                        }
                        """.trimIndent(),
                    )
                }
            val console = ScriptedConsole(listOf("/render-width", "/exit"), terminalWidth = 24)
            val runtime =
                InteractiveRuntime(
                    Models(listOf(FauxProvider())),
                    cwd = root,
                    agentDir = Files.createDirectories(root.resolve("agent")),
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "--extension",
                            extension.toString(),
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertTrue(console.output.contains("render-width:24:visible"))
        }

    @Test
    fun `interactive mode streams prompts and handles local commands`() =
        runTest {
            val provider = FauxProvider()
            provider.setResponses(
                listOf(
                    FauxResponseStep.Message(fauxAssistantMessage("first")),
                    FauxResponseStep.Message(fauxAssistantMessage("second")),
                ),
            )
            val console =
                ScriptedConsole(
                    listOf(
                        "hello",
                        "/session",
                        "/stats",
                        "!printf shell",
                        "again",
                        "/exit",
                    ),
                )
            val runtime =
                InteractiveRuntime(
                    Models(listOf(provider)),
                    cwd = Files.createTempDirectory("pi-kotlin-interactive"),
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertEquals(2, provider.state.callCount)
            assertTrue(console.output.contains("first"))
            assertTrue(console.output.contains("second"))
            assertTrue(console.output.contains("Session:"))
            assertTrue(console.output.contains("Messages:"))
            assertTrue(console.output.contains("shell"))
        }

    @Test
    fun `interactive mode applies the selected theme on ANSI consoles`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-interactive-theme")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            val theme = readBuiltinThemeJson("dark").toMutableMap()
            theme["name"] = JsonPrimitive("terminal")
            val colors = theme.getValue("colors").jsonObject.toMutableMap()
            colors["accent"] = JsonPrimitive(201)
            theme["colors"] = JsonObject(colors)
            val themesDir = Files.createDirectories(agentDir.resolve("themes"))
            Files.writeString(
                themesDir.resolve("renamed.json"),
                protocolJson.encodeToString(JsonObject.serializer(), JsonObject(theme)),
            )
            Files.writeString(agentDir.resolve("settings.json"), """{"theme":"terminal"}""")
            val console = ScriptedConsole(listOf("/exit"), ansi = true)
            val runtime =
                InteractiveRuntime(
                    Models(listOf(FauxProvider())),
                    cwd = root,
                    agentDir = agentDir,
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertTrue(console.output.contains("\u001B[38;5;201m"))
            assertTrue(console.output.contains("\u001B[1m"))
            assertTrue(console.output.contains("\u001B[38;5;201m> \u001B[39m"))
        }

    @Test
    fun `interactive startup lists system append and agent context files in order`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-interactive-context")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            Files.writeString(agentDir.resolve("SYSTEM.md"), "system")
            Files.writeString(agentDir.resolve("APPEND_SYSTEM.md"), "append")
            Files.writeString(root.resolve("AGENTS.md"), "context")
            val console = ScriptedConsole(listOf("/exit"))
            val runtime =
                InteractiveRuntime(
                    Models(listOf(FauxProvider())),
                    cwd = root,
                    agentDir = agentDir,
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                        ),
                    ),
                )

            assertEquals(0, exit)
            val contextHeader = console.output.indexOf("[Context]")
            val system = console.output.indexOf("SYSTEM.md")
            val append = console.output.indexOf("APPEND_SYSTEM.md")
            val agents = console.output.indexOf("AGENTS.md")
            assertTrue(contextHeader >= 0)
            assertTrue(system > contextHeader)
            assertTrue(append > system)
            assertTrue(agents > append)
        }

    @Test
    fun `interactive prompt follows an in-memory theme selected by an extension`() =
        runTest {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                nodeAvailable(),
                "Node.js 22+ is required for extension runtime tests",
            )
            val projectRoot = java.nio.file.Path.of(requireNotNull(System.getProperty("pi.project.root")))
            val extension = projectRoot.resolve("migration/fixtures/extension-theme.ts")
            val root = Files.createTempDirectory("pi-kotlin-interactive-memory-theme")
            val console = ScriptedConsole(listOf("/theme-probe", "/exit"), ansi = true)
            val runtime =
                InteractiveRuntime(
                    Models(listOf(FauxProvider())),
                    cwd = root,
                    agentDir = Files.createDirectories(root.resolve("agent")),
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "--extension",
                            extension.toString(),
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertTrue(console.output.contains("\u001B[38;2;1;2;3m> \u001B[39m"))
        }

    @Test
    fun `interactive options configure prompt tools thinking and session name`() =
        runTest {
            val provider =
                FauxProvider(
                    definitions =
                        listOf(
                            works.earendil.pi.ai.FauxModelDefinition("faux-1", reasoning = true),
                        ),
                )
            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { context, _, _, _ ->
                        assertTrue(context.systemPrompt.orEmpty().startsWith("custom"))
                        assertTrue(context.tools.isEmpty())
                        fauxAssistantMessage("configured")
                    },
                ),
            )
            val console = ScriptedConsole(listOf("hello", "/session", "/exit"))
            val runtime =
                InteractiveRuntime(
                    Models(listOf(provider)),
                    cwd = Files.createTempDirectory("pi-kotlin-interactive-options"),
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "--system-prompt",
                            "custom",
                            "--no-builtin-tools",
                            "--thinking",
                            "high",
                            "--name",
                            "Configured session",
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertTrue(console.output.contains("Name: Configured session"))
        }

    @Test
    fun `interactive initial prompt includes text files and images`() =
        runTest {
            val cwd = Files.createTempDirectory("pi-kotlin-interactive-files")
            val textFile = cwd.resolve("notes.txt")
            val imageFile = cwd.resolve("pixel.png")
            val imageBytes = byteArrayOf(1, 2, 3, 4)
            Files.writeString(textFile, "file body")
            Files.write(imageFile, imageBytes)
            val provider = FauxProvider()
            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { context, _, _, _ ->
                        val prompt = context.messages.filterIsInstance<works.earendil.pi.ai.UserMessage>().single()
                        val blocks =
                            (prompt.content as works.earendil.pi.ai.MessageContent.Blocks).blocks
                        val text = blocks.filterIsInstance<works.earendil.pi.ai.TextContent>().single().text
                        val image = blocks.filterIsInstance<works.earendil.pi.ai.ImageContent>().single()
                        assertTrue(text.contains("<file name=\"$textFile\">\nfile body\n</file>"))
                        assertTrue(text.endsWith("question"))
                        assertTrue(text.contains("<file name=\"$imageFile\"></file>"))
                        assertEquals("image/png", image.mimeType)
                        assertEquals(Base64.getEncoder().encodeToString(imageBytes), image.data)
                        fauxAssistantMessage("received")
                    },
                ),
            )
            val console = ScriptedConsole(listOf("/exit"))
            val runtime =
                InteractiveRuntime(
                    Models(listOf(provider)),
                    cwd = cwd,
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "@notes.txt",
                            "@pixel.png",
                            "question",
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertTrue(console.output.contains("received"))
        }

    @Test
    fun `interactive missing initial file fails before provider execution`() =
        runTest {
            val provider = FauxProvider()
            val console = ScriptedConsole(emptyList())
            val runtime =
                InteractiveRuntime(
                    Models(listOf(provider)),
                    cwd = Files.createTempDirectory("pi-kotlin-interactive-missing-file"),
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "@missing.txt",
                            "question",
                        ),
                    ),
                )

            assertEquals(1, exit)
            assertEquals(0, provider.state.callCount)
            assertTrue(console.output.contains("Error: File not found:"))
        }

    @Test
    fun `resume picker opens the selected persisted session`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-resume")
            val sessionDir = Files.createDirectories(root.resolve("sessions"))
            val session = SessionManager.create(root, sessionDir)
            session.appendMessage(works.earendil.pi.ai.UserMessage("remember this"))
            session.appendMessage(fauxAssistantMessage("remembered"))
            session.appendSessionInfo("Saved session")
            val picker = ScriptedConsole(listOf("1"))
            val conversation = ScriptedConsole(listOf("/session", "/exit"))
            val consoles = ArrayDeque(listOf(picker, conversation))
            val runtime =
                InteractiveRuntime(
                    Models(listOf(FauxProvider())),
                    cwd = root,
                    consoleFactory = { consoles.removeFirst() },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--resume",
                            "--session-dir",
                            sessionDir.toString(),
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertTrue(picker.output.contains("Saved session"))
            assertTrue(conversation.output.contains("Session: ${session.getSessionId()}"))
        }

    @Test
    fun `interactive login persists credentials and logout removes them`() =
        runTest {
            val credential =
                OAuthCredential(
                    access = "access",
                    refresh = "refresh",
                    expires = System.currentTimeMillis() + 60_000,
                )
            val oauth =
                object : OAuthAuth {
                    override val name: String = "Test OAuth"

                    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
                        val method =
                            interaction.prompt(
                                AuthPrompt.Select(
                                    "Select test login method:",
                                    listOf(AuthOption("browser", "Browser login")),
                                ),
                            )
                        assertEquals("browser", method)
                        return credential
                    }

                    override suspend fun refresh(credential: OAuthCredential): OAuthCredential = credential

                    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
                        ModelAuth(apiKey = credential.access)
                }
            val provider = FauxProvider(oauth = oauth)
            val store = InMemoryCredentialStore()
            val console =
                ScriptedConsole(
                    listOf(
                        "/login faux",
                        "",
                        "/logout faux",
                        "/exit",
                    ),
                )
            val runtime =
                InteractiveRuntime(
                    Models(listOf(provider), InMemoryModelsStore(), store),
                    cwd = Files.createTempDirectory("pi-kotlin-interactive-auth"),
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertEquals(null, store.read("faux"))
            assertTrue(console.output.contains("Logged in to Faux."))
            assertTrue(console.output.contains("Logged out of Faux."))
        }

    @Test
    fun `interactive login supports provider API key workflows`() =
        runTest {
            val base = FauxProvider()
            var refreshAllowNetwork: Boolean? = null
            val provider =
                object : Provider by base {
                    override val id: String = "api-login"
                    override val name: String = "API Login"
                    override val oauth: OAuthAuth? = null
                    override val apiKey: ApiKeyAuth =
                        object : ApiKeyAuth {
                            override val name: String = "API Login"
                            override val supportsLogin: Boolean = true

                            override suspend fun login(interaction: AuthInteraction): ApiKeyCredential =
                                ApiKeyCredential(
                                    interaction.prompt(
                                        AuthPrompt.Text(
                                            message = "Enter API key:",
                                            secret = true,
                                        ),
                                    ),
                                )

                            override suspend fun resolve(
                                context: AuthContext,
                                credential: ApiKeyCredential?,
                            ): AuthResult? =
                                credential?.key?.let { key ->
                                    AuthResult(ModelAuth(apiKey = key), "stored API key")
                                }
                        }

                    override fun getModels() = emptyList<works.earendil.pi.ai.Model>()

                    override val supportsModelRefresh: Boolean = true

                    override suspend fun refreshModels(context: RefreshModelsContext) {
                        refreshAllowNetwork = context.allowNetwork
                    }
                }
            val store = InMemoryCredentialStore()
            val console =
                ScriptedConsole(
                    listOf(
                        "/login api-login",
                        "stored-secret",
                        "/logout api-login",
                        "/exit",
                    ),
                )
            val runtime =
                InteractiveRuntime(
                    Models(listOf(base, provider), InMemoryModelsStore(), store),
                    cwd = Files.createTempDirectory("pi-kotlin-interactive-api-key-auth"),
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "--offline",
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertEquals(null, store.read("api-login"))
            assertEquals(false, refreshAllowNetwork)
            assertTrue(console.output.contains("Enter API key:"))
            assertEquals(listOf("Enter API key: "), console.secretPrompts)
            assertTrue(console.output.contains("Logged in to API Login."))
            assertTrue(console.output.contains("Logged out of API Login."))
        }

    @Test
    fun `verbose overrides quiet startup settings`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-interactive-verbose")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            Files.writeString(agentDir.resolve("settings.json"), """{"quietStartup":true}""")
            val quietConsole = ScriptedConsole(listOf("/exit"))
            val verboseConsole = ScriptedConsole(listOf("/exit"))

            val quietExit =
                InteractiveRuntime(
                    Models(listOf(FauxProvider())),
                    cwd = root,
                    agentDir = agentDir,
                    consoleFactory = { quietConsole },
                ).run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                        ),
                    ),
                )
            val verboseExit =
                InteractiveRuntime(
                    Models(listOf(FauxProvider())),
                    cwd = root,
                    agentDir = agentDir,
                    consoleFactory = { verboseConsole },
                ).run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "--verbose",
                        ),
                    ),
                )

            assertEquals(0, quietExit)
            assertEquals(0, verboseExit)
            assertFalse(quietConsole.output.contains("pi Kotlin"))
            assertTrue(verboseConsole.output.contains("pi Kotlin"))
            assertTrue(verboseConsole.output.contains("Type /help for commands."))
        }

    @Test
    fun `interactive startup reports package updates and skips checks offline`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-interactive-package-updates")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            val updateConsole = PackageUpdateConsole()
            var onlineChecks = 0
            val onlineExit =
                InteractiveRuntime(
                    Models(listOf(FauxProvider())),
                    cwd = root,
                    agentDir = agentDir,
                    consoleFactory = { updateConsole },
                    packageUpdateChecker = { _, _, _ ->
                        onlineChecks++
                        listOf("example", "github.com/example/repo")
                    },
                ).run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                        ),
                    ),
                )
            val offlineConsole = ScriptedConsole(listOf("/exit"))
            var offlineChecks = 0
            val offlineExit =
                InteractiveRuntime(
                    Models(listOf(FauxProvider())),
                    cwd = root,
                    agentDir = agentDir,
                    consoleFactory = { offlineConsole },
                    packageUpdateChecker = { _, _, _ ->
                        offlineChecks++
                        listOf("must-not-render")
                    },
                ).run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "--offline",
                        ),
                    ),
                )

            assertEquals(0, onlineExit)
            assertEquals(1, onlineChecks)
            assertTrue(updateConsole.notificationObserved)
            assertTrue(updateConsole.output.contains("Package Updates Available"))
            assertTrue(updateConsole.output.contains("Run pi update --extensions"))
            assertTrue(updateConsole.output.contains("- example"))
            assertTrue(updateConsole.output.contains("- github.com/example/repo"))
            assertEquals(0, offlineExit)
            assertEquals(0, offlineChecks)
            assertFalse(offlineConsole.output.contains("Package Updates Available"))
        }

    @Test
    fun `interactive trust prompt persists selection before project extensions load`() =
        runTest {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                nodeAvailable(),
                "Node.js 22+ is required for extension runtime tests",
            )
            val root = Files.createTempDirectory("pi-kotlin-interactive-trust")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            val projectExtensions = Files.createDirectories(root.resolve(".pi").resolve("extensions"))
            Files.writeString(
                projectExtensions.resolve("project.ts"),
                """
                export default function(pi) {
                  pi.on("session_start", (_event, ctx) => {
                    ctx.ui.notify("trusted project extension loaded", "info");
                  });
                }
                """.trimIndent(),
            )
            val console = ScriptedConsole(listOf("1", "/exit"))
            val runtime =
                InteractiveRuntime(
                    Models(listOf(FauxProvider())),
                    cwd = root,
                    agentDir = agentDir,
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertEquals(true, ProjectTrustStore(agentDir).get(root))
            assertTrue(console.output.contains("Trust project folder?"))
            assertTrue(console.output.contains("Trust parent folder"))
            assertTrue(console.output.contains("trusted project extension loaded"))
        }

    @Test
    fun `interactive extension dialogs return selected confirmed and entered values`() =
        runTest {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                nodeAvailable(),
                "Node.js 22+ is required for extension runtime tests",
            )
            val root = Files.createTempDirectory("pi-kotlin-interactive-extension-dialogs")
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
                              ctx.ui.notify(`${'$'}{choice}|${'$'}{confirmed}|${'$'}{name}`, "info");
                            },
                          });
                        }
                        """.trimIndent(),
                    )
                }
            val console = ScriptedConsole(listOf("/dialogs", "2", "y", "Ada", "/exit"))
            val runtime =
                InteractiveRuntime(
                    Models(listOf(FauxProvider())),
                    cwd = root,
                    agentDir = Files.createDirectories(root.resolve("agent")),
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "--extension",
                            extension.toString(),
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertTrue(console.output.contains("Choose"))
            assertTrue(console.output.contains("2. beta"))
            assertTrue(console.output.contains("Continue?"))
            assertTrue(console.output.contains("beta|true|Ada"))
        }

    @Test
    fun `interactive component surfaces and focused custom UI render at terminal width`() =
        runTest {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                nodeAvailable(),
                "Node.js 22+ is required for extension runtime tests",
            )
            val root = Files.createTempDirectory("pi-kotlin-interactive-custom-ui")
            val extension =
                root.resolve("custom-ui.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        import { Key, matchesKey } from "@earendil-works/pi-tui";

                        export default function(pi) {
                          pi.on("session_start", (_event, ctx) => {
                            ctx.ui.setStatus("phase", "ready");
                            ctx.ui.setHeader(() => ({
                              render(width) { return [`surface-header:${'$'}{width}`]; },
                            }));
                            ctx.ui.setWidget("array", ["surface-array"]);
                            ctx.ui.setWidget("factory", () => ({
                              render(width) { return [`surface-widget:${'$'}{width}`]; },
                            }), { placement: "belowEditor" });
                            ctx.ui.setFooter((_tui, _theme, footerData) => ({
                              render(width) {
                                const statuses = [...footerData.getExtensionStatuses()]
                                  .map(([key, value]) => `${'$'}{key}=${'$'}{value}`)
                                  .join(",");
                                return [`surface-footer:${'$'}{width}:${'$'}{statuses}`];
                              },
                            }));
                          });
                          pi.registerCommand("choose", {
                            async handler(_args, ctx) {
                              const result = await ctx.ui.custom((_tui, _theme, _keybindings, done) => {
                                let selected = 0;
                                return {
                                  render(width) {
                                    return [`custom-frame:${'$'}{width}:${'$'}{selected === 0 ? "alpha" : "beta"}`];
                                  },
                                  handleInput(input) {
                                    if (matchesKey(input, Key.down)) selected = 1;
                                    if (matchesKey(input, Key.enter)) done(selected === 0 ? "alpha" : "beta");
                                  },
                                };
                              });
                              ctx.ui.notify(`chosen:${'$'}{result}`, "info");
                            },
                          });
                        }
                        """.trimIndent(),
                    )
                }
            val console =
                ScriptedConsole(
                    listOf(
                        "/choose",
                        "down",
                        "enter",
                        "/exit",
                    ),
                    terminalWidth = 23,
                )
            val runtime =
                InteractiveRuntime(
                    Models(listOf(FauxProvider())),
                    cwd = root,
                    agentDir = Files.createDirectories(root.resolve("agent")),
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "--extension",
                            extension.toString(),
                        ),
                    ),
                )

            assertEquals(0, exit)
            val output = console.output.toString()
            assertTrue(output.contains("surface-header:23"))
            assertEquals(1, output.split("surface-header:23").size - 1)
            assertFalse(output.contains("pi Kotlin faux/faux-1"))
            assertTrue(output.contains("surface-array"))
            assertTrue(output.contains("surface-widget:23"))
            assertTrue(output.contains("surface-footer:23:phase=ready"))
            assertTrue(output.contains("custom-frame:23:alpha"))
            assertTrue(output.contains("custom-frame:23:beta"))
            assertTrue(output.contains("chosen:beta"))
            assertTrue(output.endsWith("> "))
        }

    @Test
    fun `custom UI line commands map to terminal input sequences`() {
        assertEquals("\u001b[A", extensionCustomInputSequence("up"))
        assertEquals("\u001b[B", extensionCustomInputSequence("DOWN"))
        assertEquals("\r", extensionCustomInputSequence(""))
        assertEquals("\r", extensionCustomInputSequence("enter"))
        assertEquals("\u001b", extensionCustomInputSequence("escape"))
        assertEquals("typed text", extensionCustomInputSequence("typed text"))
    }

    @Test
    fun `interactive extension dialogs stop blocking on timeout and abort`() =
        runTest {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                nodeAvailable(),
                "Node.js 22+ is required for extension runtime tests",
            )
            val root = Files.createTempDirectory("pi-kotlin-interactive-extension-dialog-cancellation")
            val extension =
                java.nio.file.Path
                    .of(requireNotNull(System.getProperty("pi.project.root")))
                    .resolve("migration/fixtures/extension-runtime/dialog-cancellation.ts")
            val console = CancellingDialogConsole()
            val runtime =
                InteractiveRuntime(
                    Models(listOf(FauxProvider())),
                    cwd = root,
                    agentDir = Files.createDirectories(root.resolve("agent")),
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "--extension",
                            extension.toString(),
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertEquals(
                2,
                console.dialogCancellationCount.get(),
                "output=${console.output}; waitTimeouts=${console.dialogWaitTimeoutCount.get()}",
            )
            assertEquals(0, console.dialogWaitTimeoutCount.get(), "output=${console.output}")
            assertTrue(console.output.contains("dialog-cancelled:timeout|aborted"))
        }

    @Test
    fun `interactive extension shortcuts execute handlers and preserve the editor buffer`() =
        runTest {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                nodeAvailable(),
                "Node.js 22+ is required for extension runtime tests",
            )
            val root = Files.createTempDirectory("pi-kotlin-interactive-extension-shortcut")
            val extension =
                root.resolve("shortcut.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        export default function(pi) {
                          pi.registerShortcut("ctrl+y", {
                            description: "Ask for a name",
                            async handler(ctx) {
                              const name = await ctx.ui.input("Shortcut name", "name");
                              ctx.ui.notify(`shortcut:${'$'}{name}`, "info");
                            },
                          });
                        }
                        """.trimIndent(),
                    )
                }
            val console = ShortcutConsole()
            val runtime =
                InteractiveRuntime(
                    Models(listOf(FauxProvider())),
                    cwd = root,
                    agentDir = Files.createDirectories(root.resolve("agent")),
                    consoleFactory = { console },
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "--extension",
                            extension.toString(),
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertEquals("ctrl+y", console.shortcutKey)
            assertEquals("draft", console.restoredBuffer)
            assertTrue(console.output.contains("Shortcut name"))
            assertTrue(console.output.contains("shortcut:Ada"))
            assertTrue(console.output.contains("built-in shortcut for tui.editor.yank"))
            assertTrue(console.output.contains("Ctrl+Y"))
            assertTrue(console.output.contains("Ask for a name"))
        }

    private fun nodeAvailable(): Boolean =
        runCatching {
            val process = ProcessBuilder("node", "--version").start()
            process.waitFor()
            process.exitValue() == 0 &&
                process.inputStream.bufferedReader().readText().trim().removePrefix("v").substringBefore('.').toInt() >= 22
        }.getOrDefault(false)

    private class ScriptedConsole(
        private val inputs: List<String>,
        private val terminalWidth: Int = 80,
        private val ansi: Boolean = false,
    ) : InteractiveConsole {
        private var index = 0
        val output = StringBuilder()
        val secretPrompts = mutableListOf<String>()

        override fun readLine(prompt: String): String? {
            output.append(prompt)
            return inputs.getOrNull(index++)
        }

        override fun readSecret(prompt: String): String? {
            secretPrompts += prompt
            output.append(prompt)
            return inputs.getOrNull(index++)
        }

        override fun print(text: String) {
            output.append(text)
        }

        override fun println(text: String) {
            output.append(text).append('\n')
        }

        override fun error(text: String) {
            output.append("Error: ").append(text).append('\n')
        }

        override fun width(): Int = terminalWidth

        override fun supportsAnsi(): Boolean = ansi
    }

    private class CancellingDialogConsole : InteractiveConsole {
        private val commandInputs = ArrayDeque(listOf("/cancel-dialogs", "/exit"))
        val dialogCancellationCount = AtomicInteger()
        val dialogWaitTimeoutCount = AtomicInteger()
        val output = StringBuilder()

        override fun readLine(prompt: String): String? {
            synchronized(output) {
                output.append(prompt)
            }
            return commandInputs.removeFirstOrNull()
        }

        override fun readLine(
            prompt: String,
            cancellation: ExtensionUiCancellation,
        ): String? {
            synchronized(output) {
                output.append(prompt)
            }
            val cancelled = CountDownLatch(1)
            val registration =
                cancellation.onCancellation {
                    dialogCancellationCount.incrementAndGet()
                    cancelled.countDown()
                }
            return try {
                if (!cancelled.await(2, TimeUnit.SECONDS)) {
                    dialogWaitTimeoutCount.incrementAndGet()
                    "late"
                } else {
                    null
                }
            } finally {
                registration.close()
            }
        }

        override fun print(text: String) {
            synchronized(output) {
                output.append(text)
            }
        }

        override fun println(text: String) {
            synchronized(output) {
                output.append(text).append('\n')
            }
        }

        override fun error(text: String) {
            synchronized(output) {
                output.append("Error: ").append(text).append('\n')
            }
        }
    }

    private class PackageUpdateConsole : InteractiveConsole {
        private val notification = CountDownLatch(1)
        val output = StringBuilder()
        var notificationObserved = false

        override fun readLine(prompt: String): String? {
            output.append(prompt)
            notificationObserved = notification.await(2, TimeUnit.SECONDS)
            return "/exit"
        }

        override fun print(text: String) {
            output.append(text)
        }

        override fun println(text: String) {
            output.append(text).append('\n')
        }

        override fun printlnAbove(text: String) {
            output.append(text).append('\n')
            notification.countDown()
        }

        override fun error(text: String) {
            output.append("Error: ").append(text).append('\n')
        }
    }

    private class ShortcutConsole : InteractiveConsole {
        private var mainReadCount = 0
        val output = StringBuilder()
        var shortcutKey: String? = null
        var restoredBuffer: String? = null

        override fun readLine(prompt: String): String? {
            output.append(prompt)
            return "Ada"
        }

        override fun readLineWithShortcuts(
            prompt: String,
            shortcuts: List<InteractiveShortcutBinding>,
            initialBuffer: String,
        ): InteractiveReadResult {
            output.append(prompt)
            return when (mainReadCount++) {
                0 -> {
                    val shortcut = shortcuts.single()
                    shortcutKey = shortcut.key
                    InteractiveReadResult.Shortcut(shortcut.id, "draft")
                }

                1 -> {
                    restoredBuffer = initialBuffer
                    InteractiveReadResult.Line("/hotkeys")
                }

                else -> InteractiveReadResult.Line("/exit")
            }
        }

        override fun print(text: String) {
            output.append(text)
        }

        override fun println(text: String) {
            output.append(text).append('\n')
        }

        override fun error(text: String) {
            output.append("Error: ").append(text).append('\n')
        }
    }
}
