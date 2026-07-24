package works.earendil.pi.ai.providers

import com.github.luben.zstd.Zstd
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelThinkingLevel
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.Transport
import works.earendil.pi.ai.createGrammarToolInputProperties
import works.earendil.pi.ai.http.postSse
import works.earendil.pi.ai.resolveGrammarConstrainedSampling

class OpenAICodexProvider private constructor(
    override val id: String,
    override val name: String,
    private val models: List<Model>,
    private val apiKeyEnvNames: List<String>,
    private val client: HttpClient,
    private val userAgent: () -> String,
    websocketDependencies: OpenAICodexWebSocketDependencies,
) : Provider {
    override val oauth: OAuthAuth = OpenAICodexOAuth()

    constructor(
        id: String,
        name: String,
        models: List<Model>,
        apiKeyEnvNames: List<String> = DEFAULT_OPENAI_CODEX_API_KEY_ENV_NAMES,
        client: HttpClient = HttpClient.newHttpClient(),
        userAgent: () -> String = ::defaultOpenAICodexUserAgent,
    ) : this(
        id = id,
        name = name,
        models = models,
        apiKeyEnvNames = apiKeyEnvNames,
        client = client,
        userAgent = userAgent,
        websocketDependencies =
            OpenAICodexWebSocketDependencies(
                JavaOpenAICodexWebSocketConnector(client),
            ),
    )

    internal constructor(
        id: String,
        name: String,
        models: List<Model>,
        websocketConnector: OpenAICodexWebSocketConnector,
        apiKeyEnvNames: List<String> = DEFAULT_OPENAI_CODEX_API_KEY_ENV_NAMES,
        client: HttpClient = HttpClient.newHttpClient(),
        userAgent: () -> String = ::defaultOpenAICodexUserAgent,
    ) : this(
        id = id,
        name = name,
        models = models,
        apiKeyEnvNames = apiKeyEnvNames,
        client = client,
        userAgent = userAgent,
        websocketDependencies = OpenAICodexWebSocketDependencies(websocketConnector),
    )

    override val baseUrl: String? = models.firstOrNull()?.baseUrl

    private val responses =
        OpenAIResponsesProvider(
            id = id,
            name = name,
            baseUrl = baseUrl ?: DEFAULT_OPENAI_CODEX_BASE_URL,
            models = models,
            apiKeyEnvNames = apiKeyEnvNames,
            client = client,
        )
    private val websocketTransport =
        OpenAICodexWebSocketTransport(websocketDependencies.connector)

    override fun getModels(): List<Model> = models

    internal fun closeWebSocketSessions(sessionId: String? = null) {
        websocketTransport.closeSessions(sessionId)
    }

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions,
    ): AssistantMessageEventStream =
        responses.streamWithRequest(model, context, options) {
            val token = resolveApiKey(id, options.apiKey, options.env, apiKeyEnvNames)
            val accountId = extractOpenAICodexAccountId(token)
            val sessionId = openAICodexSessionId(options)
            val cacheSessionId =
                options.sessionId?.takeIf {
                    options.cacheRetention != CacheRetention.NONE
                }
            val url = resolveOpenAICodexUrl(model.baseUrl)
            val body = buildOpenAICodexRequestBody(model, context, options, responses)
            val sseHeaders =
                openAICodexSseHeaders(
                    model = model,
                    options = options,
                    accountId = accountId,
                    token = token,
                    sessionId = sessionId,
                    userAgent = userAgent(),
                )
            OpenAIResponsesHttpRequest(
                url = url,
                modelId = model.id,
                headers = sseHeaders,
                body = body,
                headersAreFinal = true,
                stopAfterTerminal = true,
                encodeBody = ::compressOpenAICodexRequest,
                usageCostMultiplier = { response ->
                    openAICodexServiceTierCostMultiplier(
                        model = model,
                        resolveOpenAICodexServiceTier(
                            response.string("service_tier"),
                            options.serviceTier,
                        ),
                    )
                },
                eventStream =
                    if (options.transport == Transport.SSE) {
                        null
                    } else {
                        { requestBody, onEvent ->
                            val requestId = sessionId ?: UUID.randomUUID().toString()
                            websocketTransport.stream(
                                url = resolveOpenAICodexWebSocketUrl(model.baseUrl),
                                body = requestBody,
                                headers =
                                    openAICodexWebSocketHeaders(
                                        model = model,
                                        options = options,
                                        accountId = accountId,
                                        token = token,
                                        requestId = requestId,
                                        userAgent = userAgent(),
                                    ),
                                transport = options.transport,
                                cacheSessionId = cacheSessionId,
                                idleTimeoutMs = options.timeoutMs,
                                connectTimeoutMs = options.websocketConnectTimeoutMs,
                                onEvent = onEvent,
                                fallbackToSse = {
                                    streamOpenAICodexSse(
                                        client = client,
                                        url = url,
                                        body = requestBody,
                                        headers = sseHeaders,
                                        options = options,
                                        onEvent = onEvent,
                                    )
                                },
                            )
                        }
                    },
            )
        }

    override suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream {
        val apiKey = resolveApiKey(id, options.stream.apiKey, options.stream.env, apiKeyEnvNames)
        return stream(
            model,
            context,
            openAICodexSimpleStreamOptions(model, options).copy(
                apiKey = apiKey,
            ),
        )
    }
}

