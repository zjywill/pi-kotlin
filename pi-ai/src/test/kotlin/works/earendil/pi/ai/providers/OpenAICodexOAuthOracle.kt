package works.earendil.pi.ai.providers

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.OAuthCredential

fun main() =
    runBlocking {
        val requests = mutableListOf<OAuthHttpRequest>()
        val transport =
            OAuthHttpTransport { request ->
                requests += request
                when {
                    request.url.endsWith("/api/accounts/deviceauth/usercode") ->
                        OAuthHttpResponse(
                            200,
                            """{"device_auth_id":"device-auth-id","user_code":"ABCD-1234","interval":"5"}""",
                        )

                    request.url.endsWith("/api/accounts/deviceauth/token") ->
                        OAuthHttpResponse(
                            200,
                            """{"authorization_code":"device-code","code_verifier":"device-verifier"}""",
                        )

                    request.url.endsWith("/oauth/token") -> {
                        val form = parseForm(request.body)
                        when {
                            form["grant_type"] == "refresh_token" ->
                                OAuthHttpResponse(
                                    200,
                                    tokenResponse(
                                        accessToken("refresh-account"),
                                        "rotated-refresh",
                                        60,
                                    ),
                                )

                            form["code"] == "browser-code" ->
                                OAuthHttpResponse(
                                    200,
                                    tokenResponse(
                                        accessToken("browser-account"),
                                        "browser-refresh",
                                        3_600,
                                    ),
                                )

                            form["code"] == "device-code" ->
                                OAuthHttpResponse(
                                    200,
                                    tokenResponse(
                                        accessToken("device-account"),
                                        "device-refresh",
                                        3_600,
                                    ),
                                )

                            else -> error("Unexpected token request: ${request.body}")
                        }
                    }

                    else -> error("Unexpected OAuth request: ${request.url}")
                }
            }
        val now = 1_000L
        val oauth =
            OpenAICodexOAuth(
                transport = transport,
                now = { now },
                callbackServerFactory = { null },
            )

        var browserUrl = ""
        var browserPrompt: JsonObject? = null
        val browserCredential =
            oauth.login(
                object : AuthInteraction {
                    override suspend fun prompt(prompt: AuthPrompt): String =
                        when (prompt) {
                            is AuthPrompt.Select -> {
                                browserPrompt = promptProjection(prompt)
                                "browser"
                            }

                            is AuthPrompt.ManualCode -> {
                                val state = parseForm(URI.create(browserUrl).rawQuery).getValue("state")
                                "http://localhost:1455/auth/callback?code=browser-code&state=$state"
                            }

                            is AuthPrompt.Text -> error("Unexpected browser prompt")
                        }

                    override fun notify(event: AuthEvent) {
                        if (event is AuthEvent.AuthUrl) {
                            browserUrl = event.url
                        }
                    }
                },
            )

        var devicePrompt: JsonObject? = null
        var deviceEvent: JsonObject? = null
        val deviceCredential =
            oauth.login(
                object : AuthInteraction {
                    override suspend fun prompt(prompt: AuthPrompt): String {
                        require(prompt is AuthPrompt.Select)
                        devicePrompt = promptProjection(prompt)
                        return "device_code"
                    }

                    override fun notify(event: AuthEvent) {
                        if (event is AuthEvent.DeviceCode) {
                            deviceEvent =
                                buildJsonObject {
                                    put("userCode", event.userCode)
                                    put("verificationUri", event.verificationUri)
                                    event.intervalSeconds?.let { interval ->
                                        if (interval % 1.0 == 0.0) {
                                            put("intervalSeconds", interval.toInt())
                                        } else {
                                            put("intervalSeconds", interval)
                                        }
                                    }
                                    event.expiresInSeconds?.let { put("expiresInSeconds", it) }
                                }
                        }
                    }
                },
            )

        val refreshCredential =
            oauth.refresh(
                OAuthCredential(
                    access = "old-access",
                    refresh = "old-refresh",
                    expires = 0,
                ),
            )

        val authorization = parseForm(URI.create(browserUrl).rawQuery)
        val browserExchange =
            requests.first { parseForm(it.body)["code"] == "browser-code" }
        val browserForm = parseForm(browserExchange.body)
        val verifier = browserForm.getValue("code_verifier")
        val challenge =
            MessageDigest
                .getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.UTF_8))
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val userCodeRequest = requests.first { it.url.endsWith("/deviceauth/usercode") }
        val pollRequest = requests.first { it.url.endsWith("/deviceauth/token") }
        val deviceExchange =
            requests.first { parseForm(it.body)["code"] == "device-code" }
        val refreshRequest =
            requests.first { parseForm(it.body)["grant_type"] == "refresh_token" }

        val output =
            buildJsonObject {
                put(
                    "browser",
                    buildJsonObject {
                        put("prompt", requireNotNull(browserPrompt))
                        put(
                            "authorization",
                            buildJsonObject {
                                put("response_type", authorization.getValue("response_type"))
                                put("client_id", authorization.getValue("client_id"))
                                put("redirect_uri", authorization.getValue("redirect_uri"))
                                put("scope", authorization.getValue("scope"))
                                put("code_challenge_method", authorization.getValue("code_challenge_method"))
                                put("stateLength", authorization.getValue("state").length)
                                put("challengeLength", authorization.getValue("code_challenge").length)
                                put(
                                    "id_token_add_organizations",
                                    authorization.getValue("id_token_add_organizations"),
                                )
                                put(
                                    "codex_cli_simplified_flow",
                                    authorization.getValue("codex_cli_simplified_flow"),
                                )
                                put("originator", authorization.getValue("originator"))
                            },
                        )
                        put(
                            "exchange",
                            buildJsonObject {
                                put("grant_type", browserForm.getValue("grant_type"))
                                put("client_id", browserForm.getValue("client_id"))
                                put("code", browserForm.getValue("code"))
                                put("redirect_uri", browserForm.getValue("redirect_uri"))
                                put("verifierLength", verifier.length)
                                put("challengeMatches", challenge == authorization["code_challenge"])
                            },
                        )
                        put("credential", credentialProjection(browserCredential, now))
                    },
                )
                put(
                    "device",
                    buildJsonObject {
                        put("prompt", requireNotNull(devicePrompt))
                        put("event", requireNotNull(deviceEvent))
                        put("userCodeRequest", oracleJson.parseToJsonElement(userCodeRequest.body).jsonObject)
                        put("pollRequest", oracleJson.parseToJsonElement(pollRequest.body).jsonObject)
                        put("exchange", formProjection(deviceExchange))
                        put("credential", credentialProjection(deviceCredential, now))
                    },
                )
                put(
                    "refresh",
                    buildJsonObject {
                        put("request", formProjection(refreshRequest))
                        put("credential", credentialProjection(refreshCredential, now))
                        put(
                            "auth",
                            buildJsonObject {
                                put("apiKey", requireNotNull(oauth.toAuth(refreshCredential).apiKey))
                            },
                        )
                    },
                )
            }
        println(oracleJson.encodeToString(JsonObject.serializer(), output))
    }

