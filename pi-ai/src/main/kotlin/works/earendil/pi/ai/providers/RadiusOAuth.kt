package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthOption
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential

internal data class RadiusOAuthDiscovery(
    val authorizationEndpoint: String,
)

internal data class RadiusPkce(
    val verifier: String,
    val challenge: String,
)

internal class RadiusOAuthCallbackServer(
    private val closeAction: () -> Unit,
    val code: CompletableDeferred<String?>,
) {
    fun close() = closeAction()
}

internal class RadiusOAuth(
    override val name: String = "Radius",
    gateway: String = DEFAULT_RADIUS_GATEWAY,
    private val transport: OAuthHttpTransport = JavaOAuthHttpTransport(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: SecureRandom = SecureRandom(),
    private val stateFactory: () -> String = { UUID.randomUUID().toString() },
    private val callbackServerFactory: (String) -> RadiusOAuthCallbackServer? = ::startRadiusOAuthCallbackServer,
) : OAuthAuth {
    private val gateway = normalizeRadiusGatewayUrl(gateway)

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val method =
            interaction.prompt(
                AuthPrompt.Select(
                    message = "Sign in to $name:",
                    options =
                        listOf(
                            AuthOption("browser", "Sign in with browser (recommended)"),
                            AuthOption(
                                "device-code",
                                "Sign in with device code (when signing in from another device)",
                            ),
                        ),
                ),
            )
        return when (method) {
            "browser" -> loginWithBrowser(loadDiscovery().authorizationEndpoint, interaction)
            "device-code" -> loginWithDeviceCode(interaction)
            else -> error("Unknown $name sign-in method: $method")
        }
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential {
        return requestToken(
            formBody(
                "grant_type" to "refresh_token",
                "client_id" to RADIUS_OAUTH_CLIENT_ID,
                "refresh_token" to credential.refresh,
            ),
        )
    }

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
        ModelAuth(apiKey = credential.access)

    private suspend fun loadDiscovery(): RadiusOAuthDiscovery {
        val response =
            transport.execute(
                OAuthHttpRequest(
                    url = URI.create(gateway).resolve("/v1/oauth").toString(),
                    method = "GET",
                    headers = mapOf("accept" to "application/json"),
                ),
            )
        if (response.status !in 200..299) {
            error("Could not load Radius OAuth config from $gateway: ${response.status} ${response.body}")
        }
        val body =
            runCatching { providerJson.parseToJsonElement(response.body).jsonObject }
                .getOrElse { error("Invalid Radius OAuth config from $gateway") }
        return RadiusOAuthDiscovery(
            authorizationEndpoint = body.requiredRadiusString("authorizationEndpoint"),
        )
    }

    private suspend fun loginWithBrowser(
        authorizationEndpoint: String,
        interaction: AuthInteraction,
    ): OAuthCredential {
        val pkce = createRadiusPkce(random)
        val state = stateFactory()
        val callback = callbackServerFactory(state)
        val authorizationUrl =
            radiusUrl(
                authorizationEndpoint,
                "response_type" to "code",
                "client_id" to RADIUS_OAUTH_CLIENT_ID,
                "redirect_uri" to RADIUS_REDIRECT_URI,
                "scope" to RADIUS_OAUTH_SCOPE,
                "code_challenge" to pkce.challenge,
                "code_challenge_method" to "S256",
                "handoff" to "url",
                "state" to state,
            )
        interaction.notify(
            AuthEvent.Progress(
                "Listening for OAuth callback on $RADIUS_REDIRECT_URI",
            ),
        )
        interaction.notify(
            AuthEvent.AuthUrl(
                url = authorizationUrl,
                instructions = "Continue in your browser.",
            ),
        )
        return try {
            val code = callback?.code?.await()
                ?: error("OAuth callback did not complete.")
            requestToken(
                formBody(
                    "grant_type" to "authorization_code",
                    "client_id" to RADIUS_OAUTH_CLIENT_ID,
                    "redirect_uri" to RADIUS_REDIRECT_URI,
                    "code" to code,
                    "code_verifier" to pkce.verifier,
                ),
            )
        } finally {
            callback?.close()
        }
    }

    private suspend fun loginWithDeviceCode(interaction: AuthInteraction): OAuthCredential {
        val response =
            transport.execute(
                OAuthHttpRequest(
                    url = URI.create(gateway).resolve("/v1/oauth/device").toString(),
                    headers = RADIUS_FORM_HEADERS,
                    body =
                        formBody(
                            "client_id" to RADIUS_OAUTH_CLIENT_ID,
                            "scope" to RADIUS_OAUTH_SCOPE,
                        ),
                ),
            )
        if (response.status !in 200..299) {
            throw radiusOAuthResponseError(
                response,
                "Radius OAuth device authorization failed",
            )
        }
        val body =
            runCatching { providerJson.parseToJsonElement(response.body).jsonObject }
                .getOrElse {
                    error("Radius OAuth device authorization response is missing required fields")
                }
        val deviceCode = body.stringValue("device_code")
        val userCode = body.stringValue("user_code")
        val verificationUri = body.stringValue("verification_uri")
        val expiresIn = body.numberValue("expires_in")
        if (
            deviceCode.isNullOrEmpty() ||
            userCode.isNullOrEmpty() ||
            verificationUri.isNullOrEmpty() ||
            expiresIn == null ||
            expiresIn <= 0.0
        ) {
            error("Radius OAuth device authorization response is missing required fields")
        }
        val interval = body.numberValue("interval")
        interaction.notify(
            AuthEvent.DeviceCode(
                userCode = userCode,
                verificationUri = verificationUri,
                intervalSeconds = interval,
                expiresInSeconds = expiresIn.toInt(),
            ),
        )
        val deadline = now() + (expiresIn * 1_000).toLong()
        var intervalMs =
            maxOf(
                MINIMUM_RADIUS_POLL_INTERVAL_MS,
                ((interval ?: DEFAULT_RADIUS_POLL_INTERVAL_SECONDS) * 1_000).toLong(),
            )
        var slowDownResponses = 0
        while (now() < deadline) {
            try {
                return requestToken(
                    formBody(
                        "grant_type" to RADIUS_DEVICE_CODE_GRANT_TYPE,
                        "client_id" to RADIUS_OAUTH_CLIENT_ID,
                        "device_code" to deviceCode,
                    ),
                )
            } catch (error: RadiusOAuthResponseException) {
                when (error.oauthError) {
                    "authorization_pending" -> Unit
                    "slow_down" -> {
                        slowDownResponses++
                        intervalMs += RADIUS_SLOW_DOWN_INCREMENT_MS
                    }

                    "expired_token" -> error("Device authorization expired.")
                    "access_denied" -> error("Device authorization was denied.")
                    else -> throw error
                }
            }
            val remaining = deadline - now()
            if (remaining <= 0) {
                break
            }
            sleep(minOf(intervalMs, remaining))
        }
        if (slowDownResponses > 0) {
            error(RADIUS_SLOW_DOWN_TIMEOUT_MESSAGE)
        }
        error("Device flow timed out")
    }

    private suspend fun requestToken(
        body: String,
    ): OAuthCredential {
        val response =
            transport.execute(
                OAuthHttpRequest(
                    url = URI.create(gateway).resolve("/v1/oauth/token").toString(),
                    headers = RADIUS_FORM_HEADERS,
                    body = body,
                ),
            )
        if (response.status !in 200..299) {
            throw radiusOAuthResponseError(response, "Radius OAuth token request failed")
        }
        val parsed =
            runCatching { providerJson.parseToJsonElement(response.body).jsonObject }
                .getOrElse { error("Radius OAuth token request returned invalid JSON") }
        val access = parsed.stringValue("access_token")
        val refresh = parsed.stringValue("refresh_token")
        val expiresIn = parsed.numberValue("expires_in")
        if (access.isNullOrEmpty() || refresh.isNullOrEmpty() || expiresIn == null) {
            error("Radius OAuth token response is missing required fields")
        }
        return OAuthCredential(
            access = access,
            refresh = refresh,
            expires = now() + (expiresIn * 1_000).toLong() - RADIUS_TOKEN_EXPIRY_SKEW_MS,
            scope = parsed.stringValue("scope"),
        )
    }
}

internal fun createRadiusPkce(random: SecureRandom = SecureRandom()): RadiusPkce {
    val verifier =
        ByteArray(32)
            .also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    val challenge =
        MessageDigest
            .getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.UTF_8))
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    return RadiusPkce(verifier, challenge)
}

