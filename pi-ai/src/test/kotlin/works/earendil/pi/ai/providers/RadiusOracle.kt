package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.AssistantError
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.AssistantMessageEvent
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.InMemoryCredentialStore
import works.earendil.pi.ai.InMemoryModelsStore
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.ModelsRefreshOptions
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.ToolCallEnd
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.UserMessage

fun main() =
    runBlocking {
        var currentTime = 1_000_000L
        val sleeps = mutableListOf<Long>()
        val pollTimes = mutableListOf<Long>()
        val oauthRequests = mutableListOf<OAuthHttpRequest>()
        var devicePoll = 0
        val transport =
            OAuthHttpTransport { request ->
                oauthRequests += request
                when {
                    request.method == "GET" ->
                        OAuthHttpResponse(200, radiusOAuthConfig())

                    request.url.endsWith("/device") ->
                        OAuthHttpResponse(
                            200,
                            """
                            {
                              "device_code":"device-code",
                              "user_code":"ABCD-1234",
                              "verification_uri":"https://verify.example/device",
                              "verification_uri_complete":"https://verify.example/device?code=ABCD-1234",
                              "expires_in":120,
                              "interval":0.25
                            }
                            """.trimIndent(),
                        )

                    request.url.endsWith("/token") -> {
                        val fields = parseRadiusOracleForm(request.body)
                        when (fields["grant_type"]) {
                            "authorization_code" ->
                                OAuthHttpResponse(
                                    200,
                                    """
                                    {
                                      "access_token":"browser-access",
                                      "refresh_token":"browser-refresh",
                                      "expires_in":3600,
                                      "scope":"openid profile"
                                    }
                                    """.trimIndent(),
                                )

                            "refresh_token" ->
                                OAuthHttpResponse(
                                    200,
                                    """
                                    {
                                      "access_token":"refreshed-access",
                                      "refresh_token":"refreshed-refresh",
                                      "expires_in":600,
                                      "scope":"scope-2"
                                    }
                                    """.trimIndent(),
                                )

                            else -> {
                                pollTimes += currentTime
                                when (devicePoll++) {
                                    0 -> OAuthHttpResponse(400, """{"error":"authorization_pending"}""")
                                    1 -> OAuthHttpResponse(400, """{"error":"slow_down"}""")
                                    else ->
                                        OAuthHttpResponse(
                                            200,
                                            """
                                            {
                                              "access_token":"device-access",
                                              "refresh_token":"device-refresh",
                                              "expires_in":3600,
                                              "scope":"openid profile"
                                            }
                                            """.trimIndent(),
                                        )
                                }
                            }
                        }
                    }

                    else -> error("Unexpected Radius OAuth request: ${request.url}")
                }
            }

        val browserInteraction = RadiusOracleInteraction("browser")
        val browserOAuth =
            RadiusOAuth(
                transport = transport,
                gateway = "radius.example/",
                now = { currentTime },
                random = SecureRandom(byteArrayOf(1, 2, 3, 4)),
                stateFactory = { "fixed-state" },
                callbackServerFactory = {
                    RadiusOAuthCallbackServer(
                        closeAction = {},
                        code = CompletableDeferred("browser-code"),
                    )
                },
            )
        val browserCredential = browserOAuth.login(browserInteraction)

        val deviceInteraction = RadiusOracleInteraction("device-code")
        val deviceOAuth =
            RadiusOAuth(
                transport = transport,
                gateway = "radius.example/",
                now = { currentTime },
                sleep = { milliseconds ->
                    sleeps += milliseconds
                    currentTime += milliseconds
                },
            )
        val deviceCredential = deviceOAuth.login(deviceInteraction)
        val refreshedCredential = deviceOAuth.refresh(deviceCredential)
        val requestAuth = deviceOAuth.toAuth(refreshedCredential)

        val providerRequests = mutableListOf<RadiusProviderRequest>()
        val fixture =
            HttpServer
                .create(InetSocketAddress("127.0.0.1", 0), 0)
                .apply {
                    createContext("/") { exchange ->
                        providerRequests += exchange.recordRadiusRequest()
                        when (exchange.requestURI.toString()) {
                            "/v1/config" ->
                                exchange.respondRadiusJson(
                                    buildJsonObject {
                                        put("baseUrl", "http://127.0.0.1:${exchange.localAddress.port}/v1")
                                        put("models", buildJsonArray { add(radiusOracleModel()) })
                                    }.toString(),
                                )

                            "/v1/messages?debug=1" -> {
                                exchange.responseHeaders.add(
                                    "x-pi-gateway-upstream-provider",
                                    "anthropic",
                                )
                                exchange.respondRadiusSse(radiusOracleSse())
                            }

                            else -> exchange.respondRadiusJson("{}", 404)
                        }
                    }
                    start()
                }
        val providerOutput =
            try {
                val gateway = "http://127.0.0.1:${fixture.address.port}"
                val store = InMemoryModelsStore()
                val models =
                    Models(
                        providers =
                            listOf(
                                RadiusProvider(
                                    gateway = gateway,
                                    client = HttpClient.newHttpClient(),
                                    environment = { null },
                                ),
                            ),
                        modelsStore = store,
                        credentials =
                            InMemoryCredentialStore(
                                mapOf("radius" to refreshedCredential),
                            ),
                        currentTimeMillis = { currentTime },
                    )
                val refresh = models.refresh(ModelsRefreshOptions(allowNetwork = true))
                val model = requireNotNull(models.getModel("radius", "auto"))
                var responseProvider: String? = null
                val eventStream =
                    models.stream(
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
                            debug = true,
                            maxTokens = 64,
                            reasoning = works.earendil.pi.ai.ThinkingLevel.HIGH,
                            sessionId = "session-1",
                            toolChoice = JsonPrimitive("auto"),
                            headers = mapOf("x-custom" to "1"),
                            onResponse = { response, _ ->
                                responseProvider = response.headers["x-pi-gateway-upstream-provider"]
                            },
                        ),
                    )
                val events = async { eventStream.events.toList() }
                val result = eventStream.result()

                val legacyModels =
                    Models(
                        providers =
                            listOf(
                                RadiusProvider(
                                    gateway = "http://127.0.0.1:1",
                                    environment = { null },
                                ),
                            ),
                        modelsStore = InMemoryModelsStore(),
                        credentials =
                            InMemoryCredentialStore(
                                mapOf(
                                    "radius" to
                                        OAuthCredential(
                                            access = "legacy-access",
                                            refresh = "legacy-refresh",
                                            expires = currentTime + 60_000,
                                            gatewayConfig =
                                                buildJsonObject {
                                                    put("baseUrl", "https://legacy.example/v1")
                                                    put(
                                                        "models",
                                                        buildJsonArray { add(radiusOracleModel()) },
                                                    )
                                                },
                                        ),
                                ),
                            ),
                        currentTimeMillis = { currentTime },
                    )
                legacyModels.refresh(ModelsRefreshOptions(allowNetwork = false))
                val legacyModel = requireNotNull(legacyModels.getModel("radius", "auto"))
                val configRequest = providerRequests.first { it.path == "/v1/config" }
                val messageRequest = providerRequests.first { it.path == "/v1/messages?debug=1" }

                buildJsonObject {
                    put(
                        "refreshErrors",
                        JsonArray(refresh.errors.keys.sorted().map(::JsonPrimitive)),
                    )
                    put("model", radiusModelProjection(model))
                    put(
                        "storedModels",
                        JsonArray(
                            requireNotNull(store.read("radius"))
                                .models
                                .map(::radiusModelProjection),
                        ),
                    )
                    put("legacyModel", radiusModelProjection(legacyModel))
                    put(
                        "configRequest",
                        buildJsonObject {
                            put("path", configRequest.path)
                            configRequest.headers["authorization"]?.let { put("authorization", it) }
                            configRequest.headers["accept"]?.let { put("accept", it) }
                        },
                    )
                    put(
                        "messageRequest",
                        buildJsonObject {
                            put("path", messageRequest.path)
                            messageRequest.headers["authorization"]?.let { put("authorization", it) }
                            messageRequest.headers["accept"]?.let { put("accept", it) }
                            messageRequest.headers["content-type"]?.let { put("contentType", it) }
                            messageRequest.headers["x-custom"]?.let { put("custom", it) }
                            put("body", requireNotNull(messageRequest.body))
                        },
                    )
                    responseProvider?.let { put("responseProvider", it) }
                    put(
                        "events",
                        JsonArray(events.await().map(::radiusStreamEventProjection)),
                    )
                    put("result", radiusResultProjection(result))
                }
            } finally {
                fixture.stop(0)
            }

        val browserTokenRequest =
            oauthRequests.first {
                parseRadiusOracleForm(it.body)["grant_type"] == "authorization_code"
            }
        val browserAuthUrl =
            (browserInteraction.events.first { it is AuthEvent.AuthUrl } as AuthEvent.AuthUrl).url
        val browserQuery = parseRadiusOracleForm(requireNotNull(URI(browserAuthUrl).rawQuery))
        val browserForm = parseRadiusOracleForm(browserTokenRequest.body)
        val deviceRequest = oauthRequests.first { it.url.endsWith("/device") }
        val pollRequests =
            oauthRequests.filter {
                parseRadiusOracleForm(it.body)["grant_type"] == "urn:radius:device"
            }
        val refreshRequest =
            oauthRequests.first {
                parseRadiusOracleForm(it.body)["grant_type"] == "refresh_token"
            }
        val output =
            buildJsonObject {
                put(
                    "browser",
                    buildJsonObject {
                        put("prompt", radiusPromptProjection(browserInteraction.prompts.single()))
                        put(
                            "events",
                            JsonArray(browserInteraction.events.map(::radiusBrowserEventProjection)),
                        )
                        put(
                            "authorization",
                            buildJsonObject {
                                put("responseType", browserQuery["response_type"])
                                put("clientId", browserQuery["client_id"])
                                put("redirectUri", browserQuery["redirect_uri"])
                                put("scope", browserQuery["scope"])
                                put("challengeMethod", browserQuery["code_challenge_method"])
                                put("handoff", browserQuery["handoff"])
                                put("hasState", !browserQuery["state"].isNullOrEmpty())
                                put(
                                    "challengeMatchesVerifier",
                                    browserQuery["code_challenge"] ==
                                        radiusSha256Base64Url(browserForm["code_verifier"].orEmpty()),
                                )
                            },
                        )
                        put("request", radiusBrowserRequestProjection(browserTokenRequest))
                        put("credential", radiusCredentialProjection(browserCredential))
                    },
                )
                put(
                    "device",
                    buildJsonObject {
                        put("prompt", radiusPromptProjection(deviceInteraction.prompts.single()))
                        put(
                            "events",
                            JsonArray(deviceInteraction.events.map(::radiusEventProjection)),
                        )
                        put("sleeps", JsonArray(sleeps.map(::JsonPrimitive)))
                        put("pollTimes", JsonArray(pollTimes.map(::JsonPrimitive)))
                        put("request", radiusRequestProjection(deviceRequest))
                        put(
                            "poll",
                            buildJsonObject {
                                put("count", pollRequests.size)
                                put("request", radiusRequestProjection(pollRequests.first()))
                            },
                        )
                        put("credential", radiusCredentialProjection(deviceCredential))
                    },
                )
                put(
                    "refresh",
                    buildJsonObject {
                        put("request", radiusRequestProjection(refreshRequest))
                        put("credential", radiusCredentialProjection(refreshedCredential))
                        put(
                            "auth",
                            buildJsonObject {
                                requestAuth.apiKey?.let { put("apiKey", it) }
                            },
                        )
                    },
                )
                put("provider", providerOutput)
            }
        println(radiusOracleJson.encodeToString(JsonObject.serializer(), output))
    }