private fun promptProjection(prompt: AuthPrompt.Select): JsonObject =
    buildJsonObject {
        put("message", prompt.message)
        put(
            "options",
            buildJsonArray {
                prompt.options.forEach { option ->
                    add(
                        buildJsonObject {
                            put("id", option.id)
                            put("label", option.label)
                        },
                    )
                }
            },
        )
    }

private fun credentialProjection(
    credential: OAuthCredential,
    start: Long,
): JsonObject =
    buildJsonObject {
        put("type", "oauth")
        put("access", credential.access)
        put("refresh", credential.refresh)
        put("expiresInSeconds", (credential.expires - start) / 1_000)
        credential.accountId?.let { put("accountId", it) }
    }

private fun formProjection(request: OAuthHttpRequest): JsonObject =
    buildJsonObject {
        parseForm(request.body).forEach { (name, value) ->
            put(name, value)
        }
    }

private fun tokenResponse(
    access: String,
    refresh: String,
    expiresIn: Int,
): String =
    oracleJson.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("access_token", access)
            put("refresh_token", refresh)
            put("expires_in", expiresIn)
        },
    )

private fun accessToken(accountId: String): String {
    val payload =
        oracleJson.encodeToString(
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
    val encoded =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    return "header.$encoded.signature"
}

private fun parseForm(value: String): Map<String, String> =
    value
        .split('&')
        .filter(String::isNotBlank)
        .associate { entry ->
            val separator = entry.indexOf('=')
            val name = if (separator < 0) entry else entry.substring(0, separator)
            val content = if (separator < 0) "" else entry.substring(separator + 1)
            decodeForm(name) to decodeForm(content)
        }

private fun decodeForm(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

private val oracleJson =
    Json {
        explicitNulls = false
    }
