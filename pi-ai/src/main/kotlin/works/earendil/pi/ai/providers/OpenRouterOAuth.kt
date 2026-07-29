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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential

internal data class OpenRouterOAuthEndpoints(
    val authorizeUrl: String = "https://openrouter.ai/auth",
    val tokenUrl: String = "https://openrouter.ai/api/v1/auth/keys",
)

internal data class OpenRouterPkce(
    val verifier: String,
    val challenge: String,
)

internal class OpenRouterOAuth(
    private val transport: OAuthHttpTransport = JavaOAuthHttpTransport(),
    private val endpoints: OpenRouterOAuthEndpoints = OpenRouterOAuthEndpoints(),
    private val random: SecureRandom = SecureRandom(),
    private val callbackHost: () -> String = {
        System.getenv("PI_OAUTH_CALLBACK_HOST")?.takeIf(String::isNotBlank) ?: "127.0.0.1"
    },
    private val callbackPath: () -> String = {
        "/oauth/callback/${UUID.randomUUID()}"
    },
    private val loginTimeoutMs: Long = OPENROUTER_LOGIN_TIMEOUT_MS,
    private val callbackServerFactory: (
        callbackPath: String,
        verifier: String,
    ) -> OpenRouterCallbackServer = { path, verifier ->
        startOpenRouterCallbackServer(
            callbackPath = path,
            verifier = verifier,
            host = callbackHost(),
            transport = transport,
            endpoints = endpoints,
        )
    },
) : OAuthAuth {
    override val name: String = "OpenRouter OAuth"
    override val loginLabel: String = "Sign in with OpenRouter"

    override suspend fun login(interaction: AuthInteraction): OAuthCredential {
        val pkce = createOpenRouterPkce(random)
        val callback = callbackServerFactory(callbackPath(), pkce.verifier)
        return try {
            withTimeout(loginTimeoutMs) {
                val authorizationUrl =
                    createOpenRouterAuthorizationUrl(
                        endpoints.authorizeUrl,
                        callback.callbackUrl,
                        pkce.challenge,
                    )
                interaction.notify(
                    AuthEvent.Progress(
                        "Listening for OpenRouter OAuth callback on ${callback.callbackUrl}",
                    ),
                )
                interaction.notify(
                    AuthEvent.AuthUrl(
                        url = authorizationUrl,
                        instructions =
                            "Complete sign-in in your browser. If the browser is on another machine, " +
                                "paste the final redirect URL here.",
                    ),
                )
                awaitOpenRouterCredential(
                    interaction = interaction,
                    callback = callback,
                    verifier = pkce.verifier,
                )
            }
        } catch (_: TimeoutCancellationException) {
            error("OpenRouter OAuth login timed out")
        } finally {
            callback.close()
        }
    }

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential = credential

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
        ModelAuth(apiKey = credential.access)

    private suspend fun awaitOpenRouterCredential(
        interaction: AuthInteraction,
        callback: OpenRouterCallbackServer,
        verifier: String,
    ): OAuthCredential =
        coroutineScope {
            val manual =
                async(Dispatchers.IO) {
                    runCatching {
                        interaction.prompt(
                            AuthPrompt.ManualCode(
                                message =
                                    "Complete sign-in in your browser, or paste the authorization code / " +
                                        "redirect URL here:",
                                placeholder = callback.callbackUrl,
                            ),
                        )
                    }
                }
            val callbackWait = async { callback.waitForCredential() }
            val first =
                select<OpenRouterLoginResult> {
                    callbackWait.onAwait { credential ->
                        OpenRouterLoginResult.Callback(credential)
                    }
                    manual.onAwait { result ->
                        callback.cancelWait()
                        OpenRouterLoginResult.Manual(result)
                    }
                }
            val manualResult =
                when (first) {
                    is OpenRouterLoginResult.Callback -> {
                        if (first.credential != null) {
                            manual.cancel()
                            return@coroutineScope first.credential
                        }
                        manual.await()
                    }

                    is OpenRouterLoginResult.Manual -> {
                        val callbackCredential = callbackWait.await()
                        first.result.exceptionOrNull()?.let { throw it }
                        if (callbackCredential != null) {
                            return@coroutineScope callbackCredential
                        }
                        first.result
                    }
                }
            val input = manualResult.getOrThrow()
            val code =
                parseOpenRouterAuthorizationInput(input)
                    ?: error("Missing authorization code")
            interaction.notify(
                AuthEvent.Progress(
                    "Exchanging authorization code for an API key...",
                ),
            )
            exchangeOpenRouterAuthorizationCode(
                code = code,
                verifier = verifier,
                transport = transport,
                endpoints = endpoints,
            )
        }
}

