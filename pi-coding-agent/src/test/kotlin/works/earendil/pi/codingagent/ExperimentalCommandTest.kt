package works.earendil.pi.codingagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ExperimentalCommandTest {
    @Test
    fun `composes pi command with existing options`() {
        val result =
            assertIs<ExperimentalCommandParseResult.Success>(
                parseExperimentalCommand(
                    listOf(
                        "--listen",
                        "unix:///tmp/pi.sock",
                        "--auth-token",
                        "secret",
                        "--provider",
                        "anthropic",
                        "--model",
                        "claude-sonnet",
                        "--thinking",
                        "high",
                        "inspect",
                    ),
                ),
            )
        val command = assertIs<ExperimentalCommand.Pi>(result.command)

        assertEquals(listOf(UnixTransportAddress("/tmp/pi.sock")), command.listen)
        assertEquals(ExperimentalAuthInput.Token("secret"), command.auth)
        assertEquals("anthropic", command.options.provider)
        assertEquals("claude-sonnet", command.options.model)
        assertEquals(AgentThinkingLevel.HIGH, command.options.thinking)
        assertEquals(listOf("inspect"), command.options.messages)
    }

    @Test
    fun `stops command option parsing when existing arguments begin`() {
        val systemPrompt =
            assertIs<ExperimentalCommand.Pi>(
                assertIs<ExperimentalCommandParseResult.Success>(
                    parseExperimentalCommand(
                        listOf("--system-prompt", "--listen", "unix:///tmp/pi.sock"),
                    ),
                ).command,
            )
        assertEquals("--listen", systemPrompt.options.systemPrompt)
        assertEquals(listOf("unix:///tmp/pi.sock"), systemPrompt.options.messages)

        val model =
            assertIs<ExperimentalCommand.Pi>(
                assertIs<ExperimentalCommandParseResult.Success>(
                    parseExperimentalCommand(
                        listOf("--model", "claude-sonnet", "--listen=unix:///tmp/second.sock"),
                    ),
                ).command,
            )
        assertEquals("claude-sonnet", model.options.model)
        assertTrue(model.listen.isEmpty())
        assertEquals("unix:///tmp/second.sock", model.options.unknownFlags["listen"])
    }

    @Test
    fun `parses server client auth and empty commands`() {
        assertEquals(
            ExperimentalCommandParseResult.Success(
                ExperimentalCommand.Server(
                    listen = listOf(UnixTransportAddress("/tmp/pi.sock")),
                ),
            ),
            parseExperimentalCommand(listOf("server", "--listen", "unix:///tmp/pi.sock")),
        )
        assertEquals(
            ExperimentalCommandParseResult.Success(
                ExperimentalCommand.Client(
                    connect = UnixTransportAddress("/tmp/pi.sock"),
                    auth = ExperimentalAuthInput.File("/tmp/token"),
                ),
            ),
            parseExperimentalCommand(
                listOf("client", "--connect", "unix:///tmp/pi.sock", "--auth-token-file", "/tmp/token"),
            ),
        )
        listOf(
            emptyList(),
            listOf("server"),
            listOf("client"),
        ).forEach { arguments ->
            val command =
                assertIs<ExperimentalCommandParseResult.Success>(
                    parseExperimentalCommand(arguments),
                ).command
            assertEquals(arguments.firstOrNull() ?: "pi", command.commandName())
            assertEquals(null, command.auth)
        }
    }

    @Test
    fun `rejects invalid input with upstream error ordering`() {
        val cases =
            listOf(
                listOf("--listen", "unix:///tmp/pi.sock", "--listen", "unix:///tmp/pi-admin.sock") to
                    "--listen may only be specified once",
                listOf("--auth-token", "secret", "--auth-token-file", "/tmp/token") to
                    "--auth-token and --auth-token-file are mutually exclusive",
                listOf("--listen", "/tmp/pi.sock") to "Invalid --listen address",
                listOf("--listen", "ws://localhost:8080") to "Unsupported --listen transport",
                listOf("--listen", "unix://relative.sock") to "Unix transport address must not include an authority",
                listOf("--listen", "unix:///tmp/pi.sock?wrong=value") to "Invalid --listen address",
                listOf("--listen", "unix:///tmp/%00pi.sock") to "Invalid --listen address",
                listOf("client", "--connect", "ws://localhost:8080") to "Unsupported --connect transport",
                listOf("--listen") to "--listen requires a value",
                listOf("--connect=") to "--connect is only valid for client mode",
            )
        cases.forEach { (arguments, error) ->
            val failure =
                assertIs<ExperimentalCommandParseResult.Failure>(
                    parseExperimentalCommand(arguments),
                    "arguments=$arguments",
                )
            assertTrue(failure.errors.any { error in it }, "arguments=$arguments errors=${failure.errors}")
        }

        assertEquals(
            listOf("The experimental client command does not support existing CLI options yet"),
            assertIs<ExperimentalCommandParseResult.Failure>(
                parseExperimentalCommand(
                    listOf(
                        "client",
                        "--listen",
                        "ws://localhost:8080",
                        "--auth-token",
                        "secret",
                        "--auth-token-file",
                        "/tmp/token",
                    ),
                ),
            ).errors,
        )
        assertEquals(
            listOf(
                "Invalid UI mode \"wrong\". Valid values: regular, fullscreen",
                "The experimental client command does not support existing CLI options yet",
            ),
            assertIs<ExperimentalCommandParseResult.Failure>(
                parseExperimentalCommand(
                    listOf("client", "--ui-mode", "wrong", "--model", "claude-sonnet"),
                ),
            ).errors,
        )
    }

    @Test
    fun `passes separator file and unknown arguments to pi parser`() {
        val command =
            assertIs<ExperimentalCommand.Pi>(
                assertIs<ExperimentalCommandParseResult.Success>(
                    parseExperimentalCommand(
                        listOf("--unknown", "@prompt.md", "--", "--listen", "unix:///tmp/pi.sock"),
                    ),
                ).command,
            )

        assertEquals(listOf("prompt.md"), command.options.fileArgs)
        assertEquals(true, command.options.unknownFlags["unknown"])
        assertEquals("unix:///tmp/pi.sock", command.options.unknownFlags["listen"])
        assertFalse(command.options.diagnostics.any { it.type == Diagnostic.Type.ERROR })
    }

    @Test
    fun `executes selected command`() =
        runTest {
            val calls = mutableListOf<String>()
            val context =
                object : ExperimentalCommandContext {
                    override suspend fun runPi(command: ExperimentalCommand.Pi) {
                        calls += "pi"
                    }

                    override suspend fun runServer(command: ExperimentalCommand.Server) {
                        calls += "server"
                    }

                    override suspend fun runClient(command: ExperimentalCommand.Client) {
                        calls += "client"
                    }
                }

            listOf(
                emptyList(),
                listOf("server"),
                listOf("client"),
            ).forEach { arguments ->
                assertIs<ExperimentalCommandParseResult.Success>(
                    executeExperimentalCommand(arguments, context),
                )
            }
            assertEquals(listOf("pi", "server", "client"), calls)
        }

    private fun ExperimentalCommand.commandName(): String =
        when (this) {
            is ExperimentalCommand.Pi -> "pi"
            is ExperimentalCommand.Server -> "server"
            is ExperimentalCommand.Client -> "client"
        }
    }
