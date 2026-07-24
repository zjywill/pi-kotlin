package works.earendil.pi.ai.providers

import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.Transport

internal interface OpenAICodexWebSocketConnection {
    val isOpen: Boolean

    suspend fun send(text: String)

    suspend fun receive(timeoutMs: Long?): JsonObject

    fun close(
        code: Int = 1000,
        reason: String = "done",
    )
}

internal fun interface OpenAICodexWebSocketConnector {
    suspend fun connect(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): OpenAICodexWebSocketConnection
}

internal class JavaOpenAICodexWebSocketConnector(
    private val client: HttpClient,
) : OpenAICodexWebSocketConnector {
    override suspend fun connect(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): OpenAICodexWebSocketConnection {
        val listener = JavaOpenAICodexWebSocketListener()
        val builder = client.newWebSocketBuilder()
        if (timeoutMs > 0) {
            builder.connectTimeout(Duration.ofMillis(timeoutMs))
        }
        headers.forEach(builder::header)
        val socket = builder.buildAsync(URI.create(url), listener).await()
        return JavaOpenAICodexWebSocketConnection(socket, listener)
    }
}

private class JavaOpenAICodexWebSocketConnection(
    private val socket: WebSocket,
    private val listener: JavaOpenAICodexWebSocketListener,
) : OpenAICodexWebSocketConnection {
    override val isOpen: Boolean
        get() = listener.isOpen

    override suspend fun send(text: String) {
        check(isOpen) { "OpenAI Codex WebSocket is closed" }
        socket.sendText(text, true).await()
    }

    override suspend fun receive(timeoutMs: Long?): JsonObject {
        val message =
            if (timeoutMs != null && timeoutMs > 0) {
                withTimeoutOrNull(timeoutMs) {
                    listener.messages.receive()
                } ?: run {
                    close(reason = "idle_timeout")
                    throw OpenAICodexWebSocketTransportException(
                        "WebSocket idle timeout after ${timeoutMs}ms",
                    )
                }
            } else {
                listener.messages.receive()
            }
        return message.getOrThrow()
    }

    override fun close(
        code: Int,
        reason: String,
    ) {
        listener.markClosed()
        runCatching { socket.sendClose(code, reason) }
    }
}

private class JavaOpenAICodexWebSocketListener : WebSocket.Listener {
    val messages = Channel<Result<JsonObject>>(Channel.UNLIMITED)
    private val open = AtomicBoolean(false)
    private val text = StringBuilder()

    val isOpen: Boolean
        get() = open.get()

    override fun onOpen(webSocket: WebSocket) {
        open.set(true)
        webSocket.request(1)
    }

    override fun onText(
        webSocket: WebSocket,
        data: CharSequence,
        last: Boolean,
    ): CompletionStage<*>? {
        val complete =
            synchronized(text) {
                text.append(data)
                if (last) {
                    text.toString().also { text.setLength(0) }
                } else {
                    null
                }
            }
        if (complete != null) {
            messages.trySend(
                runCatching {
                    providerJson.parseToJsonElement(complete).jsonObject
                }.recoverCatching { cause ->
                    throw OpenAICodexProtocolException(
                        "Invalid Codex WebSocket JSON: ${cause.message ?: cause::class.simpleName}",
                        cause,
                    )
                },
            )
        }
        webSocket.request(1)
        return null
    }

    override fun onClose(
        webSocket: WebSocket,
        statusCode: Int,
        reason: String,
    ): CompletionStage<*>? {
        open.set(false)
        val suffix =
            buildString {
                append(" ")
                append(statusCode)
                if (reason.isNotBlank()) {
                    append(" ")
                    append(reason)
                } else if (statusCode == 1009) {
                    append(" message too big")
                }
            }
        messages.trySend(
            Result.failure(
                OpenAICodexWebSocketTransportException(
                    "WebSocket closed$suffix",
                ),
            ),
        )
        return null
    }

    override fun onError(
        webSocket: WebSocket,
        error: Throwable,
    ) {
        open.set(false)
        messages.trySend(
            Result.failure(
                OpenAICodexWebSocketTransportException(
                    error.message ?: "WebSocket error",
                    error,
                ),
            ),
        )
    }

    fun markClosed() {
        open.set(false)
    }
}

