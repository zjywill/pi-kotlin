package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.InMemoryCredentialStore
import works.earendil.pi.ai.InMemoryModelsStore
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.UserMessage

fun main() =
    runBlocking {
        var currentTime = 1_000_000L
        val sleeps = mutableListOf<Long>()
        val pollTimes = mutableListOf<Long>()
        val oauthRequests = mutableListOf<OAuthHttpRequest>()
        var devicePoll = 0
        var refreshCall = 0
        val oauth =
            KimiCodingOAuth(
                transport =
                    OAuthHttpTransport { request ->
                        oauthRequests += request
                        when {
                            request.url.endsWith("/device_authorization") ->
                                OAuthHttpResponse(
                                    200,
                                    """
                                    {
                                      "user_code":"ABCD-1234",
                                      "device_code":"device-code-123",
                                      "verification_uri":"https://www.kimi.com/code",
                                      "verification_uri_complete":"https://www.kimi.com/code?user_code=ABCD-1234",
                                      "interval":1,
                                      "expires_in":60
                                    }
                                    """.trimIndent(),
                                )

                            parseKimiOracleForm(request.body)["grant_type"] == "refresh_token" ->
                                when (++refreshCall) {
                                    1 ->
                                        OAuthHttpResponse(
                                            429,
                                            """{"error":"temporarily_unavailable"}""",
                                        )

                                    2 ->
                                        OAuthHttpResponse(
                                            500,
                                            """{"error":"server_error"}""",
                                        )

                                    3 ->
                                        OAuthHttpResponse(
                                            200,
                                            """
                                            {
                                              "access_token":"refreshed-access",
                                              "refresh_token":"refreshed-refresh",
                                              "expires_in":600
                                            }
                                            """.trimIndent(),
                                        )

                                    else ->
                                        OAuthHttpResponse(
                                            400,
                                            """
                                            {
                                              "error":"invalid_grant",
                                              "error_description":"session revoked"
                                            }
                                            """.trimIndent(),
                                        )
                                }

                            request.url.endsWith("/token") -> {
                                pollTimes += currentTime
                                when (devicePoll++) {
                                    0 ->
                                        OAuthHttpResponse(
                                            400,
                                            """{"error":"authorization_pending"}""",
                                        )

                                    1 ->
                                        OAuthHttpResponse(
                                            400,
                                            """{"error":"slow_down","interval":2}""",
                                        )

                                    else ->
                                        OAuthHttpResponse(
                                            200,
                                            """
                                            {
                                              "access_token":"access-token",
                                              "refresh_token":"refresh-token",
                                              "expires_in":3600
                                            }
                                            """.trimIndent(),
                                        )
                                }
                            }

                            else -> error("Unexpected Kimi OAuth request: ${request.url}")
                        }
                    },
                now = { currentTime },
                sleep = { milliseconds ->
                    sleeps += milliseconds
                    currentTime += milliseconds
                },
            )
        var deviceEvent: AuthEvent.DeviceCode? = null
        val loginCredential =
            oauth.login(
                object : AuthInteraction {
                    override suspend fun prompt(prompt: AuthPrompt): String =
                        error("Unexpected Kimi prompt")

                    override fun notify(event: AuthEvent) {
                        if (event is AuthEvent.DeviceCode) {
                            deviceEvent = event
                        }
                    }
                },
            )
        val refreshedCredential = oauth.refresh(loginCredential)
        val auth = oauth.toAuth(refreshedCredential)
        val unauthorizedMessage =
            runCatching { oauth.refresh(refreshedCredential) }
                .exceptionOrNull()
                ?.message
                .orEmpty()

        var providerRequest: KimiProviderRequest? = null
        val fixture = startKimiProviderFixture { providerRequest = it }
        try {
            val provider = builtInProviders().single { it.id == "kimi-coding" }
            val model =
                provider
                    .getModels()
                    .single { it.id == "kimi-for-coding" }
                    .copy(
                        baseUrl = "http://127.0.0.1:${fixture.address.port}",
                        reasoning = false,
                    )
            val models =
                Models(
                    listOf(provider),
                    InMemoryModelsStore(),
                    InMemoryCredentialStore(mapOf("kimi-coding" to refreshedCredential)),
                    currentTimeMillis = { currentTime },
                )
            val result =
                models.complete(
                    model,
                    Context(
                        systemPrompt = "Project instructions",
                        messages = mutableListOf(UserMessage("hi", timestamp = 1)),
                        tools =
                            listOf(
                                ToolDefinition(
                                    name = "echo",
                                    description = "Echo",
                                    parameters = buildJsonObject { put("type", "object") },
                                ),
                            ),
                    ),
                    StreamOptions(
                        cacheRetention = CacheRetention.NONE,
                        maxRetries = 0,
                        maxTokens = 64,
                    ),
                )
            val deviceRequest = oauthRequests.first { it.url.endsWith("/device_authorization") }
            val pollRequests =
                oauthRequests.filter {
                    parseKimiOracleForm(it.body)["grant_type"] ==
                        "urn:ietf:params:oauth:grant-type:device_code"
                }
            val refreshRequests =
                oauthRequests.filter {
                    parseKimiOracleForm(it.body)["grant_type"] == "refresh_token"
                }
            val captured = requireNotNull(providerRequest)
            val output =
                buildJsonObject {
                    put(
                        "login",
                        buildJsonObject {
                            put("name", oauth.name)
                            put("loginLabel", oauth.loginLabel)
                            put("device", deviceProjection(requireNotNull(deviceEvent)))
                            put("sleeps", longArray(sleeps))
                            put("pollTimes", longArray(pollTimes))
                            put("credential", credentialProjection(loginCredential))
                        },
                    )
                    put(
                        "requests",
                        buildJsonObject {
                            put("device", requestProjection(deviceRequest))
                            put(
                                "poll",
                                buildJsonObject {
                                    put("count", pollRequests.size)
                                    put("request", requestProjection(pollRequests.first()))
                                },
                            )
                            put(
                                "refresh",
                                buildJsonObject {
                                    put("count", refreshRequests.size - 1)
                                    put("request", requestProjection(refreshRequests.first()))
                                },
                            )
                            put("unauthorized", requestProjection(refreshRequests.last()))
                        },
                    )
                    put(
                        "refresh",
                        buildJsonObject {
                            put("credential", credentialProjection(refreshedCredential))
                            put(
                                "auth",
                                buildJsonObject {
                                    put(
                                        "headers",
                                        JsonObject(
                                            auth.headers
                                                .filterValues { it != null }
                                                .mapValues { JsonPrimitive(requireNotNull(it.value)) },
                                        ),
                                    )
                                },
                            )
                            put("unauthorizedMessage", unauthorizedMessage)
                        },
                    )
                    put(
                        "provider",
                        buildJsonObject {
                            put("path", captured.path)
                            captured.headers["authorization"]?.let {
                                put("authorization", it)
                            }
                            captured.headers["x-api-key"]?.let { put("xApiKey", it) }
                            captured.headers["user-agent"]?.let { put("userAgent", it) }
                            captured.headers["anthropic-beta"]?.let {
                                put("anthropicBeta", it)
                            }
                            put("body", captured.body)
                            put("result", resultProjection(result))
                        },
                    )
                }
            println(kimiOracleJson.encodeToString(JsonObject.serializer(), output))
        } finally {
            fixture.stop(0)
        }
    }

