package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Collections
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
        val oauth =
            XaiOAuth(
                transport =
                    OAuthHttpTransport { request ->
                        oauthRequests += request
                        when {
                            request.url.endsWith("/device/code") ->
                                OAuthHttpResponse(
                                    200,
                                    """
                                    {
                                      "device_code":"device-code",
                                      "user_code":"ABCD-1234",
                                      "verification_uri":"https://accounts.x.ai/oauth2/device",
                                      "verification_uri_complete":"https://accounts.x.ai/oauth2/device?user_code=ABCD-1234",
                                      "expires_in":60,
                                      "interval":1
                                    }
                                    """.trimIndent(),
                                )

                            parseOracleForm(request.body)["grant_type"] == "refresh_token" ->
                                OAuthHttpResponse(
                                    200,
                                    """{"access_token":"refreshed-access"}""",
                                )

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
                                              "expires_in":21600
                                            }
                                            """.trimIndent(),
                                        )
                                }
                            }

                            else -> error("Unexpected xAI OAuth request: ${request.url}")
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
                        error("Unexpected xAI prompt")

                    override fun notify(event: AuthEvent) {
                        if (event is AuthEvent.DeviceCode) {
                            deviceEvent = event
                        }
                    }
                },
            )
        val refreshedCredential = oauth.refresh(loginCredential)
        val auth = oauth.toAuth(refreshedCredential)

        val providerRequests =
            Collections.synchronizedList(
                mutableListOf<XaiProviderRequest>(),
            )
        val fixture = startXaiProviderFixture(providerRequests)
        try {
            val provider = builtInProviders().single { it.id == "xai" }
            val baseUrl = "http://127.0.0.1:${fixture.address.port}"
            val chatModel =
                provider
                    .getModels()
                    .single { it.id == "grok-4.3" }
                    .copy(baseUrl = baseUrl)
            val responsesModel =
                provider
                    .getModels()
                    .single { it.id == "grok-4.5" }
                    .copy(baseUrl = baseUrl)
            val models =
                Models(
                    listOf(provider),
                    InMemoryModelsStore(),
                    InMemoryCredentialStore(mapOf("xai" to refreshedCredential)),
                    currentTimeMillis = { currentTime },
                )
            val context =
                Context(
                    messages = mutableListOf(UserMessage("hi", timestamp = 1)),
                    tools =
                        listOf(
                            ToolDefinition(
                                name = "echo",
                                description = "Echo",
                                parameters = buildJsonObject { put("type", "object") },
                            ),
                        ),
                )
            val options =
                StreamOptions(
                    cacheRetention = CacheRetention.NONE,
                    maxRetries = 0,
                    maxTokens = 64,
                )
            val chatResult = models.complete(chatModel, context, options)
            val responsesResult = models.complete(responsesModel, context, options)
            val deviceRequest = oauthRequests.first { it.url.endsWith("/device/code") }
            val pollRequests =
                oauthRequests.filter {
                    parseOracleForm(it.body)["grant_type"] ==
                        "urn:ietf:params:oauth:grant-type:device_code"
                }
            val refreshRequest =
                oauthRequests.first {
                    parseOracleForm(it.body)["grant_type"] == "refresh_token"
                }
            val results = listOf(chatResult, responsesResult)
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
                            put("refresh", requestProjection(refreshRequest))
                        },
                    )
                    put(
                        "refresh",
                        buildJsonObject {
                            put("credential", credentialProjection(refreshedCredential))
                            put(
                                "auth",
                                buildJsonObject {
                                    auth.apiKey?.let { put("apiKey", it) }
                                },
                            )
                        },
                    )
                    put(
                        "provider",
                        buildJsonArray {
                            providerRequests.forEachIndexed { index, request ->
                                add(
                                    buildJsonObject {
                                        put(
                                            "api",
                                            if (index == 0) {
                                                "openai-completions"
                                            } else {
                                                "openai-responses"
                                            },
                                        )
                                        put("path", request.path)
                                        request.headers["authorization"]?.let {
                                            put("authorization", it)
                                        }
                                        put("body", request.body)
                                        put("result", resultProjection(results[index]))
                                    },
                                )
                            }
                        },
                    )
                }
            println(xaiOracleJson.encodeToString(JsonObject.serializer(), output))
        } finally {
            fixture.stop(0)
        }
    }

private data class XaiProviderRequest(
    val path: String,
    val headers: Map<String, String>,
    val body: JsonObject,
)

private fun startXaiProviderFixture(requests: MutableList<XaiProviderRequest>): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        val body =
            xaiOracleJson
                .parseToJsonElement(
                    exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8),
                ) as JsonObject
        requests +=
            XaiProviderRequest(
                path = exchange.requestURI.toString(),
                headers =
                    exchange.requestHeaders.entries.associate { (name, values) ->
                        name.lowercase() to values.joinToString(",")
                    },
                body = body,
            )
        val response =
            if (exchange.requestURI.path.endsWith("/chat/completions")) {
                chatSse()
            } else {
                responsesSse()
            }
        val bytes = response.toByteArray(StandardCharsets.UTF_8)
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
                parseOracleForm(request.body)
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

private fun parseOracleForm(value: String): Map<String, String> =
    value
        .split("&")
        .filter(String::isNotEmpty)
        .associate { field ->
            val parts = field.split("=", limit = 2)
            decode(parts[0]) to decode(parts.getOrElse(1) { "" })
        }

private fun decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)

private fun chatSse(): String =
    listOf(
        """data: {"choices":[{"delta":{"content":"hello "}}]}""",
        """data: {"choices":[{"delta":{"content":"world"}}]}""",
        """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"echo","arguments":"{\"value\":\"ok\"}"}}]},"finish_reason":"tool_calls"}]}""",
        """data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":2}}}""",
        "data: [DONE]",
        "",
    ).joinToString("\n\n")

private fun responsesSse(): String =
    listOf(
        """data: {"type":"response.created","response":{"id":"resp-1"}}""",
        """data: {"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg-1","content":[]}}""",
        """data: {"type":"response.output_text.delta","output_index":0,"delta":"responses"}""",
        """data: {"type":"response.output_item.done","output_index":0,"item":{"type":"message","id":"msg-1","content":[{"type":"output_text","text":"responses"}]}}""",
        """data: {"type":"response.completed","response":{"id":"resp-1","status":"completed","usage":{"input_tokens":12,"output_tokens":3,"total_tokens":15,"input_tokens_details":{"cached_tokens":2},"output_tokens_details":{"reasoning_tokens":1}},"output":[]}}""",
        "",
    ).joinToString("\n\n")

private val xaiOracleJson =
    Json {
        explicitNulls = false
    }