internal class OpenAICodexWebSocketTransport(
    private val connector: OpenAICodexWebSocketConnector,
    private val scope: CoroutineScope = providerScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val idleTtlMs: Long = OPENAI_CODEX_WEBSOCKET_IDLE_TTL_MS,
    private val maxAgeMs: Long = OPENAI_CODEX_WEBSOCKET_MAX_AGE_MS,
) {
    private val lock = Any()
    private val sessions = mutableMapOf<String, CachedConnection>()
    private val sseFallbackSessions = mutableSetOf<String>()

    suspend fun stream(
        url: String,
        body: JsonObject,
        headers: Map<String, String>,
        transport: Transport,
        cacheSessionId: String?,
        idleTimeoutMs: Long?,
        connectTimeoutMs: Long?,
        onEvent: (JsonObject) -> Unit,
        fallbackToSse: suspend () -> Unit,
    ) {
        if (cacheSessionId != null && isSseFallbackActive(cacheSessionId)) {
            fallbackToSse()
            return
        }

        var retriedConnectionLimit = false
        var retriedMissingContinuation = false
        while (true) {
            var outputStarted = false
            try {
                streamOnce(
                    url = url,
                    body = body,
                    headers = headers,
                    cacheSessionId = cacheSessionId,
                    useCachedContext =
                        transport == Transport.AUTO ||
                            transport == Transport.WEBSOCKET_CACHED,
                    idleTimeoutMs = idleTimeoutMs,
                    connectTimeoutMs =
                        connectTimeoutMs
                            ?.takeIf { it > 0 }
                            ?: DEFAULT_OPENAI_CODEX_WEBSOCKET_CONNECT_TIMEOUT_MS,
                ) { event ->
                    outputStarted = true
                    onEvent(event)
                }
                return
            } catch (error: Throwable) {
                val connectionLimit =
                    !outputStarted &&
                        error is OpenAICodexApiException &&
                        error.code == OPENAI_CODEX_WEBSOCKET_CONNECTION_LIMIT_CODE
                val missingContinuation =
                    error is OpenAICodexApiException &&
                        error.code == OPENAI_CODEX_PREVIOUS_RESPONSE_NOT_FOUND_CODE
                if (missingContinuation && !retriedMissingContinuation) {
                    retriedMissingContinuation = true
                    continue
                }
                if (connectionLimit && !retriedConnectionLimit) {
                    retriedConnectionLimit = true
                    continue
                }
                if (
                    error is OpenAICodexApiException && !connectionLimit ||
                    error is OpenAICodexProtocolException
                ) {
                    throw error
                }
                if (outputStarted) {
                    throw error
                }
                cacheSessionId?.let(::recordSseFallback)
                fallbackToSse()
                return
            }
        }
    }

    fun closeSessions(sessionId: String? = null) {
        val entries =
            synchronized(lock) {
                if (sessionId != null) {
                    listOfNotNull(sessions.remove(sessionId))
                } else {
                    sessions.values.toList().also { sessions.clear() }
                }
            }
        entries.forEach { entry ->
            entry.idleJob?.cancel()
            entry.connection.close(reason = "session_cleanup")
        }
    }

    internal fun isSseFallbackActive(sessionId: String): Boolean =
        synchronized(lock) { sessionId in sseFallbackSessions }

    private suspend fun streamOnce(
        url: String,
        body: JsonObject,
        headers: Map<String, String>,
        cacheSessionId: String?,
        useCachedContext: Boolean,
        idleTimeoutMs: Long?,
        connectTimeoutMs: Long,
        onEvent: (JsonObject) -> Unit,
    ) {
        val lease =
            acquire(
                url = url,
                headers = headers,
                sessionId = cacheSessionId,
                connectTimeoutMs = connectTimeoutMs,
            )
        var keepConnection = true
        try {
            val requestBody =
                if (useCachedContext && lease.entry != null) {
                    buildCachedRequestBody(lease.entry, body)
                } else {
                    body
                }
            lease.connection.send(
                providerJson.encodeToString(
                    JsonObject.serializer(),
                    JsonObject(
                        mapOf("type" to JsonPrimitive("response.create")) +
                            requestBody,
                    ),
                ),
            )

            val output = ResponseOutputCapture()
            var responseId: String? = null
            while (true) {
                val event = checkedCodexEvent(lease.connection.receive(idleTimeoutMs)) ?: continue
                val type = event.string("type")
                if (type == "response.created") {
                    responseId = event.obj("response")?.string("id") ?: responseId
                }
                if (type == "response.output_item.done") {
                    event.obj("item")?.let(output::add)
                }
                if (type in OPENAI_CODEX_TERMINAL_EVENT_TYPES) {
                    val response = event.obj("response")
                    responseId = response?.string("id") ?: responseId
                    if (output.isEmpty()) {
                        response?.array("output")
                            ?.mapNotNull { it as? JsonObject }
                            ?.forEach(output::add)
                    }
                }
                onEvent(event)
                if (type in OPENAI_CODEX_TERMINAL_EVENT_TYPES) {
                    if (useCachedContext && lease.entry != null && responseId != null) {
                        lease.entry.continuation =
                            CachedContinuation(
                                lastRequestBody = body,
                                lastResponseId = responseId,
                                lastResponseItems = output.toResponseInput(),
                            )
                    }
                    break
                }
            }
        } catch (error: Throwable) {
            lease.entry?.continuation = null
            keepConnection = false
            throw error
        } finally {
            release(lease, keepConnection)
        }
    }

    private suspend fun acquire(
        url: String,
        headers: Map<String, String>,
        sessionId: String?,
        connectTimeoutMs: Long,
    ): ConnectionLease {
        if (sessionId == null) {
            return ConnectionLease(
                connection =
                    connector.connect(
                        url,
                        headers,
                        connectTimeoutMs,
                    ),
                sessionId = null,
                entry = null,
            )
        }

        val plan =
            synchronized(lock) {
                val cached = sessions[sessionId]
                when {
                    cached == null -> AcquirePlan.Connect(cache = true)
                    cached.busy -> AcquirePlan.Connect(cache = false)
                    nowMillis() - cached.createdAt >= maxAgeMs -> {
                        sessions.remove(sessionId)
                        cached.idleJob?.cancel()
                        AcquirePlan.Connect(cache = true, stale = cached.connection)
                    }

                    !cached.connection.isOpen -> {
                        sessions.remove(sessionId)
                        cached.idleJob?.cancel()
                        AcquirePlan.Connect(cache = true, stale = cached.connection)
                    }

                    else -> {
                        cached.idleJob?.cancel()
                        cached.idleJob = null
                        cached.busy = true
                        AcquirePlan.Reuse(cached)
                    }
                }
            }
        if (plan is AcquirePlan.Reuse) {
            return ConnectionLease(plan.entry.connection, sessionId, plan.entry)
        }
        plan as AcquirePlan.Connect
        plan.stale?.close(reason = "connection_age_limit")
        val connection =
            connector.connect(
                url,
                headers,
                connectTimeoutMs,
            )
        if (!plan.cache) {
            return ConnectionLease(connection, null, null)
        }
        val entry =
            CachedConnection(
                connection = connection,
                busy = true,
                createdAt = nowMillis(),
            )
        val installed =
            synchronized(lock) {
                if (sessions[sessionId] == null) {
                    sessions[sessionId] = entry
                    true
                } else {
                    false
                }
            }
        return if (installed) {
            ConnectionLease(connection, sessionId, entry)
        } else {
            ConnectionLease(connection, null, null)
        }
    }

    private fun release(
        lease: ConnectionLease,
        keep: Boolean,
    ) {
        val entry = lease.entry
        val sessionId = lease.sessionId
        if (entry == null || sessionId == null) {
            lease.connection.close()
            return
        }
        var close = false
        synchronized(lock) {
            if (!keep || !entry.connection.isOpen || sessions[sessionId] !== entry) {
                if (sessions[sessionId] === entry) {
                    sessions.remove(sessionId)
                }
                entry.idleJob?.cancel()
                close = true
            } else {
                entry.busy = false
                entry.idleJob?.cancel()
                entry.idleJob =
                    scope.launch {
                        delay(idleTtlMs)
                        val expired =
                            synchronized(lock) {
                                if (!entry.busy && sessions[sessionId] === entry) {
                                    sessions.remove(sessionId)
                                    true
                                } else {
                                    false
                                }
                            }
                        if (expired) {
                            entry.connection.close(reason = "idle_timeout")
                        }
                    }
            }
        }
        if (close) {
            entry.connection.close()
        }
    }

    private fun buildCachedRequestBody(
        entry: CachedConnection,
        body: JsonObject,
    ): JsonObject {
        val continuation = entry.continuation ?: return body
        val delta = cachedInputDelta(body, continuation)
        if (delta == null) {
            entry.continuation = null
            return body
        }
        return JsonObject(
            body +
                mapOf(
                    "previous_response_id" to JsonPrimitive(continuation.lastResponseId),
                    "input" to delta,
                ),
        )
    }

    private fun cachedInputDelta(
        body: JsonObject,
        continuation: CachedContinuation,
    ): JsonArray? {
        val comparableBody =
            JsonObject(body.filterKeys { it != "input" && it != "previous_response_id" })
        val comparablePrevious =
            JsonObject(
                continuation.lastRequestBody.filterKeys {
                    it != "input" && it != "previous_response_id"
                },
            )
        if (comparableBody != comparablePrevious) {
            return null
        }
        val currentInput = body.array("input") ?: JsonArray(emptyList())
        val previousInput =
            continuation.lastRequestBody.array("input") ?: JsonArray(emptyList())
        val baseline = previousInput + continuation.lastResponseItems
        if (currentInput.size < baseline.size) {
            return null
        }
        if (currentInput.take(baseline.size) != baseline) {
            return null
        }
        return JsonArray(currentInput.drop(baseline.size))
    }

    private fun recordSseFallback(sessionId: String) {
        synchronized(lock) {
            sseFallbackSessions += sessionId
        }
    }

    private data class CachedConnection(
        val connection: OpenAICodexWebSocketConnection,
        var busy: Boolean,
        val createdAt: Long,
        var idleJob: Job? = null,
        var continuation: CachedContinuation? = null,
    )

    private data class CachedContinuation(
        val lastRequestBody: JsonObject,
        val lastResponseId: String,
        val lastResponseItems: JsonArray,
    )

    private data class ConnectionLease(
        val connection: OpenAICodexWebSocketConnection,
        val sessionId: String?,
        val entry: CachedConnection?,
    )

    private sealed interface AcquirePlan {
        data class Reuse(
            val entry: CachedConnection,
        ) : AcquirePlan

        data class Connect(
            val cache: Boolean,
            val stale: OpenAICodexWebSocketConnection? = null,
        ) : AcquirePlan
    }
}

