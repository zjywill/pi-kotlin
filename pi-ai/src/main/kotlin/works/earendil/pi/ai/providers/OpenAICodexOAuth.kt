package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthOption
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential

internal data class OAuthHttpRequest(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val timeoutMs: Long? = null,
)

internal data class OAuthHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

internal fun interface OAuthHttpTransport {
    suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse
}

internal class JavaOAuthHttpTransport(
    private val client: HttpClient = HttpClient.newHttpClient(),
) : OAuthHttpTransport {
    override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse =
        withContext(Dispatchers.IO) {
            val httpRequest =
                HttpRequest
                    .newBuilder(URI.create(request.url))
                    .method(
                        request.method,
                        if (request.method == "GET" && request.body.isEmpty()) {
                            HttpRequest.BodyPublishers.noBody()
                        } else {
                            HttpRequest.BodyPublishers.ofString(request.body)
                        },
                    )
                    .apply {
                        request.headers.forEach(::header)
                        request.timeoutMs?.let { timeout(Duration.ofMillis(it)) }
                    }.build()
            val response =
                client.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                )
            OAuthHttpResponse(
                status = response.statusCode(),
                body = response.body(),
                headers =
                    response
                        .headers()
                        .map()
                        .mapValues { (_, values) -> values.firstOrNull().orEmpty() },
            )
        }
}

internal data class OpenAICodexOAuthEndpoints(
    val authorizeUrl: String = "https://auth.openai.com/oauth/authorize",
    val tokenUrl: String = "https://auth.openai.com/oauth/token",
    val redirectUri: String = "http://localhost:1455/auth/callback",
    val deviceUserCodeUrl: String = "https://auth.openai.com/api/accounts/deviceauth/usercode",
    val deviceTokenUrl: String = "https://auth.openai.com/api/accounts/deviceauth/token",
    val deviceVerificationUri: String = "https://auth.openai.com/codex/device",
    val deviceRedirectUri: String = "https://auth.openai.com/deviceauth/callback",
)

internal data class OpenAICodexAuthorizationFlow(
    val verifier: String,
    val state: String,
    val url: String,
)

internal data class OpenAICodexAuthorizationInput(
    val code: String?,
    val state: String?,
)