private sealed interface OpenRouterLoginResult {
    data class Callback(
        val credential: OAuthCredential?,
    ) : OpenRouterLoginResult

    data class Manual(
        val result: Result<String>,
    ) : OpenRouterLoginResult
}

internal fun createOpenRouterPkce(random: SecureRandom = SecureRandom()): OpenRouterPkce {
    val verifier =
        ByteArray(32)
            .also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    val challenge =
        MessageDigest
            .getInstance("SHA-256")
            .digest(verifier.toByteArray(StandardCharsets.UTF_8))
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    return OpenRouterPkce(verifier, challenge)
}

internal fun createOpenRouterAuthorizationUrl(
    authorizeUrl: String,
    callbackUrl: String,
    challenge: String,
): String =
    "$authorizeUrl?" +
        openRouterFormBody(
            "callback_url" to callbackUrl,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
        )

internal class OpenRouterCallbackServer(
    private val server: HttpServer,
    val callbackUrl: String,
    private val credential: CompletableDeferred<OAuthCredential?>,
    private val claimed: AtomicBoolean,
) {
    suspend fun waitForCredential(): OAuthCredential? = credential.await()

    fun cancelWait() {
        if (credential.isCompleted) {
            return
        }
        if (claimed.compareAndSet(false, true)) {
            credential.complete(null)
            server.stop(0)
        }
    }

    fun close() {
        server.stop(0)
    }
}

private fun startOpenRouterCallbackServer(
    callbackPath: String,
    verifier: String,
    host: String,
    transport: OAuthHttpTransport,
    endpoints: OpenRouterOAuthEndpoints,
): OpenRouterCallbackServer {
    val credential = CompletableDeferred<OAuthCredential?>()
    val claimed = AtomicBoolean(false)
    val server = HttpServer.create(InetSocketAddress(host, 0), 0)
    server.createContext("/") { exchange ->
        handleOpenRouterCallback(
            exchange = exchange,
            callbackPath = callbackPath,
            verifier = verifier,
            claimed = claimed,
            credential = credential,
            transport = transport,
            endpoints = endpoints,
        )
    }
    server.start()
    return OpenRouterCallbackServer(
        server = server,
        callbackUrl = "http://$host:${server.address.port}$callbackPath",
        credential = credential,
        claimed = claimed,
    )
}

private fun handleOpenRouterCallback(
    exchange: HttpExchange,
    callbackPath: String,
    verifier: String,
    claimed: AtomicBoolean,
    credential: CompletableDeferred<OAuthCredential?>,
    transport: OAuthHttpTransport,
    endpoints: OpenRouterOAuthEndpoints,
) {
    if (exchange.requestMethod != "GET" || exchange.requestURI.path != callbackPath) {
        exchange.respondOpenRouter(
            404,
            openRouterOAuthPage("Authentication failed", "OAuth callback route not found."),
        )
        return
    }
    if (claimed.get() || credential.isCompleted) {
        exchange.respondOpenRouter(
            409,
            openRouterOAuthPage("Authentication failed", "This OAuth callback has already been used."),
        )
        return
    }
    val params = parseOpenRouterQuery(exchange.requestURI.rawQuery.orEmpty())
    val oauthError = params["error"]
    if (!oauthError.isNullOrBlank()) {
        val description = params["error_description"]?.takeIf(String::isNotBlank) ?: oauthError
        exchange.respondOpenRouter(
            400,
            openRouterOAuthPage(
                "Authentication failed",
                "OpenRouter authorization was denied. $description",
            ),
        )
        credential.completeExceptionally(
            IllegalStateException("OpenRouter authorization failed: $description"),
        )
        return
    }
    val code = params["code"]
    if (code.isNullOrBlank()) {
        exchange.respondOpenRouter(
            400,
            openRouterOAuthPage(
                "Authentication failed",
                "OpenRouter returned no authorization code.",
            ),
        )
        return
    }
    if (!claimed.compareAndSet(false, true)) {
        exchange.respondOpenRouter(
            409,
            openRouterOAuthPage("Authentication failed", "This OAuth callback has already been used."),
        )
        return
    }
    runCatching {
        runBlocking {
            exchangeOpenRouterAuthorizationCode(
                code = code,
                verifier = verifier,
                transport = transport,
                endpoints = endpoints,
            )
        }
    }.onSuccess { result ->
        exchange.respondOpenRouter(
            200,
            openRouterOAuthPage(
                "Authentication successful",
                "Signed in to OpenRouter. You may now close this page.",
            ),
        )
        credential.complete(result)
    }.onFailure { error ->
        val message = error.message ?: "Unknown token exchange error"
        exchange.respondOpenRouter(
            502,
            openRouterOAuthPage("Authentication failed", "OpenRouter key exchange failed. $message"),
        )
        credential.completeExceptionally(error)
    }
}

