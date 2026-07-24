package works.earendil.pi.ai.providers

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential

internal class GitHubCopilotOAuth(
    private val transport: OAuthHttpTransport = JavaOAuthHttpTransport(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val knownModelIds: List<String> = builtInModels("github-copilot").map { it.id },
) : OAuthAuth {
    override val name: String = "GitHub Copilot"

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val input =
            interaction.prompt(
                AuthPrompt.Text(
                    message = "GitHub Enterprise URL/domain (blank for github.com)",
                    placeholder = "company.ghe.com",
                ),
            )
        val trimmed = input.trim()
        val enterpriseDomain = normalizeGitHubDomain(input)
        if (trimmed.isNotEmpty() && enterpriseDomain == null) {
            error("Invalid GitHub Enterprise URL/domain")
        }
        val domain = enterpriseDomain ?: GITHUB_PUBLIC_DOMAIN
        val device = startDeviceFlow(domain)
        interaction.notify(
            AuthEvent.DeviceCode(
                userCode = device.userCode,
                verificationUri = device.verificationUri,
                intervalSeconds = device.intervalSeconds,
                expiresInSeconds = device.expiresInSeconds,
            ),
        )
        val githubAccessToken = pollForGitHubAccessToken(domain, device)
        val credential = refreshCopilotAccessToken(githubAccessToken, enterpriseDomain)
        interaction.notify(AuthEvent.Progress("Enabling models..."))
        enableAllModels(credential.access, enterpriseDomain)
        return credential.copy(
            availableModelIds = fetchAvailableModelIds(credential.access, enterpriseDomain),
        )
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential {
        val enterpriseDomain = normalizeGitHubDomain(credential.enterpriseUrl.orEmpty())
        val refreshed = refreshCopilotAccessToken(credential.refresh, enterpriseDomain)
        return refreshed.copy(
            availableModelIds = fetchAvailableModelIds(refreshed.access, enterpriseDomain),
        )
    }

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
        ModelAuth(
            apiKey = credential.access,
            baseUrl =
                githubCopilotBaseUrl(
                    credential.access,
                    normalizeGitHubDomain(credential.enterpriseUrl.orEmpty()),
                ),
        )

    private suspend fun startDeviceFlow(domain: String): CopilotDeviceCode {
        val urls = githubCopilotUrls(domain)
        val response =
            transport.execute(
                OAuthHttpRequest(
                    url = urls.deviceCodeUrl,
                    headers = GITHUB_FORM_HEADERS,
                    body =
                        formBody(
                            "client_id" to GITHUB_COPILOT_CLIENT_ID,
                            "scope" to "read:user",
                        ),
                ),
            )
        val json = fetchJson(response)
        val deviceCode = json.string("device_code")
        val userCode = json.string("user_code")
        val verificationUri = json.string("verification_uri")
        val interval = json.number("interval")
        val expiresIn = json.long("expires_in")
        if (
            deviceCode.isNullOrBlank() ||
            userCode.isNullOrBlank() ||
            verificationUri.isNullOrBlank() ||
            (interval != null && interval < 0.0) ||
            expiresIn == null
        ) {
            error("Invalid device code response fields")
        }
        return CopilotDeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = normalizeVerificationUri(verificationUri),
            intervalSeconds = interval,
            expiresInSeconds = expiresIn.toInt(),
        )
    }

    private suspend fun pollForGitHubAccessToken(
        domain: String,
        device: CopilotDeviceCode,
    ): String {
        val deadline = now() + device.expiresInSeconds * 1_000L
        var intervalMs =
            maxOf(
                MINIMUM_DEVICE_POLL_INTERVAL_MS,
                ((device.intervalSeconds ?: DEFAULT_DEVICE_POLL_INTERVAL_SECONDS) * 1_000).toLong(),
            )
        var slowDownResponses = 0
        sleepBeforePoll(intervalMs, deadline)
        while (now() < deadline) {
            val response =
                transport.execute(
                    OAuthHttpRequest(
                        url = githubCopilotUrls(domain).accessTokenUrl,
                        headers = GITHUB_FORM_HEADERS,
                        body =
                            formBody(
                                "client_id" to GITHUB_COPILOT_CLIENT_ID,
                                "device_code" to device.deviceCode,
                                "grant_type" to GITHUB_DEVICE_GRANT_TYPE,
                            ),
                    ),
                )
            val json = fetchJson(response)
            json.string("access_token")?.takeIf(String::isNotBlank)?.let { return it }
            when (val error = json.string("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> {
                    slowDownResponses++
                    intervalMs =
                        json.number("interval")
                            ?.takeIf { it > 0.0 }
                            ?.let { maxOf(MINIMUM_DEVICE_POLL_INTERVAL_MS, (it * 1_000).toLong()) }
                            ?: (intervalMs + SLOW_DOWN_INTERVAL_INCREMENT_MS)
                }

                null -> error("Invalid device token response")
                else -> {
                    val description = json.string("error_description")
                    error(
                        "Device flow failed: $error" +
                            description?.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
                    )
                }
            }
            sleepBeforePoll(intervalMs, deadline)
        }
        if (slowDownResponses > 0) {
            error(
                "Device flow timed out after one or more slow_down responses. " +
                    "This is often caused by clock drift in WSL or VM environments. " +
                    "Please sync or restart the VM clock and try again.",
            )
        }
        error("Device flow timed out")
    }

    private suspend fun sleepBeforePoll(
        intervalMs: Long,
        deadline: Long,
    ) {
        val remaining = deadline - now()
        if (remaining > 0) {
            sleep(minOf(intervalMs, remaining))
        }
    }

    private suspend fun refreshCopilotAccessToken(
        refreshToken: String,
        enterpriseDomain: String?,
    ): OAuthCredential {
        val domain = enterpriseDomain ?: GITHUB_PUBLIC_DOMAIN
        val response =
            transport.execute(
                OAuthHttpRequest(
                    url = githubCopilotUrls(domain).copilotTokenUrl,
                    method = "GET",
                    headers =
                        COPILOT_HEADERS +
                            mapOf(
                                "Accept" to "application/json",
                                "Authorization" to "Bearer $refreshToken",
                            ),
                    body = "",
                ),
            )
        val json = fetchJson(response)
        val token = json.string("token")
        val expiresAt = json.long("expires_at")
        if (token.isNullOrBlank() || expiresAt == null) {
            error("Invalid Copilot token response fields")
        }
        return OAuthCredential(
            refresh = refreshToken,
            access = token,
            expires = expiresAt * 1_000L - COPILOT_EXPIRY_SKEW_MS,
            enterpriseUrl = enterpriseDomain,
        )
    }

    private suspend fun fetchAvailableModelIds(
        copilotToken: String,
        enterpriseDomain: String?,
    ): List<String> {
        val response =
            transport.execute(
                OAuthHttpRequest(
                    url = "${githubCopilotBaseUrl(copilotToken, enterpriseDomain)}/models",
                    method = "GET",
                    headers =
                        COPILOT_HEADERS +
                            mapOf(
                                "Accept" to "application/json",
                                "Authorization" to "Bearer $copilotToken",
                                "X-GitHub-Api-Version" to COPILOT_API_VERSION,
                            ),
                    body = "",
                    timeoutMs = COPILOT_MODELS_TIMEOUT_MS,
                ),
            )
        return parseAvailableGitHubCopilotModelIds(fetchJson(response))
    }

    private suspend fun enableAllModels(
        copilotToken: String,
        enterpriseDomain: String?,
    ) {
        coroutineScope {
            knownModelIds.map { modelId ->
                async {
                    enableModel(copilotToken, modelId, enterpriseDomain)
                }
            }.awaitAll()
        }
    }

    private suspend fun enableModel(
        copilotToken: String,
        modelId: String,
        enterpriseDomain: String?,
    ): Boolean =
        runCatching {
            val encodedModel = encodePathSegment(modelId)
            val response =
                transport.execute(
                    OAuthHttpRequest(
                        url =
                            "${githubCopilotBaseUrl(copilotToken, enterpriseDomain)}" +
                                "/models/$encodedModel/policy",
                        headers =
                            COPILOT_HEADERS +
                                mapOf(
                                    "Content-Type" to "application/json",
                                    "Authorization" to "Bearer $copilotToken",
                                    "openai-intent" to "chat-policy",
                                    "x-interaction-type" to "chat-policy",
                                ),
                        body = """{"state":"enabled"}""",
                    ),
                )
            response.status in 200..299
        }.getOrDefault(false)

    private fun fetchJson(response: OAuthHttpResponse): JsonObject {
        if (response.status !in 200..299) {
            error("${response.status}: ${response.body}")
        }
        return runCatching {
            providerJson.parseToJsonElement(response.body).jsonObject
        }.getOrElse {
            error("Invalid JSON response")
        }
    }
}

