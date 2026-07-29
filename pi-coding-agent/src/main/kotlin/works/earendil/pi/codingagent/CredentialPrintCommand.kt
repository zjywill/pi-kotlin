package works.earendil.pi.codingagent

import java.io.PrintStream
import kotlinx.coroutines.CancellationException
import works.earendil.pi.ai.AuthResolutionOverrides
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.Models

internal enum class CredentialPrintKind {
    API_KEY,
    BEARER_TOKEN,
}

internal data class CredentialPrintCommand(
    val kind: CredentialPrintKind,
    val arguments: List<String>,
    val minExpiryMs: Long? = null,
)

internal class CredentialPrintException(
    message: String,
) : RuntimeException(message)

internal fun isCredentialPrintHelp(arguments: List<String>): Boolean =
    arguments.firstOrNull() == "auth" &&
        arguments.getOrNull(1) in setOf(null, "help", "--help", "-h")

internal fun printCredentialPrintHelp(output: PrintStream = System.out) {
    output.println(
        """
        Usage:
          pi auth print-api-key --model <model> [--provider <provider>]
          pi auth print-bearer-token --model <model> [--provider <provider>] [--min-expiry <duration>]

        Prints the configured credential alone on stdout. Provider inference uses configured credentials; specify --provider to select explicitly. Bearer tokens have a 30-minute minimum expiry by default. --min-expiry accepts ms, s, m, or h (for example, 30m).
        """.trimIndent(),
    )
}

internal fun parseCredentialPrintCommand(arguments: List<String>): CredentialPrintCommand? {
    if (arguments.firstOrNull() != "auth") {
        return null
    }
    val kind =
        when (arguments.getOrNull(1)) {
            "print-api-key" -> CredentialPrintKind.API_KEY
            "print-bearer-token" -> CredentialPrintKind.BEARER_TOKEN
            else ->
                throw CredentialPrintException(
                    "Unknown auth command \"${arguments.getOrNull(1).orEmpty()}\". " +
                        "Use \"pi auth print-api-key\" or \"pi auth print-bearer-token\".",
                )
        }

    val commandArguments = mutableListOf<String>()
    var minExpiryMs: Long? = null
    var index = 2
    while (index < arguments.size) {
        if (arguments[index] != "--min-expiry") {
            commandArguments += arguments[index]
            index++
            continue
        }
        if (kind != CredentialPrintKind.BEARER_TOKEN) {
            throw CredentialPrintException("--min-expiry is only supported by print-bearer-token")
        }
        val value = arguments.getOrNull(index + 1)
        minExpiryMs = parseCredentialDuration(value)
        index += 2
    }
    return CredentialPrintCommand(kind, commandArguments, minExpiryMs)
}

internal fun validateCredentialPrintArgs(arguments: Args) {
    if (arguments.model.isNullOrBlank()) {
        throw CredentialPrintException("Credential printing requires --model <model>")
    }
    if (arguments.apiKey != null) {
        throw CredentialPrintException(
            "Credential printing reads configured credentials; --api-key is not supported",
        )
    }
    if (
        arguments.messages.isNotEmpty() ||
        arguments.fileArgs.isNotEmpty() ||
        arguments.unknownFlags.isNotEmpty()
    ) {
        throw CredentialPrintException("Credential printing only accepts --provider and --model")
    }
}

internal suspend fun resolveCredentialForPrint(
    arguments: Args,
    models: Models,
    kind: CredentialPrintKind,
    minExpiryMs: Long? = null,
): String {
    validateCredentialPrintArgs(arguments)
    val modelPattern = requireNotNull(arguments.model).trim()
    val credentialTypes =
        models
            .listCredentials()
            .associate { it.providerId to it.type }
    val candidates =
        if (arguments.provider != null) {
            listOf(resolveExplicitCredentialModel(arguments.provider.orEmpty(), modelPattern, models))
        } else {
            models
                .getProviders()
                .asSequence()
                .filter { it.id in credentialTypes }
                .mapNotNull { provider ->
                    findCredentialModel(provider.id, modelPattern, models.getModels(provider.id))
                }.toList()
                .ifEmpty {
                    throw CredentialPrintException(
                        "Model \"$modelPattern\" not found. Use --list-models to see available models.",
                    )
                }
        }

    val credentials =
        candidates.mapNotNull { model ->
            val type = credentialTypes[model.provider]
            if (kind == CredentialPrintKind.API_KEY && type == "oauth") {
                return@mapNotNull null
            }
            if (kind == CredentialPrintKind.BEARER_TOKEN && type != "oauth") {
                return@mapNotNull null
            }
            val resolution =
                models.getAuth(
                    model.provider,
                    if (kind == CredentialPrintKind.BEARER_TOKEN) {
                        AuthResolutionOverrides(
                            minOAuthValidityMs =
                                minExpiryMs ?: DEFAULT_BEARER_TOKEN_MIN_EXPIRY_MS,
                        )
                    } else {
                        AuthResolutionOverrides()
                    },
                )
            val authorization =
                resolution
                    ?.auth
                    ?.headers
                    ?.entries
                    ?.firstOrNull { (name) -> name.equals("authorization", ignoreCase = true) }
                    ?.value
            val bearerToken =
                authorization
                    ?.let { BEARER_TOKEN_REGEX.matchEntire(it)?.groupValues?.get(1) }
            val value =
                if (kind == CredentialPrintKind.BEARER_TOKEN) {
                    resolution?.auth?.apiKey ?: bearerToken
                } else {
                    resolution?.auth?.apiKey
                }
            value?.let { model.provider to it }
        }

    if (credentials.size == 1) {
        return credentials.single().second
    }
    if (credentials.isEmpty()) {
        val providerId = candidates.firstOrNull()?.provider
        val type = providerId?.let(credentialTypes::get)
        if (arguments.provider != null && kind == CredentialPrintKind.API_KEY && type == "oauth") {
            throw CredentialPrintException(
                "Provider \"$providerId\" is configured with OAuth, not an API key",
            )
        }
        if (arguments.provider != null && kind == CredentialPrintKind.BEARER_TOKEN && type != "oauth") {
            throw CredentialPrintException(
                "Provider \"$providerId\" is not configured with an OAuth bearer token",
            )
        }
        throw CredentialPrintException(
            "No usable ${if (kind == CredentialPrintKind.API_KEY) "API key" else "OAuth bearer token"} is configured",
        )
    }
    throw CredentialPrintException(
        "Model \"$modelPattern\" has multiple configured providers " +
            "(${credentials.joinToString(", ") { it.first }}). Specify --provider.",
    )
}

