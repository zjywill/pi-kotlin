package works.earendil.pi.ai.providers

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.AssistantError
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.AssistantMessageDiagnostic
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.AssistantStart
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.DiagnosticErrorInfo
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.ProviderHttpRequest
import works.earendil.pi.ai.ProviderResponse
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.TextDelta
import works.earendil.pi.ai.TextEnd
import works.earendil.pi.ai.TextStart
import works.earendil.pi.ai.ThinkingContent
import works.earendil.pi.ai.ThinkingDelta
import works.earendil.pi.ai.ThinkingEnd
import works.earendil.pi.ai.ThinkingStart
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolCallDelta
import works.earendil.pi.ai.ToolCallEnd
import works.earendil.pi.ai.ToolCallStart
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.createAssistantMessageEventStream
import works.earendil.pi.ai.http.executeProviderHttpRequest

class PiMessagesProvider(
    override val id: String,
    override val name: String,
    override val baseUrl: String,
    private val models: List<Model>,
    private val apiKeyEnvNames: List<String>,
    private val client: HttpClient = defaultPiMessagesHttpClient(),
    private val environment: (String) -> String? = System::getenv,
) : Provider {
    override fun getModels(): List<Model> = models

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions,
    ): AssistantMessageEventStream =
        streamPiMessages(
            model = model,
            context = context,
            options = options,
            apiKeyEnvNames = apiKeyEnvNames,
            client = client,
            environment = environment,
        )
}

internal suspend fun streamPiMessages(
    model: Model,
    context: Context,
    options: StreamOptions,
    apiKeyEnvNames: List<String>,
    client: HttpClient,
    environment: (String) -> String? = System::getenv,
): AssistantMessageEventStream {
    val stream = createAssistantMessageEventStream()
    providerScope.launch {
        runCatching {
            executePiMessages(
                model = model,
                context = context,
                options = options,
                apiKeyEnvNames = apiKeyEnvNames,
                client = client,
                environment = environment,
                stream = stream,
            )
        }.onFailure { error ->
            stream.push(piMessagesErrorEvent(model, error))
        }
    }
    return stream
}

internal suspend fun buildPiMessagesRequestBody(
    model: Model,
    context: Context,
    options: StreamOptions,
    environment: (String) -> String? = System::getenv,
): JsonElement {
    val cacheRetention =
        options.cacheRetention
            ?: if ((options.env["PI_CACHE_RETENTION"] ?: environment("PI_CACHE_RETENTION")) == "long") {
                CacheRetention.LONG
            } else {
                null
            }
    val payload =
        buildJsonObject {
            put("model", model.id)
            put("context", piMessagesContext(context))
            put(
                "options",
                buildJsonObject {
                    options.temperature?.let { put("temperature", it) }
                    options.maxTokens?.let { put("maxTokens", it) }
                    options.reasoning?.let {
                        put("reasoning", piMessagesJson.encodeToJsonElement(ThinkingLevel.serializer(), it))
                    }
                    cacheRetention?.let {
                        put("cacheRetention", piMessagesJson.encodeToJsonElement(CacheRetention.serializer(), it))
                    }
                    options.sessionId?.let { put("sessionId", it) }
                    options.toolChoice?.let { put("toolChoice", it) }
                },
            )
        }
    return options.onPayload?.invoke(payload, model) ?: payload
}