internal fun normalizeGitHubDomain(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) {
        return null
    }
    return runCatching {
        val uri = URI(if ("://" in trimmed) trimmed else "https://$trimmed")
        uri.host
            ?.takeIf(String::isNotBlank)
            ?.lowercase()
    }.getOrNull()
}

internal data class GitHubCopilotUrls(
    val deviceCodeUrl: String,
    val accessTokenUrl: String,
    val copilotTokenUrl: String,
)

internal fun githubCopilotUrls(domain: String): GitHubCopilotUrls =
    GitHubCopilotUrls(
        deviceCodeUrl = "https://$domain/login/device/code",
        accessTokenUrl = "https://$domain/login/oauth/access_token",
        copilotTokenUrl = "https://api.$domain/copilot_internal/v2/token",
    )

internal fun githubCopilotBaseUrl(
    token: String?,
    enterpriseDomain: String?,
): String {
    val tokenBase =
        token
            ?.let { COPILOT_PROXY_ENDPOINT.find(it)?.groupValues?.getOrNull(1) }
            ?.takeIf(String::isNotBlank)
            ?.replace(Regex("^proxy\\."), "api.")
            ?.let { "https://$it" }
    return tokenBase
        ?: enterpriseDomain?.let { "https://copilot-api.$it" }
        ?: "https://api.individual.githubcopilot.com"
}

