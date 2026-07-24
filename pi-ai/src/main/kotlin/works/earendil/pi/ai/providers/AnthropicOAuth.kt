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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential

internal data class AnthropicOAuthEndpoints(
    val authorizeUrl: String = "https://claude.ai/oauth/authorize",
    val tokenUrl: String = "https://platform.claude.com/v1/oauth/token",
    val redirectUri: String = "http://localhost:53692/callback",
)

internal data class AnthropicAuthorizationFlow(
    val verifier: String,
    val challenge: String,
    val url: String,
)

internal data class AnthropicAuthorizationInput(
    val code: String?,
    val state: String?,
)

internal class AnthropicOAuth(
    private val transport: OAuthHttpTransport = JavaOAuthHttpTransport(),
    private val endpoints: AnthropicOAuthEndpoints = AnthropicOAuthEndpoints(),
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val callbackHost: () -> String = {
        System.getenv("PI_OAUTH_CALLBACK_HOST")?.takeIf(String::isNotBlank) ?: "127.0.0.1"
    },
    private val callbackServerFactory: (String) -> AnthropicCallbackServer? = { state ->
        startAnthropicCallbackServer(
            state = state,
            redirectUri = endpoints.redirectUri,
            host = callbackHost(),
        )
    },
) : OAuthAuth {
    override val name: String = "Anthropic (Claude Pro/Max)"

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val flow = createAnthropicAuthorizationFlow(endpoints, random)
        val callbackServer = callbackServerFactory(flow.verifier)
        interaction.notify(
            AuthEvent.AuthUrl(
                url = flow.url,
                instructions =
                    "Complete login in your browser. If the browser is on another machine, " +
                        "paste the final redirect URL here.",
            ),
        )
        val input =
            try {
                awaitAuthorizationInput(interaction, callbackServer)
            } finally {
                callbackServer?.close()
            }
        val parsed = parseAnthropicAuthorizationInput(input)
        if (parsed.state != null && parsed.state != flow.verifier) {
            error("OAuth state mismatch")
        }
        val code = parsed.code?.takeIf(String::isNotBlank) ?: error("Missing authorization code")
        val state = parsed.state ?: flow.verifier
        interaction.notify(AuthEvent.Progress("Exchanging authorization code for tokens..."))
        return exchangeAuthorizationCode(
            code = code,
            state = state,
            verifier = flow.verifier,
            redirectUri = endpoints.redirectUri,
        )
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
        tokenRequest(
            operation = "refresh",
            body =
                buildJsonObject {
                    put("grant_type", "refresh_token")
                    put("client_id", ANTHROPIC_CLIENT_ID)
                    put("refresh_token", credential.refresh)
                },
        )

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
        ModelAuth(apiKey = credential.access)

    private suspend fun awaitAuthorizationInput(
        interaction: AuthInteraction,
        callbackServer: AnthropicCallbackServer?,
    ): String =
        coroutineScope {
            val manual =
                async(Dispatchers.IO) {
                    interaction.prompt(
                        AuthPrompt.ManualCode(
                            message =
                                "Complete login in your browser, or paste the authorization code / " +
                                    "redirect URL here:",
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

    private suspend fun exchangeAuthorizationCode(
        code: String,
        state: String,
        verifier: String,
        redirectUri: String,
    ): OAuthCredential =
        tokenRequest(
            operation = "exchange",
            body =
                buildJsonObject {
                    put("grant_type", "authorization_code")
                    put("client_id", ANTHROPIC_CLIENT_ID)
                    put("code", code)
                    put("state", state)
                    put("redirect_uri", redirectUri)
                    put("code_verifier", verifier)
                },
        )

    private suspend fun tokenRequest(
        operation: String,
        body: JsonObject,
    ): OAuthCredential {
        val response =
            transport.execute(
                OAuthHttpRequest(
                    url = endpoints.tokenUrl,
                    headers = ANTHROPIC_TOKEN_HEADERS,
                    body = providerJson.encodeToString(JsonObject.serializer(), body),
                    timeoutMs = ANTHROPIC_TOKEN_TIMEOUT_MS,
                ),
            )
        if (response.status !in 200..299) {
            error(
                "Anthropic token $operation failed. status=${response.status}; " +
                    "url=${endpoints.tokenUrl}; body=${response.body}",
            )
        }
        val json =
            runCatching { providerJson.parseToJsonElement(response.body).jsonObject }
                .getOrElse {
                    error(
                        "Anthropic token $operation returned invalid JSON. " +
                            "url=${endpoints.tokenUrl}; body=${response.body}",
                    )
                }
        val access = json.string("access_token")
        val refresh = json.string("refresh_token")
        val expiresIn = json["expires_in"]?.jsonPrimitive?.longOrNull
        if (access.isNullOrBlank() || refresh.isNullOrBlank() || expiresIn == null) {
            error("Anthropic token $operation response missing fields: ${response.body}")
        }
        return OAuthCredential(
            access = access,
            refresh = refresh,
            expires = now() + expiresIn * 1_000L - ANTHROPIC_EXPIRY_SKEW_MS,
        )
    }
}

internal fun createAnthropicAuthorizationFlow(
    endpoints: AnthropicOAuthEndpoints = AnthropicOAuthEndpoints(),
    random: SecureRandom = SecureRandom(),
): AnthropicAuthorizationFlow {
    val verifier =
        ByteArray(32)
            .also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    val challenge =
        MessageDigest
            .getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.UTF_8))
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    val query =
        anthropicFormBody(
            "code" to "true",
            "client_id" to ANTHROPIC_CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to endpoints.redirectUri,
            "scope" to ANTHROPIC_SCOPES,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to verifier,
        )
    return AnthropicAuthorizationFlow(
        verifier = verifier,
        challenge = challenge,
        url = "${endpoints.authorizeUrl}?$query",
    )
}

internal fun parseAnthropicAuthorizationInput(input: String): AnthropicAuthorizationInput {
    val parsed = parseOpenAICodexAuthorizationInput(input)
    return AnthropicAuthorizationInput(parsed.code, parsed.state)
}

internal class AnthropicCallbackServer(
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

private fun startAnthropicCallbackServer(
    state: String,
    redirectUri: String,
    host: String,
): AnthropicCallbackServer {
    val uri = URI.create(redirectUri)
    val port = if (uri.port >= 0) uri.port else 80
    val path = uri.path.ifBlank { "/" }
    val code = CompletableDeferred<String>()
    val server = HttpServer.create(InetSocketAddress(host, port), 0)
    server.createContext(path) { exchange ->
        handleAnthropicCallback(exchange, state, code)
    }
    server.start()
    return AnthropicCallbackServer(server, code)
}

private fun handleAnthropicCallback(
    exchange: HttpExchange,
    expectedState: String,
    result: CompletableDeferred<String>,
) {
    val params = parseAnthropicQuery(exchange.requestURI.rawQuery.orEmpty())
    val error = params["error"]
    when {
        !error.isNullOrBlank() ->
            exchange.respondAnthropic(
                400,
                anthropicOAuthPage(
                    "Authentication failed",
                    "Anthropic authentication did not complete. Error: $error",
                ),
            )

        params["state"] != expectedState ->
            exchange.respondAnthropic(400, anthropicOAuthPage("Authentication failed", "State mismatch."))

        params["code"].isNullOrBlank() ->
            exchange.respondAnthropic(
                400,
                anthropicOAuthPage("Authentication failed", "Missing code or state parameter."),
            )

        else -> {
            exchange.respondAnthropic(
                200,
                anthropicOAuthPage(
                    "Authentication successful",
                    "Anthropic authentication completed. You can close this window.",
                ),
            )
            result.complete(requireNotNull(params["code"]))
        }
    }
}

private fun HttpExchange.respondAnthropic(
    status: Int,
    body: String,
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.set("Content-Type", "text/html; charset=utf-8")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun anthropicOAuthPage(
    heading: String,
    message: String,
): String =
    """
    <!doctype html>
    <html lang="en">
      <head><meta charset="utf-8"><title>${heading.anthropicEscapeHtml()}</title></head>
      <body><main><h1>${heading.anthropicEscapeHtml()}</h1><p>${message.anthropicEscapeHtml()}</p></main></body>
    </html>
    """.trimIndent()

private fun String.anthropicEscapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private fun parseAnthropicQuery(query: String): Map<String, String> =
    query
        .split('&')
        .mapNotNull { entry ->
            if (entry.isBlank()) {
                null
            } else {
                val separator = entry.indexOf('=')
                val name = if (separator >= 0) entry.substring(0, separator) else entry
                val value = if (separator >= 0) entry.substring(separator + 1) else ""
                URLDecoder.decode(name, StandardCharsets.UTF_8) to
                    URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
        }.toMap()

private fun anthropicFormBody(vararg values: Pair<String, String>): String =
    values.joinToString("&") { (name, value) ->
        "${URLEncoder.encode(name, StandardCharsets.UTF_8)}=" +
            URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

private val ANTHROPIC_TOKEN_HEADERS =
    mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json",
    )
private const val ANTHROPIC_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
private const val ANTHROPIC_SCOPES =
    "org:create_api_key user:profile user:inference user:sessions:claude_code " +
        "user:mcp_servers user:file_upload"
private const val ANTHROPIC_TOKEN_TIMEOUT_MS = 30_000L
private const val ANTHROPIC_EXPIRY_SKEW_MS = 5 * 60 * 1_000L