internal class OpenAICodexOAuth(
    private val transport: OAuthHttpTransport = JavaOAuthHttpTransport(),
    private val endpoints: OpenAICodexOAuthEndpoints = OpenAICodexOAuthEndpoints(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: SecureRandom = SecureRandom(),
    private val callbackHost: () -> String = {
        System.getenv("PI_OAUTH_CALLBACK_HOST")?.takeIf(String::isNotBlank) ?: "127.0.0.1"
    },
    private val callbackServerFactory: (String) -> OpenAICodexCallbackServer? = { state ->
        startOpenAICodexCallbackServer(
            state = state,
            redirectUri = endpoints.redirectUri,
            host = callbackHost(),
        )
    },
) : OAuthAuth {
    override val name: String = "OpenAI (ChatGPT Plus/Pro)"

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val method =
            interaction.prompt(
                AuthPrompt.Select(
                    message = "Select OpenAI Codex login method:",
                    options =
                        listOf(
                            AuthOption("browser", "Browser login (default)"),
                            AuthOption("device_code", "Device code login (headless)"),
                        ),
                ),
            )
        return when (method) {
            "browser" -> loginBrowser(interaction)
            "device_code" -> loginDeviceCode(interaction)
            else -> error("Unknown OpenAI Codex login method: $method")
        }
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential {
        val response =
            try {
                transport.execute(
                    OAuthHttpRequest(
                        url = endpoints.tokenUrl,
                        headers = FORM_HEADERS,
                        body =
                            formBody(
                                "grant_type" to "refresh_token",
                                "refresh_token" to credential.refresh,
                                "client_id" to OPENAI_CODEX_CLIENT_ID,
                            ),
                    ),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) {
                    throw error
                }
                throw IllegalStateException(
                    "OpenAI Codex token refresh error: ${error.message ?: error::class.simpleName}",
                    error,
                )
            }
        return credentialsFromToken(readTokenResponse(response, "refresh"))
    }

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
        ModelAuth(apiKey = credential.access)

    private suspend fun loginBrowser(interaction: AuthInteraction): OAuthCredential {
        val flow = createOpenAICodexAuthorizationFlow(endpoints, random = random)
        val callbackServer = callbackServerFactory(flow.state)
        interaction.notify(
            AuthEvent.AuthUrl(
                url = flow.url,
                instructions = "A browser window should open. Complete login to finish.",
            ),
        )
        val input =
            try {
                awaitBrowserAuthorizationInput(interaction, callbackServer)
            } finally {
                callbackServer?.close()
            }
        val parsed = parseOpenAICodexAuthorizationInput(input)
        if (parsed.state != null && parsed.state != flow.state) {
            error("State mismatch")
        }
        val code = parsed.code?.takeIf(String::isNotBlank) ?: error("Missing authorization code")
        return exchangeAuthorizationCode(
            code = code,
            verifier = flow.verifier,
            redirectUri = endpoints.redirectUri,
        )
    }

    private suspend fun awaitBrowserAuthorizationInput(
        interaction: AuthInteraction,
        callbackServer: OpenAICodexCallbackServer?,
    ): String =
        coroutineScope {
            val manual =
                async(Dispatchers.IO) {
                    interaction.prompt(
                        AuthPrompt.ManualCode(
                            message = "Complete login in your browser, or paste the authorization code / redirect URL here:",
                            placeholder = endpoints.redirectUri,
                        ),
                    )
                }
            if (callbackServer == null) {
                return@coroutineScope manual.await()
            }
            select {
                callbackServer.code.onAwait { code ->
                    manual.cancel()
                    code
                }
                manual.onAwait { value ->
                    callbackServer.cancel()
                    value
                }
            }
        }

    private suspend fun loginDeviceCode(interaction: AuthInteraction): OAuthCredential {
        val device = startDeviceAuth()
        interaction.notify(
            AuthEvent.DeviceCode(
                userCode = device.userCode,
                verificationUri = endpoints.deviceVerificationUri,
                intervalSeconds = device.intervalSeconds,
                expiresInSeconds = OPENAI_CODEX_DEVICE_TIMEOUT_SECONDS,
            ),
        )
        val token = pollDeviceAuth(device)
        return exchangeAuthorizationCode(
            code = token.authorizationCode,
            verifier = token.codeVerifier,
            redirectUri = endpoints.deviceRedirectUri,
        )
    }

    private suspend fun startDeviceAuth(): DeviceAuthInfo {
        val response =
            transport.execute(
                OAuthHttpRequest(
                    url = endpoints.deviceUserCodeUrl,
                    headers = JSON_HEADERS,
                    body =
                        oauthJson.encodeToString(
                            JsonObject.serializer(),
                            buildJsonObject {
                                put("client_id", OPENAI_CODEX_CLIENT_ID)
                            },
                        ),
                ),
            )
        if (response.status !in 200..299) {
            if (response.status == 404) {
                error(
                    "OpenAI Codex device code login is not enabled for this server. " +
                        "Use browser login or verify the server URL.",
                )
            }
            error(
                "OpenAI Codex device code request failed with status ${response.status}" +
                    response.body.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
            )
        }
        val json = parseJsonObject(response.body, "Invalid OpenAI Codex device code response")
        val interval =
            (json["interval"] as? JsonPrimitive)
                ?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
        val deviceAuthId = json.string("device_auth_id")
        val userCode = json.string("user_code")
        if (deviceAuthId.isNullOrBlank() || userCode.isNullOrBlank() || interval == null || interval < 0.0) {
            error("Invalid OpenAI Codex device code response: ${response.body}")
        }
        return DeviceAuthInfo(deviceAuthId, userCode, interval)
    }

    private suspend fun pollDeviceAuth(device: DeviceAuthInfo): DeviceTokenSuccess {
        val deadline = now() + OPENAI_CODEX_DEVICE_TIMEOUT_SECONDS * 1_000L
        var intervalMs = maxOf(MINIMUM_DEVICE_POLL_INTERVAL_MS, (device.intervalSeconds * 1_000).toLong())
        var slowDownResponses = 0
        while (now() < deadline) {
            val response =
                transport.execute(
                    OAuthHttpRequest(
                        url = endpoints.deviceTokenUrl,
                        headers = JSON_HEADERS,
                        body =
                            oauthJson.encodeToString(
                                JsonObject.serializer(),
                                buildJsonObject {
                                    put("device_auth_id", device.deviceAuthId)
                                    put("user_code", device.userCode)
                                },
                            ),
                    ),
                )
            if (response.status in 200..299) {
                val json = parseJsonObject(response.body, "Invalid OpenAI Codex device auth token response")
                val authorizationCode = json.string("authorization_code")
                val codeVerifier = json.string("code_verifier")
                if (authorizationCode.isNullOrBlank() || codeVerifier.isNullOrBlank()) {
                    error("Invalid OpenAI Codex device auth token response: ${response.body}")
                }
                return DeviceTokenSuccess(authorizationCode, codeVerifier)
            }
            if (response.status != 403 && response.status != 404) {
                when (deviceErrorCode(response.body)) {
                    "deviceauth_authorization_pending" -> Unit
                    "slow_down" -> {
                        slowDownResponses++
                        intervalMs += SLOW_DOWN_INTERVAL_INCREMENT_MS
                    }

                    else ->
                        error(
                            "OpenAI Codex device auth failed with status ${response.status}" +
                                response.body.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
                        )
                }
            }
            val remaining = deadline - now()
            if (remaining <= 0) {
                break
            }
            sleep(minOf(intervalMs, remaining))
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

    private suspend fun exchangeAuthorizationCode(
        code: String,
        verifier: String,
        redirectUri: String,
    ): OAuthCredential {
        val response =
            transport.execute(
                OAuthHttpRequest(
                    url = endpoints.tokenUrl,
                    headers = FORM_HEADERS,
                    body =
                        formBody(
                            "grant_type" to "authorization_code",
                            "client_id" to OPENAI_CODEX_CLIENT_ID,
                            "code" to code,
                            "code_verifier" to verifier,
                            "redirect_uri" to redirectUri,
                        ),
                ),
            )
        return credentialsFromToken(readTokenResponse(response, "exchange"))
    }

    private fun readTokenResponse(
        response: OAuthHttpResponse,
        operation: String,
    ): OAuthToken {
        if (response.status !in 200..299) {
            error(
                "OpenAI Codex token $operation failed (${response.status}): " +
                    response.body.ifBlank { "HTTP ${response.status}" },
            )
        }
        val json =
            runCatching { oauthJson.parseToJsonElement(response.body).jsonObject }
                .getOrNull()
        val access = json?.string("access_token")
        val refresh = json?.string("refresh_token")
        val expiresIn = json?.get("expires_in")?.jsonPrimitive?.longOrNull
        if (access.isNullOrBlank() || refresh.isNullOrBlank() || expiresIn == null) {
            error("OpenAI Codex token $operation response missing fields: ${response.body}")
        }
        return OAuthToken(
            access = access,
            refresh = refresh,
            expires = now() + expiresIn * 1_000L,
        )
    }
}

internal fun createOpenAICodexAuthorizationFlow(
    endpoints: OpenAICodexOAuthEndpoints = OpenAICodexOAuthEndpoints(),
    originator: String = "pi",
    random: SecureRandom = SecureRandom(),
): OpenAICodexAuthorizationFlow {
    val verifier = randomBytes(random, 32).base64Url()
    val challenge =
        MessageDigest
            .getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.UTF_8))
            .base64Url()
    val state = randomBytes(random, 16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val query =
        formBody(
            "response_type" to "code",
            "client_id" to OPENAI_CODEX_CLIENT_ID,
            "redirect_uri" to endpoints.redirectUri,
            "scope" to OPENAI_CODEX_SCOPE,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to originator,
        )
    return OpenAICodexAuthorizationFlow(
        verifier = verifier,
        state = state,
        url = "${endpoints.authorizeUrl}?$query",
    )
}

internal fun parseOpenAICodexAuthorizationInput(input: String): OpenAICodexAuthorizationInput {
    val value = input.trim()
    if (value.isEmpty()) {
        return OpenAICodexAuthorizationInput(null, null)
    }
    runCatching {
        val uri = URI(value)
        if (uri.scheme != null) {
            val params = parseQuery(uri.rawQuery.orEmpty())
            return OpenAICodexAuthorizationInput(params["code"], params["state"])
        }
    }
    if ('#' in value) {
        val parts = value.split('#', limit = 2)
        return OpenAICodexAuthorizationInput(parts[0], parts.getOrNull(1))
    }
    if ("code=" in value) {
        val params = parseQuery(value)
        return OpenAICodexAuthorizationInput(params["code"], params["state"])
    }
    return OpenAICodexAuthorizationInput(value, null)
}

internal fun extractOpenAICodexOAuthAccountId(token: String): String? =
    runCatching {
        val parts = token.split('.')
        if (parts.size != 3) {
            return@runCatching null
        }
        val payload =
            oauthJson
                .parseToJsonElement(
                    Base64
                        .getUrlDecoder()
                        .decode(parts[1].withBase64Padding())
                        .toString(StandardCharsets.UTF_8),
                ).jsonObject
        payload[OPENAI_CODEX_JWT_CLAIM]
            ?.jsonObject
            ?.string("chatgpt_account_id")
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

private data class OAuthToken(
    val access: String,
    val refresh: String,
    val expires: Long,
)

private data class DeviceAuthInfo(
    val deviceAuthId: String,
    val userCode: String,
    val intervalSeconds: Double,
)

private data class DeviceTokenSuccess(
    val authorizationCode: String,
    val codeVerifier: String,
)

internal class OpenAICodexCallbackServer(
    private val server: HttpServer,
    val code: CompletableDeferred<String>,
) {
    fun cancel() {
        code.cancel()
    }

    fun close() {
        server.stop(0)
    }
}

private fun startOpenAICodexCallbackServer(
    state: String,
    redirectUri: String,
    host: String,
): OpenAICodexCallbackServer? =
    runCatching {
        val uri = URI.create(redirectUri)
        val port = if (uri.port >= 0) uri.port else 80
        val path = uri.path.ifBlank { "/" }
        val code = CompletableDeferred<String>()
        val server = HttpServer.create(InetSocketAddress(host, port), 0)
        server.createContext(path) { exchange ->
            handleOAuthCallback(exchange, state, code)
        }
        server.start()
        OpenAICodexCallbackServer(server, code)
    }.getOrNull()

private fun handleOAuthCallback(
    exchange: HttpExchange,
    expectedState: String,
    result: CompletableDeferred<String>,
) {
    val params = parseQuery(exchange.requestURI.rawQuery.orEmpty())
    when {
        params["state"] != expectedState ->
            exchange.respond(400, oauthPage("Authentication failed", "State mismatch."))

        params["code"].isNullOrBlank() ->
            exchange.respond(400, oauthPage("Authentication failed", "Missing authorization code."))

        else -> {
            exchange.respond(
                200,
                oauthPage(
                    "Authentication successful",
                    "OpenAI authentication completed. You can close this window.",
                ),
            )
            result.complete(requireNotNull(params["code"]))
        }
    }
}

private fun HttpExchange.respond(
    status: Int,
    body: String,
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.set("Content-Type", "text/html; charset=utf-8")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun oauthPage(
    heading: String,
    message: String,
): String =
    """
    <!doctype html>
    <html lang="en">
      <head><meta charset="utf-8"><title>${heading.escapeHtml()}</title></head>
      <body><main><h1>${heading.escapeHtml()}</h1><p>${message.escapeHtml()}</p></main></body>
    </html>
    """.trimIndent()

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private fun OpenAICodexOAuth.credentialsFromToken(token: OAuthToken): OAuthCredential {
    val accountId =
        extractOpenAICodexOAuthAccountId(token.access)
            ?: error("Failed to extract accountId from token")
    return OAuthCredential(
        access = token.access,
        refresh = token.refresh,
        expires = token.expires,
        accountId = accountId,
    )
}

private fun deviceErrorCode(body: String): String? =
    runCatching {
        val error = oauthJson.parseToJsonElement(body).jsonObject["error"]
        when (error) {
            is JsonPrimitive -> error.content
            is JsonObject -> error.string("code")
            else -> null
        }
    }.getOrNull()

private fun parseJsonObject(
    body: String,
    message: String,
): JsonObject =
    runCatching { oauthJson.parseToJsonElement(body).jsonObject }
        .getOrElse { error("$message: $body") }

private fun parseQuery(query: String): Map<String, String> =
    query
        .split('&')
        .mapNotNull { entry ->
            if (entry.isBlank()) {
                null
            } else {
                val separator = entry.indexOf('=')
                val name = if (separator >= 0) entry.substring(0, separator) else entry
                val value = if (separator >= 0) entry.substring(separator + 1) else ""
                decodeForm(name) to decodeForm(value)
            }
        }.toMap()

private fun formBody(vararg values: Pair<String, String>): String =
    values.joinToString("&") { (name, value) ->
        "${encodeForm(name)}=${encodeForm(value)}"
    }

private fun encodeForm(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

private fun decodeForm(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

private fun randomBytes(
    random: SecureRandom,
    size: Int,
): ByteArray = ByteArray(size).also(random::nextBytes)

private fun ByteArray.base64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun String.withBase64Padding(): String =
    this + "=".repeat((4 - length % 4) % 4)

private val oauthJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

private val JSON_HEADERS = mapOf("Content-Type" to "application/json")
private val FORM_HEADERS = mapOf("Content-Type" to "application/x-www-form-urlencoded")
private const val OPENAI_CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
private const val OPENAI_CODEX_SCOPE = "openid profile email offline_access"
private const val OPENAI_CODEX_JWT_CLAIM = "https://api.openai.com/auth"
private const val OPENAI_CODEX_DEVICE_TIMEOUT_SECONDS = 15 * 60
private const val MINIMUM_DEVICE_POLL_INTERVAL_MS = 1_000L
private const val SLOW_DOWN_INTERVAL_INCREMENT_MS = 5_000L
