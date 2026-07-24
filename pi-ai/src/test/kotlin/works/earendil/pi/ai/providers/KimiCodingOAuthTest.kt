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

class KimiCodingOAuthTest {
    @Test
    fun `device login waits before polling and persists complete token response`() =
        runTest {
            var currentTime = 1_000_000L
            val sleeps = mutableListOf<Long>()
            val requests = mutableListOf<OAuthHttpRequest>()
            var poll = 0
            val oauth =
                KimiCodingOAuth(
                    transport =
                        OAuthHttpTransport { request ->
                            requests += request
                            when {
                                request.url.endsWith("/device_authorization") ->
                                    OAuthHttpResponse(200, deviceResponse())

                                poll++ == 0 ->
                                    OAuthHttpResponse(
                                        400,
                                        """{"error":"authorization_pending"}""",
                                    )

                                else ->
                                    OAuthHttpResponse(
                                        200,
                                        tokenResponse(),
                                    )
                            }
                        },
                    now = { currentTime },
                    sleep = { milliseconds ->
                        sleeps += milliseconds
                        currentTime += milliseconds
                    },
                )
            val interaction = RecordingKimiInteraction()

            val credential = oauth.login(interaction)

            assertEquals(listOf(5_000L, 5_000L), sleeps)
            assertEquals(
                AuthEvent.DeviceCode(
                    userCode = "ABCD-1234",
                    verificationUri = "https://www.kimi.com/code?user_code=ABCD-1234",
                    intervalSeconds = 5.0,
                    expiresInSeconds = 600,
                ),
                interaction.events.single(),
            )
            assertEquals(
                OAuthCredential(
                    access = "access-token",
                    refresh = "refresh-token",
                    expires = currentTime + 3_600_000L,
                ),
                credential,
            )
            assertEquals(
                mapOf("client_id" to "17e5f671-d194-4dfb-9706-5516cb48c098"),
                parseKimiForm(requests.first().body),
            )
            assertEquals(
                mapOf(
                    "client_id" to "17e5f671-d194-4dfb-9706-5516cb48c098",
                    "device_code" to "device-code-123",
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
                parseKimiForm(requests.last().body),
            )
            assertEquals(30_000L, requests.first().timeoutMs)
            assertEquals("application/json", requests.first().headers["Accept"])
        }

    @Test
    fun `host override trims slashes and invalid timing fields use defaults`() =
        runTest {
            var currentTime = 0L
            val sleeps = mutableListOf<Long>()
            val urls = mutableListOf<String>()
            var requestCount = 0
            val oauth =
                KimiCodingOAuth(
                    transport =
                        OAuthHttpTransport { request ->
                            urls += request.url
                            if (requestCount++ == 0) {
                                OAuthHttpResponse(
                                    200,
                                    deviceResponse(
                                        """,
                                        "verification_uri":"http://localhost/code",
                                        "interval":0,
                                        "expires_in":0
                                        """,
                                    ),
                                )
                            } else {
                                OAuthHttpResponse(200, tokenResponse())
                            }
                        },
                    environment = { name ->
                        "https://auth.example.com///".takeIf { name == "KIMI_CODE_OAUTH_HOST" }
                    },
                    now = { currentTime },
                    sleep = { milliseconds ->
                        sleeps += milliseconds
                        currentTime += milliseconds
                    },
                )
            val interaction = RecordingKimiInteraction()

            oauth.login(interaction)

            assertEquals(listOf(5_000L), sleeps)
            assertEquals(
                listOf(
                    "https://auth.example.com/api/oauth/device_authorization",
                    "https://auth.example.com/api/oauth/token",
                ),
                urls,
            )
            assertEquals(5.0, (interaction.events.single() as AuthEvent.DeviceCode).intervalSeconds)
            assertEquals(900, (interaction.events.single() as AuthEvent.DeviceCode).expiresInSeconds)
        }

    @Test
    fun `device authorization rejects untrusted URLs and malformed responses`() =
        runTest {
            listOf(
                deviceResponse(""","verification_uri_complete":"file:///tmp/token""""),
                """{"device_code":"device","user_code":"code"}""",
                "not-json",
            ).forEach { response ->
                val oauth =
                    KimiCodingOAuth(
                        transport = OAuthHttpTransport { OAuthHttpResponse(200, response) },
                    )

                val error =
                    assertFailsWith<IllegalStateException> {
                        oauth.login(RecordingKimiInteraction())
                    }

                assertContains(error.message.orEmpty(), "Invalid Kimi Code device authorization response")
            }
        }

    @Test
    fun `device denial expiry and server errors surface stable failures`() =
        runTest {
            mapOf(
                "expired_token" to "Kimi Code device authorization expired. Please restart login.",
                "access_denied" to "Kimi Code login was denied.",
            ).forEach { (upstreamError, expected) ->
                var requestCount = 0
                var currentTime = 0L
                val oauth =
                    KimiCodingOAuth(
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

                assertEquals(
                    expected,
                    assertFailsWith<IllegalStateException> {
                        oauth.login(RecordingKimiInteraction())
                    }.message,
                )
            }

            var requestCount = 0
            var currentTime = 0L
            val serverError =
                KimiCodingOAuth(
                    transport =
                        OAuthHttpTransport {
                            if (requestCount++ == 0) {
                                OAuthHttpResponse(200, deviceResponse(""","interval":1"""))
                            } else {
                                OAuthHttpResponse(503, "upstream unavailable")
                            }
                        },
                    now = { currentTime },
                    sleep = { currentTime += it },
                )
            assertEquals(
                "Kimi Code device token request failed with status 503: upstream unavailable",
                assertFailsWith<IllegalStateException> {
                    serverError.login(RecordingKimiInteraction())
                }.message,
            )
        }

    @Test
    fun `refresh retries throttling and server failures with exponential backoff`() =
        runTest {
            var currentTime = 20_000L
            val sleeps = mutableListOf<Long>()
            val requests = mutableListOf<OAuthHttpRequest>()
            var attempt = 0
            val oauth =
                KimiCodingOAuth(
                    transport =
                        OAuthHttpTransport { request ->
                            requests += request
                            when (attempt++) {
                                0 ->
                                    OAuthHttpResponse(
                                        429,
                                        """{"error":"temporarily_unavailable"}""",
                                    )

                                1 ->
                                    OAuthHttpResponse(
                                        500,
                                        """{"error":"server_error"}""",
                                    )

                                else ->
                                    OAuthHttpResponse(
                                        200,
                                        tokenResponse(
                                            access = "new-access",
                                            refresh = "new-refresh",
                                            expiresIn = 60,
                                        ),
                                    )
                            }
                        },
                    now = { currentTime },
                    sleep = { milliseconds ->
                        sleeps += milliseconds
                        currentTime += milliseconds
                    },
                )

            val credential = oauth.refresh(oldCredential())

            assertEquals(listOf(1_000L, 2_000L), sleeps)
            assertEquals(3, requests.size)
            assertEquals(
                OAuthCredential(
                    access = "new-access",
                    refresh = "new-refresh",
                    expires = currentTime + 60_000L,
                ),
                credential,
            )
            assertEquals(
                mapOf(
                    "client_id" to "17e5f671-d194-4dfb-9706-5516cb48c098",
                    "grant_type" to "refresh_token",
                    "refresh_token" to "old-refresh",
                ),
                parseKimiForm(requests.first().body),
            )
            assertEquals(
                mapOf("Authorization" to "Bearer new-access"),
                oauth.toAuth(credential).headers,
            )
            assertNull(oauth.toAuth(credential).apiKey)
        }

    @Test
    fun `refresh unauthorized failures are not retried`() =
        runTest {
            listOf(
                OAuthHttpResponse(
                    401,
                    """{"error":"invalid_token","error_description":"expired"}""",
                ) to "Kimi Code token refresh unauthorized (status 401): expired",
                OAuthHttpResponse(
                    400,
                    """{"error":"invalid_grant"}""",
                ) to "Kimi Code token refresh unauthorized (status 400)",
            ).forEach { (response, expected) ->
                var attempts = 0
                val oauth =
                    KimiCodingOAuth(
                        transport =
                            OAuthHttpTransport {
                                attempts++
                                response
                            },
                        sleep = { error("must not retry unauthorized refresh") },
                    )

                assertEquals(
                    expected,
                    assertFailsWith<IllegalStateException> {
                        oauth.refresh(oldCredential())
                    }.message,
                )
                assertEquals(1, attempts)
            }
        }

    @Test
    fun `refresh retries transport failures four times and preserves the last error`() =
        runTest {
            val sleeps = mutableListOf<Long>()
            var attempts = 0
            val oauth =
                KimiCodingOAuth(
                    transport =
                        OAuthHttpTransport {
                            attempts++
                            throw IllegalArgumentException("network-$attempts")
                        },
                    sleep = { sleeps += it },
                )

            val error =
                assertFailsWith<IllegalArgumentException> {
                    oauth.refresh(oldCredential())
                }

            assertEquals("network-4", error.message)
            assertEquals(4, attempts)
            assertEquals(listOf(1_000L, 2_000L, 4_000L), sleeps)
        }

    @Test
    fun `device and token response failures retain upstream details`() =
        runTest {
            val deviceFailure =
                KimiCodingOAuth(
                    transport =
                        OAuthHttpTransport {
                            OAuthHttpResponse(400, "invalid client")
                        },
                )
            assertEquals(
                "Kimi Code device authorization failed with status 400: invalid client",
                assertFailsWith<IllegalStateException> {
                    deviceFailure.login(RecordingKimiInteraction())
                }.message,
            )

            val missingToken =
                KimiCodingOAuth(
                    transport =
                        OAuthHttpTransport {
                            OAuthHttpResponse(200, """{"access_token":"only-access"}""")
                        },
                )
            assertEquals(
                "Kimi Code token refresh response missing fields: {\"access_token\":\"only-access\"}",
                assertFailsWith<IllegalStateException> {
                    missingToken.refresh(oldCredential())
                }.message,
            )

            val rejectedRefresh =
                KimiCodingOAuth(
                    transport =
                        OAuthHttpTransport {
                            OAuthHttpResponse(400, """{"error":"bad_request"}""")
                        },
                )
            assertEquals(
                "Kimi Code token refresh failed with status 400: {\"error\":\"bad_request\"}",
                assertFailsWith<IllegalStateException> {
                    rejectedRefresh.refresh(oldCredential())
                }.message,
            )
        }

    private class RecordingKimiInteraction : AuthInteraction {
        val events = mutableListOf<AuthEvent>()

        override suspend fun prompt(prompt: AuthPrompt): String =
            error("Unexpected prompt")

        override fun notify(event: AuthEvent) {
            events += event
        }
    }

    private fun oldCredential(): OAuthCredential =
        OAuthCredential(
            access = "old-access",
            refresh = "old-refresh",
            expires = 0,
        )

    private fun deviceResponse(extraFields: String = ""): String =
        """
        {
          "user_code":"ABCD-1234",
          "device_code":"device-code-123",
          "verification_uri":"https://www.kimi.com/code",
          "verification_uri_complete":"https://www.kimi.com/code?user_code=ABCD-1234",
          "interval":5,
          "expires_in":600
          $extraFields
        }
        """.trimIndent()

    private fun tokenResponse(
        access: String = "access-token",
        refresh: String = "refresh-token",
        expiresIn: Int = 3_600,
    ): String =
        """
        {
          "access_token":"$access",
          "refresh_token":"$refresh",
          "expires_in":$expiresIn
        }
        """.trimIndent()
}

private fun parseKimiForm(value: String): Map<String, String> =
    value
        .split("&")
        .filter(String::isNotEmpty)
        .associate { field ->
            val parts = field.split("=", limit = 2)
            URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
        }
