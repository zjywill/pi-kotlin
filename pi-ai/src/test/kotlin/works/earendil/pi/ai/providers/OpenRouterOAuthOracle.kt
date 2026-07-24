package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.UserMessage

fun main() =
    runBlocking {
        val tokenRequests = mutableListOf<OAuthHttpRequest>()
        val callbackResponse = AtomicReference<HttpResponse<String>>()
        val callbackThread = AtomicReference<Thread>()
        var progressMessage = ""
        var authorizationUrl = ""
        var authorizationInstructions: String? = null
        val oauth =
            OpenRouterOAuth(
                transport =
                    OAuthHttpTransport { request ->
                        tokenRequests += request
                        OAuthHttpResponse(200, """{"key":"openrouter-oauth-key"}""")
                    },
            )
        val credential =
            oauth.login(
                object : AuthInteraction {
                    override suspend fun prompt(prompt: AuthPrompt): String =
                        error("Unexpected OpenRouter prompt")

                    override fun notify(event: AuthEvent) {
                        when (event) {
                            is AuthEvent.Progress -> progressMessage = event.message
                            is AuthEvent.AuthUrl -> {
                                authorizationUrl = event.url
                                authorizationInstructions = event.instructions
                                val callbackUrl =
                                    parseOpenRouterOracleQuery(
                                        URI.create(event.url).rawQuery,
                                    ).getValue("callback_url")
                                callbackThread.set(
                                    Thread {
                                        callbackResponse.set(
                                            HttpClient
                                                .newHttpClient()
                                                .send(
                                                    HttpRequest
                                                        .newBuilder(
                                                            URI.create("$callbackUrl?code=oracle-code"),
                                                        ).GET()
                                                        .build(),
                                                    HttpResponse.BodyHandlers.ofString(),
                                                ),
                                        )
                                    }.also(Thread::start),
                                )
                            }

                            else -> Unit
                        }
                    }
                },
            )
        requireNotNull(callbackThread.get()).join()
        val refreshed = oauth.refresh(credential)
        val auth = oauth.toAuth(credential)

        val fixture = openRouterProviderFixture()
        val provider = builtInProviders().single { it.id == "openrouter" }
        val model =
            model(
                id = "openrouter-test",
                api = "openai-completions",
                provider = "openrouter",
                baseUrl = fixture.baseUrl,
            )
        val models =
            Models(
                listOf(provider),
                InMemoryModelsStore(),
                InMemoryCredentialStore(mapOf("openrouter" to credential)),
            )
        val result =
            models
                .stream(
                    model,
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
                    ),
                    StreamOptions(
                        cacheRetention = CacheRetention.NONE,
                        maxRetries = 0,
                    ),
                ).result()
        fixture.close()

        val authorization = URI.create(authorizationUrl)
        val authorizationQuery = parseOpenRouterOracleQuery(authorization.rawQuery)
        val callback = URI.create(authorizationQuery.getValue("callback_url"))
        val tokenRequest = tokenRequests.single()
        val tokenBody = providerJson.parseToJsonElement(tokenRequest.body).jsonObject
        val verifier = tokenBody.string("code_verifier").orEmpty()
        val challenge =
            MessageDigest
                .getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.UTF_8))
                .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val callbackResult = requireNotNull(callbackResponse.get())
        val providerRequest = requireNotNull(fixture.request)
        val output =
            buildJsonObject {
                put(
                    "login",
                    buildJsonObject {
                        put("name", oauth.name)
                        put("loginLabel", oauth.loginLabel)
                        put(
                            "events",
                            buildJsonObject {
                                put("progressMatchesCallback", progressMessage.endsWith(callback.toString()))
                                putNullable("instructions", authorizationInstructions)
                            },
                        )
                        put(
                            "authorization",
                            buildJsonObject {
                                put("protocol", "${authorization.scheme}:")
                                put("host", authorization.host)
                                put("path", authorization.path)
                                put("callbackHost", callback.host)
                                put("callbackPortPositive", callback.port > 0)
                                put(
                                    "callbackPathPrefix",
                                    callback.path.startsWith("/oauth/callback/"),
                                )
                                put("callbackIdLength", callback.path.substringAfterLast('/').length)
                                put(
                                    "codeChallengeMethod",
                                    authorizationQuery["code_challenge_method"],
                                )
                                put(
                                    "challengeLength",
                                    authorizationQuery["code_challenge"].orEmpty().length,
                                )
                                put(
                                    "challengeMatches",
                                    challenge == authorizationQuery["code_challenge"],
                                )
                            },
                        )
                        put(
                            "request",
                            buildJsonObject {
                                put("url", tokenRequest.url)
                                put("method", tokenRequest.method)
                                put("accept", tokenRequest.header("accept"))
                                put("contentType", tokenRequest.header("content-type"))
                                put("code", tokenBody.string("code"))
                                put(
                                    "codeChallengeMethod",
                                    tokenBody.string("code_challenge_method"),
                                )
                                put("verifierLength", verifier.length)
                            },
                        )
                        put(
                            "callback",
                            buildJsonObject {
                                put("status", callbackResult.statusCode())
                                put(
                                    "contentType",
                                    callbackResult.headers().firstValue("content-type").orElse(null),
                                )
                                put(
                                    "cacheControl",
                                    callbackResult.headers().firstValue("cache-control").orElse(null),
                                )
                                put("success", callbackResult.body().contains("Signed in to OpenRouter"))
                            },
                        )
                        put("credential", credentialProjection(credential))
                        put("refreshUnchanged", refreshed == credential)
                        put(
                            "auth",
                            buildJsonObject {
                                put("apiKey", auth.apiKey)
                            },
                        )
                    },
                )
                put(
                    "provider",
                    buildJsonObject {
                        put("path", providerRequest.path)
                        put("authorization", providerRequest.header("authorization"))
                        put("body", providerRequest.body)
                        put(
                            "result",
                            buildJsonObject {
                                put("stopReason", "toolUse")
                                put(
                                    "text",
                                    result.content
                                        .filterIsInstance<TextContent>()
                                        .joinToString("") { it.text },
                                )
                                put(
                                    "toolName",
                                    result.content.filterIsInstance<ToolCall>().single().name,
                                )
                            },
                        )
                    },
                )
            }
        println(openRouterOracleJson.encodeToString(JsonObject.serializer(), output))
    }

