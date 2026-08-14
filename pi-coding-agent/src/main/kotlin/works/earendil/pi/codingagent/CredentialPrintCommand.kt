package works.earendil.pi.codingagent

import java.io.PrintStream
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.ApiKeyCredential
import works.earendil.pi.ai.AuthType
import works.earendil.pi.ai.AuthResolutionOverrides
import works.earendil.pi.ai.Credential
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.OAuthCredential

internal enum class CredentialPrintKind {
    CHECK,
    API_KEY,
    BEARER_TOKEN,
}

internal data class CredentialPrintCommand(
    val kind: CredentialPrintKind,
    val arguments: List<String>,
    val minExpiryMs: Long? = null,
    val json: Boolean = false,
    val credentials: Boolean = false,
    val noRefresh: Boolean = false,
)

internal class CredentialPrintException(
    message: String,
) : RuntimeException(message)

internal fun isCredentialPrintHelp(arguments: List<String>): Boolean =
    arguments.firstOrNull() == "auth" &&
        (
            arguments.getOrNull(1) in setOf(null, "help", "--help", "-h") ||
                arguments.drop(1).any { it == "--help" || it == "-h" }
        )

internal fun printCredentialPrintHelp(output: PrintStream = System.out) {
    output.println(
        """
        Usage:
          pi auth print-api-key [--provider <provider>] [--model <model>]
          pi auth print-bearer-token [--provider <provider>] [--model <model>] [--min-expiry <duration>]
          pi auth check [--provider <provider>] [--model <model>] [--json] [--credentials] [--no-refresh]

        Auth commands require at least one of --provider or --model. Checks refresh expired OAuth credentials by default; --no-refresh prevents this. --credentials emits the credential, or includes it in JSON output.
        """.trimIndent(),
    )
}