private data class KimiProviderRequest(
    val path: String,
    val headers: Map<String, String>,
    val body: JsonObject,
)

private fun startKimiProviderFixture(capture: (KimiProviderRequest) -> Unit): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        capture(
            KimiProviderRequest(
                path = exchange.requestURI.toString(),
                headers =
                    exchange.requestHeaders.entries.associate { (name, values) ->
                        name.lowercase() to values.joinToString(",")
                    },
                body =
                    kimiOracleJson
                        .parseToJsonElement(
                            exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8),
                        ) as JsonObject,
            ),
        )
        val bytes = anthropicSse().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("content-type", "text/event-stream")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    return server
}

private fun requestProjection(request: OAuthHttpRequest): JsonObject =
    buildJsonObject {
        put("url", request.url)
        put("method", request.method)
        request.header("accept")?.let { put("accept", it) }
        request.header("content-type")?.let { put("contentType", it) }
        put(
            "form",
            JsonObject(
                parseKimiOracleForm(request.body)
                    .mapValues { JsonPrimitive(it.value) },
            ),
        )
    }

private fun deviceProjection(event: AuthEvent.DeviceCode): JsonObject =
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

private fun credentialProjection(credential: OAuthCredential): JsonObject =
    buildJsonObject {
        put("type", "oauth")
        put("access", credential.access)
        put("refresh", credential.refresh)
        put("expires", credential.expires)
    }

private fun resultProjection(message: works.earendil.pi.ai.AssistantMessage): JsonObject =
    buildJsonObject {
        put(
            "stopReason",
            when (message.stopReason) {
                StopReason.TOOL_USE -> "toolUse"
                else -> message.stopReason.name.lowercase()
            },
        )
        put(
            "text",
            message.content
                .filterIsInstance<TextContent>()
                .joinToString("") { it.text },
        )
        message.content
            .filterIsInstance<ToolCall>()
            .firstOrNull()
            ?.let { put("toolName", it.name) }
    }

private fun longArray(values: List<Long>): JsonArray =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

private fun OAuthHttpRequest.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

private fun parseKimiOracleForm(value: String): Map<String, String> =
    value
        .split("&")
        .filter(String::isNotEmpty)
        .associate { field ->
            val parts = field.split("=", limit = 2)
            decode(parts[0]) to decode(parts.getOrElse(1) { "" })
        }

private fun decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

private fun anthropicSse(): String =
    listOf(
        "event: message_start",
        """data: {"type":"message_start","message":{"id":"msg-1","usage":{"input_tokens":7,"output_tokens":0}}}""",
        "",
        "event: content_block_start",
        """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "",
        "event: content_block_delta",
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}""",
        "",
        "event: content_block_stop",
        """data: {"type":"content_block_stop","index":0}""",
        "",
        "event: content_block_start",
        """data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tool-1","name":"echo","input":{}}}""",
        "",
        "event: content_block_delta",
        """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"value\":\"ok\"}"}}""",
        "",
        "event: content_block_stop",
        """data: {"type":"content_block_stop","index":1}""",
        "",
        "event: message_delta",
        """data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""",
        "",
        "event: message_stop",
        """data: {"type":"message_stop"}""",
        "",
    ).joinToString("\n")

private val kimiOracleJson =
    Json {
        explicitNulls = false
    }