private fun credentialProjection(credential: OAuthCredential): JsonObject =
    buildJsonObject {
        put("type", "oauth")
        put("access", credential.access)
        put("refresh", credential.refresh)
        put("expires", credential.expires)
    }

private fun openRouterProviderFixture(): OpenRouterProviderFixture {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val fixture = OpenRouterProviderFixture(server)
    server.createContext("/") { exchange ->
        val body =
            providerJson
                .parseToJsonElement(
                    exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8),
                ).jsonObject
        fixture.request =
            OpenRouterProviderRequest(
                path = exchange.requestURI.path,
                headers =
                    exchange.requestHeaders.entries.associate { (name, values) ->
                        name.lowercase() to values.joinToString(",")
                    },
                body = body,
            )
        val response =
            """
            data: {"choices":[{"delta":{"content":"hello "}}]}

            data: {"choices":[{"delta":{"content":"world"}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"echo","arguments":"{\"value\":\"ok\"}"}}]},"finish_reason":"tool_calls"}]}

            data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":2}}}

            data: [DONE]

            """.trimIndent()
        val bytes = response.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("content-type", "text/event-stream")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    return fixture
}

private data class OpenRouterProviderRequest(
    val path: String,
    val headers: Map<String, String>,
    val body: JsonObject,
) {
    fun header(name: String): String = requireNotNull(headers[name.lowercase()])
}

private class OpenRouterProviderFixture(
    private val server: HttpServer,
) : AutoCloseable {
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"
    var request: OpenRouterProviderRequest? = null

    override fun close() {
        server.stop(0)
    }
}

private fun OAuthHttpRequest.header(name: String): String =
    requireNotNull(headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value)

private fun parseOpenRouterOracleQuery(query: String): Map<String, String> =
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
        put(name, JsonNull)
    } else {
        put(name, value)
    }
}

private val openRouterOracleJson =
    Json {
        explicitNulls = false
    }
