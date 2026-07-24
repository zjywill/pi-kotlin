package works.earendil.pi.ai.providers

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential

internal data class XaiOAuthEndpoints(
    val deviceCodeUrl: String = "https://auth.x.ai/oauth2/device/code",
    val tokenUrl: String = "https://auth.x.ai/oauth2/token",
)

internal class XaiOAuth(
    private val transport: OAuthHttpTransport = JavaOAuthHttpTransport(),
    private val endpoints: XaiOAuthEndpoints = XaiOAuthEndpoints(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : OAuthAuth {
    override val name: String = "xAI (Grok/X subscription)"
    override val loginLabel: String = "Sign in with SuperGrok or X Premium"

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val device = requestDeviceCode()
        interaction.notify(
            AuthEvent.DeviceCode(
                userCode = device.userCode,
                verificationUri = device.verificationUriComplete ?: device.verificationUri,
                intervalSeconds = device.intervalSeconds,
                expiresInSeconds = device.expiresInSeconds.toInt(),
            ),
        )
        return pollForTokens(device)
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential {
        val response =
            postForm(
                endpoints.tokenUrl,
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to XAI_CLIENT_ID,
                    "refresh_token" to credential.refresh,
                ),
            )
        val body = parseResponseBody(response)
        if (response.status !in 200..299) {
            throw requestFailure("token refresh", response, body)
        }
        return credentialsFromTokenResponse(body, credential.refresh)
    }

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
        ModelAuth(apiKey = credential.access)

    private suspend fun requestDeviceCode(): XaiDeviceCode {
        val response =
            postForm(
                endpoints.deviceCodeUrl,
                mapOf(
                    "client_id" to XAI_CLIENT_ID,
                    "scope" to XAI_SCOPE,
                    "referrer" to "pi",
                ),
            )
        val body = parseResponseBody(response)
        if (response.status !in 200..299) {
            throw requestFailure("device authorization", response, body)
        }
        val interval =
            body.number("interval")
                ?.takeIf { it.isFinite() && it > 0.0 }
        val verificationUriComplete =
            body.optionalString("verification_uri_complete")
                ?.takeIf(String::isNotEmpty)
                ?.let(::validateVerificationUri)
        return XaiDeviceCode(
            deviceCode = body.requiredString("device_code"),
            userCode = body.requiredString("user_code"),
            verificationUri = validateVerificationUri(body.requiredString("verification_uri")),
            verificationUriComplete = verificationUriComplete,
            intervalSeconds = interval,
            expiresInSeconds = body.positiveNumber("expires_in"),
        )
    }

    private suspend fun pollForTokens(device: XaiDeviceCode): OAuthCredential {
        val deadline = now() + (device.expiresInSeconds * 1_000).toLong()
        var intervalMs =
            maxOf(
                MINIMUM_POLL_INTERVAL_MS,
                ((device.intervalSeconds ?: DEFAULT_POLL_INTERVAL_SECONDS) * 1_000).toLong(),
            )
        var slowDownResponses = 0
        sleepBeforePoll(intervalMs, deadline)
        while (now() < deadline) {
            val response =
                postForm(
                    endpoints.tokenUrl,
                    mapOf(
                        "grant_type" to XAI_DEVICE_GRANT_TYPE,
                        "client_id" to XAI_CLIENT_ID,
                        "device_code" to device.deviceCode,
                    ),
                )
            val body = parseResponseBody(response)
            if (response.status in 200..299) {
                return credentialsFromTokenResponse(body)
            }
            when (body.optionalString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> {
                    slowDownResponses++
                    intervalMs =
                        body
                            .number("interval")
                            ?.takeIf { it.isFinite() && it > 0.0 }
                            ?.let { maxOf(MINIMUM_POLL_INTERVAL_MS, (it * 1_000).toLong()) }
                            ?: maxOf(MINIMUM_POLL_INTERVAL_MS, intervalMs + SLOW_DOWN_INCREMENT_MS)
                }

                "access_denied", "authorization_denied" ->
                    error("xAI device authorization was denied")

                "expired_token" ->
                    error("xAI device code expired")

                else ->
                    throw requestFailure("device token polling", response, body)
            }
            sleepBeforePoll(intervalMs, deadline)
        }
        if (slowDownResponses > 0) {
            error(SLOW_DOWN_TIMEOUT_MESSAGE)
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

    private fun credentialsFromTokenResponse(
        body: JsonObject,
        previousRefreshToken: String? = null,
    ): OAuthCredential {
        val access = body.requiredString("access_token")
        val refresh =
            if ("refresh_token" !in body && !previousRefreshToken.isNullOrEmpty()) {
                previousRefreshToken
            } else {
                body.requiredString("refresh_token")
            }
        val expiresInSeconds =
            if ("expires_in" in body) {
                body.positiveNumber("expires_in")
            } else {
                DEFAULT_TOKEN_LIFETIME_SECONDS
            }
        return OAuthCredential(
            access = access,
            refresh = refresh,
            expires = now() + (expiresInSeconds * 1_000).toLong() - REFRESH_SKEW_MS,
        )
    }

    private suspend fun postForm(
        url: String,
        fields: Map<String, String>,
    ): OAuthHttpResponse =
        transport.execute(
            OAuthHttpRequest(
                url = url,
                headers = XAI_FORM_HEADERS,
                body =
                    fields.entries.joinToString("&") { (name, value) ->
                        "${encodeForm(name)}=${encodeForm(value)}"
                    },
            ),
        )
}

private data class XaiDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val intervalSeconds: Double?,
    val expiresInSeconds: Double,
)

private fun parseResponseBody(response: OAuthHttpResponse): JsonObject {
    val trimmed = response.body.trim()
    if (
        trimmed.isEmpty() ||
        (trimmed.first().isLetter() && trimmed !in setOf("true", "false", "null"))
    ) {
        error("xAI OAuth returned invalid JSON (HTTP ${response.status})")
    }
    return runCatching {
        xaiOAuthJson.parseToJsonElement(trimmed) as? JsonObject ?: JsonObject(emptyMap())
    }.getOrElse {
        error("xAI OAuth returned invalid JSON (HTTP ${response.status})")
    }
}

private fun requestFailure(
    action: String,
    response: OAuthHttpResponse,
    body: JsonObject,
): IllegalStateException {
    val detail =
        listOfNotNull(
            body.optionalString("error"),
            body.optionalString("error_description"),
        ).filter(String::isNotEmpty)
            .joinToString(": ")
    return IllegalStateException(
        "xAI OAuth $action failed (HTTP ${response.status})" +
            detail.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty(),
    )
}

private fun validateVerificationUri(raw: String): String {
    val uri =
        runCatching { URI(raw) }
            .getOrElse { error("Untrusted verification URI in xAI OAuth response") }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
        error("Untrusted verification URI in xAI OAuth response")
    }
    return uri.toASCIIString()
}

private fun JsonObject.requiredString(field: String): String =
    optionalString(field)
        ?.takeIf(String::isNotEmpty)
        ?: error("Invalid xAI OAuth response field: $field")

private fun JsonObject.optionalString(field: String): String? =
    (this[field] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content

private fun JsonObject.number(field: String): Double? =
    (this[field] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.doubleOrNull

private fun JsonObject.positiveNumber(field: String): Double =
    number(field)
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?: error("Invalid xAI OAuth response field: $field")

private fun encodeForm(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

private val XAI_FORM_HEADERS =
    mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/x-www-form-urlencoded",
    )
private const val XAI_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
private const val XAI_SCOPE = "openid profile email offline_access grok-cli:access api:access"
private const val XAI_DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
private const val MINIMUM_POLL_INTERVAL_MS = 1_000L
private const val DEFAULT_POLL_INTERVAL_SECONDS = 5.0
private const val SLOW_DOWN_INCREMENT_MS = 5_000L
private const val REFRESH_SKEW_MS = 5 * 60 * 1_000L
private const val DEFAULT_TOKEN_LIFETIME_SECONDS = 3_600.0
private const val SLOW_DOWN_TIMEOUT_MESSAGE =
    "Device flow timed out after one or more slow_down responses. " +
        "This is often caused by clock drift in WSL or VM environments. " +
        "Please sync or restart the VM clock and try again."
private val xaiOAuthJson = Json