internal suspend fun runCredentialPrintCommand(
    arguments: List<String>,
    loadModels: suspend () -> Models = ::loadBuiltInModels,
    output: PrintStream = System.out,
    errorOutput: PrintStream = System.err,
): Int? {
    if (isCredentialPrintHelp(arguments)) {
        printCredentialPrintHelp(output)
        return 0
    }
    val command =
        try {
            parseCredentialPrintCommand(arguments)
        } catch (error: CredentialPrintException) {
            errorOutput.println("Error: ${error.message}")
            return 1
        } ?: return null
    val parsed = parseArgs(command.arguments)
    if (parsed.diagnostics.isNotEmpty()) {
        parsed.diagnostics.forEach { diagnostic ->
            errorOutput.println("Error: ${diagnostic.message}")
        }
        return 1
    }
    return try {
        validateCredentialPrintArgs(parsed)
        val credential =
            resolveCredentialForPrint(
                arguments = parsed,
                models = loadModels(),
                kind = command.kind,
                minExpiryMs = command.minExpiryMs,
            )
        output.println(credential)
        0
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        val message =
            if (error is CredentialPrintException) {
                error.message
            } else {
                "Failed to resolve credential"
            }
        errorOutput.println("Error: $message")
        1
    }
}

private fun parseCredentialDuration(value: String?): Long {
    val match =
        value
            ?.let(DURATION_REGEX::matchEntire)
            ?: throw CredentialPrintException("--min-expiry must use a duration such as 30m or 1h")
    val amount =
        match.groupValues[1]
            .toLongOrNull()
            ?: throw CredentialPrintException("--min-expiry must use a duration such as 30m or 1h")
    val multiplier =
        when (match.groupValues[2].lowercase()) {
            "ms" -> 1L
            "s" -> 1_000L
            "m" -> 60_000L
            else -> 3_600_000L
        }
    return runCatching { Math.multiplyExact(amount, multiplier) }
        .getOrElse {
            throw CredentialPrintException("--min-expiry must use a duration such as 30m or 1h")
        }
}

private fun resolveExplicitCredentialModel(
    requestedProvider: String,
    modelPattern: String,
    models: Models,
): Model {
    val provider =
        models
            .getProviders()
            .firstOrNull { it.id.equals(requestedProvider, ignoreCase = true) }
            ?: throw CredentialPrintException(
                "Unknown provider \"$requestedProvider\". Use --list-models to see available providers/models.",
            )
    val providerModels = models.getModels(provider.id)
    if (providerModels.isEmpty()) {
        throw CredentialPrintException(
            "Model \"${provider.id}/$modelPattern\" not found. Use --list-models to see available models.",
        )
    }
    val normalizedPattern =
        modelPattern
            .removePrefixCaseInsensitive("${provider.id}/")
    return findCredentialModel(provider.id, normalizedPattern, providerModels)
        ?: providerModels.first().copy(
            id = normalizedPattern,
            name = normalizedPattern,
        )
}

private fun findCredentialModel(
    providerId: String,
    modelPattern: String,
    providerModels: List<Model>,
): Model? {
    val providerPattern = modelPattern.removePrefixCaseInsensitive("$providerId/")
    providerModels.firstOrNull { it.id.equals(providerPattern, ignoreCase = true) }?.let { return it }
    val pattern = providerPattern.substringBeforeThinkingSuffix()
    providerModels.firstOrNull { it.id.equals(pattern, ignoreCase = true) }?.let { return it }
    val matches =
        providerModels.filter { model ->
            model.id.contains(pattern, ignoreCase = true) ||
                model.name.contains(pattern, ignoreCase = true)
        }
    val aliases = matches.filterNot { DATE_SUFFIX_REGEX.containsMatchIn(it.id) }
    return (aliases.ifEmpty { matches }).maxByOrNull(Model::id)
}

private fun String.removePrefixCaseInsensitive(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

private fun String.substringBeforeThinkingSuffix(): String {
    val separator = lastIndexOf(':')
    if (separator < 0) {
        return this
    }
    return if (substring(separator + 1).lowercase() in THINKING_LEVELS) {
        substring(0, separator)
    } else {
        this
    }
}

private const val DEFAULT_BEARER_TOKEN_MIN_EXPIRY_MS = 30 * 60 * 1_000L
private val DURATION_REGEX = Regex("""^(\d+)(ms|s|m|h)$""", RegexOption.IGNORE_CASE)
private val BEARER_TOKEN_REGEX = Regex("""^Bearer\s+(.+)$""", RegexOption.IGNORE_CASE)
private val DATE_SUFFIX_REGEX = Regex("""-\d{8}$""")
private val THINKING_LEVELS = setOf("off", "minimal", "low", "medium", "high", "xhigh", "max")
