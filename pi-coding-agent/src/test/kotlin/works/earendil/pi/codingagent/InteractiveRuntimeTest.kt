package works.earendil.pi.codingagent

import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthOption
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.FauxResponseStep
import works.earendil.pi.ai.InMemoryCredentialStore
import works.earendil.pi.ai.InMemoryModelsStore
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.fauxAssistantMessage
import works.earendil.pi.codingagent.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InteractiveRuntimeTest {
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
    ) : InteractiveConsole {
        private var index = 0
        val output = StringBuilder()

        override fun readLine(prompt: String): String? {
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