internal fun openAICodexSimpleStreamOptions(
    model: Model,
    options: SimpleStreamOptions,
): StreamOptions {
    val reasoningEffort =
        options.reasoning?.let { requested ->
            val clamped = model.clampThinkingLevel(requested)
            model.thinkingLevelMap[clamped] ?: clamped.name.lowercase()
        }
    return options.stream.copy(
        reasoningEffort = reasoningEffort,
        thinkingBudgets = options.thinkingBudgets,
    )
}

internal fun buildOpenAICodexRequestBody(
    model: Model,
    context: Context,
    options: StreamOptions,
): JsonObject {
    val responses =
        OpenAIResponsesProvider(
            id = model.provider,
            name = model.provider,
            baseUrl = model.baseUrl,
            models = listOf(model),
            apiKeyEnvNames = emptyList(),
        )
    return buildOpenAICodexRequestBody(model, context, options, responses)
}

private fun buildOpenAICodexRequestBody(
    model: Model,
    context: Context,
    options: StreamOptions,
    responses: OpenAIResponsesProvider,
): JsonObject {
    val supportsOpenAIGrammarTools =
        model.compat?.codexBooleanValue("supportsOpenAIGrammarTools") ?: false
    val supportsStrictMode = model.compat?.codexBooleanValue("supportsStrictMode") ?: true
    val grammarToolInputProperties =
        createGrammarToolInputProperties(context.tools, supportsOpenAIGrammarTools)
    val input =
        responses.responseInput(
            model = model,
            context = context,
            grammarToolInputProperties = grammarToolInputProperties,
            includeSystemPrompt = false,
        )
    val sessionId = openAICodexSessionId(options)
    return buildJsonObject {
        put("model", model.id)
        put("store", false)
        put("stream", true)
        put("instructions", context.systemPrompt ?: DEFAULT_OPENAI_CODEX_INSTRUCTIONS)
        put("input", input)
        put(
            "text",
            buildJsonObject {
                put("verbosity", options.textVerbosity ?: "low")
            },
        )
        put("include", JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content"))))
        sessionId?.let { put("prompt_cache_key", it) }
        put("tool_choice", (options.toolChoice as? JsonPrimitive)?.content ?: "auto")
        put("parallel_tool_calls", true)
        options.temperature?.let { put("temperature", it) }
        options.serviceTier?.let { put("service_tier", it) }
        if (context.tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    context.tools.forEach { tool ->
                        val grammar =
                            resolveGrammarConstrainedSampling(tool, supportsOpenAIGrammarTools)
                        add(
                            if (grammar != null) {
                                buildJsonObject {
                                    put("type", "custom")
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put(
                                        "format",
                                        buildJsonObject {
                                            put("type", "grammar")
                                            put("syntax", grammar.format)
                                            put("definition", grammar.definition)
                                        },
                                    )
                                }
                            } else {
                                buildJsonObject {
                                    put("type", "function")
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.parameters)
                                    if (supportsStrictMode) {
                                        put("strict", JsonNull)
                                    }
                                }
                            },
                        )
                    }
                },
            )
        }
        openAICodexReasoning(model, options)?.let { put("reasoning", it) }
    }
}

