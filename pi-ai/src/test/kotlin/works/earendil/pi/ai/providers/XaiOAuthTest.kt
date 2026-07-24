package works.earendil.pi.ai.providers

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.OAuthCredential
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class XaiOAuthTest {
    @Test
    fun `device login waits before polling and handles pending and slow down`() =
        runTest {
            var currentTime = 1_000_000L
            val sleeps = mutableListOf<Long>()
            val requests = mutableListOf<OAuthHttpRequest>()
            var poll = 0
            val oauth =
                XaiOAuth(
                    transport =
                        OAuthHttpTransport { request ->
                            requests += request
                            when (request.url) {
                                "https://auth.x.ai/oauth2/device/code" ->
                                    OAuthHttpResponse(
                                        200,
                                        deviceResponse(),
                                    )

                                "https://auth.x.ai/oauth2/token" ->
                                    when (poll++) {
                                        0 -> OAuthHttpResponse(400, """{"error":"authorization_pending"}""")
                                        1 -> OAuthHttpResponse(400, """{"error":"slow_down","interval":10}""")
                                        else ->
                                            OAuthHttpResponse(
                                                200,
                                                tokenResponse(),
                                            )
                                    }

                                else -> error("Unexpected URL: ${request.url}")
                            }
                        },
                    now = { currentTime },
                    sleep = { milliseconds ->
                        sleeps += milliseconds
                        currentTime += milliseconds
                    },
                )
            val interaction = RecordingXaiInteraction()

            val credential = oauth.login(interaction)

            assertEquals(listOf(5_000L, 5_000L, 10_000L), sleeps)
            assertEquals(3, poll)
            assertEquals(
                AuthEvent.DeviceCode(
                    userCode = "ABCD-1234",
                    verificationUri = "https://accounts.x.ai/oauth2/device",
                    intervalSeconds = 5.0,
                    expiresInSeconds = 900,
                ),
                interaction.events.single(),
            )
            assertEquals(
                OAuthCredential(
                    access = "access-token",
                    refresh = "refresh-token",
                    expires = currentTime + 21_600_000L - 300_000L,
                ),
                credential,
            )

            val deviceRequest = requests.first()
            assertEquals("application/json", deviceRequest.headers["Accept"])
            assertEquals(
                "application/x-www-form-urlencoded",
                deviceRequest.headers["Content-Type"],
            )
            assertEquals(
                mapOf(
                    "client_id" to "b1a00492-073a-47ea-816f-4c329264a828",
                    "scope" to "openid profile email offline_access grok-cli:access api:access",
                    "referrer" to "pi",
                ),
                parseXaiForm(deviceRequest.body),
            )
            assertEquals(
                mapOf(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to "b1a00492-073a-47ea-816f-4c329264a828",
                    "device_code" to "device-code",
                ),
                parseXaiForm(requests.first { it.url.endsWith("/token") }.body),
            )
        }

    @Test
    fun `interval zero uses RFC default and complete verification URL is preferred`() =
        runTest {
            var currentTime = 0L
            val sleeps = mutableListOf<Long>()
            var requestCount = 0
            val oauth =
                XaiOAuth(
                    transport =
                        OAuthHttpTransport {
                            if (requestCount++ == 0) {
                                OAuthHttpResponse(
                                    200,
                                    deviceResponse(
                                        """,
                                        "interval":0,
                                        "verification_uri_complete":"https://accounts.x.ai/oauth2/device?user_code=ABCD-1234"
                                        """,
                                    ),
                                )
                            } else {
                                OAuthHttpResponse(200, tokenResponse())
                            }
                        },
                    now = { currentTime },
                    sleep = { milliseconds ->
                        sleeps += milliseconds
                        currentTime += milliseconds
                    },
                )
            val interaction = RecordingXaiInteraction()

            oauth.login(interaction)

            assertEquals(listOf(5_000L), sleeps)
            assertEquals(
                AuthEvent.DeviceCode(
                    userCode = "ABCD-1234",
                    verificationUri = "https://accounts.x.ai/oauth2/device?user_code=ABCD-1234",
                    intervalSeconds = null,
                    expiresInSeconds = 900,
                ),
                interaction.events.single(),
            )
        }

    @Test
    fun `subsecond polling clamps to one second and slow down adds five seconds`() =
        runTest {
            var currentTime = 0L
            val sleeps = mutableListOf<Long>()
            var requestCount = 0
            val oauth =
                XaiOAuth(
                    transport =
                        OAuthHttpTransport {
                            when (requestCount++) {
                                0 ->
                                    OAuthHttpResponse(
                                        200,
                                        deviceResponse(""","interval":0.25"""),
                                    )

                                1 ->
                                    OAuthHttpResponse(
                                        400,
                                        """{"error":"slow_down"}""",
                                    )

                                else -> OAuthHttpResponse(200, tokenResponse())
                            }
                        },
                    now = { currentTime },
                    sleep = { milliseconds ->
                        sleeps += milliseconds
                        currentTime += milliseconds
                    },
                )

            oauth.login(RecordingXaiInteraction())

            assertEquals(listOf(1_000L, 6_000L), sleeps)
        }

    @Test
    fun `verification URLs must use https`() =
        runTest {
            listOf(
                "http://accounts.x.ai/oauth2/device",
                "file:///etc/passwd",
                "not a url",
            ).forEach { verificationUri ->
                val oauth =
                    XaiOAuth(
                        transport =
                            OAuthHttpTransport {
                                OAuthHttpResponse(
                                    200,
                                    deviceResponse(
                                        """,
                                        "verification_uri":"$verificationUri"
                                        """,
                                    ),
                                )
                            },
                    )

                val error =
                    assertFailsWith<IllegalStateException> {
                        oauth.login(RecordingXaiInteraction())
                    }

                assertContains(error.message.orEmpty(), "Untrusted verification URI")
            }

            val complete =
                XaiOAuth(
                    transport =
                        OAuthHttpTransport {
                            OAuthHttpResponse(
                                200,
                                deviceResponse(
                                    """,
                                    "verification_uri_complete":"http://accounts.x.ai/oauth2/device"
                                    """,
                                ),
                            )
                        },
                )
            assertFailsWith<IllegalStateException> {
                complete.login(RecordingXaiInteraction())
            }
        }

    @Test
    fun `device denial and expiry surface stable failures`() =
        runTest {
            mapOf(
                "access_denied" to "xAI device authorization was denied",
                "authorization_denied" to "xAI device authorization was denied",
                "expired_token" to "xAI device code expired",
            ).forEach { (upstreamError, expected) ->
                var requestCount = 0
                var currentTime = 0L
                val oauth =
                    XaiOAuth(
                        transport =
                            OAuthHttpTransport {
                                if (requestCount++ == 0) {
                                    OAuthHttpResponse(
                                        200,
                                        deviceResponse(""","interval":1"""),
                                    )
                                } else {
                                    OAuthHttpResponse(400, """{"error":"$upstreamError"}""")
                                }
                            },
                        now = { currentTime },
                        sleep = { currentTime += it },
                    )

                val error =
                    assertFailsWith<IllegalStateException> {
                        oauth.login(RecordingXaiInteraction())
                    }

                assertEquals(expected, error.message)
            }
        }

    @Test
    fun `refresh rotates or preserves refresh token and defaults token lifetime`() =
        runTest {
            var currentTime = 20_000L
            val requests = mutableListOf<OAuthHttpRequest>()
            val replies =
                ArrayDeque(
                    listOf(
                        """
                        {
                          "access_token":"new-access",
                          "refresh_token":"new-refresh",
                          "expires_in":21600
                        }
                        """.trimIndent(),
                        """
                        {
                          "access_token":"newer-access",
                          "expires_in":21600
                        }
                        """.trimIndent(),
                        """
                        {
                          "access_token":"default-life",
                          "refresh_token":"last-refresh"
                        }
                        """.trimIndent(),
                    ),
                )
            val oauth =
                XaiOAuth(
                    transport =
                        OAuthHttpTransport { request ->
                            requests += request
                            OAuthHttpResponse(200, replies.removeFirst())
                        },
                    now = { currentTime },
                )

            val rotated = oauth.refresh(oldCredential("old-refresh"))
            currentTime = 30_000L
            val preserved = oauth.refresh(oldCredential("keep-refresh"))
            currentTime = 40_000L
            val defaultLifetime = oauth.refresh(oldCredential("last-refresh"))

            assertEquals("new-refresh", rotated.refresh)
            assertEquals("new-access", rotated.access)
            assertEquals("keep-refresh", preserved.refresh)
            assertEquals("newer-access", preserved.access)
            assertEquals(40_000L + 3_600_000L - 300_000L, defaultLifetime.expires)
            assertEquals("default-life", oauth.toAuth(defaultLifetime).apiKey)
            assertNull(oauth.toAuth(defaultLifetime).baseUrl)
            assertEquals(
                listOf("old-refresh", "keep-refresh", "last-refresh"),
                requests.map { parseXaiForm(it.body).getValue("refresh_token") },
            )
        }

    @Test
    fun `invalid fields JSON and upstream details are rejected`() =
        runTest {
            suspend fun refreshWith(response: OAuthHttpResponse): IllegalStateException {
                val oauth =
                    XaiOAuth(
                        transport = OAuthHttpTransport { response },
                    )
                return assertFailsWith {
                    oauth.refresh(oldCredential("old-refresh"))
                }
            }

            assertEquals(
                "Invalid xAI OAuth response field: access_token",
                refreshWith(
                    OAuthHttpResponse(
                        200,
                        """{"refresh_token":"refresh-token","expires_in":3600}""",
                    ),
                ).message,
            )
            assertEquals(
                "xAI OAuth returned invalid JSON (HTTP 502)",
                refreshWith(OAuthHttpResponse(502, "not-json")).message,
            )
            assertEquals(
                "xAI OAuth token refresh failed (HTTP 400): invalid_grant: refresh token revoked",
                refreshWith(
                    OAuthHttpResponse(
                        400,
                        """{"error":"invalid_grant","error_description":"refresh token revoked"}""",
                    ),
                ).message,
            )
        }

    private class RecordingXaiInteraction : AuthInteraction {
        val events = mutableListOf<AuthEvent>()

        override suspend fun prompt(prompt: AuthPrompt): String =
            error("Unexpected prompt")

        override fun notify(event: AuthEvent) {
            events += event
        }
    }

    private fun oldCredential(refresh: String): OAuthCredential =
        OAuthCredential(
            access = "old-access",
            refresh = refresh,
            expires = 0,
        )

    private fun deviceResponse(extraFields: String = ""): String =
        """
        {
          "device_code":"device-code",
          "user_code":"ABCD-1234",
          "verification_uri":"https://accounts.x.ai/oauth2/device",
          "expires_in":900,
          "interval":5
          $extraFields
        }
        """.trimIndent()

    private fun tokenResponse(extraFields: String = ""): String =
        """
        {
          "access_token":"access-token",
          "refresh_token":"refresh-token",
          "expires_in":21600
          $extraFields
        }
        """.trimIndent()
}

private fun parseXaiForm(value: String): Map<String, String> =
    value
        .split("&")
        .filter(String::isNotEmpty)
        .associate { field ->
            val parts = field.split("=", limit = 2)
            URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
        }