internal fun parseAvailableGitHubCopilotModelIds(json: JsonObject): List<String> {
    val data = json["data"] as? JsonArray ?: error("Invalid Copilot models response")
    return data.mapNotNull { raw ->
        val item = raw as? JsonObject ?: return@mapNotNull null
        val id = item.string("id") ?: return@mapNotNull null
        val pickerEnabled = item["model_picker_enabled"]?.jsonPrimitive?.booleanOrNull == true
        val policyDisabled =
            item["policy"]
                ?.let { it as? JsonObject }
                ?.string("state") == "disabled"
        val toolCallsUnsupported =
            item["capabilities"]
                ?.let { it as? JsonObject }
                ?.get("supports")
                ?.let { it as? JsonObject }
                ?.get("tool_calls")
                ?.jsonPrimitive
                ?.booleanOrNull == false
        id.takeIf { pickerEnabled && !policyDisabled && !toolCallsUnsupported }
    }
}

private fun normalizeVerificationUri(value: String): String {
    val escaped =
        buildString {
            value.forEach { character ->
                when {
                    character.code < 0x20 || character.code == 0x7f ->
                        append("%%%02X".format(character.code))

                    character == ' ' -> append("%20")
                    else -> append(character)
                }
            }
        }
    val authorityStart = escaped.indexOf("://").takeIf { it >= 0 }?.plus(3)
    val validationValue =
        if (authorityStart == null) {
            escaped
        } else {
            val pathStart =
                escaped
                    .indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
                    .takeIf { it >= 0 }
                    ?: escaped.length
            escaped.substring(0, pathStart) +
                escaped
                    .substring(pathStart)
                    .replace("[", "%5B")
                    .replace("]", "%5D")
        }
    val uri =
        runCatching { URI(validationValue) }
            .getOrElse { error("Untrusted verification_uri in device code response") }
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
        error("Untrusted verification_uri in device code response")
    }
    return escaped
}

private fun JsonObject.number(name: String): Double? =
    this[name]?.jsonPrimitive?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

private fun formBody(vararg values: Pair<String, String>): String =
    values.joinToString("&") { (name, value) ->
        "${encodeForm(name)}=${encodeForm(value)}"
    }

private fun encodeForm(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

private fun encodePathSegment(value: String): String =
    encodeForm(value).replace("+", "%20")

private data class CopilotDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSeconds: Double?,
    val expiresInSeconds: Int,
)

internal val GITHUB_COPILOT_HEADERS: Map<String, String> =
    mapOf(
        "User-Agent" to "GitHubCopilotChat/0.35.0",
        "Editor-Version" to "vscode/1.107.0",
        "Editor-Plugin-Version" to "copilot-chat/0.35.0",
        "Copilot-Integration-Id" to "vscode-chat",
    )

private val COPILOT_HEADERS = GITHUB_COPILOT_HEADERS
private val GITHUB_FORM_HEADERS =
    mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/x-www-form-urlencoded",
        "User-Agent" to "GitHubCopilotChat/0.35.0",
    )
private val COPILOT_PROXY_ENDPOINT = Regex("proxy-ep=([^;]+)")
private const val GITHUB_COPILOT_CLIENT_ID = "Iv1.b507a08c87ecfe98"
private const val GITHUB_PUBLIC_DOMAIN = "github.com"
private const val GITHUB_DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
private const val COPILOT_API_VERSION = "2026-06-01"
private const val COPILOT_EXPIRY_SKEW_MS = 5 * 60 * 1_000L
private const val COPILOT_MODELS_TIMEOUT_MS = 5_000L
private const val MINIMUM_DEVICE_POLL_INTERVAL_MS = 1_000L
private const val DEFAULT_DEVICE_POLL_INTERVAL_SECONDS = 5.0
private const val SLOW_DOWN_INTERVAL_INCREMENT_MS = 5_000L