internal fun resolveOpenAICodexUrl(baseUrl: String?): String {
    val normalized =
        baseUrl
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.trimEnd('/')
            ?: DEFAULT_OPENAI_CODEX_BASE_URL
    return when {
        normalized.endsWith("/codex/responses") -> normalized
        normalized.endsWith("/codex") -> "$normalized/responses"
        else -> "$normalized/codex/responses"
    }
}

internal fun resolveOpenAICodexWebSocketUrl(baseUrl: String?): String =
    resolveOpenAICodexUrl(baseUrl)
        .let { url ->
            when {
                url.startsWith("https://") -> "wss://${url.removePrefix("https://")}"
                url.startsWith("http://") -> "ws://${url.removePrefix("http://")}"
                else -> url
            }
        }

internal fun extractOpenAICodexAccountId(token: String): String {
    val parts = token.split('.')
    require(parts.size == 3) { "Failed to extract accountId from token" }
    return runCatching {
        val payloadBytes = Base64.getUrlDecoder().decode(parts[1].withBase64Padding())
        val payload =
            providerJson
                .parseToJsonElement(payloadBytes.toString(StandardCharsets.UTF_8))
                .jsonObject
        payload
            .getValue(OPENAI_CODEX_JWT_CLAIM)
            .jsonObject
            .string("chatgpt_account_id")
            ?.takeIf(String::isNotBlank)
            ?: error("No account ID in token")
    }.getOrElse {
        error("Failed to extract accountId from token")
    }
}

internal fun openAICodexSseHeaders(
    model: Model,
    options: StreamOptions,
    accountId: String,
    token: String,
    sessionId: String? = openAICodexSessionId(options),
    userAgent: String = defaultOpenAICodexUserAgent(),
): Map<String, String> {
    val custom = mergedHeaders(emptyMap(), model.headers, options.headers)
    val mandatory =
        linkedMapOf(
            "authorization" to "Bearer $token",
            "chatgpt-account-id" to accountId,
            "originator" to "pi",
            "user-agent" to userAgent,
            "openai-beta" to "responses=experimental",
            "accept" to "text/event-stream",
            "content-type" to "application/json",
            "content-encoding" to "zstd",
        )
    sessionId?.let {
        mandatory["session-id"] = it
        mandatory["x-client-request-id"] = it
    }
    return mergedHeaders(custom, mandatory, emptyMap())
}

internal fun openAICodexWebSocketHeaders(
    model: Model,
    options: StreamOptions,
    accountId: String,
    token: String,
    requestId: String,
    userAgent: String = defaultOpenAICodexUserAgent(),
): Map<String, String> {
    val custom =
        mergedHeaders(emptyMap(), model.headers, options.headers)
            .filterKeys { name ->
                !name.equals("accept", ignoreCase = true) &&
                    !name.equals("content-type", ignoreCase = true) &&
                    !name.equals("openai-beta", ignoreCase = true)
            }
    val mandatory =
        linkedMapOf(
            "authorization" to "Bearer $token",
            "chatgpt-account-id" to accountId,
            "originator" to "pi",
            "user-agent" to userAgent,
            "openai-beta" to OPENAI_CODEX_WEBSOCKET_BETA,
            "x-client-request-id" to requestId,
            "session-id" to requestId,
        )
    return mergedHeaders(custom, mandatory, emptyMap())
}