private class ResponseOutputCapture {
    private val items = mutableListOf<JsonObject>()

    fun add(item: JsonObject) {
        items += item
    }

    fun isEmpty(): Boolean = items.isEmpty()

    fun toResponseInput(): JsonArray {
        val text =
            items
                .filter { it.string("type") == "message" }
                .flatMap { it.array("content").orEmpty() }
                .joinToString("") { content ->
                    content.jsonObject.string("text")
                        ?: content.jsonObject.string("refusal").orEmpty()
                }
        return buildJsonArray {
            if (text.isNotEmpty()) {
                add(
                    buildJsonObject {
                        put("role", "assistant")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "output_text")
                                        put("text", text)
                                    },
                                )
                            },
                        )
                    },
                )
            }
            items.forEach { item ->
                when (item.string("type")) {
                    "function_call" ->
                        add(
                            buildJsonObject {
                                put("type", "function_call")
                                put("call_id", item.string("call_id").orEmpty())
                                item.string("id")
                                    ?.takeIf { it.startsWith("fc_") }
                                    ?.let { put("id", it) }
                                put("name", item.string("name").orEmpty())
                                put("arguments", item.string("arguments").orEmpty())
                            },
                        )

                    "custom_tool_call" ->
                        add(
                            buildJsonObject {
                                put("type", "custom_tool_call")
                                put("call_id", item.string("call_id").orEmpty())
                                item.string("id")?.let { put("id", it) }
                                put("name", item.string("name").orEmpty())
                                put("input", item.string("input").orEmpty())
                            },
                        )
                }
            }
        }
    }
}

