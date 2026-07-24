package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.OAuthCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAICodexOAuthTest {
    @Test
    fun `browser login builds PKCE URL parses manual redirect and exchanges code`() =
        runTest {
            val requests = mutableListOf<OAuthHttpRequest>()
            val transport =
                OAuthHttpTransport { request ->
                    requests += request
                    OAuthHttpResponse(
                        200,
                        tokenResponse(codexToken("browser-account"), "browser-refresh", 3_600),
                    )
                }
            var authorizationUrl: String? = null
            val oauth =
                OpenAICodexOAuth(
                    transport = transport,
                    now = { 1_000 },
                    callbackServerFactory = { null },
                )
            val credential =
                oauth.login(
                    object : AuthInteraction {
                        override suspend fun prompt(prompt: AuthPrompt): String =
                            when (prompt) {
                                is AuthPrompt.Select -> "browser"
                                is AuthPrompt.ManualCode -> {
                                    val state =
                                        query(URI.create(requireNotNull(authorizationUrl)).rawQuery)
                                            .getValue("state")
                                    "http://localhost:1455/auth/callback?code=browser-code&state=$state"
                                }

                                is AuthPrompt.Text -> error("Unexpected prompt")
                            }

                        override fun notify(event: AuthEvent) {
                            if (event is AuthEvent.AuthUrl) {
                                authorizationUrl = event.url
                            }
                        }
                    },
                )

            val authorization = query(URI.create(requireNotNull(authorizationUrl)).rawQuery)
            assertEquals("code", authorization["response_type"])
            assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", authorization["client_id"])
            assertEquals("http://localhost:1455/auth/callback", authorization["redirect_uri"])
            assertEquals("openid profile email offline_access", authorization["scope"])
            assertEquals("S256", authorization["code_challenge_method"])
            assertEquals("true", authorization["id_token_add_organizations"])
            assertEquals("true", authorization["codex_cli_simplified_flow"])
            assertEquals("pi", authorization["originator"])
            assertEquals(32, authorization.getValue("state").length)

            val exchange = requests.single()
            val form = query(exchange.body)
            assertEquals("https://auth.openai.com/oauth/token", exchange.url)
            assertEquals("authorization_code", form["grant_type"])
            assertEquals("browser-code", form["code"])
            assertEquals("http://localhost:1455/auth/callback", form["redirect_uri"])
            val verifier = form.getValue("code_verifier")
            val challenge =
                Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                        MessageDigest
                            .getInstance("SHA-256")
                            .digest(verifier.toByteArray(StandardCharsets.UTF_8)),
                    )
            assertEquals(authorization["code_challenge"], challenge)
            assertEquals(
                OAuthCredential(
                    access = codexToken("browser-account"),
                    refresh = "browser-refresh",
                    expires = 3_601_000,
                    accountId = "browser-account",
                ),
                credential,
            )
        }

    @Test
    fun `browser login rejects mismatched manual state before exchange`() =
        runTest {
            val oauth =
                OpenAICodexOAuth(
                    transport = OAuthHttpTransport { error("must not exchange") },
                    callbackServerFactory = { null },
                )

            val error =
                assertFailsWith<IllegalStateException> {
                    oauth.login(
                        object : AuthInteraction {
                            override suspend fun prompt(prompt: AuthPrompt): String =
                                when (prompt) {
                                    is AuthPrompt.Select -> "browser"
                                    is AuthPrompt.ManualCode -> "code#wrong-state"
                                    is AuthPrompt.Text -> error("Unexpected prompt")
                                }

                            override fun notify(event: AuthEvent) = Unit
                        },
                    )
                }

            assertEquals("State mismatch", error.message)
        }

    @Test
    fun `browser callback wins while manual prompt remains pending`() =
        runTest {
            val callbackHttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            callbackHttpServer.start()
            val callback =
                OpenAICodexCallbackServer(
                    callbackHttpServer,
                    CompletableDeferred("callback-code"),
                )
            var request: OAuthHttpRequest? = null
            val oauth =
                OpenAICodexOAuth(
                    transport =
                        OAuthHttpTransport { captured ->
                            request = captured
                            OAuthHttpResponse(
                                200,
                                tokenResponse(codexToken("callback-account"), "callback-refresh", 60),
                            )
                        },
                    now = { 1_000 },
                    callbackServerFactory = { callback },
                )

            val credential =
                oauth.login(
                    object : AuthInteraction {
                        override suspend fun prompt(prompt: AuthPrompt): String =
                            when (prompt) {
                                is AuthPrompt.Select -> "browser"
                                is AuthPrompt.ManualCode -> awaitCancellation()
                                is AuthPrompt.Text -> error("Unexpected prompt")
                            }

                        override fun notify(event: AuthEvent) = Unit
                    },
                )

            assertEquals("callback-code", query(assertNotNull(request).body)["code"])
            assertEquals("callback-account", credential.accountId)
        }

    @Test
    fun `device flow polls pending and slow down then exchanges verifier`() =
        runTest {
            var currentTime = 10_000L
            val sleeps = mutableListOf<Long>()
            val requests = mutableListOf<OAuthHttpRequest>()
            var devicePoll = 0
            val transport =
                OAuthHttpTransport { request ->
                    requests += request
                    when (request.url) {
                        "https://auth.openai.com/api/accounts/deviceauth/usercode" ->
                            OAuthHttpResponse(
                                200,
                                """{"device_auth_id":"device-id","user_code":"ABCD-1234","interval":"1"}""",
                            )

                        "https://auth.openai.com/api/accounts/deviceauth/token" ->
                            when (devicePoll++) {
                                0 -> OAuthHttpResponse(403, """{"error":"pending"}""")
                                1 -> OAuthHttpResponse(429, """{"error":{"code":"slow_down"}}""")
                                else ->
                                    OAuthHttpResponse(
                                        200,
                                        """{"authorization_code":"device-code","code_verifier":"device-verifier"}""",
                                    )
                            }

                        "https://auth.openai.com/oauth/token" ->
                            OAuthHttpResponse(
                                200,
                                tokenResponse(codexToken("device-account"), "device-refresh", 3_600),
                            )

                        else -> error("Unexpected URL: ${request.url}")
                    }
                }
            val events = mutableListOf<AuthEvent>()
            val oauth =
                OpenAICodexOAuth(
                    transport = transport,
                    now = { currentTime },
                    sleep = { milliseconds ->
                        sleeps += milliseconds
                        currentTime += milliseconds
                    },
                    callbackServerFactory = { null },
                )

            val credential =
                oauth.login(
                    object : AuthInteraction {
                        override suspend fun prompt(prompt: AuthPrompt): String {
                            assertTrue(prompt is AuthPrompt.Select)
                            return "device_code"
                        }

                        override fun notify(event: AuthEvent) {
                            events += event
                        }
                    },
                )

            assertEquals(
                AuthEvent.DeviceCode(
                    userCode = "ABCD-1234",
                    verificationUri = "https://auth.openai.com/codex/device",
                    intervalSeconds = 1.0,
                    expiresInSeconds = 900,
                ),
                events.single(),
            )
            assertEquals(listOf(1_000L, 6_000L), sleeps)
            assertEquals(3, requests.count { it.url.endsWith("/deviceauth/token") })
            assertEquals(
                mapOf(
                    "device_auth_id" to "device-id",
                    "user_code" to "ABCD-1234",
                ),
                jsonFields(requests.first { it.url.endsWith("/deviceauth/token") }.body),
            )
            val exchange = requests.last()
            val form = query(exchange.body)
            assertEquals("authorization_code", form["grant_type"])
            assertEquals("device-code", form["code"])
            assertEquals("device-verifier", form["code_verifier"])
            assertEquals("https://auth.openai.com/deviceauth/callback", form["redirect_uri"])
            assertEquals("device-account", credential.accountId)
            assertEquals(currentTime + 3_600_000, credential.expires)
        }

    @Test
    fun `refresh posts rotated token payload and derives request auth`() =
        runTest {
            var captured: OAuthHttpRequest? = null
            val oauth =
                OpenAICodexOAuth(
                    transport =
                        OAuthHttpTransport { request ->
                            captured = request
                            OAuthHttpResponse(
                                200,
                                tokenResponse(codexToken("refreshed-account"), "rotated-refresh", 60),
                            )
                        },
                    now = { 2_000 },
                    callbackServerFactory = { null },
                )

            val credential =
                oauth.refresh(
                    OAuthCredential(
                        access = "old-access",
                        refresh = "old-refresh",
                        expires = 0,
                    ),
                )

            assertEquals(
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to "old-refresh",
                    "client_id" to "app_EMoamEEZ73f0CkXaXp7hrann",
                ),
                query(assertNotNull(captured).body),
            )
            assertEquals("refreshed-account", credential.accountId)
            assertEquals("rotated-refresh", credential.refresh)
            assertEquals(62_000, credential.expires)
            assertEquals(credential.access, oauth.toAuth(credential).apiKey)
        }

    @Test
    fun `authorization input accepts URL hash form query and raw code`() {
        assertEquals(
            OpenAICodexAuthorizationInput("url-code", "url-state"),
            parseOpenAICodexAuthorizationInput(
                "http://localhost:1455/auth/callback?code=url-code&state=url-state",
            ),
        )
        assertEquals(
            OpenAICodexAuthorizationInput("hash-code", "hash-state"),
            parseOpenAICodexAuthorizationInput("hash-code#hash-state"),
        )
        assertEquals(
            OpenAICodexAuthorizationInput("form-code", "form-state"),
            parseOpenAICodexAuthorizationInput("code=form-code&state=form-state"),
        )
        assertEquals(
            OpenAICodexAuthorizationInput("raw-code", null),
            parseOpenAICodexAuthorizationInput(" raw-code "),
        )
        assertEquals("account-id", extractOpenAICodexOAuthAccountId(codexToken("account-id")))
        assertEquals(null, extractOpenAICodexOAuthAccountId("invalid"))
    }

    private fun tokenResponse(
        access: String,
        refresh: String,
        expiresIn: Int,
    ): String =
        oauthTestJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("access_token", access)
                put("refresh_token", refresh)
                put("expires_in", expiresIn)
            },
        )

    private fun codexToken(accountId: String): String {
        val payload =
            oauthTestJson.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put(
                        "https://api.openai.com/auth",
                        buildJsonObject {
                            put("chatgpt_account_id", accountId)
                        },
                    )
                },
            )
        return "header.${
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        }.signature"
    }

    private fun query(value: String): Map<String, String> =
        value
            .split('&')
            .filter(String::isNotBlank)
            .associate { entry ->
                val separator = entry.indexOf('=')
                val name = if (separator < 0) entry else entry.substring(0, separator)
                val content = if (separator < 0) "" else entry.substring(separator + 1)
                decode(name) to decode(content)
            }

    private fun jsonFields(value: String): Map<String, String> =
        oauthTestJson
            .parseToJsonElement(value)
            .jsonObject
            .mapValues { (_, element) -> element.toString().trim('"') }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8)
}

private val oauthTestJson = kotlinx.serialization.json.Json
