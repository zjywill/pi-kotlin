package works.earendil.pi.codingagent

import java.nio.file.Files
import java.util.Base64
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
}
