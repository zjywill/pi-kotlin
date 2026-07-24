package works.earendil.pi.ai.providers

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.OAuthCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenRouterOAuthTest {
    @Test
    fun `loopback login exchanges PKCE code for permanent API key`() =
        runBlocking {
            val requests = mutableListOf<OAuthHttpRequest>()
            val callbackResponse = AtomicReference<HttpResponse<String>>()
            val callbackThread = AtomicReference<Thread>()
            val events = mutableListOf<AuthEvent>()
            val oauth =
                OpenRouterOAuth(
                    transport =
                        OAuthHttpTransport { request ->
                            requests += request
                            OAuthHttpResponse(200, """{"key":"openrouter-oauth-key"}""")
                        },
                    callbackPath = { "/oauth/callback/test-flow" },
                )

            val credential =
                oauth.login(
                    object : AuthInteraction {
                        override suspend fun prompt(prompt: AuthPrompt): String =
                            error("OpenRouter login does not prompt for manual input")

                        override fun notify(event: AuthEvent) {
                            events += event
                            if (event is AuthEvent.AuthUrl) {
                                val callbackUrl =
                                    parseQuery(URI.create(event.url).rawQuery)
                                        .getValue("callback_url")
                                callbackThread.set(
                                    Thread {
                                        callbackResponse.set(
                                            HttpClient
                                                .newHttpClient()
                                                .send(
                                                    HttpRequest
                                                        .newBuilder(
                                                            URI.create("$callbackUrl?code=callback-code"),
                                                        ).GET()
                                                        .build(),
                                                    HttpResponse.BodyHandlers.ofString(),
                                                ),
                                        )
                                    }.also(Thread::start),
                                )
                            }
                        }
                    },
                )
            assertNotNull(callbackThread.get()).join()

            val progress = events[0] as AuthEvent.Progress
            val authUrl = (events[1] as AuthEvent.AuthUrl).url
            val authorization = URI.create(authUrl)
            val query = parseQuery(authorization.rawQuery)
            val callback = URI.create(query.getValue("callback_url"))
            assertEquals("https", authorization.scheme)
            assertEquals("openrouter.ai", authorization.host)
            assertEquals("/auth", authorization.path)
            assertEquals("127.0.0.1", callback.host)
            assertTrue(callback.port > 0)
            assertEquals("/oauth/callback/test-flow", callback.path)
            assertEquals("S256", query["code_challenge_method"])
            assertEquals("Listening for OpenRouter OAuth callback on $callback", progress.message)

            val exchange = requests.single()
            val body = providerJson.parseToJsonElement(exchange.body).jsonObject
            assertEquals("https://openrouter.ai/api/v1/auth/keys", exchange.url)
            assertEquals("POST", exchange.method)
            assertEquals("application/json", exchange.headers["Accept"])
            assertEquals("application/json", exchange.headers["Content-Type"])
            assertEquals(30_000L, exchange.timeoutMs)
            assertEquals("callback-code", body.string("code"))
            assertEquals("S256", body.string("code_challenge_method"))
            val verifier = assertNotNull(body.string("code_verifier"))
            val challenge =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(verifier.toByteArray(StandardCharsets.UTF_8))
                    .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            assertEquals(43, verifier.length)
            assertEquals(challenge, query["code_challenge"])
            assertEquals(
                OAuthCredential(
                    access = "openrouter-oauth-key",
                    refresh = "",
                    expires = 9_007_199_254_740_991L,
                ),
                credential,
            )
            assertEquals(credential, oauth.refresh(credential))
            assertEquals("openrouter-oauth-key", oauth.toAuth(credential).apiKey)

            val callbackResult = assertNotNull(callbackResponse.get())
            assertEquals(200, callbackResult.statusCode())
            assertEquals(
                "no-store",
                callbackResult.headers().firstValue("cache-control").orElse(null),
            )
            assertTrue(callbackResult.body().contains("Signed in to OpenRouter"))
        }

    @Test
    fun `authorization denial rejects login with provider description`() =
        runBlocking {
            val callbackThread = AtomicReference<Thread>()
            val oauth =
                OpenRouterOAuth(
                    transport = OAuthHttpTransport { error("must not exchange") },
                    callbackPath = { "/oauth/callback/denied" },
                )

            val error =
                assertFailsWith<IllegalStateException> {
                    oauth.login(
                        callbackInteraction(
                            callbackThread,
                            "error=access_denied&error_description=User%20declined",
                        ),
                    )
                }
            assertNotNull(callbackThread.get()).join()

            assertEquals("OpenRouter authorization failed: User declined", error.message)
        }

    @Test
    fun `exchange failure projects nested OpenRouter error detail`() =
        runBlocking {
            val callbackThread = AtomicReference<Thread>()
            val oauth =
                OpenRouterOAuth(
                    transport =
                        OAuthHttpTransport {
                            OAuthHttpResponse(
                                401,
                                """{"error":{"message":"Key creation is disabled"}}""",
                            )
                        },
                    callbackPath = { "/oauth/callback/exchange-failure" },
                )

            val error =
                assertFailsWith<IllegalStateException> {
                    oauth.login(callbackInteraction(callbackThread, "code=denied-code"))
                }
            assertNotNull(callbackThread.get()).join()

            assertEquals(
                "OpenRouter OAuth key exchange failed (HTTP 401): Key creation is disabled",
                error.message,
            )
        }

    @Test
    fun `login timeout closes the loopback server`() =
        runBlocking {
            val oauth =
                OpenRouterOAuth(
                    transport = OAuthHttpTransport { error("must not exchange") },
                    callbackPath = { "/oauth/callback/timeout" },
                    loginTimeoutMs = 1,
                )

            val error =
                assertFailsWith<IllegalStateException> {
                    oauth.login(
                        object : AuthInteraction {
                            override suspend fun prompt(prompt: AuthPrompt): String = error("Unexpected prompt")

                            override fun notify(event: AuthEvent) = Unit
                        },
                    )
                }

            assertEquals("OpenRouter OAuth login timed out", error.message)
        }
}

private fun callbackInteraction(
    thread: AtomicReference<Thread>,
    callbackQuery: String,
): AuthInteraction =
    object : AuthInteraction {
        override suspend fun prompt(prompt: AuthPrompt): String = error("Unexpected prompt")

        override fun notify(event: AuthEvent) {
            if (event is AuthEvent.AuthUrl) {
                val callbackUrl =
                    parseQuery(URI.create(event.url).rawQuery)
                        .getValue("callback_url")
                thread.set(
                    Thread {
                        runCatching {
                            HttpClient
                                .newHttpClient()
                                .send(
                                    HttpRequest
                                        .newBuilder(URI.create("$callbackUrl?$callbackQuery"))
                                        .GET()
                                        .build(),
                                    HttpResponse.BodyHandlers.discarding(),
                                )
                        }
                    }.also(Thread::start),
                )
            }
        }
    }

private fun parseQuery(query: String): Map<String, String> =
    query
        .split('&')
        .filter(String::isNotBlank)
        .associate { entry ->
            val parts = entry.split('=', limit = 2)
            java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
        }