private class RadiusOAuthResponseException(
    message: String,
    val oauthError: String?,
) : IllegalStateException(message)

private fun radiusOAuthResponseError(
    response: OAuthHttpResponse,
    message: String,
): RadiusOAuthResponseException {
    val body =
        runCatching { providerJson.parseToJsonElement(response.body).jsonObject }
            .getOrNull()
    val oauthError = body?.stringValue("error")
    val description =
        body?.stringValue("error_description")
            ?: response.body.takeIf(String::isNotEmpty)
    val detail =
        when {
            oauthError != null && description != null -> "$oauthError: $description"
            oauthError != null -> oauthError
            description != null -> description
            else -> response.status.toString()
        }
    return RadiusOAuthResponseException("$message: $detail", oauthError)
}

private fun startRadiusOAuthCallbackServer(expectedState: String): RadiusOAuthCallbackServer? =
    runCatching {
        val result = CompletableDeferred<String?>()
        val server = HttpServer.create(InetSocketAddress(RADIUS_CALLBACK_HOST, RADIUS_CALLBACK_PORT), 0)
        server.createContext("/") { exchange ->
            handleRadiusOAuthCallback(exchange, expectedState, result)
        }
        server.start()
        RadiusOAuthCallbackServer(
            closeAction = {
                if (!result.isCompleted) {
                    result.complete(null)
                }
                server.stop(0)
            },
            code = result,
        )
    }.getOrNull()