internal fun openAICodexServiceTierCostMultiplier(
    model: Model,
    serviceTier: String?,
): Double =
    when (serviceTier) {
        "flex" -> 0.5
        "priority" -> if (model.id == "gpt-5.5") 2.5 else 2.0
        else -> 1.0
    }

private fun openAICodexReasoning(
    model: Model,
    options: StreamOptions,
): JsonObject? {
    val requested = options.reasoningEffort ?: return null
    val effort =
        if (requested == "none") {
            if (model.thinkingLevelMap.containsKey(ModelThinkingLevel.OFF)) {
                model.thinkingLevelMap[ModelThinkingLevel.OFF]
            } else {
                "none"
            }
        } else {
            val level =
                ModelThinkingLevel.entries.firstOrNull {
                    it.name.equals(requested, ignoreCase = true)
                }
            level?.let { model.thinkingLevelMap[it] ?: requested } ?: requested
        }
    return effort?.let {
        buildJsonObject {
            put("effort", it)
            put("summary", options.reasoningSummary ?: "auto")
        }
    }
}

private fun openAICodexSessionId(options: StreamOptions): String? =
    options.sessionId
        ?.takeIf { options.cacheRetention != CacheRetention.NONE }
        ?.take(OPENAI_CODEX_SESSION_ID_LIMIT)

private fun compressOpenAICodexRequest(body: String): ByteArray =
    Zstd.compress(body.toByteArray(StandardCharsets.UTF_8), OPENAI_CODEX_ZSTD_LEVEL)

private suspend fun streamOpenAICodexSse(
    client: HttpClient,
    url: String,
    body: JsonObject,
    headers: Map<String, String>,
    options: StreamOptions,
    onEvent: (JsonObject) -> Unit,
) {
    var sawTerminal = false
    postSse(
        client = client,
        url = url,
        body =
            compressOpenAICodexRequest(
                providerJson.encodeToString(JsonObject.serializer(), body),
            ),
        headers = headers,
        timeoutMs = options.timeoutMs,
        maxRetries = options.maxRetries,
        maxRetryDelayMs = options.maxRetryDelayMs,
        shouldStop = { sawTerminal },
    ) { sse ->
        if (sse.data.isBlank() || sse.data == "[DONE]") {
            return@postSse
        }
        val event = providerJson.parseToJsonElement(sse.data).jsonObject
        onEvent(event)
        sawTerminal =
            event.string("type") in OPENAI_CODEX_TERMINAL_EVENT_TYPES
    }
}

private fun resolveOpenAICodexServiceTier(
    response: String?,
    request: String?,
): String? =
    if (response == "default" && request in setOf("flex", "priority")) {
        request
    } else {
        response ?: request
    }

private fun defaultOpenAICodexUserAgent(): String {
    val os = System.getProperty("os.name").orEmpty()
    val release = System.getProperty("os.version").orEmpty()
    val arch = System.getProperty("os.arch").orEmpty()
    return "pi ($os $release; $arch)"
}

private fun String.withBase64Padding(): String =
    this + "=".repeat((4 - length % 4) % 4)

private fun JsonObject.codexBooleanValue(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private const val DEFAULT_OPENAI_CODEX_BASE_URL = "https://chatgpt.com/backend-api"
private const val DEFAULT_OPENAI_CODEX_INSTRUCTIONS = "You are a helpful assistant."
private const val OPENAI_CODEX_JWT_CLAIM = "https://api.openai.com/auth"
private const val OPENAI_CODEX_SESSION_ID_LIMIT = 64
private const val OPENAI_CODEX_ZSTD_LEVEL = 3
private const val OPENAI_CODEX_WEBSOCKET_BETA = "responses_websockets=2026-02-06"
private val DEFAULT_OPENAI_CODEX_API_KEY_ENV_NAMES =
    listOf(
        "OPENAI_CODEX_TOKEN",
        "OPENAI_CODEX_API_KEY",
    )

private data class OpenAICodexWebSocketDependencies(
    val connector: OpenAICodexWebSocketConnector,
)
