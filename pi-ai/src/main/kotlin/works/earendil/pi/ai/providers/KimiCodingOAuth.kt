package works.earendil.pi.ai.providers

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential

internal class KimiCodingOAuth(
    private val transport: OAuthHttpTransport = JavaOAuthHttpTransport(),
    private val environment: (String) -> String? = System::getenv,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : OAuthAuth {
    override val name: String = "Kimi Code (subscription)"
    override val loginLabel: String = "Sign in with Kimi Code"

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val oauthHost = oauthHost()
        val device = startDeviceAuthorization(oauthHost)
        interaction.notify(
            AuthEvent.DeviceCode(
                userCode = device.userCode,
                verificationUri = device.verificationUriComplete,
                intervalSeconds = device.intervalSeconds,
                expiresInSeconds = device.expiresInSeconds.toInt(),
            ),
        )
        return pollForToken(oauthHost, device)
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
        refreshToken(oauthHost(), credential.refresh)

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
        ModelAuth(
            headers = mapOf("Authorization" to "Bearer ${credential.access}"),
        )

    private fun oauthHost(): String {
        val override =
            environment("KIMI_CODE_OAUTH_HOST")
                ?.takeIf(String::isNotEmpty)
                ?: environment("KIMI_OAUTH_HOST")?.takeIf(String::isNotEmpty)
        return (override ?: DEFAULT_OAUTH_HOST).trimEnd('/')
    }

    private suspend fun startDeviceAuthorization(oauthHost: String): KimiDeviceAuthorization {
        val response =
            postForm(
                url = "$oauthHost/api/oauth/device_authorization",
                fields = mapOf("client_id" to KIMI_CLIENT_ID),
            )
        if (response.status !in 200..299) {
            error(
                "Kimi Code device authorization failed with status ${response.status}" +
                    response.body.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty(),
            )
        }
        val json = readKimiJson(response.body)
        val body = json as? JsonObject
        val deviceCode = body?.kimiString("device_code")
        val userCode = body?.kimiString("user_code")
        val verificationUri = body?.kimiString("verification_uri")
        val verificationUriComplete = body?.kimiString("verification_uri_complete")
        if (
            deviceCode == null ||
            userCode == null ||
            verificationUri == null ||
            verificationUriComplete == null ||
            !trustedHttpUrl(verificationUriComplete) ||
            !trustedHttpUrl(verificationUri)
        ) {
            error(
                "Invalid Kimi Code device authorization response: ${stringifyKimiJson(json)}",
            )
        }
        val interval =
            body.kimiNumber("interval")
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: DEFAULT_POLL_INTERVAL_SECONDS
        val expiresIn =
            body.kimiNumber("expires_in")
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: DEVICE_CODE_TIMEOUT_SECONDS
        return KimiDeviceAuthorization(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            verificationUriComplete = verificationUriComplete,
            intervalSeconds = interval,
            expiresInSeconds = expiresIn,
        )
    }

    private suspend fun pollForToken(
        oauthHost: String,
        device: KimiDeviceAuthorization,
    ): OAuthCredential {
        val deadline = now() + (device.expiresInSeconds * 1_000).toLong()
        var intervalMs =
            maxOf(
                MINIMUM_POLL_INTERVAL_MS,
                (device.intervalSeconds * 1_000).toLong(),
            )
        var slowDownResponses = 0
        sleepBeforePoll(intervalMs, deadline)
        while (now() < deadline) {
            val response =
                postForm(
                    url = "$oauthHost/api/oauth/token",
                    fields =
                        mapOf(
                            "client_id" to KIMI_CLIENT_ID,
                            "device_code" to device.deviceCode,
                            "grant_type" to KIMI_DEVICE_GRANT_TYPE,
                        ),
                )
            if (response.status >= 500) {
                error(
                    "Kimi Code device token request failed with status ${response.status}" +
                        response.body.takeIf(String::isNotEmpty)?.let { ": $it" }.orEmpty(),
                )
            }
            val json = readKimiJson(response.body)
            val body = json as? JsonObject
            if (response.status in 200..299 && body?.kimiString("access_token") != null) {
                return parseTokenResponse(json, "poll")
            }
            val upstreamError = body?.kimiString("error")
            val description =
                body
                    ?.kimiString("error_description")
                    ?.let { ": $it" }
                    .orEmpty()
            when (upstreamError) {
                "authorization_pending" -> Unit
                "slow_down" -> {
                    slowDownResponses++
                    intervalMs =
                        body
                            .kimiNumber("interval")
                            ?.takeIf { it.isFinite() && it > 0.0 }
                            ?.let { maxOf(MINIMUM_POLL_INTERVAL_MS, (it * 1_000).toLong()) }
                            ?: maxOf(MINIMUM_POLL_INTERVAL_MS, intervalMs + SLOW_DOWN_INCREMENT_MS)
                }

                "expired_token" ->
                    error("Kimi Code device authorization expired. Please restart login.")

                "access_denied" ->
                    error("Kimi Code login was denied.")

                else ->
                    error(
                        "Kimi Code device token request failed (status ${response.status})" +
                            upstreamError?.let { ": $it$description" }.orEmpty(),
                    )
            }
            sleepBeforePoll(intervalMs, deadline)
        }
        if (slowDownResponses > 0) {
            error(SLOW_DOWN_TIMEOUT_MESSAGE)
        }
        error("Device flow timed out")
    }

    private suspend fun refreshToken(
        oauthHost: String,
        refreshToken: String,
    ): OAuthCredential {
        var lastError: Throwable? = null
        for (attempt in 0..REFRESH_MAX_RETRIES) {
            if (attempt > 0) {
                sleep(1_000L shl (attempt - 1))
            }
            val response =
                try {
                    postForm(
                        url = "$oauthHost/api/oauth/token",
                        fields =
                            mapOf(
                                "client_id" to KIMI_CLIENT_ID,
                                "grant_type" to "refresh_token",
                                "refresh_token" to refreshToken,
                            ),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    lastError = error
                    continue
                }
            val json = readKimiJson(response.body)
            val body = json as? JsonObject
            if (response.status in 200..299) {
                return parseTokenResponse(json, "refresh")
            }
            val upstreamError = body?.kimiString("error")
            if (
                response.status == 401 ||
                response.status == 403 ||
                upstreamError == "invalid_grant"
            ) {
                val description =
                    body
                        ?.kimiString("error_description")
                        ?.let { ": $it" }
                        .orEmpty()
                error(
                    "Kimi Code token refresh unauthorized (status ${response.status})$description",
                )
            }
            if (
                (response.status == 429 || response.status >= 500) &&
                attempt < REFRESH_MAX_RETRIES
            ) {
                lastError =
                    IllegalStateException(
                        "Kimi Code token refresh failed with status ${response.status}",
                    )
                continue
            }
            error(
                "Kimi Code token refresh failed with status ${response.status}: " +
                    stringifyKimiJson(json),
            )
        }
        throw lastError ?: IllegalStateException("Kimi Code token refresh failed")
    }

    private fun parseTokenResponse(
        json: JsonElement?,
        operation: String,
    ): OAuthCredential {
        val body = json as? JsonObject
        val access = body?.kimiString("access_token")
        val refresh = body?.kimiString("refresh_token")
        val expiresIn = body?.kimiNumber("expires_in")
        if (
            access.isNullOrEmpty() ||
            refresh.isNullOrEmpty() ||
            expiresIn == null ||
            !expiresIn.isFinite() ||
            expiresIn <= 0.0
        ) {
            error(
                "Kimi Code token $operation response missing fields: ${stringifyKimiJson(json)}",
            )
        }
        return OAuthCredential(
            access = access,
            refresh = refresh,
            expires = now() + (expiresIn * 1_000).toLong(),
        )
    }

    private suspend fun postForm(
        url: String,
        fields: Map<String, String>,
    ): OAuthHttpResponse =
        transport.execute(
            OAuthHttpRequest(
                url = url,
                headers = KIMI_FORM_HEADERS,
                body =
                    fields.entries.joinToString("&") { (name, value) ->
                        "${encodeForm(name)}=${encodeForm(value)}"
                    },
                timeoutMs = REQUEST_TIMEOUT_MS,
            ),
        )

    private suspend fun sleepBeforePoll(
        intervalMs: Long,
        deadline: Long,
    ) {
        val remaining = deadline - now()
        if (remaining > 0) {
            sleep(minOf(intervalMs, remaining))
        }
    }
}

private data class KimiDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val intervalSeconds: Double,
    val expiresInSeconds: Double,
)

private fun readKimiJson(body: String): JsonElement? =
    runCatching {
        providerJson
            .parseToJsonElement(body)
            .takeIf { it is JsonObject || it is JsonArray }
    }.getOrNull()

private fun stringifyKimiJson(json: JsonElement?): String = json?.toString() ?: "null"

private fun trustedHttpUrl(value: String): Boolean {
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return (
        uri.scheme.equals("https", ignoreCase = true) ||
            uri.scheme.equals("http", ignoreCase = true)
    ) &&
        !uri.host.isNullOrBlank()
}

private fun JsonObject.kimiString(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content

private fun JsonObject.kimiNumber(name: String): Double? =
    (this[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.doubleOrNull

private fun encodeForm(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

private val KIMI_FORM_HEADERS =
    mapOf(
        "Content-Type" to "application/x-www-form-urlencoded",
        "Accept" to "application/json",
    )
private const val KIMI_CLIENT_ID = "17e5f671-d194-4dfb-9706-5516cb48c098"
private const val DEFAULT_OAUTH_HOST = "https://auth.kimi.com"
private const val DEVICE_CODE_TIMEOUT_SECONDS = 15.0 * 60
private const val DEFAULT_POLL_INTERVAL_SECONDS = 5.0
private const val REQUEST_TIMEOUT_MS = 30_000L
private const val REFRESH_MAX_RETRIES = 3
private const val MINIMUM_POLL_INTERVAL_MS = 1_000L
private const val SLOW_DOWN_INCREMENT_MS = 5_000L
private const val KIMI_DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
private const val SLOW_DOWN_TIMEOUT_MESSAGE =
    "Device flow timed out after one or more slow_down responses. " +
        "This is often caused by clock drift in WSL or VM environments. " +
        "Please sync or restart the VM clock and try again."
