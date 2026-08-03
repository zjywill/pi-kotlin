package works.earendil.pi.codingagent

import java.net.URI

sealed interface ExperimentalAuthInput {
    data class Token(
        val token: String,
    ) : ExperimentalAuthInput

    data class File(
        val path: String,
    ) : ExperimentalAuthInput
}

data class UnixTransportAddress(
    val path: String,
)

sealed interface ExperimentalCommand {
    val auth: ExperimentalAuthInput?

    data class Pi(
        val listen: List<UnixTransportAddress> = emptyList(),
        override val auth: ExperimentalAuthInput? = null,
        val options: Args = Args(),
    ) : ExperimentalCommand

    data class Server(
        val listen: List<UnixTransportAddress> = emptyList(),
        override val auth: ExperimentalAuthInput? = null,
    ) : ExperimentalCommand

    data class Client(
        val connect: UnixTransportAddress? = null,
        override val auth: ExperimentalAuthInput? = null,
    ) : ExperimentalCommand
}

sealed interface ExperimentalCommandParseResult {
    data class Success(
        val command: ExperimentalCommand,
    ) : ExperimentalCommandParseResult

    data class Failure(
        val errors: List<String>,
    ) : ExperimentalCommandParseResult
}

interface ExperimentalCommandContext {
    suspend fun runPi(command: ExperimentalCommand.Pi)

    suspend fun runServer(command: ExperimentalCommand.Server)

    suspend fun runClient(command: ExperimentalCommand.Client)
}

private data class RawExperimentalOptions(
    var authToken: String? = null,
    var authTokenFile: String? = null,
    val listenValues: MutableList<String> = mutableListOf(),
    var connectValue: String? = null,
    val remainingArgs: MutableList<String> = mutableListOf(),
)

fun parseExperimentalCommand(arguments: List<String>): ExperimentalCommandParseResult {
    val commandName =
        when (arguments.firstOrNull()) {
            "server" -> "server"
            "client" -> "client"
            else -> "pi"
        }
    val input = if (commandName == "pi") arguments else arguments.drop(1)
    val supportedOptions =
        when (commandName) {
            "client" -> CLIENT_VALUE_OPTIONS
            else -> SERVER_VALUE_OPTIONS
        }
    val raw = RawExperimentalOptions()
    val errors = mutableListOf<String>()
    var index = 0
    while (index < input.size) {
        val argument = input[index]
        if (argument == "--") {
            raw.remainingArgs += input.drop(index)
            break
        }
        val equals = argument.indexOf('=')
        val option = if (equals < 0) argument else argument.substring(0, equals)
        if (option !in supportedOptions) {
            raw.remainingArgs += input.drop(index)
            break
        }
        var value = if (equals < 0) null else argument.substring(equals + 1)
        if (value == null) {
            val next = input.getOrNull(index + 1)
            if (next != null && !next.startsWith("-")) {
                value = next
                index++
            }
        }
        if (value.isNullOrEmpty()) {
            errors += "$option requires a value"
            index++
            continue
        }
        when (option) {
            "--listen" -> {
                if (raw.listenValues.isNotEmpty()) {
                    errors += "--listen may only be specified once"
                } else {
                    raw.listenValues += value
                }
            }

            "--connect" -> {
                if (raw.connectValue != null) {
                    errors += "--connect may only be specified once"
                } else {
                    raw.connectValue = value
                }
            }

            "--auth-token" -> {
                if (raw.authToken != null) {
                    errors += "--auth-token may only be specified once"
                } else {
                    raw.authToken = value
                }
            }

            "--auth-token-file" -> {
                if (raw.authTokenFile != null) {
                    errors += "--auth-token-file may only be specified once"
                } else {
                    raw.authTokenFile = value
                }
            }
        }
        index++
    }

    val listen =
        raw.listenValues.mapNotNull { value ->
            parseUnixTransportAddress(value, "--listen").fold(
                onSuccess = { it },
                onFailure = { error ->
                    errors += requireNotNull(error.message)
                    null
                },
            )
        }
    val connect =
        raw.connectValue?.let { value ->
            parseUnixTransportAddress(value, "--connect").fold(
                onSuccess = { it },
                onFailure = { error ->
                    errors += requireNotNull(error.message)
                    null
                },
            )
        }
    val auth =
        when {
            raw.authToken != null && raw.authTokenFile != null -> {
                errors += "--auth-token and --auth-token-file are mutually exclusive"
                null
            }

            raw.authToken != null -> ExperimentalAuthInput.Token(requireNotNull(raw.authToken))
            raw.authTokenFile != null -> ExperimentalAuthInput.File(requireNotNull(raw.authTokenFile))
            else -> null
        }
    val legacyOptions = parseArgs(raw.remainingArgs)
    errors +=
        legacyOptions.diagnostics
            .filter { it.type == Diagnostic.Type.ERROR }
            .map(Diagnostic::message)
    if (commandName == "pi" && legacyOptions.unknownFlags.containsKey("connect")) {
        errors += "--connect is only valid for client mode"
    }
    if (commandName != "pi" && raw.remainingArgs.isNotEmpty()) {
        errors += "The experimental $commandName command does not support existing CLI options yet"
    }
    if (errors.isNotEmpty()) {
        return ExperimentalCommandParseResult.Failure(errors)
    }
    val command =
        when (commandName) {
            "server" -> ExperimentalCommand.Server(listen, auth)
            "client" -> ExperimentalCommand.Client(connect, auth)
            else -> ExperimentalCommand.Pi(listen, auth, legacyOptions)
        }
    return ExperimentalCommandParseResult.Success(command)
}

suspend fun executeExperimentalCommand(
    arguments: List<String>,
    context: ExperimentalCommandContext,
): ExperimentalCommandParseResult {
    val result = parseExperimentalCommand(arguments)
    if (result !is ExperimentalCommandParseResult.Success) {
        return result
    }
    when (val command = result.command) {
        is ExperimentalCommand.Pi -> context.runPi(command)
        is ExperimentalCommand.Server -> context.runServer(command)
        is ExperimentalCommand.Client -> context.runClient(command)
    }
    return result
}

private fun parseUnixTransportAddress(
    value: String,
    option: String,
): Result<UnixTransportAddress> =
    runCatching {
        val uri =
            try {
                URI(value)
            } catch (_: Exception) {
                error("Invalid $option address \"$value\"")
            }
        if (uri.scheme == null) {
            error("Invalid $option address \"$value\"")
        }
        if (uri.scheme != "unix") {
            error("Unsupported $option transport \"${uri.scheme?.let { "$it:" } ?: ""}\"")
        }
        if (uri.rawAuthority != null) {
            error("Unix transport address must not include an authority")
        }
        if (
            !value.startsWith("unix:///") ||
            value.startsWith("unix:////") ||
            uri.rawQuery != null ||
            uri.rawFragment != null ||
            uri.toASCIIString() != value
        ) {
            error("Invalid $option address \"$value\"")
        }
        val path = uri.path ?: error("Invalid $option address \"$value\"")
        if ('\u0000' in path) {
            error("Invalid $option address \"$value\"")
        }
        if (!path.startsWith('/')) {
            error("Unix transport address requires an absolute path")
        }
        UnixTransportAddress(path)
    }

private val SERVER_VALUE_OPTIONS =
    setOf("--listen", "--auth-token", "--auth-token-file")
private val CLIENT_VALUE_OPTIONS =
    setOf("--connect", "--auth-token", "--auth-token-file")