internal fun parseOpenRouterAuthorizationInput(input: String): String? {
    val value = input.trim()
    if (value.isEmpty()) {
        return null
    }
    runCatching {
        URI.create(value)
    }.getOrNull()?.takeIf(URI::isAbsolute)?.let { uri ->
        return uri.rawQuery?.let(::parseOpenRouterQuery)?.get("code")
    }
    if ("code=" in value) {
        return parseOpenRouterQuery(value.removePrefix("?"))["code"]
    }
    return value
}

private suspend fun exchangeOpenRouterAuthorizationCode(
    code: String,
    verifier: String,
    transport: OAuthHttpTransport,
    endpoints: OpenRouterOAuthEndpoints,
): OAuthCredential {
    val response =
        transport.execute(
            OAuthHttpRequest(
                url = endpoints.tokenUrl,
                headers =
                    mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/json",
                    ),
                body =
                    providerJson.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("code", code)
                            put("code_verifier", verifier)
                            put("code_challenge_method", "S256")
                        },
                    ),
                timeoutMs = OPENROUTER_TOKEN_TIMEOUT_MS,
            ),
        )
    val body =
        runCatching { providerJson.parseToJsonElement(response.body).jsonObject }
            .getOrElse {
                if (response.status in 200..299) {
                    error("OpenRouter OAuth returned invalid JSON")
                }
                JsonObject(emptyMap())
            }
    if (response.status !in 200..299) {
        val detail = openRouterErrorDetail(body)
        error(
            "OpenRouter OAuth key exchange failed (HTTP ${response.status})" +
                detail?.let { ": $it" }.orEmpty(),
        )
    }
    val key = body.string("key")
    if (key.isNullOrEmpty()) {
        error("OpenRouter OAuth response carries no \"key\"")
    }
    return OAuthCredential(
        access = key,
        refresh = "",
        expires = JAVASCRIPT_MAX_SAFE_INTEGER,
    )
}

private fun openRouterErrorDetail(body: JsonObject): String? =
    body.string("error_description")
        ?: body.string("message")
        ?: body.string("error")
        ?: body.obj("error")?.string("message")

private fun HttpExchange.respondOpenRouter(
    status: Int,
    body: String,
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.set("Content-Type", "text/html; charset=utf-8")
    responseHeaders.set("Cache-Control", "no-store")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun openRouterOAuthPage(
    heading: String,
    message: String,
): String =
    """
    <!doctype html>
    <html lang="en">
      <head><meta charset="utf-8"><title>${heading.openRouterEscapeHtml()}</title></head>
      <body><main><h1>${heading.openRouterEscapeHtml()}</h1><p>${message.openRouterEscapeHtml()}</p></main></body>
    </html>
    """.trimIndent()

private fun String.openRouterEscapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

private fun parseOpenRouterQuery(query: String): Map<String, String> =
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

private fun openRouterFormBody(vararg values: Pair<String, String>): String =
    values.joinToString("&") { (name, value) ->
        "${URLEncoder.encode(name, StandardCharsets.UTF_8)}=" +
            URLEncoder.encode(value, StandardCharsets.UTF_8)
    }

private const val OPENROUTER_LOGIN_TIMEOUT_MS = 5 * 60 * 1_000L
private const val OPENROUTER_TOKEN_TIMEOUT_MS = 30_000L
private const val JAVASCRIPT_MAX_SAFE_INTEGER = 9_007_199_254_740_991L