private class RadiusOracleInteraction(
    private val answer: String,
) : AuthInteraction {
    val prompts = mutableListOf<AuthPrompt>()
    val events = mutableListOf<AuthEvent>()

    override suspend fun prompt(prompt: AuthPrompt): String {
        prompts += prompt
        return answer
    }

    override fun notify(event: AuthEvent) {
        events += event
    }
}

private data class RadiusProviderRequest(
    val path: String,
    val headers: Map<String, String>,
    val body: JsonObject?,
)

private fun HttpExchange.recordRadiusRequest(): RadiusProviderRequest {
    val raw = requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
    return RadiusProviderRequest(
        path = requestURI.toString(),
        headers =
            requestHeaders.entries.associate { (name, values) ->
                name.lowercase() to values.joinToString(", ")
            },
        body =
            raw.takeIf(String::isNotEmpty)
                ?.let { radiusOracleJson.parseToJsonElement(it).jsonObject },
    )
}

private fun HttpExchange.respondRadiusJson(
    body: String,
    status: Int = 200,
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("content-type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun HttpExchange.respondRadiusSse(body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("content-type", "text/event-stream")
    sendResponseHeaders(200, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun radiusOAuthConfig(): String =
    """
    {
      "issuer":"https://issuer.example",
      "authorizationEndpoint":"https://oauth.example/authorize",
      "tokenEndpoint":"https://oauth.example/token",
      "deviceAuthorizationEndpoint":"https://oauth.example/device",
      "deviceAuthorizationEventsEndpoint":"https://oauth.example/events",
      "verificationEndpoint":"https://oauth.example/verify",
      "clientId":"radius-client",
      "scope":"openid profile",
      "deviceCodeGrantType":"urn:radius:device"
    }
    """.trimIndent()

private fun radiusOracleModel(): JsonObject =
    buildJsonObject {
        put("id", "auto")
        put("name", "Radius Auto")
        put("reasoning", true)
        put(
            "thinkingLevelMap",
            buildJsonObject {
                put("off", kotlinx.serialization.json.JsonNull)
                put("high", "high")
            },
        )
        put("input", buildJsonArray { add(JsonPrimitive("text")) })
        put(
            "cost",
            buildJsonObject {
                put("input", 1)
                put("output", 2)
                put("cacheRead", 0.1)
                put("cacheWrite", 0.2)
            },
        )
        put("contextWindow", 128_000)
        put("maxTokens", 16_384)
    }

private fun radiusOracleSse(): String {
    val usage =
        """
        {"input":10,"output":5,"cacheRead":0,"cacheWrite":0,"totalTokens":15,"cost":{"input":0.1,"output":0.2,"cacheRead":0,"cacheWrite":0,"total":0.3}}
        """.trimIndent()
    return listOf(
        """data: {"type":"start"}""",
        """data: {"type":"text_start","contentIndex":0}""",
        """data: {"type":"text_delta","contentIndex":0,"delta":"hello"}""",
        """data: {"type":"text_end","contentIndex":0,"content":"hello","contentSignature":"text-sig"}""",
        """data: {"type":"toolcall_start","contentIndex":1,"id":"call-1","toolName":"echo"}""",
        """data: {"type":"toolcall_delta","contentIndex":1,"delta":"{\"value\":"}""",
        """data: {"type":"toolcall_delta","contentIndex":1,"delta":"\"ok\"}"}""",
        """data: {"type":"toolcall_end","contentIndex":1,"toolCall":{"type":"toolCall","id":"call-1","name":"echo","arguments":{"value":"ok"}}}""",
        """
        data: {"type":"done","reason":"toolUse","usage":$usage,"responseId":"response-1","rewrite":{"policyId":"policy-1","policyVersion":2,"changed":true,"tokenCountChange":-3,"messageCountChange":0,"systemPromptChanged":false}}
        """.trimIndent(),
        "",
    ).joinToString("\n\n")
}

private fun radiusPromptProjection(prompt: AuthPrompt): JsonObject {
    val select = prompt as AuthPrompt.Select
    return buildJsonObject {
        put("message", select.message)
        put(
            "options",
            JsonArray(
                select.options.map { option ->
                    buildJsonObject {
                        put("id", option.id)
                        put("label", option.label)
                    }
                },
            ),
        )
    }
}

private fun radiusEventProjection(event: AuthEvent): JsonObject =
    when (event) {
        is AuthEvent.DeviceCode ->
            buildJsonObject {
                put("type", "device_code")
                put("userCode", event.userCode)
                put("verificationUri", event.verificationUri)
                event.intervalSeconds?.let { put("intervalSeconds", it) }
                event.expiresInSeconds?.let { put("expiresInSeconds", it) }
            }

        is AuthEvent.AuthUrl ->
            buildJsonObject {
                put("type", "auth_url")
                put("url", event.url)
                event.instructions?.let { put("instructions", it) }
            }

        is AuthEvent.Progress ->
            buildJsonObject {
                put("type", "progress")
                put("message", event.message)
            }

        is AuthEvent.Info ->
            buildJsonObject {
                put("type", "info")
                put("message", event.message)
            }
    }

private fun radiusBrowserEventProjection(event: AuthEvent): JsonObject =
    if (event is AuthEvent.AuthUrl) {
        buildJsonObject {
            put("type", "auth_url")
            event.instructions?.let { put("instructions", it) }
            put("url", "https://oauth.example/authorize")
        }
    } else {
        radiusEventProjection(event)
    }

private fun radiusRequestProjection(request: OAuthHttpRequest): JsonObject =
    buildJsonObject {
        put("url", request.url)
        put("method", request.method)
        request.headerValue("accept")?.let { put("accept", it) }
        request.headerValue("content-type")?.let { put("contentType", it) }
        put(
            "form",
            JsonObject(
                parseRadiusOracleForm(request.body)
                    .mapValues { JsonPrimitive(it.value) },
            ),
        )
    }

private fun radiusBrowserRequestProjection(request: OAuthHttpRequest): JsonObject {
    val fields = parseRadiusOracleForm(request.body).toMutableMap()
    if ("code_verifier" in fields) {
        fields["code_verifier"] = "<pkce>"
    }
    return buildJsonObject {
        put("url", request.url)
        put("method", request.method)
        request.headerValue("accept")?.let { put("accept", it) }
        request.headerValue("content-type")?.let { put("contentType", it) }
        put("form", JsonObject(fields.mapValues { JsonPrimitive(it.value) }))
    }
}

private fun radiusCredentialProjection(credential: OAuthCredential): JsonObject =
    buildJsonObject {
        put("type", "oauth")
        put("access", credential.access)
        put("refresh", credential.refresh)
        put("expires", credential.expires)
        credential.scope?.let { put("scope", it) }
    }

private fun radiusModelProjection(model: Model): JsonObject =
    buildJsonObject {
        put("id", model.id)
        put("name", model.name)
        put("api", model.api)
        put("provider", model.provider)
        put("baseUrl", normalizeRadiusOracleBaseUrl(model.baseUrl))
        put("reasoning", model.reasoning)
        put(
            "thinkingLevelMap",
            buildJsonObject {
                model.thinkingLevelMap.forEach { (level, value) ->
                    val key =
                        when (level) {
                            works.earendil.pi.ai.ModelThinkingLevel.OFF -> "off"
                            works.earendil.pi.ai.ModelThinkingLevel.MINIMAL -> "minimal"
                            works.earendil.pi.ai.ModelThinkingLevel.LOW -> "low"
                            works.earendil.pi.ai.ModelThinkingLevel.MEDIUM -> "medium"
                            works.earendil.pi.ai.ModelThinkingLevel.HIGH -> "high"
                            works.earendil.pi.ai.ModelThinkingLevel.XHIGH -> "xhigh"
                            works.earendil.pi.ai.ModelThinkingLevel.MAX -> "max"
                        }
                    if (value == null) {
                        put(key, kotlinx.serialization.json.JsonNull)
                    } else {
                        put(key, value)
                    }
                }
            },
        )
        put(
            "input",
            JsonArray(
                model.input.map { input ->
                    JsonPrimitive(
                        when (input) {
                            works.earendil.pi.ai.ModelInput.TEXT -> "text"
                            works.earendil.pi.ai.ModelInput.IMAGE -> "image"
                        },
                    )
                },
            ),
        )
        put(
            "cost",
            buildJsonObject {
                put("input", model.cost.input)
                put("output", model.cost.output)
                put("cacheRead", model.cost.cacheRead)
                put("cacheWrite", model.cost.cacheWrite)
            },
        )
        put("contextWindow", model.contextWindow)
        put("maxTokens", model.maxTokens)
    }

private fun radiusStreamEventProjection(event: AssistantMessageEvent): JsonObject =
    buildJsonObject {
        put(
            "type",
            when (event) {
                is works.earendil.pi.ai.AssistantStart -> "start"
                is works.earendil.pi.ai.TextStart -> "text_start"
                is works.earendil.pi.ai.TextDelta -> "text_delta"
                is works.earendil.pi.ai.TextEnd -> "text_end"
                is works.earendil.pi.ai.ThinkingStart -> "thinking_start"
                is works.earendil.pi.ai.ThinkingDelta -> "thinking_delta"
                is works.earendil.pi.ai.ThinkingEnd -> "thinking_end"
                is works.earendil.pi.ai.ToolCallStart -> "toolcall_start"
                is works.earendil.pi.ai.ToolCallDelta -> "toolcall_delta"
                is ToolCallEnd -> "toolcall_end"
                is AssistantDone -> "done"
                is AssistantError -> "error"
            },
        )
        when (event) {
            is works.earendil.pi.ai.TextStart -> put("contentIndex", event.contentIndex)
            is works.earendil.pi.ai.TextDelta -> {
                put("contentIndex", event.contentIndex)
                put("delta", event.delta)
            }

            is works.earendil.pi.ai.TextEnd -> {
                put("contentIndex", event.contentIndex)
                put("content", event.content)
            }

            is works.earendil.pi.ai.ThinkingStart -> put("contentIndex", event.contentIndex)
            is works.earendil.pi.ai.ThinkingDelta -> {
                put("contentIndex", event.contentIndex)
                put("delta", event.delta)
            }

            is works.earendil.pi.ai.ThinkingEnd -> {
                put("contentIndex", event.contentIndex)
                put("content", event.content)
            }

            is works.earendil.pi.ai.ToolCallStart -> put("contentIndex", event.contentIndex)
            is works.earendil.pi.ai.ToolCallDelta -> {
                put("contentIndex", event.contentIndex)
                put("delta", event.delta)
            }

            is ToolCallEnd -> {
                put("contentIndex", event.contentIndex)
                put(
                    "toolCall",
                    radiusOracleJson.encodeToJsonElement(ContentBlock.serializer(), event.toolCall),
                )
            }

            is AssistantDone -> put("reason", event.reason.radiusValue())
            is AssistantError -> put("reason", event.reason.radiusValue())
            is works.earendil.pi.ai.AssistantStart -> Unit
        }
    }

private fun radiusResultProjection(message: AssistantMessage): JsonObject =
    buildJsonObject {
        put("stopReason", message.stopReason.radiusValue())
        message.responseId?.let { put("responseId", it) }
        put(
            "content",
            JsonArray(
                message.content.map { content ->
                    radiusOracleJson.encodeToJsonElement(ContentBlock.serializer(), content)
                },
            ),
        )
        put(
            "usage",
            buildJsonObject {
                put("input", message.usage.input)
                put("output", message.usage.output)
                put("cacheRead", message.usage.cacheRead)
                put("cacheWrite", message.usage.cacheWrite)
                put("totalTokens", message.usage.totalTokens)
                put(
                    "cost",
                    buildJsonObject {
                        put("input", message.usage.cost.input)
                        put("output", message.usage.cost.output)
                        put("cacheRead", message.usage.cost.cacheRead)
                        put("cacheWrite", message.usage.cost.cacheWrite)
                        put("total", message.usage.cost.total)
                    },
                )
            },
        )
        message.diagnostics?.let { diagnostics ->
            put(
                "diagnostics",
                JsonArray(
                    diagnostics.map { diagnostic ->
                        buildJsonObject {
                            put("type", diagnostic.type)
                            diagnostic.details?.let { put("details", it) }
                        }
                    },
                ),
            )
        }
    }

private fun StopReason.radiusValue(): String =
    when (this) {
        StopReason.STOP -> "stop"
        StopReason.LENGTH -> "length"
        StopReason.TOOL_USE -> "toolUse"
        StopReason.ERROR -> "error"
        StopReason.ABORTED -> "aborted"
    }

private fun parseRadiusOracleForm(body: String): Map<String, String> =
    body
        .split('&')
        .filter(String::isNotBlank)
        .associate { entry ->
            val separator = entry.indexOf('=')
            val name = if (separator >= 0) entry.substring(0, separator) else entry
            val value = if (separator >= 0) entry.substring(separator + 1) else ""
            URLDecoder.decode(name, StandardCharsets.UTF_8) to
                URLDecoder.decode(value, StandardCharsets.UTF_8)
        }

private fun OAuthHttpRequest.headerValue(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

private fun radiusSha256Base64Url(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

private fun normalizeRadiusOracleBaseUrl(value: String): String =
    value.replace(Regex("""http://127\.0\.0\.1:\d+"""), "http://127.0.0.1:<port>")

private val radiusOracleJson =
    Json {
        explicitNulls = false
    }