private suspend fun executePiMessages(
    model: Model,
    context: Context,
    options: StreamOptions,
    apiKeyEnvNames: List<String>,
    client: HttpClient,
    environment: (String) -> String?,
    stream: AssistantMessageEventStream,
) {
    val apiKey =
        options.apiKey?.takeIf(String::isNotBlank)
            ?: apiKeyEnvNames.firstNotNullOfOrNull { name ->
                options.env[name]?.takeIf(String::isNotBlank)
                    ?: environment(name)?.takeIf(String::isNotBlank)
            }
            ?: error("No API key provided for provider \"${model.provider}\"")
    val requestBody =
        piMessagesJson.encodeToString(
            JsonElement.serializer(),
            buildPiMessagesRequestBody(model, context, options, environment),
        )
    val url =
        buildString {
            append(model.baseUrl.trimEnd('/'))
            append("/messages")
            if (options.debug) {
                append("?debug=1")
            }
        }
    val headers =
        mergedHeaders(
            base =
                mapOf(
                    "authorization" to "Bearer $apiKey",
                    "accept" to "text/event-stream",
                    "content-type" to "application/json",
                ),
            model = model.headers,
            override = options.headers,
        )
    val response =
        executeProviderHttpRequest(
            client = client,
            fetch = options.fetch,
            request =
                ProviderHttpRequest(
                    method = "POST",
                    url = url,
                    headers = headers,
                    body = requestBody.toByteArray(StandardCharsets.UTF_8),
                    timeoutMs = options.timeoutMs,
                ),
        )
    response.use {
        options.onResponse?.invoke(
            ProviderResponse(
                status = it.status,
                headers =
                    it.headers.mapValues { (_, values) ->
                        values.joinToString(", ")
                    },
            ),
            model,
        )
        if (it.status !in 200..299) {
            val body = it.body.readAllBytes().toString(StandardCharsets.UTF_8)
            throw piMessagesResponseError(model, url, it.status, body)
        }

        val converter = PiMessagesEventConverter(model)
        var terminal = false
        withContext(Dispatchers.IO) {
            BufferedReader(InputStreamReader(it.body, StandardCharsets.UTF_8)).use { reader ->
                val lines = mutableListOf<String>()

                fun flush() {
                    val data =
                        lines
                            .firstOrNull { line -> line.startsWith("data:") }
                            ?.removePrefix("data:")
                            ?.trim()
                    lines.clear()
                    if (data.isNullOrEmpty() || data == "[DONE]" || terminal) {
                        return
                    }
                    val event = converter.convert(piMessagesJson.parseToJsonElement(data).jsonObject)
                    stream.push(event)
                    terminal = event is AssistantDone || event is AssistantError
                }

                while (!terminal) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) {
                        flush()
                    } else {
                        lines += line
                    }
                }
                if (!terminal && lines.isNotEmpty()) {
                    flush()
                }
            }
        }
        check(terminal) { "${model.provider} stream ended without a terminal event" }
    }
}