private fun checkedCodexEvent(event: JsonObject): JsonObject? {
    val type = event.string("type") ?: return null
    if (type == "error") {
        val nested = event.obj("error")
        val code = event.string("code") ?: nested?.string("code")
        val message = event.string("message") ?: nested?.string("message")
        throw OpenAICodexApiException(
            message = "Codex error: ${message ?: code ?: event}",
            code = code,
        )
    }
    if (type == "response.failed") {
        val error = event.obj("response")?.obj("error")
        throw OpenAICodexApiException(
            message = error?.string("message") ?: "Codex response failed",
            code = error?.string("code"),
        )
    }
    return event
}

internal class OpenAICodexApiException(
    message: String,
    val code: String?,
) : IllegalStateException(message)

internal class OpenAICodexProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class OpenAICodexWebSocketTransportException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal val OPENAI_CODEX_TERMINAL_EVENT_TYPES =
    setOf(
        "response.completed",
        "response.incomplete",
        "response.done",
    )

private const val DEFAULT_OPENAI_CODEX_WEBSOCKET_CONNECT_TIMEOUT_MS = 15_000L
private const val OPENAI_CODEX_WEBSOCKET_IDLE_TTL_MS = 5 * 60 * 1_000L
private const val OPENAI_CODEX_WEBSOCKET_MAX_AGE_MS = 55 * 60 * 1_000L
private const val OPENAI_CODEX_WEBSOCKET_CONNECTION_LIMIT_CODE =
    "websocket_connection_limit_reached"
private const val OPENAI_CODEX_PREVIOUS_RESPONSE_NOT_FOUND_CODE =
    "previous_response_not_found"
