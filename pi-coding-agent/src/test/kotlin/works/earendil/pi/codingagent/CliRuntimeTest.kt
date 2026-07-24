package works.earendil.pi.codingagent

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.earendil.pi.ai.BashExecutionMessage
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.FauxResponseStep
import works.earendil.pi.ai.InMemoryCredentialStore
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.ai.contentText
import works.earendil.pi.ai.fauxAssistantMessage
import works.earendil.pi.ai.providers.builtInModels
import works.earendil.pi.ai.providers.githubCopilotProvider
import works.earendil.pi.codingagent.session.NewSessionOptions
import works.earendil.pi.codingagent.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliRuntimeTest {
    @Test
    fun `print mode runs a provider and emits text`() =
        runTest {
            val provider = FauxProvider()
            provider.setResponses(listOf(FauxResponseStep.Message(fauxAssistantMessage("ok"))))
            val stdout = StringWriter()
            val stderr = StringWriter()
            val runtime =
                CliRuntime(
                    models = Models(listOf(provider)),
                    cwd = Files.createTempDirectory("pi-kotlin-cli"),
                    stdout = PrintWriter(stdout, true),
                    stderr = PrintWriter(stderr, true),
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
                            "-p",
                            "hello",
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertEquals("ok\n", stdout.toString())
            assertEquals("", stderr.toString())
        }

    @Test
    fun `list models supports fuzzy provider model queries`() =
        runTest {
            val provider = FauxProvider()
            val stdout = StringWriter()
            val runtime =
                CliRuntime(
                    Models(listOf(provider)),
                    stdout = PrintWriter(stdout, true),
                    stderr = PrintWriter(StringWriter(), true),
                )

            assertEquals(0, runtime.run(parseArgs(listOf("--list-models", "faux/faux-1"))))
            assertTrue(stdout.toString().contains("faux/faux-1"))
        }

    @Test
    fun `list models filters GitHub Copilot to the authenticated account catalog`() =
        runTest {
            val catalogModels = builtInModels("github-copilot").take(2)
            val selected = catalogModels.first()
            val excluded = catalogModels.last()
            val stdout = StringWriter()
            val runtime =
                CliRuntime(
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
                    ),
                    stdout = PrintWriter(stdout, true),
                    stderr = PrintWriter(StringWriter(), true),
                )

            assertEquals(0, runtime.run(parseArgs(listOf("--list-models", "github-copilot"))))

            assertTrue(stdout.toString().contains("github-copilot/${selected.id}"))
            assertFalse(stdout.toString().contains("github-copilot/${excluded.id}"))
        }

    @Test
    fun `multiple prompts print only the final assistant response`() =
        runTest {
            val provider = FauxProvider()
            provider.setResponses(
                listOf(
                    FauxResponseStep.Message(fauxAssistantMessage("intermediate")),
                    FauxResponseStep.Message(fauxAssistantMessage("final")),
                ),
            )
            val stdout = StringWriter()
            val runtime =
                CliRuntime(
                    Models(listOf(provider)),
                    cwd = Files.createTempDirectory("pi-kotlin-cli-multiple"),
                    stdout = PrintWriter(stdout, true),
                    stderr = PrintWriter(StringWriter(), true),
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
                            "-p",
                            "first",
                            "second",
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertEquals("final\n", stdout.toString())
            assertEquals(2, provider.state.callCount)
        }

    @Test
    fun `session id persists and continue restores provider context`() =
        runTest {
            val provider = FauxProvider()
            provider.setResponses(listOf(FauxResponseStep.Message(fauxAssistantMessage("first response"))))
            val cwd = Files.createTempDirectory("pi-kotlin-cli-continue")
            val sessionDir = Files.createDirectories(cwd.resolve("sessions"))
            val firstError = StringWriter()
            val first =
                CliRuntime(
                    Models(listOf(provider)),
                    cwd = cwd,
                    stdout = PrintWriter(StringWriter(), true),
                    stderr = PrintWriter(firstError, true),
                )

            assertEquals(
                0,
                first.run(
                    parseArgs(
                        listOf(
                            "--session-dir",
                            sessionDir.toString(),
                            "--session-id",
                            "continued-session",
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "-p",
                            "first prompt",
                        ),
                    ),
                ),
            )
            assertTrue(firstError.toString().contains("creating a new session"))

            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { context, _, _, _ ->
                        val userMessages = context.messages.filterIsInstance<UserMessage>()
                        assertEquals(2, userMessages.size)
                        assertEquals("first prompt", contentText(userMessages.first().content))
                        assertEquals("second prompt", contentText(userMessages.last().content))
                        assertTrue(
                            context.messages
                                .filterIsInstance<works.earendil.pi.ai.AssistantMessage>()
                                .any { contentText(it.content) == "first response" },
                        )
                        fauxAssistantMessage("continued response")
                    },
                ),
            )
            val secondOutput = StringWriter()
            val second =
                CliRuntime(
                    Models(listOf(provider)),
                    cwd = cwd,
                    stdout = PrintWriter(secondOutput, true),
                    stderr = PrintWriter(StringWriter(), true),
                )

            val secondExit =
                second.run(
                    parseArgs(
                        listOf(
                            "--session-dir",
                            sessionDir.toString(),
                            "--continue",
                            "-p",
                            "second prompt",
                        ),
                    ),
                )

            assertEquals(0, secondExit)
            assertEquals("continued response\n", secondOutput.toString())
        }

    @Test
    fun `provider context applies coding message projection to resumed sessions`() =
        runTest {
            val cwd = Files.createTempDirectory("pi-kotlin-cli-projection")
            val sessionDir = Files.createDirectories(cwd.resolve("sessions"))
            val session =
                SessionManager.create(
                    cwd,
                    sessionDir,
                    NewSessionOptions(id = "projection-session"),
                )
            session.appendMessage(UserMessage("history", 1))
            session.appendMessage(
                BashExecutionMessage(
                    command = "printf secret",
                    output = "secret",
                    excludeFromContext = true,
                    timestamp = 2,
                ),
            )
            session.appendMessage(
                BashExecutionMessage(
                    command = "printf visible",
                    output = "visible",
                    exitCode = 0,
                    timestamp = 3,
                ),
            )
            session.appendMessage(fauxAssistantMessage("previous response"))
            val sessionFile = assertNotNull(session.getSessionFile())
            val provider = FauxProvider()
            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { context, _, _, _ ->
                        assertTrue(context.messages.none { it is BashExecutionMessage })
                        val userText =
                            context.messages
                                .filterIsInstance<UserMessage>()
                                .map { contentText(it.content) }
                        assertTrue(userText.none { it.contains("secret") })
                        assertTrue(userText.contains("Ran `printf visible`\n```\nvisible\n```"))
                        assertEquals("next prompt", userText.last())
                        fauxAssistantMessage("projected")
                    },
                ),
            )
            val stdout = StringWriter()
            val runtime =
                CliRuntime(
                    Models(listOf(provider)),
                    cwd = cwd,
                    stdout = PrintWriter(stdout, true),
                    stderr = PrintWriter(StringWriter(), true),
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--session",
                            sessionFile.toString(),
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "-p",
                            "next prompt",
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertEquals("projected\n", stdout.toString())
        }

    @Test
    fun `json mode emits session header and lifecycle events as jsonl`() =
        runTest {
            val provider = FauxProvider()
            provider.setResponses(listOf(FauxResponseStep.Message(fauxAssistantMessage("ok"))))
            val stdout = StringWriter()
            val runtime =
                CliRuntime(
                    Models(listOf(provider)),
                    cwd = Files.createTempDirectory("pi-kotlin-cli-json"),
                    stdout = PrintWriter(stdout, true),
                    stderr = PrintWriter(StringWriter(), true),
                )

            assertEquals(
                0,
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "--mode",
                            "json",
                            "hello",
                        ),
                    ),
                ),
            )

            val lines = stdout.toString().lineSequence().filter(String::isNotBlank).toList()
            val objects = lines.map { Json.parseToJsonElement(it).jsonObject }
            assertEquals("session", objects.first()["type"]?.jsonPrimitive?.contentOrNull)
            val eventTypes = objects.drop(1).mapNotNull { it["type"]?.jsonPrimitive?.contentOrNull }
            assertEquals("agent_start", eventTypes.first())
            assertTrue("message_update" in eventTypes)
            assertEquals("agent_end", eventTypes.last())
        }

    @Test
    fun `stdin file and first message form one initial prompt`() =
        runTest {
            val cwd = Files.createTempDirectory("pi-kotlin-cli-files")
            val file = cwd.resolve("input.txt")
            Files.writeString(file, "file body")
            val expectedPrompt = "stdin<file name=\"$file\">\nfile body\n</file>\nquestion"
            val provider = FauxProvider()
            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { context, _, _, _ ->
                        val prompt = context.messages.filterIsInstance<UserMessage>().single()
                        assertEquals(expectedPrompt, contentText(prompt.content))
                        fauxAssistantMessage(expectedPrompt)
                    },
                ),
            )
            val stdout = StringWriter()
            val runtime =
                CliRuntime(
                    Models(listOf(provider)),
                    cwd = cwd,
                    stdinContent = "stdin",
                    stdout = PrintWriter(stdout, true),
                    stderr = PrintWriter(StringWriter(), true),
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
                            "-p",
                            "@input.txt",
                            "question",
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertEquals("$expectedPrompt\n", stdout.toString())
        }

    @Test
    fun `print mode loads project context and honors no context files`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-cli-context")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            val cwd = Files.createDirectories(root.resolve("workspace").resolve("project"))
            Files.writeString(agentDir.resolve("AGENTS.md"), "global guidance")
            Files.writeString(root.resolve("workspace").resolve("CLAUDE.md"), "workspace guidance")
            Files.writeString(cwd.resolve("AGENTS.md"), "project guidance")
            val provider = FauxProvider()
            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { context, _, _, _ ->
                        val prompt = context.systemPrompt.orEmpty()
                        assertTrue(prompt.indexOf("global guidance") < prompt.indexOf("workspace guidance"))
                        assertTrue(prompt.indexOf("workspace guidance") < prompt.indexOf("project guidance"))
                        fauxAssistantMessage("with context")
                    },
                    FauxResponseStep.Factory { context, _, _, _ ->
                        assertFalse(context.systemPrompt.orEmpty().contains("project guidance"))
                        fauxAssistantMessage("without context")
                    },
                ),
            )
            val runtime =
                CliRuntime(
                    Models(listOf(provider)),
                    cwd = cwd,
                    agentDir = agentDir,
                    stdout = PrintWriter(StringWriter(), true),
                    stderr = PrintWriter(StringWriter(), true),
                )

            assertEquals(
                0,
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "-p",
                            "first",
                        ),
                    ),
                ),
            )
            assertEquals(
                0,
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "--no-context-files",
                            "-p",
                            "second",
                        ),
                    ),
                ),
            )
        }

    @Test
    fun `no builtin tools produces an empty provider tool list`() =
        runTest {
            val provider = FauxProvider()
            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { context, _, _, _ ->
                        assertTrue(context.tools.isEmpty())
                        assertTrue(context.systemPrompt.orEmpty().contains("Available tools:\n(none)"))
                        fauxAssistantMessage("ok")
                    },
                ),
            )
            val runtime =
                CliRuntime(
                    Models(listOf(provider)),
                    cwd = Files.createTempDirectory("pi-kotlin-cli-no-builtins"),
                    agentDir = Files.createTempDirectory("pi-kotlin-cli-agent-dir"),
                    stdout = PrintWriter(StringWriter(), true),
                    stderr = PrintWriter(StringWriter(), true),
                )

            assertEquals(
                0,
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "--no-builtin-tools",
                            "-p",
                            "hello",
                        ),
                    ),
                ),
            )
        }

    @Test
    fun `slash model ids and thinking suffix reach the provider intact`() =
        runTest {
            val provider =
                FauxProvider(
                    definitions =
                        listOf(
                            works.earendil.pi.ai.FauxModelDefinition(
                                id = "vendor/model",
                                reasoning = true,
                            ),
                        ),
                )
            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { _, options, _, model ->
                        assertEquals("vendor/model", model.id)
                        assertEquals(works.earendil.pi.ai.ThinkingLevel.HIGH, options.reasoning)
                        fauxAssistantMessage("selected")
                    },
                ),
            )
            val runtime =
                CliRuntime(
                    Models(listOf(provider)),
                    cwd = Files.createTempDirectory("pi-kotlin-cli-slash-model"),
                    agentDir = Files.createTempDirectory("pi-kotlin-cli-slash-agent"),
                    stdout = PrintWriter(StringWriter(), true),
                    stderr = PrintWriter(StringWriter(), true),
                )

            assertEquals(
                0,
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "vendor/model:high",
                            "--no-session",
                            "-p",
                            "hello",
                        ),
                    ),
                ),
            )
        }
}