private class PiMessagesEventConverter(
    private val model: Model,
) {
    private val startedAt = System.currentTimeMillis()
    private val blocks = mutableListOf<ContentBlock>()
    private val toolJson = mutableMapOf<Int, String>()
    private var usage = Usage()
    private var stopReason = StopReason.PENDING
    private var responseId: String? = null
    private var errorMessage: String? = null
    private var diagnostics: List<AssistantMessageDiagnostic>? = null

    fun convert(event: JsonObject): works.earendil.pi.ai.AssistantMessageEvent =
        when (event.string("type")) {
            "start" -> AssistantStart(snapshot())
            "text_start" -> {
                val index = event.requiredIndex()
                setBlock(index, TextContent(""))
                TextStart(index, snapshot())
            }

            "text_delta" -> {
                val index = event.requiredIndex()
                val delta = event.string("delta").orEmpty()
                val current = blocks.getOrNull(index) as? TextContent ?: TextContent("")
                setBlock(index, current.copy(text = current.text + delta))
                TextDelta(index, delta, snapshot())
            }

            "text_end" -> {
                val index = event.requiredIndex()
                val content = event.string("content").orEmpty()
                setBlock(
                    index,
                    TextContent(
                        text = content,
                        textSignature = event.string("contentSignature"),
                    ),
                )
                TextEnd(index, content, snapshot())
            }

            "thinking_start" -> {
                val index = event.requiredIndex()
                setBlock(index, ThinkingContent(""))
                ThinkingStart(index, snapshot())
            }

            "thinking_delta" -> {
                val index = event.requiredIndex()
                val delta = event.string("delta").orEmpty()
                val current = blocks.getOrNull(index) as? ThinkingContent ?: ThinkingContent("")
                setBlock(index, current.copy(thinking = current.thinking + delta))
                ThinkingDelta(index, delta, snapshot())
            }

            "thinking_end" -> {
                val index = event.requiredIndex()
                val content = event.string("content").orEmpty()
                setBlock(
                    index,
                    ThinkingContent(
                        thinking = content,
                        thinkingSignature = event.string("contentSignature"),
                        redacted = event.boolean("redacted"),
                    ),
                )
                ThinkingEnd(index, content, snapshot())
            }

            "toolcall_start" -> {
                val index = event.requiredIndex()
                setBlock(
                    index,
                    ToolCall(
                        id = event.string("id").orEmpty(),
                        name = event.string("toolName").orEmpty(),
                        arguments = JsonObject(emptyMap()),
                    ),
                )
                toolJson[index] = ""
                ToolCallStart(index, snapshot())
            }

            "toolcall_delta" -> {
                val index = event.requiredIndex()
                val delta = event.string("delta").orEmpty()
                val json = toolJson.getOrDefault(index, "") + delta
                toolJson[index] = json
                val current =
                    blocks.getOrNull(index) as? ToolCall
                        ?: ToolCall("", "", JsonObject(emptyMap()))
                setBlock(index, current.copy(arguments = parseStreamingJsonObject(json)))
                ToolCallDelta(index, delta, snapshot())
            }

            "toolcall_end" -> {
                val index = event.requiredIndex()
                val toolCall =
                    piMessagesJson.decodeFromJsonElement(
                        ToolCall.serializer(),
                        event["toolCall"] ?: error("pi-messages toolcall_end is missing toolCall"),
                    )
                setBlock(index, toolCall)
                toolJson.remove(index)
                ToolCallEnd(index, toolCall, snapshot())
            }

            "done" -> {
                stopReason = event.stopReason()
                usage = event.usage()
                responseId = event.string("responseId")
                appendRewriteDiagnostic(event["rewrite"] as? JsonObject)
                AssistantDone(stopReason, snapshot())
            }

            "error" -> {
                stopReason = event.stopReason()
                usage = event.usage()
                errorMessage = event.string("errorMessage")
                responseId = event.string("responseId")
                appendRewriteDiagnostic(event["rewrite"] as? JsonObject)
                AssistantError(stopReason, snapshot())
            }

            else -> error("Unknown pi-messages event type: ${event.string("type")}")
        }

    private fun setBlock(
        index: Int,
        block: ContentBlock,
    ) {
        require(index in 0..blocks.size) { "Invalid pi-messages content index: $index" }
        if (index == blocks.size) {
            blocks += block
        } else {
            blocks[index] = block
        }
    }

    private fun appendRewriteDiagnostic(rewrite: JsonObject?) {
        if (rewrite == null) {
            return
        }
        diagnostics =
            diagnostics.orEmpty() +
                AssistantMessageDiagnostic(
                    type = "pi_messages_rewrite",
                    timestamp = System.currentTimeMillis(),
                    details = rewrite,
                )
    }

    private fun snapshot(): AssistantMessage =
        AssistantMessage(
            content = blocks.toList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            responseId = responseId,
            diagnostics = diagnostics,
            usage = usage,
            stopReason = stopReason,
            errorMessage = errorMessage,
            timestamp = startedAt,
        )
}

private class PiMessagesResponseException(
    message: String,
    val code: String?,
    val details: JsonObject,
) : IllegalStateException(message)

