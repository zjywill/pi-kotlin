package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.OAuthCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnthropicOAuthTest {
    @Test
    fun `manual login builds PKCE URL and exchanges JSON token request`() =
        runTest {
            val requests = mutableListOf<OAuthHttpRequest>()
            var authorizationUrl: String? = null
            val oauth =
                AnthropicOAuth(
                    transport =
                        OAuthHttpTransport { request ->
                            requests += request
                            OAuthHttpResponse(
                                200,
                                """
                                {
                                  "access_token":"sk-ant-oat-access",
                                  "refresh_token":"refresh-token",
                                  "expires_in":3600
                                }
                                """.trimIndent(),
                            )
                        },
                    now = { 1_000L },
                    callbackServerFactory = { null },
                )
            val events = mutableListOf<AuthEvent>()

            val credential =
                oauth.login(
                    object : AuthInteraction {
                        override suspend fun prompt(prompt: AuthPrompt): String {
                            assertTrue(prompt is AuthPrompt.ManualCode)
                            val url = URI.create(requireNotNull(authorizationUrl))
                            val query = parseQuery(url.rawQuery)
                            return "${query.getValue("redirect_uri")}" +
                                "?code=manual-code&state=${query.getValue("state")}"
                        }

                        override fun notify(event: AuthEvent) {
                            events += event
                            if (event is AuthEvent.AuthUrl) {
                                authorizationUrl = event.url
                            }
                        }
                    },
                )

            val authorization = parseQuery(URI.create(requireNotNull(authorizationUrl)).rawQuery)
            assertEquals("true", authorization["code"])
            assertEquals("9d1c250a-e61b-44d9-88ed-5944d1962f5e", authorization["client_id"])
            assertEquals("code", authorization["response_type"])
            assertEquals("http://localhost:53692/callback", authorization["redirect_uri"])
            assertEquals(
                "org:create_api_key user:profile user:inference user:sessions:claude_code " +
                    "user:mcp_servers user:file_upload",
                authorization["scope"],
            )
            assertEquals("S256", authorization["code_challenge_method"])
            assertEquals(43, authorization.getValue("state").length)
            val challenge =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(authorization.getValue("state").toByteArray(StandardCharsets.UTF_8))
                    .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
            assertEquals(challenge, authorization["code_challenge"])

            val request = requests.single()
            val body = providerJson.parseToJsonElement(request.body).jsonObject
            assertEquals("https://platform.claude.com/v1/oauth/token", request.url)
            assertEquals("POST", request.method)
            assertEquals("application/json", request.headers["Content-Type"])
            assertEquals("application/json", request.headers["Accept"])
            assertEquals(30_000L, request.timeoutMs)
            assertEquals("authorization_code", body.string("grant_type"))
            assertEquals("manual-code", body.string("code"))
            assertEquals(authorization["state"], body.string("state"))
            assertEquals(authorization["state"], body.string("code_verifier"))
            assertEquals("http://localhost:53692/callback", body.string("redirect_uri"))
            assertEquals(
                OAuthCredential(
                    access = "sk-ant-oat-access",
                    refresh = "refresh-token",
                    expires = 3_301_000L,
                ),
                credential,
            )
            assertEquals(
                AuthEvent.Progress("Exchanging authorization code for tokens..."),
                events.last(),
            )
            assertEquals("sk-ant-oat-access", oauth.toAuth(credential).apiKey)
        }

    @Test
    fun `callback result wins while the manual prompt remains pending`() =
        runTest {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val callback =
                AnthropicCallbackServer(
                    server,
                    CompletableDeferred("callback-code"),
                )
            var tokenBody: JsonObject? = null
            val oauth =
                AnthropicOAuth(
                    transport =
                        OAuthHttpTransport { request ->
                            tokenBody = providerJson.parseToJsonElement(request.body).jsonObject
                            OAuthHttpResponse(
                                200,
                                """{"access_token":"sk-ant-oat-callback","refresh_token":"refresh","expires_in":600}""",
                            )
                        },
                    now = { 1_000L },
                    callbackServerFactory = { callback },
                )

            val credential =
                oauth.login(
                    object : AuthInteraction {
                        override suspend fun prompt(prompt: AuthPrompt): String = awaitCancellation()

                        override fun notify(event: AuthEvent) = Unit
                    },
                )

            assertEquals("callback-code", assertNotNull(tokenBody).string("code"))
            assertEquals(assertNotNull(tokenBody).string("state"), assertNotNull(tokenBody).string("code_verifier"))
            assertEquals("sk-ant-oat-callback", credential.access)
            assertEquals(301_000L, credential.expires)
        }

    @Test
    fun `refresh rotates tokens and omits scope`() =
        runTest {
            var request: OAuthHttpRequest? = null
            val oauth =
                AnthropicOAuth(
                    transport =
                        OAuthHttpTransport { captured ->
                            request = captured
                            OAuthHttpResponse(
                                200,
                                """
                                {
                                  "access_token":"sk-ant-oat-new",
                                  "refresh_token":"new-refresh",
                                  "expires_in":3600,
                                  "scope":"ignored"
                                }
                                """.trimIndent(),
                            )
                        },
                    now = { 2_000L },
                    callbackServerFactory = { null },
                )

            val credential =
                oauth.refresh(
                    OAuthCredential(
                        access = "old",
                        refresh = "old-refresh",
                        expires = 0,
                    ),
                )

            val body =
                providerJson
                    .parseToJsonElement(assertNotNull(request).body)
                    .jsonObject
            assertEquals("refresh_token", body.string("grant_type"))
            assertEquals("old-refresh", body.string("refresh_token"))
            assertEquals("9d1c250a-e61b-44d9-88ed-5944d1962f5e", body.string("client_id"))
            assertFalse("scope" in body)
            assertEquals("sk-ant-oat-new", credential.access)
            assertEquals("new-refresh", credential.refresh)
            assertEquals(3_302_000L, credential.expires)
        }

    @Test
    fun `manual state mismatch fails before token exchange`() =
        runTest {
            val oauth =
                AnthropicOAuth(
                    transport = OAuthHttpTransport { error("must not exchange") },
                    callbackServerFactory = { null },
                )

            val error =
                assertFailsWith<IllegalStateException> {
                    oauth.login(
                        object : AuthInteraction {
                            override suspend fun prompt(prompt: AuthPrompt): String = "code#wrong-state"

                            override fun notify(event: AuthEvent) = Unit
                        },
                    )
                }

            assertEquals("OAuth state mismatch", error.message)
        }

    @Test
    fun `authorization input accepts URL hash form and raw code`() {
        assertEquals(
            AnthropicAuthorizationInput("url-code", "url-state"),
            parseAnthropicAuthorizationInput(
                "http://localhost:53692/callback?code=url-code&state=url-state",
            ),
        )
        assertEquals(
            AnthropicAuthorizationInput("hash-code", "hash-state"),
            parseAnthropicAuthorizationInput("hash-code#hash-state"),
        )
        assertEquals(
            AnthropicAuthorizationInput("form-code", "form-state"),
            parseAnthropicAuthorizationInput("code=form-code&state=form-state"),
        )
        assertEquals(
            AnthropicAuthorizationInput("raw-code", null),
            parseAnthropicAuthorizationInput(" raw-code "),
        )
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