internal fun parseCredentialPrintCommand(arguments: List<String>): CredentialPrintCommand? {
    if (arguments.firstOrNull() != "auth") {
        return null
    }
    val kind =
        when (arguments.getOrNull(1)) {
            "check" -> CredentialPrintKind.CHECK
            "print-api-key" -> CredentialPrintKind.API_KEY
            "print-bearer-token" -> CredentialPrintKind.BEARER_TOKEN
            else ->
                throw CredentialPrintException(
                    "Unknown auth command \"${arguments.getOrNull(1).orEmpty()}\". " +
                        "Use \"pi auth print-api-key\", \"pi auth print-bearer-token\", or \"pi auth check\".",
                )
        }

    val commandArguments = mutableListOf<String>()
    var minExpiryMs: Long? = null
    var json = false
    var credentials = false
    var noRefresh = false
    var index = 2
    while (index < arguments.size) {
        val argument = arguments[index]
        if (argument == "--json" || argument == "--credentials" || argument == "--no-refresh") {
            if (kind != CredentialPrintKind.CHECK) {
                throw CredentialPrintException("$argument is only supported by auth check")
            }
            when (argument) {
                "--json" -> json = true
                "--credentials" -> credentials = true
                "--no-refresh" -> noRefresh = true
            }
            index++
            continue
        }
        if (argument != "--min-expiry") {
            commandArguments += argument
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
    return CredentialPrintCommand(
        kind = kind,
        arguments = commandArguments,
        minExpiryMs = minExpiryMs,
        json = json,
        credentials = credentials,
        noRefresh = noRefresh,
    )
}

internal fun validateCredentialPrintArgs(
    arguments: Args,
    kind: CredentialPrintKind = CredentialPrintKind.API_KEY,
) {
    if (arguments.provider.isNullOrBlank() && arguments.model.isNullOrBlank()) {
        throw CredentialPrintException(
            if (kind == CredentialPrintKind.CHECK) {
                "Auth checks require --provider <provider> or --model <model>"
            } else {
                "Credential printing requires --provider <provider> or --model <model>"
            },
        )
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
        throw CredentialPrintException("Auth commands only accept --provider and --model")
    }
}

internal suspend fun resolveCredentialForPrint(
    arguments: Args,
    models: Models,
    kind: CredentialPrintKind,
    minExpiryMs: Long? = null,
): String {
    validateCredentialPrintArgs(arguments, kind)
    val modelPattern = arguments.model?.trim()?.takeIf(String::isNotEmpty)
    val credentialTypes =
        models
            .listCredentials()
            .associate { it.providerId to it.type }
    if (modelPattern == null) {
        val provider =
            models
                .getProviders()
                .firstOrNull { it.id.equals(arguments.provider, ignoreCase = true) }
                ?: throw CredentialPrintException(
                    "Unknown provider \"${arguments.provider}\". Use --list-models to see available providers/models.",
                )
        val type = credentialTypes[provider.id]
        if (kind == CredentialPrintKind.API_KEY && type == "oauth") {
            throw CredentialPrintException(
                "Provider \"${provider.id}\" is configured with OAuth, not an API key",
            )
        }
        if (kind == CredentialPrintKind.BEARER_TOKEN && type != "oauth") {
            throw CredentialPrintException(
                "Provider \"${provider.id}\" is not configured with an OAuth bearer token",
            )
        }
        val resolution =
            models.getAuth(
                provider.id,
                if (kind == CredentialPrintKind.BEARER_TOKEN) {
                    AuthResolutionOverrides(
                        minOAuthValidityMs = minExpiryMs ?: DEFAULT_BEARER_TOKEN_MIN_EXPIRY_MS,
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
        val value =
            if (kind == CredentialPrintKind.BEARER_TOKEN) {
                resolution?.auth?.apiKey
                    ?: authorization?.let { BEARER_TOKEN_REGEX.matchEntire(it)?.groupValues?.get(1) }
            } else {
                resolution?.auth?.apiKey
            }
        return value
            ?: throw CredentialPrintException(
                "No usable ${if (kind == CredentialPrintKind.API_KEY) "API key" else "OAuth bearer token"} is configured",
            )
    }
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
        val models = loadModels()
        if (command.kind == CredentialPrintKind.CHECK) {
            validateCredentialPrintArgs(parsed, CredentialPrintKind.CHECK)
            runAuthCheckCommand(parsed, models, command, output)
        } else {
            validateCredentialPrintArgs(parsed, command.kind)
            val credential =
                resolveCredentialForPrint(
                    arguments = parsed,
                    models = models,
                    kind = command.kind,
                    minExpiryMs = command.minExpiryMs,
                )
            output.println(credential)
            0
        }
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

private suspend fun runAuthCheckCommand(
    arguments: Args,
    models: Models,
    command: CredentialPrintCommand,
    output: PrintStream,
): Int {
    val providerId = resolveAuthProvider(arguments, models)
    var result =
        try {
            val check = models.checkAuth(providerId)
            if (check == null) {
                AuthCheckOutput(
                    status = "not_ready",
                    provider = providerId,
                    reason = "credentials_not_configured",
                    authType = null,
                )
            } else if (!command.noRefresh && models.getAuth(providerId) == null) {
                AuthCheckOutput(
                    status = "not_ready",
                    provider = providerId,
                    reason = "credentials_not_configured",
                    authType = null,
                )
            } else {
                AuthCheckOutput(
                    status = "ready",
                    provider = providerId,
                    reason = null,
                    authType = check.type.wireName,
                )
            }
        } catch (_: Throwable) {
            AuthCheckOutput(
                status = "invalid",
                provider = providerId,
                reason = "invalid_state",
                authType = null,
            )
        }

    val credential =
        if (command.credentials && result.status == "ready") {
            val value =
                if (command.noRefresh) {
                    models.getStoredCredential(providerId).credentialValue()
                } else {
                    models.getAuth(providerId)?.authCredentialValue()
                }
            if (value == null) {
                result = result.copy(status = "not_ready", reason = "credential_not_available")
            }
            value
        } else {
            null
        }

    if (command.json) {
        val json =
            buildJsonObject {
                put("status", result.status)
                put("provider", result.provider)
                result.reason?.let { put("reason", it) }
                result.authType?.let { put("authType", it) }
                credential?.let { put("credentials", it) }
            }
        output.println(protocolJson.encodeToString(JsonObject.serializer(), json))
    } else {
        output.println(credential ?: result.status)
    }
    return when (result.status) {
        "ready" -> 0
        "not_ready" -> 1
        else -> 2
    }
}

private data class AuthCheckOutput(
    val status: String,
    val provider: String,
    val reason: String?,
    val authType: String?,
)

private suspend fun resolveAuthProvider(
    arguments: Args,
    models: Models,
): String {
    arguments.provider
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.let { requested ->
            return models
                .getProviders()
                .firstOrNull { it.id.equals(requested, ignoreCase = true) }
                ?.id
                ?: throw CredentialPrintException(
                    "Unknown provider \"$requested\". Use --list-models to see available providers/models.",
                )
        }
    val requestedModel =
        arguments.model
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw CredentialPrintException("Auth checks require --provider <provider> or --model <model>")
    val exactProviders =
        models
            .getModels()
            .filter { model ->
                model.id.equals(requestedModel, ignoreCase = true) ||
                    "${model.provider}/${model.id}".equals(requestedModel, ignoreCase = true)
            }.map(Model::provider)
            .distinct()
    if (exactProviders.size == 1) {
        return exactProviders.single()
    }
    parseModelReference(null, requestedModel).provider?.let { provider ->
        models.getProviders().firstOrNull { it.id.equals(provider, ignoreCase = true) }?.let { return it.id }
    }
    throw CredentialPrintException(
        "Unable to resolve model \"$requestedModel\". Use --list-models to see available models.",
    )
}

private fun Credential?.credentialValue(): String? =
    when (this) {
        is ApiKeyCredential -> key
        is OAuthCredential -> access
        null -> null
    }

private fun works.earendil.pi.ai.AuthResult.authCredentialValue(): String? =
    auth.apiKey
        ?: auth.headers.entries
            .firstOrNull { (name) -> name.equals("authorization", ignoreCase = true) }
            ?.value
            ?.let { BEARER_TOKEN_REGEX.matchEntire(it)?.groupValues?.get(1) }

private val AuthType.wireName: String
    get() =
        when (this) {
            AuthType.API_KEY -> "api_key"
            AuthType.OAUTH -> "oauth"
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
