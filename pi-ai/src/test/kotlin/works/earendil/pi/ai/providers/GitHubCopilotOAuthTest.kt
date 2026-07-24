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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubCopilotOAuthTest {
    @Test
    fun `login polls after delay enables models and filters the account catalog`() =
        runTest {
            var currentTime = 1_000L
            val sleeps = mutableListOf<Long>()
            val requests = mutableListOf<OAuthHttpRequest>()
            var polls = 0
            val token = "tid=test;proxy-ep=proxy.individual.githubcopilot.com;exp=9999999999"
            val transport =
                OAuthHttpTransport { request ->
                    requests += request
                    when {
                        request.url == "https://github.com/login/device/code" ->
                            OAuthHttpResponse(
                                200,
                                """
                                {
                                  "device_code":"device-code",
                                  "user_code":"ABCD-EFGH",
                                  "verification_uri":"https://github.com/login/device",
                                  "interval":5,
                                  "expires_in":900
                                }
                                """.trimIndent(),
                            )

                        request.url == "https://github.com/login/oauth/access_token" ->
                            when (polls++) {
                                0 -> OAuthHttpResponse(200, """{"error":"authorization_pending"}""")
                                1 -> OAuthHttpResponse(200, """{"error":"slow_down","interval":7}""")
                                else -> OAuthHttpResponse(200, """{"access_token":"ghu-refresh"}""")
                            }

                        request.url == "https://api.github.com/copilot_internal/v2/token" ->
                            OAuthHttpResponse(
                                200,
                                """{"token":"$token","expires_at":9999999999}""",
                            )

                        request.url.endsWith("/policy") ->
                            OAuthHttpResponse(
                                if (request.url.contains("claude-opus-4.7")) 500 else 200,
                                "",
                            )

                        request.url == "https://api.individual.githubcopilot.com/models" ->
                            OAuthHttpResponse(
                                200,
                                """
                                {
                                  "data":[
                                    {
                                      "id":"gpt-4.1",
                                      "model_picker_enabled":true,
                                      "capabilities":{"supports":{"tool_calls":true}}
                                    },
                                    {
                                      "id":"claude-opus-4.7",
                                      "model_picker_enabled":true,
                                      "policy":{"state":"disabled"},
                                      "capabilities":{"supports":{"tool_calls":true}}
                                    },
                                    {
                                      "id":"gpt-5.4-nano",
                                      "model_picker_enabled":false,
                                      "capabilities":{"supports":{"tool_calls":true}}
                                    },
                                    {
                                      "id":"no-tools",
                                      "model_picker_enabled":true,
                                      "capabilities":{"supports":{"tool_calls":false}}
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            )

                        else -> error("Unexpected URL: ${request.url}")
                    }
                }
            val interaction = RecordingInteraction("")
            val oauth =
                GitHubCopilotOAuth(
                    transport = transport,
                    now = { currentTime },
                    sleep = { milliseconds ->
                        sleeps += milliseconds
                        currentTime += milliseconds
                    },
                    knownModelIds = listOf("gpt-4.1", "claude-opus-4.7"),
                )

            val credential = oauth.login(interaction)

            assertEquals(listOf(5_000L, 5_000L, 7_000L), sleeps)
            assertEquals(3, polls)
            assertEquals(
                AuthEvent.DeviceCode(
                    userCode = "ABCD-EFGH",
                    verificationUri = "https://github.com/login/device",
                    intervalSeconds = 5.0,
                    expiresInSeconds = 900,
                ),
                interaction.events.first(),
            )
            assertEquals(AuthEvent.Progress("Enabling models..."), interaction.events.last())
            assertEquals("ghu-refresh", credential.refresh)
            assertEquals(token, credential.access)
            assertEquals(9_999_999_699_000L, credential.expires)
            assertNull(credential.enterpriseUrl)
            assertEquals(listOf("gpt-4.1"), credential.availableModelIds)

            val deviceForm = parseForm(requests.first().body)
            assertEquals("Iv1.b507a08c87ecfe98", deviceForm["client_id"])
            assertEquals("read:user", deviceForm["scope"])
            val pollForm =
                parseForm(
                    requests.first { it.url.endsWith("/login/oauth/access_token") }.body,
                )
            assertEquals("device-code", pollForm["device_code"])
            assertEquals(
                "urn:ietf:params:oauth:grant-type:device_code",
                pollForm["grant_type"],
            )
            assertEquals(
                2,
                requests.count { it.url.endsWith("/policy") },
            )
            assertTrue(
                requests
                    .filter { it.url.endsWith("/policy") }
                    .all { it.body == """{"state":"enabled"}""" },
            )
            val modelsRequest = requests.single { it.url.endsWith("/models") }
            assertEquals("GET", modelsRequest.method)
            assertEquals(5_000L, modelsRequest.timeoutMs)
            assertEquals("Bearer $token", modelsRequest.headers["Authorization"])
        }

    @Test
    fun `slow down timeout stops at the device deadline`() =
        runTest {
            var currentTime = 0L
            val sleeps = mutableListOf<Long>()
            var polls = 0
            val oauth =
                GitHubCopilotOAuth(
                    transport =
                        OAuthHttpTransport { request ->
                            when {
                                request.url.endsWith("/login/device/code") ->
                                    OAuthHttpResponse(
                                        200,
                                        """
                                        {
                                          "device_code":"device-code",
                                          "user_code":"ABCD-EFGH",
                                          "verification_uri":"https://github.com/login/device",
                                          "interval":5,
                                          "expires_in":25
                                        }
                                        """.trimIndent(),
                                    )

                                request.url.endsWith("/login/oauth/access_token") -> {
                                    polls++
                                    OAuthHttpResponse(200, """{"error":"slow_down"}""")
                                }

                                else -> error("Unexpected URL: ${request.url}")
                            }
                        },
                    now = { currentTime },
                    sleep = { milliseconds ->
                        sleeps += milliseconds
                        currentTime += milliseconds
                    },
                    knownModelIds = emptyList(),
                )

            val error =
                assertFailsWith<IllegalStateException> {
                    oauth.login(RecordingInteraction(""))
                }

            assertContains(
                error.message.orEmpty(),
                "Device flow timed out after one or more slow_down responses",
            )
            assertEquals(listOf(5_000L, 10_000L, 10_000L), sleeps)
            assertEquals(2, polls)
        }

    @Test
    fun `verification uri is normalized and non-http schemes are rejected`() =
        runTest {
            val rawUri = "https://github.com/login/\u001B]8;;evil"
            val normalizedEvents = mutableListOf<AuthEvent>()
            val normalized =
                GitHubCopilotOAuth(
                    transport =
                        OAuthHttpTransport {
                            OAuthHttpResponse(
                                200,
                                """
                                {
                                  "device_code":"device-code",
                                  "user_code":"ABCD-EFGH",
                                  "verification_uri":"${rawUri.replace("\u001B", "\\u001b")}",
                                  "interval":1,
                                  "expires_in":0
                                }
                                """.trimIndent(),
                            )
                        },
                    now = { 0 },
                    knownModelIds = emptyList(),
                )
            assertFailsWith<IllegalStateException> {
                normalized.login(
                    object : AuthInteraction {
                        override suspend fun prompt(prompt: AuthPrompt): String = ""

                        override fun notify(event: AuthEvent) {
                            normalizedEvents += event
                        }
                    },
                )
            }
            val event = normalizedEvents.single() as AuthEvent.DeviceCode
            assertFalse(event.verificationUri.contains('\u001B'))
            assertContains(event.verificationUri.lowercase(), "%1b")

            val rejectedEvents = mutableListOf<AuthEvent>()
            val rejected =
                GitHubCopilotOAuth(
                    transport =
                        OAuthHttpTransport {
                            OAuthHttpResponse(
                                200,
                                """
                                {
                                  "device_code":"device-code",
                                  "user_code":"ABCD-EFGH",
                                  "verification_uri":"file:///tmp/copilot",
                                  "interval":1,
                                  "expires_in":900
                                }
                                """.trimIndent(),
                            )
                        },
                    knownModelIds = emptyList(),
                )
            val error =
                assertFailsWith<IllegalStateException> {
                    rejected.login(
                        object : AuthInteraction {
                            override suspend fun prompt(prompt: AuthPrompt): String = ""

                            override fun notify(event: AuthEvent) {
                                rejectedEvents += event
                            }
                        },
                    )
                }
            assertContains(error.message.orEmpty(), "Untrusted verification_uri")
            assertTrue(rejectedEvents.isEmpty())
        }

    @Test
    fun `refresh normalizes enterprise domain applies expiry skew and derives base url`() =
        runTest {
            val requests = mutableListOf<OAuthHttpRequest>()
            val proxyToken = "tid=test;proxy-ep=proxy.enterprise.githubcopilot.com;exp=900"
            val oauth =
                GitHubCopilotOAuth(
                    transport =
                        OAuthHttpTransport { request ->
                            requests += request
                            when {
                                request.url.endsWith("/copilot_internal/v2/token") ->
                                    OAuthHttpResponse(
                                        200,
                                        """{"token":"$proxyToken","expires_at":900}""",
                                    )

                                request.url.endsWith("/models") ->
                                    OAuthHttpResponse(200, """{"data":[]}""")

                                else -> error("Unexpected URL: ${request.url}")
                            }
                        },
                    knownModelIds = emptyList(),
                )

            val refreshed =
                oauth.refresh(
                    OAuthCredential(
                        access = "old",
                        refresh = "ghu-enterprise",
                        expires = 0,
                        enterpriseUrl = "https://company.ghe.com/some/path",
                    ),
                )

            assertEquals("company.ghe.com", refreshed.enterpriseUrl)
            assertEquals(600_000L, refreshed.expires)
            assertEquals(emptyList(), refreshed.availableModelIds)
            assertEquals(
                "https://api.company.ghe.com/copilot_internal/v2/token",
                requests.first().url,
            )
            assertEquals(
                "https://api.enterprise.githubcopilot.com",
                oauth.toAuth(refreshed).baseUrl,
            )
            assertEquals(
                "https://copilot-api.company.ghe.com",
                githubCopilotBaseUrl("token-without-proxy", "company.ghe.com"),
            )
        }

    @Test
    fun `domain endpoint and model policy helpers match the upstream contract`() {
        assertNull(normalizeGitHubDomain(" "))
        assertEquals("github.com", normalizeGitHubDomain("github.com"))
        assertEquals("company.ghe.com", normalizeGitHubDomain("https://company.ghe.com/path"))
        assertEquals("company.ghe.com", normalizeGitHubDomain("HTTPS://Company.GHE.com/path"))
        assertNull(normalizeGitHubDomain("https://"))
        assertEquals(
            GitHubCopilotUrls(
                deviceCodeUrl = "https://company.ghe.com/login/device/code",
                accessTokenUrl = "https://company.ghe.com/login/oauth/access_token",
                copilotTokenUrl = "https://api.company.ghe.com/copilot_internal/v2/token",
            ),
            githubCopilotUrls("company.ghe.com"),
        )
        assertEquals(
            listOf("selectable"),
            parseAvailableGitHubCopilotModelIds(
                providerJson.parseToJsonElement(
                    """
                    {
                      "data":[
                        {
                          "id":"selectable",
                          "model_picker_enabled":true,
                          "capabilities":{"supports":{"tool_calls":true}}
                        },
                        {
                          "id":"disabled",
                          "model_picker_enabled":true,
                          "policy":{"state":"disabled"},
                          "capabilities":{"supports":{"tool_calls":true}}
                        },
                        {
                          "id":"no-tools",
                          "model_picker_enabled":true,
                          "capabilities":{"supports":{"tool_calls":false}}
                        }
                      ]
                    }
                    """.trimIndent(),
                ).let { it as kotlinx.serialization.json.JsonObject },
            ),
        )
        assertFailsWith<IllegalStateException> {
            parseAvailableGitHubCopilotModelIds(
                providerJson.parseToJsonElement("""{"models":[]}""")
                    as kotlinx.serialization.json.JsonObject,
            )
        }
    }

    private class RecordingInteraction(
        private val answer: String,
    ) : AuthInteraction {
        val events = mutableListOf<AuthEvent>()

        override suspend fun prompt(prompt: AuthPrompt): String = answer

        override fun notify(event: AuthEvent) {
            events += event
        }
    }
}

private fun parseForm(value: String): Map<String, String> =
    value
        .split("&")
        .filter(String::isNotEmpty)
        .associate { field ->
            val (name, rawValue) = field.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            URLDecoder.decode(name, StandardCharsets.UTF_8) to
                URLDecoder.decode(rawValue, StandardCharsets.UTF_8)
        }