private fun handleRadiusOAuthCallback(
    exchange: HttpExchange,
    expectedState: String,
    result: CompletableDeferred<String?>,
) {
    if (exchange.requestURI.path != RADIUS_CALLBACK_PATH) {
        exchange.respondRadius(404, radiusOAuthPage("Authentication failed", "Callback route not found."))
        return
    }
    val params = parseRadiusQuery(exchange.requestURI.rawQuery.orEmpty())
    if (params["state"] != expectedState) {
        exchange.respondRadius(400, radiusOAuthPage("Authentication failed", "OAuth state mismatch."))
        return
    }
    val oauthError = params["error"]
    if (!oauthError.isNullOrEmpty()) {
        exchange.respondRadius(
            400,
            radiusOAuthPage(
                "Authentication failed",
                params["error_description"] ?: oauthError,
            ),
        )
        result.complete(null)
        return
    }
    val code = params["code"]
    if (code.isNullOrEmpty()) {
        exchange.respondRadius(400, radiusOAuthPage("Authentication failed", "Missing authorization code."))
        return
    }
    exchange.respondRadius(
        200,
        radiusOAuthPage(
            "Authentication successful",
            "Signed in to Radius. You may now close this page.",
        ),
    )
    result.complete(code)
}

private fun HttpExchange.respondRadius(
    status: Int,
    body: String,
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.set("content-type", "text/html; charset=utf-8")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun radiusOAuthPage(
    heading: String,
    message: String,
): String =
    """
    <!doctype html>
    <html lang="en">
      <head><meta charset="utf-8"><title>${heading.escapeRadiusHtml()}</title></head>
      <body><main><h1>${heading.escapeRadiusHtml()}</h1><p>${message.escapeRadiusHtml()}</p></main></body>
    </html>
    """.trimIndent()

private fun String.escapeRadiusHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private fun radiusUrl(
    base: String,
    vararg values: Pair<String, String>,
): String = base.substringBefore('?') + "?" + formBody(*values)

private fun formBody(vararg values: Pair<String, String>): String =
    values.joinToString("&") { (name, value) ->
        "${encodeRadiusForm(name)}=${encodeRadiusForm(value)}"
    }

private fun parseRadiusQuery(query: String): Map<String, String> =
    query
        .split('&')
        .mapNotNull { entry ->
            if (entry.isBlank()) {
                null
            } else {
                val separator = entry.indexOf('=')
                val name = if (separator >= 0) entry.substring(0, separator) else entry
                val value = if (separator >= 0) entry.substring(separator + 1) else ""
                decodeRadiusForm(name) to decodeRadiusForm(value)
            }
        }.toMap()

private fun encodeRadiusForm(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

private fun decodeRadiusForm(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

private fun JsonObject.requiredRadiusString(name: String): String =
    stringValue(name)
        ?.takeIf(String::isNotEmpty)
        ?: error("Invalid Radius OAuth config field: $name")

private fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.content

private fun JsonObject.numberValue(name: String): Double? =
    (this[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.doubleOrNull

private const val RADIUS_CALLBACK_HOST = "127.0.0.1"
private const val RADIUS_CALLBACK_PORT = 1456
private const val RADIUS_CALLBACK_PATH = "/oauth/callback"
private const val RADIUS_REDIRECT_URI = "http://$RADIUS_CALLBACK_HOST:$RADIUS_CALLBACK_PORT$RADIUS_CALLBACK_PATH"
private const val RADIUS_OAUTH_CLIENT_ID = "pi-gateway"
private const val RADIUS_OAUTH_SCOPE = "gateway offline_access"
private const val RADIUS_DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
private const val RADIUS_TOKEN_EXPIRY_SKEW_MS = 60_000L
private const val MINIMUM_RADIUS_POLL_INTERVAL_MS = 1_000L
private const val DEFAULT_RADIUS_POLL_INTERVAL_SECONDS = 5.0
private const val RADIUS_SLOW_DOWN_INCREMENT_MS = 5_000L
private const val RADIUS_SLOW_DOWN_TIMEOUT_MESSAGE =
    "Device flow timed out after one or more slow_down responses. " +
        "This is often caused by clock drift in WSL or VM environments. " +
        "Please sync or restart the VM clock and try again."
private val RADIUS_FORM_HEADERS =
    mapOf(
        "accept" to "application/json",
        "content-type" to "application/x-www-form-urlencoded",
    )