private fun piMessagesResponseError(
    model: Model,
    url: String,
    status: Int,
    body: String,
): PiMessagesResponseException {
    val error =
        runCatching {
            piMessagesJson.parseToJsonElement(body).jsonObject["error"] as? JsonObject
        }.getOrNull()
    val message = error?.string("message") ?: body
    val code = error?.string("code")
    val statusText = httpStatusText(status)
    val details =
        buildJsonObject {
            put("version", 1)
            put("provider", model.provider)
            put("model", model.id)
            put("url", url)
            put("status", status)
            put("statusText", statusText)
            if (error != null) {
                put("error", error)
            } else {
                put("body", truncateDiagnosticBody(body))
            }
            put("timestampMs", System.currentTimeMillis())
        }
    return PiMessagesResponseException(
        message =
            "$status $statusText: $message" +
                code?.let { " ($it)" }.orEmpty(),
        code = code,
        details = details,
    )
}

private fun piMessagesErrorEvent(
    model: Model,
    error: Throwable,
): AssistantError {
    val diagnostics =
        if (error is PiMessagesResponseException) {
            listOf(
                AssistantMessageDiagnostic(
                    type = "pi_messages_response_failure",
                    timestamp = System.currentTimeMillis(),
                    error =
                        DiagnosticErrorInfo(
                            name = error::class.simpleName,
                            message = error.message.orEmpty(),
                            stack = error.stackTraceToString(),
                            code = error.code?.let(::JsonPrimitive),
                        ),
                    details = error.details,
                ),
            )
        } else {
            null
        }
    val message =
        AssistantMessage(
            content = emptyList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            diagnostics = diagnostics,
            stopReason = StopReason.ERROR,
            errorMessage = error.message ?: error::class.simpleName.orEmpty(),
        )
    return AssistantError(StopReason.ERROR, message)
}

private fun piMessagesContext(context: Context): JsonObject =
    buildJsonObject {
        context.systemPrompt?.let { put("systemPrompt", it) }
        put(
            "messages",
            JsonArray(
                context.messages.map { message ->
                    piMessagesJson.encodeToJsonElement(Message.serializer(), message)
                },
            ),
        )
        if (context.tools.isNotEmpty()) {
            put(
                "tools",
                JsonArray(
                    context.tools.map { tool ->
                        piMessagesJson.encodeToJsonElement(ToolDefinition.serializer(), tool)
                    },
                ),
            )
        }
    }

private fun parseStreamingJsonObject(value: String): JsonObject {
    if (value.isBlank()) {
        return JsonObject(emptyMap())
    }
    return runCatching {
        piMessagesJson.parseToJsonElement(value) as? JsonObject
    }.getOrNull() ?: JsonObject(emptyMap())
}

private fun JsonObject.requiredIndex(): Int =
    this["contentIndex"]?.jsonPrimitive?.intOrNull
        ?: error("pi-messages event is missing contentIndex")

private fun JsonObject.stopReason(): StopReason =
    when (string("reason")) {
        "stop" -> StopReason.STOP
        "length" -> StopReason.LENGTH
        "toolUse" -> StopReason.TOOL_USE
        "aborted" -> StopReason.ABORTED
        "error" -> StopReason.ERROR
        else -> error("Invalid pi-messages stop reason: ${string("reason")}")
    }

private fun JsonObject.usage(): Usage =
    piMessagesJson.decodeFromJsonElement(
        Usage.serializer(),
        this["usage"] ?: JsonObject(emptyMap()),
    )

private fun JsonObject.boolean(name: String): Boolean? =
    (this[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.contentOrNull
        ?.toBooleanStrictOrNull()

private fun truncateDiagnosticBody(value: String): String =
    if (value.length > MAX_DIAGNOSTIC_BODY_LENGTH) {
        value.take(MAX_DIAGNOSTIC_BODY_LENGTH) + "\u2026"
    } else {
        value
    }

private fun httpStatusText(status: Int): String =
    when (status) {
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        408 -> "Request Timeout"
        409 -> "Conflict"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> ""
    }

private fun defaultPiMessagesHttpClient(): HttpClient =
    HttpClient
        .newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

private const val MAX_DIAGNOSTIC_BODY_LENGTH = 8_192

private val piMessagesJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }
