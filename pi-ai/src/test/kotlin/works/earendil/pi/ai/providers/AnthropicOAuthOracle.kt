package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.UserMessage

fun main() =
    runBlocking {
        val oauthRequests = mutableListOf<OAuthHttpRequest>()
        val oauth =
            AnthropicOAuth(
                transport =
                    OAuthHttpTransport { request ->
                        oauthRequests += request
                        val body = providerJson.parseToJsonElement(request.body).jsonObject
                        val refresh = body.string("grant_type") == "refresh_token"
                        OAuthHttpResponse(
                            200,
                            if (refresh) {
                                """
                                {
                                  "access_token":"sk-ant-oat-refreshed",
                                  "refresh_token":"refresh-rotated",
                                  "expires_in":7200
                                }
                                """.trimIndent()
                            } else {
                                """
                                {
                                  "access_token":"sk-ant-oat-login",
                                  "refresh_token":"refresh-login",
                                  "expires_in":3600
                                }
                                """.trimIndent()
                            },
                        )
                    },
                now = { 1_000L },
                callbackServerFactory = { null },
            )
        var authorizationUrl = ""
        val loginEvents = mutableListOf<JsonObject>()
        val loginCredential =
            oauth.login(
                object : AuthInteraction {
                    override suspend fun prompt(prompt: AuthPrompt): String {
                        require(prompt is AuthPrompt.ManualCode)
                        val auth = URI.create(authorizationUrl)
                        val query = parseOracleQuery(auth.rawQuery)
                        return "${query.getValue("redirect_uri")}" +
                            "?code=manual-code&state=${query.getValue("state")}"
                    }

                    override fun notify(event: AuthEvent) {
                        when (event) {
                            is AuthEvent.AuthUrl -> authorizationUrl = event.url
                            is AuthEvent.Progress ->
                                loginEvents +=
                                    buildJsonObject {
                                        put("type", "progress")
                                        put("message", event.message)
                                    }

                            else -> Unit
                        }
                    }
                },
            )
        val refreshCredential = oauth.refresh(loginCredential)

        val fixture = createProviderFixture()
        val model =
            model(
                id = "claude-test",
                api = "anthropic-messages",
                provider = "anthropic",
                baseUrl = fixture.baseUrl,
            )
        val provider =
            AnthropicProvider(
                id = "anthropic",
                name = "Anthropic",
                baseUrl = fixture.baseUrl,
                models = listOf(model),
                apiKeyEnvNames = listOf("UNUSED"),
            )
        val context =
            Context(
                systemPrompt = "Project instructions",
                messages =
                    mutableListOf(
                        UserMessage("Run the tool", 1),
                        AssistantMessage(
                            content =
                                listOf(
                                    ToolCall(
                                        id = "prior-call",
                                        name = "bash",
                                        arguments = JsonObject(emptyMap()),
                                    ),
                                ),
                            api = "anthropic-messages",
                            provider = "anthropic",
                            model = "claude-test",
                            timestamp = 2,
                        ),
                        ToolResultMessage(
                            toolCallId = "prior-call",
                            toolName = "bash",
                            content = listOf(TextContent("done")),
                            isError = false,
                            timestamp = 3,
                        ),
                        UserMessage("Read the file", 4),
                    ),
                tools =
                    listOf(
                        ToolDefinition(
                            name = "read",
                            description = "Read a file",
                            parameters =
                                buildJsonObject {
                                    put("type", "object")
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put(
                                                "path",
                                                buildJsonObject { put("type", "string") },
                                            )
                                        },
                                    )
                                },
                        ),
                        ToolDefinition(
                            name = "echo",
                            description = "Echo",
                            parameters = buildJsonObject { put("type", "object") },
                        ),
                    ),
            )
        val result =
            provider
                .stream(
                    model,
                    context,
                    StreamOptions(
                        apiKey = "prefix-sk-ant-oat-session-token",
                        cacheRetention = CacheRetention.NONE,
                    ),
                ).result()
        val bearerResult =
            Models(
                providers = listOf(provider),
                environment = { name ->
                    if (name == "ANTHROPIC_AUTH_TOKEN") "gateway-token" else null
                },
            )
                .stream(
                    model,
                    context,
                    StreamOptions(
                        cacheRetention = CacheRetention.NONE,
                    ),
                ).result()
        fixture.close()

        val auth = URI.create(authorizationUrl)
        val authorization = parseOracleQuery(auth.rawQuery)
        val verifier = authorization.getValue("state")
        val challenge =
            MessageDigest
                .getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.UTF_8))
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val exchange =
            oauthRequests.first {
                providerJson.parseToJsonElement(it.body).jsonObject.string("grant_type") ==
                    "authorization_code"
            }
        val exchangeBody = providerJson.parseToJsonElement(exchange.body).jsonObject
        val refresh =
            oauthRequests.first {
                providerJson.parseToJsonElement(it.body).jsonObject.string("grant_type") ==
                    "refresh_token"
            }
        val refreshBody = providerJson.parseToJsonElement(refresh.body).jsonObject
        val request = fixture.requests[0]
        val bearerRequest = fixture.requests[1]
        val output =
            buildJsonObject {
                put(
                    "login",
                    buildJsonObject {
                        put(
                            "authorization",
                            buildJsonObject {
                                put("code", authorization.getValue("code"))
                                put("client_id", authorization.getValue("client_id"))
                                put("response_type", authorization.getValue("response_type"))
                                put("redirect_uri", authorization.getValue("redirect_uri"))
                                put("scope", authorization.getValue("scope"))
                                put(
                                    "code_challenge_method",
                                    authorization.getValue("code_challenge_method"),
                                )
                                put("stateLength", verifier.length)
                                put(
                                    "challengeLength",
                                    authorization.getValue("code_challenge").length,
                                )
                                put(
                                    "challengeMatches",
                                    challenge == authorization["code_challenge"],
                                )
                            },
                        )
                        put("events", JsonArray(loginEvents))
                        put(
                            "request",
                            buildJsonObject {
                                put("url", exchange.url)
                                put("method", exchange.method)
                                put("contentType", exchange.header("content-type"))
                                put("accept", exchange.header("accept"))
                                put("grant_type", exchangeBody.string("grant_type"))
                                put("client_id", exchangeBody.string("client_id"))
                                put("code", exchangeBody.string("code"))
                                put("redirect_uri", exchangeBody.string("redirect_uri"))
                                put(
                                    "stateMatchesVerifier",
                                    exchangeBody.string("state") == exchangeBody.string("code_verifier"),
                                )
                                put(
                                    "verifierLength",
                                    exchangeBody.string("code_verifier").orEmpty().length,
                                )
                            },
                        )
                        put("credential", credentialProjection(loginCredential, 3_300))
                    },
                )
                put(
                    "refresh",
                    buildJsonObject {
                        put(
                            "request",
                            buildJsonObject {
                                put("grant_type", refreshBody.string("grant_type"))
                                put("client_id", refreshBody.string("client_id"))
                                put("refresh_token", refreshBody.string("refresh_token"))
                                put("hasScope", "scope" in refreshBody)
                            },
                        )
                        put("credential", credentialProjection(refreshCredential, 6_900))
                    },
                )
                put(
                    "provider",
                    buildJsonObject {
                        put("path", request.path)
                        put(
                            "headers",
                            buildJsonObject {
                                put("authorization", request.header("authorization"))
                                putNullable("xApiKey", request.headerOrNull("x-api-key"))
                                put("accept", request.header("accept"))
                                put("contentType", request.header("content-type"))
                                put("anthropicVersion", request.header("anthropic-version"))
                                put("anthropicBeta", request.header("anthropic-beta"))
                                put("userAgent", request.header("user-agent"))
                                put("xApp", request.header("x-app"))
                                put(
                                    "dangerous",
                                    request.header("anthropic-dangerous-direct-browser-access"),
                                )
                            },
                        )
                        put("body", request.body)
                        put(
                            "result",
                            buildJsonObject {
                                put("stopReason", "toolUse")
                                put(
                                    "toolName",
                                    result.content.filterIsInstance<ToolCall>().single().name,
                                )
                            },
                        )
                    },
                )
                put(
                    "bearer",
                    buildJsonObject {
                        put(
                            "headers",
                            buildJsonObject {
                                put("authorization", bearerRequest.header("authorization"))
                                putNullable("xApiKey", bearerRequest.headerOrNull("x-api-key"))
                                bearerRequest.headerOrNull("anthropic-beta")?.let {
                                    put("anthropicBeta", it)
                                }
                                putNullable("xApp", bearerRequest.headerOrNull("x-app"))
                            },
                        )
                        put("body", bearerRequest.body)
                        put(
                            "result",
                            buildJsonObject {
                                put("stopReason", "toolUse")
                                put(
                                    "toolName",
                                    bearerResult.content.filterIsInstance<ToolCall>().single().name,
                                )
                            },
                        )
                    },
                )
            }
        println(oracleJson.encodeToString(JsonObject.serializer(), output))
    }

private fun credentialProjection(
    credential: OAuthCredential,
    expiresInSeconds: Int,
): JsonObject =
    buildJsonObject {
        put("access", credential.access)
        put("refresh", credential.refresh)
        put("expiresInSeconds", expiresInSeconds)
    }

private fun createProviderFixture(): AnthropicOracleFixture {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val fixture = AnthropicOracleFixture(server)
    server.createContext("/") { exchange ->
        val body =
            providerJson
                .parseToJsonElement(
                    exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8),
                ).jsonObject
        val headers =
            exchange.requestHeaders.entries.associate { (name, values) ->
                name.lowercase() to values.joinToString(",")
            }
        fixture.requests +=
            AnthropicOracleRequest(
                path = exchange.requestURI.path,
                headers = headers,
                body = body,
            )
        val response =
            """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":1,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-1","name":"Read","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"README.md\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":1}}

            event: message_stop
            data: {"type":"message_stop"}

            """.trimIndent()
        val bytes = response.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("content-type", "text/event-stream")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    return fixture
}

private data class AnthropicOracleRequest(
    val path: String,
    val headers: Map<String, String>,
    val body: JsonObject,
) {
    fun header(name: String): String = requireNotNull(headerOrNull(name))

    fun headerOrNull(name: String): String? = headers[name.lowercase()]
}

private class AnthropicOracleFixture(
    private val server: HttpServer,
) : AutoCloseable {
    val requests = mutableListOf<AnthropicOracleRequest>()
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    override fun close() {
        server.stop(0)
    }
}

private fun OAuthHttpRequest.header(name: String): String =
    requireNotNull(headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value)

private fun parseOracleQuery(query: String): Map<String, String> =
    query
        .split('&')
        .filter(String::isNotBlank)
        .associate { entry ->
            val parts = entry.split('=', limit = 2)
            URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
        }

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    name: String,
    value: String?,
) {
    if (value == null) {
        put(name, kotlinx.serialization.json.JsonNull)
    } else {
        put(name, value)
    }
}

private val oracleJson =
    Json {
        explicitNulls = false
    }
