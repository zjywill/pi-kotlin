package works.earendil.pi.ai.providers

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.OAuthCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RadiusOAuthTest {
    @Test
    fun `device flow discovers endpoints handles pending and slow down and returns skewed credentials`() =
        runTest {
            var now = 1_000_000L
            val sleeps = mutableListOf<Long>()
            var tokenPoll = 0
            val transport =
                RecordingTransport { request ->
                    when {
                        request.method == "GET" -> OAuthHttpResponse(200, oauthConfig())
                        request.url.endsWith("/device") ->
                            OAuthHttpResponse(
                                200,
                                """
                                {
                                  "device_code":"device-1",
                                  "user_code":"USER-CODE",
                                  "verification_uri":"https://verify.example/device",
                                  "verification_uri_complete":"https://verify.example/device?code=USER-CODE",
                                  "expires_in":120,
                                  "interval":0.25
                                }
                                """.trimIndent(),
                            )

                        request.url.endsWith("/token") -> {
                            tokenPoll++
                            when (tokenPoll) {
                                1 -> OAuthHttpResponse(400, """{"error":"authorization_pending"}""")
                                2 -> OAuthHttpResponse(400, """{"error":"slow_down"}""")
                                else ->
                                    OAuthHttpResponse(
                                        200,
                                        """{"access_token":"access","refresh_token":"refresh","expires_in":3600,"scope":"openid profile"}""",
                                    )
                            }
                        }

                        else -> error("Unexpected request: $request")
                    }
                }
            val interaction = RecordingInteraction("device-code")
            val oauth =
                RadiusOAuth(
                    transport = transport,
                    gateway = "radius.example/",
                    now = { now },
                    sleep = { millis ->
                        sleeps += millis
                        now += millis
                    },
                )

            val credential = oauth.login(interaction)

            assertEquals("access", credential.access)
            assertEquals("refresh", credential.refresh)
            assertEquals("openid profile", credential.scope)
            assertEquals(now + 3_600_000 - 60_000, credential.expires)
            assertEquals(listOf(1_000L, 6_000L), sleeps)
            val event = assertIs<AuthEvent.DeviceCode>(interaction.events.single())
            assertEquals("USER-CODE", event.userCode)
            assertEquals("https://verify.example/device", event.verificationUri)
            assertEquals(0.25, event.intervalSeconds)
            assertEquals(120, event.expiresInSeconds)

            assertEquals("https://radius.example/v1/oauth", transport.requests[0].url)
            assertEquals(
                mapOf("client_id" to "radius-client", "scope" to "openid profile"),
                decodeForm(transport.requests[1].body),
            )
            assertEquals(
                mapOf(
                    "grant_type" to "urn:radius:device",
                    "client_id" to "radius-client",
                    "device_code" to "device-1",
                ),
                decodeForm(transport.requests[2].body),
            )
        }

    @Test
    fun `browser flow emits PKCE authorization and exchanges callback code`() =
        runTest {
            val closed = AtomicBoolean()
            val callbackState = mutableListOf<String>()
            val transport =
                RecordingTransport { request ->
                    when {
                        request.method == "GET" -> OAuthHttpResponse(200, oauthConfig())
                        request.url.endsWith("/token") ->
                            OAuthHttpResponse(
                                200,
                                """{"access_token":"browser-access","refresh_token":"browser-refresh","expires_in":120}""",
                            )

                        else -> error("Unexpected request: $request")
                    }
                }
            val interaction = RecordingInteraction("browser")
            val oauth =
                RadiusOAuth(
                    transport = transport,
                    gateway = "https://radius.example",
                    now = { 10_000 },
                    random = SecureRandom(byteArrayOf(1, 2, 3, 4)),
                    stateFactory = { "fixed-state" },
                    callbackServerFactory = { state ->
                        callbackState += state
                        RadiusOAuthCallbackServer(
                            closeAction = { closed.set(true) },
                            code = CompletableDeferred("browser-code"),
                        )
                    },
                )

            val credential = oauth.login(interaction)

            assertEquals("browser-access", credential.access)
            assertEquals(listOf("fixed-state"), callbackState)
            assertTrue(closed.get())
            val progress = assertIs<AuthEvent.Progress>(interaction.events[0])
            assertTrue(progress.message.contains("127.0.0.1:1456/oauth/callback"))
            val authUrl = assertIs<AuthEvent.AuthUrl>(interaction.events[1]).url
            val query = decodeForm(requireNotNull(URI(authUrl).rawQuery))
            assertEquals("code", query["response_type"])
            assertEquals("radius-client", query["client_id"])
            assertEquals("http://127.0.0.1:1456/oauth/callback", query["redirect_uri"])
            assertEquals("S256", query["code_challenge_method"])
            assertEquals("url", query["handoff"])
            assertEquals("fixed-state", query["state"])

            val token = transport.requests.single { it.url.endsWith("/token") }
            val tokenForm = decodeForm(token.body)
            assertEquals("authorization_code", tokenForm["grant_type"])
            assertEquals("browser-code", tokenForm["code"])
            val verifier = requireNotNull(tokenForm["code_verifier"])
            val expectedChallenge =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(verifier.toByteArray(StandardCharsets.UTF_8))
                    .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            assertEquals(expectedChallenge, query["code_challenge"])
        }

    @Test
    fun `refresh rediscovers OAuth config and rotates tokens`() =
        runTest {
            val transport =
                RecordingTransport { request ->
                    if (request.method == "GET") {
                        OAuthHttpResponse(200, oauthConfig())
                    } else {
                        OAuthHttpResponse(
                            200,
                            """{"access_token":"new-access","refresh_token":"new-refresh","expires_in":600,"scope":"scope-2"}""",
                        )
                    }
                }
            val oauth =
                RadiusOAuth(
                    transport = transport,
                    gateway = "https://radius.example",
                    now = { 50_000 },
                )

            val refreshed =
                oauth.refresh(
                    OAuthCredential(
                        access = "old-access",
                        refresh = "old-refresh",
                        expires = 0,
                    ),
                )

            assertEquals("new-access", refreshed.access)
            assertEquals("new-refresh", refreshed.refresh)
            assertEquals("scope-2", refreshed.scope)
            assertEquals(590_000, refreshed.expires)
            assertEquals(
                mapOf(
                    "grant_type" to "refresh_token",
                    "client_id" to "radius-client",
                    "refresh_token" to "old-refresh",
                ),
                decodeForm(transport.requests.last().body),
            )
            assertEquals("new-access", oauth.toAuth(refreshed).apiKey)
        }

    @Test
    fun `device denial and OAuth failures preserve upstream details`() =
        runTest {
            val deniedTransport =
                RecordingTransport { request ->
                    when {
                        request.method == "GET" -> OAuthHttpResponse(200, oauthConfig())
                        request.url.endsWith("/device") ->
                            OAuthHttpResponse(
                                200,
                                """{"device_code":"device","user_code":"code","expires_in":60}""",
                            )

                        else -> OAuthHttpResponse(400, """{"error":"access_denied","error_description":"No access"}""")
                    }
                }
            val denied =
                RadiusOAuth(
                    transport = deniedTransport,
                    now = { 1_000 },
                    sleep = {},
                )

            assertEquals(
                "Device authorization was denied.",
                assertFailsWith<IllegalStateException> {
                    denied.login(RecordingInteraction("device-code"))
                }.message,
            )

            val failingConfig =
                RadiusOAuth(
                    transport =
                        RecordingTransport {
                            OAuthHttpResponse(503, "temporarily unavailable")
                        },
                )
            assertTrue(
                assertFailsWith<IllegalStateException> {
                    failingConfig.login(RecordingInteraction("browser"))
                }.message.orEmpty()
                    .contains("503 temporarily unavailable"),
            )
        }

    private class RecordingInteraction(
        private val answer: String,
    ) : AuthInteraction {
        val prompts = mutableListOf<AuthPrompt>()
        val events = mutableListOf<AuthEvent>()

        override suspend fun prompt(prompt: AuthPrompt): String {
            prompts += prompt
            return answer
        }

        override fun notify(event: AuthEvent) {
            events += event
        }
    }

    private class RecordingTransport(
        private val handler: suspend (OAuthHttpRequest) -> OAuthHttpResponse,
    ) : OAuthHttpTransport {
        val requests = mutableListOf<OAuthHttpRequest>()

        override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
            requests += request
            return handler(request)
        }
    }

    private fun oauthConfig(): String =
        """
        {
          "issuer":"https://issuer.example",
          "authorizationEndpoint":"https://oauth.example/authorize",
          "tokenEndpoint":"https://oauth.example/token",
          "deviceAuthorizationEndpoint":"https://oauth.example/device",
          "deviceAuthorizationEventsEndpoint":"https://oauth.example/events",
          "verificationEndpoint":"https://oauth.example/verify",
          "clientId":"radius-client",
          "scope":"openid profile",
          "deviceCodeGrantType":"urn:radius:device"
        }
        """.trimIndent()

    private fun decodeForm(body: String): Map<String, String> =
        body
            .split('&')
            .filter(String::isNotBlank)
            .associate { entry ->
                val separator = entry.indexOf('=')
                val name = if (separator >= 0) entry.substring(0, separator) else entry
                val value = if (separator >= 0) entry.substring(separator + 1) else ""
                URLDecoder.decode(name, StandardCharsets.UTF_8) to
                    URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
}
