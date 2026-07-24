package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantError
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.Transport
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAICodexWebSocketTest {
    @Test
    fun `streams over websocket with Codex headers and response create frame`() =
        runTest {
            val connection =
                ScriptedConnection(
                    listOf(
                        textResponse("resp-1", "Hello"),
                    ),
                )
            val connector = RecordingConnector(connection)
            val model = codexModel()
            val result =
                provider(model, connector)
                    .stream(
                        model,
                        Context(messages = mutableListOf(UserMessage("hello", timestamp = 1))),
                        StreamOptions(
                            apiKey = codexToken("acc-ws"),
                            transport = Transport.AUTO,
                            sessionId = "session-ws",
                        ),
                    ).result()

            assertEquals(StopReason.STOP, result.stopReason, result.errorMessage)
            assertEquals("Hello", (result.content.single() as TextContent).text)
            assertEquals("resp-1", result.responseId)
            assertEquals(1, connector.attempts)
            assertEquals(
                "wss://chatgpt.com/backend-api/codex/responses",
                connector.urls.single(),
            )
            assertEquals("Bearer ${codexToken("acc-ws")}", connector.headers.single()["authorization"])
            assertEquals("acc-ws", connector.headers.single()["chatgpt-account-id"])
            assertEquals(
                "responses_websockets=2026-02-06",
                connector.headers.single()["openai-beta"],
            )
            assertEquals("session-ws", connector.headers.single()["session-id"])
            assertEquals("session-ws", connector.headers.single()["x-client-request-id"])
            assertEquals("response.create", connection.sent.single().getValue("type").jsonPrimitive.content)
            assertEquals("gpt-5.5", connection.sent.single().getValue("model").jsonPrimitive.content)
            assertFalse(connection.closed)
        }

    @Test
    fun `closes one-shot websocket connections without cache affinity`() =
        runTest {
            val first = ScriptedConnection(listOf(listOf(completed("resp-1"))))
            val second = ScriptedConnection(listOf(listOf(completed("resp-2"))))
            val connector = RecordingConnector(first, second)
            val model = codexModel()
            val provider = provider(model, connector)
            val options =
                StreamOptions(
                    apiKey = codexToken("acc-one-shot"),
                    transport = Transport.AUTO,
                    cacheRetention = CacheRetention.NONE,
                    sessionId = "ignored",
                )

            provider
                .stream(
                    model,
                    Context(messages = mutableListOf(UserMessage("one"))),
                    options,
                ).result()
            provider
                .stream(
                    model,
                    Context(messages = mutableListOf(UserMessage("two"))),
                    options,
                ).result()

            assertEquals(2, connector.attempts)
            assertTrue(first.closed)
            assertTrue(second.closed)
            assertFalse("prompt_cache_key" in first.sent.single())
            assertFalse("prompt_cache_key" in second.sent.single())
        }

    @Test
    fun `reuses cached websocket and sends only continuation input delta`() =
        runTest {
            val connection =
                ScriptedConnection(
                    listOf(
                        functionCallResponse("resp-1"),
                        listOf(completed("resp-2")),
                    ),
                )
            val connector = RecordingConnector(connection)
            val model = codexModel()
            val provider = provider(model, connector)
            val firstContext =
                Context(
                    messages = mutableListOf(UserMessage("Use the tool", timestamp = 1)),
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
                    apiKey = codexToken("acc-cache"),
                    transport = Transport.WEBSOCKET_CACHED,
                    sessionId = "session-cache",
                )
            val first = provider.stream(model, firstContext, options).result()
            val secondContext =
                firstContext.copy(
                    messages =
                        mutableListOf(
                            *firstContext.messages.toTypedArray(),
                            first,
                            ToolResultMessage(
                                toolCallId = "call-1|fc-1",
                                toolName = "echo",
                                content = listOf(TextContent("real result")),
                                isError = false,
                                timestamp = 2,
                            ),
                            UserMessage("Now finish", timestamp = 3),
                        ),
                )

            val second = provider.stream(model, secondContext, options).result()

            assertEquals(StopReason.STOP, second.stopReason, second.errorMessage)
            assertEquals(1, connector.attempts)
            assertEquals(2, connection.sent.size)
            val firstBody = connection.sent[0]
            val secondBody = connection.sent[1]
            assertNull(firstBody["previous_response_id"])
            assertEquals(
                "resp-1",
                secondBody.getValue("previous_response_id").jsonPrimitive.content,
            )
            assertEquals(
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", "call-1")
                            put("output", "real result")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", "input_text")
                                            put("text", "Now finish")
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
                secondBody.getValue("input"),
            )
        }

    @Test
    fun `explicit websocket sends full context on a reused connection`() =
        runTest {
            val connection =
                ScriptedConnection(
                    listOf(
                        textResponse("resp-1", "Hello"),
                        listOf(completed("resp-2")),
                    ),
                )
            val connector = RecordingConnector(connection)
            val model = codexModel()
            val provider = provider(model, connector)
            val firstContext =
                Context(messages = mutableListOf(UserMessage("Hello", timestamp = 1)))
            val first =
                provider
                    .stream(
                        model,
                        firstContext,
                        StreamOptions(
                            apiKey = codexToken("acc-full"),
                            transport = Transport.AUTO,
                            sessionId = "session-full",
                        ),
                    ).result()
            val secondContext =
                Context(
                    messages =
                        mutableListOf(
                            *firstContext.messages.toTypedArray(),
                            first,
                            UserMessage("Again", timestamp = 2),
                        ),
                )

            provider
                .stream(
                    model,
                    secondContext,
                    StreamOptions(
                        apiKey = codexToken("acc-full"),
                        transport = Transport.WEBSOCKET,
                        sessionId = "session-full",
                    ),
                ).result()

            assertEquals(1, connector.attempts)
            assertNull(connection.sent[1]["previous_response_id"])
            assertEquals(3, connection.sent[1].getValue("input").jsonArray.size)
        }

    @Test
    fun `retries connection limit once before output starts`() =
        runTest {
            val limited =
                ScriptedConnection(
                    listOf(
                        listOf(
                            errorEvent(
                                "websocket_connection_limit_reached",
                                "Too many connections",
                            ),
                        ),
                    ),
                )
            val recovered = ScriptedConnection(listOf(listOf(completed("resp-ok"))))
            val connector = RecordingConnector(limited, recovered)
            val model = codexModel()
            val result =
                provider(model, connector)
                    .stream(
                        model,
                        Context(),
                        StreamOptions(apiKey = codexToken("acc-limit")),
                    ).result()

            assertEquals(StopReason.STOP, result.stopReason, result.errorMessage)
            assertEquals(2, connector.attempts)
            assertTrue(limited.closed)
        }

    @Test
    fun `retries missing continuation with full context on a new connection`() =
        runTest {
            val firstConnection =
                ScriptedConnection(
                    listOf(
                        textResponse("resp-1", "Hello"),
                        listOf(
                            errorEvent(
                                "previous_response_not_found",
                                "Previous response not found",
                            ),
                        ),
                    ),
                )
            val secondConnection =
                ScriptedConnection(
                    listOf(
                        textResponse("resp-2", "Recovered"),
                    ),
                )
            val connector = RecordingConnector(firstConnection, secondConnection)
            val model = codexModel()
            val provider = provider(model, connector)
            val options =
                StreamOptions(
                    apiKey = codexToken("acc-missing"),
                    transport = Transport.WEBSOCKET_CACHED,
                    sessionId = "session-missing",
                )
            val firstContext =
                Context(messages = mutableListOf(UserMessage("Hello", timestamp = 1)))
            val first = provider.stream(model, firstContext, options).result()
            val second =
                provider
                    .stream(
                        model,
                        Context(
                            messages =
                                mutableListOf(
                                    *firstContext.messages.toTypedArray(),
                                    first,
                                    UserMessage("Again", timestamp = 2),
                                ),
                        ),
                        options,
                    ).result()

            assertEquals("Recovered", (second.content.single() as TextContent).text)
            assertEquals(2, connector.attempts)
            assertEquals(
                "resp-1",
                firstConnection.sent[1]
                    .getValue("previous_response_id").jsonPrimitive.content,
            )
            assertNull(secondConnection.sent.single()["previous_response_id"])
            assertEquals(3, secondConnection.sent.single().getValue("input").jsonArray.size)
        }

    @Test
    fun `falls back before output and keeps SSE sticky for the cache session`() =
        runTest {
            val requests = AtomicInteger()
            val connectAttempts = AtomicInteger()
            val server = sseServer(requests)
            try {
                val connector =
                    OpenAICodexWebSocketConnector { _, _, _ ->
                        connectAttempts.incrementAndGet()
                        throw IOException("connect failed")
                    }
                val model =
                    codexModel(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                    )
                val provider = provider(model, connector)
                val options =
                    StreamOptions(
                        apiKey = codexToken("acc-fallback"),
                        transport = Transport.AUTO,
                        sessionId = "session-fallback",
                    )

                val first =
                    provider
                        .stream(
                            model,
                            Context(messages = mutableListOf(UserMessage("one"))),
                            options,
                        ).result()
                val second =
                    provider
                        .stream(
                            model,
                            Context(messages = mutableListOf(UserMessage("two"))),
                            options,
                        ).result()

                assertEquals("SSE", (first.content.single() as TextContent).text)
                assertEquals("SSE", (second.content.single() as TextContent).text)
                assertEquals(2, requests.get())
                assertEquals(1, connectAttempts.get())
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `does not fall back after websocket output starts`() =
        runTest {
            val requests = AtomicInteger()
            val server = sseServer(requests)
            try {
                val connection =
                    ScriptedConnection(
                        listOf(
                            listOf(
                                outputAddedMessage("msg-1"),
                                IOException("socket failed"),
                            ),
                        ),
                    )
                val connector = RecordingConnector(connection)
                val model =
                    codexModel(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                    )
                val result =
                    provider(model, connector)
                        .stream(
                            model,
                            Context(messages = mutableListOf(UserMessage("hello"))),
                            StreamOptions(
                                apiKey = codexToken("acc-after-start"),
                                transport = Transport.AUTO,
                                sessionId = "session-after-start",
                            ),
                        ).result()

                assertEquals(StopReason.ERROR, result.stopReason)
                assertEquals("socket failed", result.errorMessage)
                assertEquals(0, requests.get())
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `does not fall back for Codex API errors`() =
        runTest {
            val requests = AtomicInteger()
            val server = sseServer(requests)
            try {
                val connection =
                    ScriptedConnection(
                        listOf(
                            listOf(
                                errorEvent("invalid_request", "Bad request"),
                            ),
                        ),
                    )
                val connector = RecordingConnector(connection)
                val model =
                    codexModel(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                    )
                val stream =
                    provider(model, connector)
                        .stream(
                            model,
                            Context(),
                            StreamOptions(apiKey = codexToken("acc-api-error")),
                        )
                val events = stream.events.toList()
                val result = stream.result()

                assertEquals(StopReason.ERROR, result.stopReason)
                assertTrue(result.errorMessage.orEmpty().contains("Codex error: Bad request"))
                assertEquals(0, requests.get())
                assertEquals(1, events.size)
                assertTrue(events.single() is AssistantError)
            } finally {
                server.stop(0)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `expires cached websocket by idle time and connection age`() =
        runTest {
            var now = 1_000L
            val idleConnection = ScriptedConnection(listOf(listOf(completed("idle"))))
            val idleTransport =
                OpenAICodexWebSocketTransport(
                    connector = RecordingConnector(idleConnection),
                    scope = backgroundScope,
                    nowMillis = { now },
                    idleTtlMs = 100,
                )
            idleTransport.stream(
                url = "ws://fixture",
                body = requestBody("idle"),
                headers = emptyMap(),
                transport = Transport.WEBSOCKET_CACHED,
                cacheSessionId = "idle-session",
                idleTimeoutMs = null,
                connectTimeoutMs = 1_000,
                onEvent = {},
                fallbackToSse = { error("unexpected fallback") },
            )
            advanceTimeBy(101)
            runCurrent()
            assertTrue(idleConnection.closed)
            assertEquals("idle_timeout", idleConnection.closeReason)

            val agedFirst = ScriptedConnection(listOf(listOf(completed("aged-1"))))
            val agedSecond = ScriptedConnection(listOf(listOf(completed("aged-2"))))
            val agedConnector = RecordingConnector(agedFirst, agedSecond)
            val agedTransport =
                OpenAICodexWebSocketTransport(
                    connector = agedConnector,
                    scope = backgroundScope,
                    nowMillis = { now },
                )
            agedTransport.stream(
                url = "ws://fixture",
                body = requestBody("first"),
                headers = emptyMap(),
                transport = Transport.WEBSOCKET_CACHED,
                cacheSessionId = "aged-session",
                idleTimeoutMs = null,
                connectTimeoutMs = 1_000,
                onEvent = {},
                fallbackToSse = { error("unexpected fallback") },
            )
            now += 56 * 60 * 1_000L
            agedTransport.stream(
                url = "ws://fixture",
                body = requestBody("second"),
                headers = emptyMap(),
                transport = Transport.WEBSOCKET_CACHED,
                cacheSessionId = "aged-session",
                idleTimeoutMs = null,
                connectTimeoutMs = 1_000,
                onEvent = {},
                fallbackToSse = { error("unexpected fallback") },
            )

            assertEquals(2, agedConnector.attempts)
            assertTrue(agedFirst.closed)
            assertEquals("connection_age_limit", agedFirst.closeReason)
            assertFalse(agedSecond.closed)
            agedTransport.closeSessions()
        }

    private fun provider(
        model: Model,
        connector: OpenAICodexWebSocketConnector,
    ): OpenAICodexProvider =
        OpenAICodexProvider(
            id = "openai-codex",
            name = "OpenAI Codex",
            models = listOf(model),
            userAgent = { "pi (fixture)" },
            websocketConnector = connector,
        )

    private fun codexModel(
        baseUrl: String = "https://chatgpt.com/backend-api",
    ): Model =
        Model(
            id = "gpt-5.5",
            name = "GPT-5.5",
            api = "openai-codex-responses",
            provider = "openai-codex",
            baseUrl = baseUrl,
            reasoning = true,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 272_000,
            maxTokens = 128_000,
        )
}

private class RecordingConnector(
    vararg connections: ScriptedConnection,
) : OpenAICodexWebSocketConnector {
    private val remaining = ArrayDeque(connections.toList())
    val urls = mutableListOf<String>()
    val headers = mutableListOf<Map<String, String>>()
    var attempts = 0
        private set

    override suspend fun connect(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): OpenAICodexWebSocketConnection {
        attempts++
        urls += url
        this.headers += headers
        return remaining.removeFirstOrNull() ?: error("No scripted connection remains")
    }
}

private class ScriptedConnection(
    scripts: List<List<Any>>,
) : OpenAICodexWebSocketConnection {
    private val remaining = ArrayDeque(scripts)
    private val incoming = ArrayDeque<Any>()
    val sent = mutableListOf<JsonObject>()
    var closed = false
        private set
    var closeReason: String? = null
        private set

    override val isOpen: Boolean
        get() = !closed

    override suspend fun send(text: String) {
        check(isOpen)
        sent += providerJson.parseToJsonElement(text).jsonObject
        incoming.addAll(
            remaining.removeFirstOrNull() ?: error("No response script remains"),
        )
    }

    override suspend fun receive(timeoutMs: Long?): JsonObject =
        when (val next = incoming.removeFirstOrNull() ?: error("No scripted event remains")) {
            is JsonObject -> next
            is Throwable -> throw next
            else -> error("Unsupported scripted event: $next")
        }

    override fun close(
        code: Int,
        reason: String,
    ) {
        closed = true
        closeReason = reason
    }
}

private fun textResponse(
    responseId: String,
    text: String,
): List<Any> =
    listOf(
        buildJsonObject {
            put("type", "response.created")
            put("response", buildJsonObject { put("id", responseId) })
        },
        outputAddedMessage("msg-$responseId"),
        buildJsonObject {
            put("type", "response.output_text.delta")
            put("output_index", 0)
            put("delta", text)
        },
        buildJsonObject {
            put("type", "response.output_item.done")
            put("output_index", 0)
            put(
                "item",
                buildJsonObject {
                    put("type", "message")
                    put("id", "msg-$responseId")
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
        },
        completed(responseId),
    )

private fun functionCallResponse(responseId: String): List<Any> =
    listOf(
        buildJsonObject {
            put("type", "response.created")
            put("response", buildJsonObject { put("id", responseId) })
        },
        buildJsonObject {
            put("type", "response.output_item.added")
            put("output_index", 0)
            put(
                "item",
                buildJsonObject {
                    put("type", "function_call")
                    put("id", "fc-1")
                    put("call_id", "call-1")
                    put("name", "echo")
                    put("arguments", "")
                },
            )
        },
        buildJsonObject {
            put("type", "response.function_call_arguments.delta")
            put("output_index", 0)
            put("delta", """{"value":"ok"}""")
        },
        buildJsonObject {
            put("type", "response.output_item.done")
            put("output_index", 0)
            put(
                "item",
                buildJsonObject {
                    put("type", "function_call")
                    put("id", "fc-1")
                    put("call_id", "call-1")
                    put("name", "echo")
                    put("arguments", """{"value":"ok"}""")
                },
            )
        },
        completed(responseId),
    )

private fun outputAddedMessage(id: String): JsonObject =
    buildJsonObject {
        put("type", "response.output_item.added")
        put("output_index", 0)
        put(
            "item",
            buildJsonObject {
                put("type", "message")
                put("id", id)
                put("content", JsonArray(emptyList()))
            },
        )
    }

private fun completed(responseId: String): JsonObject =
    buildJsonObject {
        put("type", "response.completed")
        put(
            "response",
            buildJsonObject {
                put("id", responseId)
                put("status", "completed")
                put(
                    "usage",
                    buildJsonObject {
                        put("input_tokens", 1)
                        put("output_tokens", 1)
                        put("total_tokens", 2)
                    },
                )
                put("output", JsonArray(emptyList()))
            },
        )
    }

private fun errorEvent(
    code: String,
    message: String,
): JsonObject =
    buildJsonObject {
        put("type", "error")
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }

private fun requestBody(text: String): JsonObject =
    buildJsonObject {
        put("model", "fixture")
        put(
            "input",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", text)
                    },
                )
            },
        )
    }

private fun sseServer(requests: AtomicInteger): HttpServer {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        exchange.requestBody.readAllBytes()
        requests.incrementAndGet()
        val response =
            """
            data: {"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg-sse","content":[]}}

            data: {"type":"response.output_text.delta","output_index":0,"delta":"SSE"}

            data: {"type":"response.output_item.done","output_index":0,"item":{"type":"message","id":"msg-sse","content":[{"type":"output_text","text":"SSE"}]}}

            data: {"type":"response.completed","response":{"id":"resp-sse","status":"completed","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2},"output":[]}}

            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("content-type", "text/event-stream")
        exchange.sendResponseHeaders(200, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }
    server.start()
    return server
}

private fun codexToken(accountId: String): String {
    val payload =
        providerJson.encodeToString(
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
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
    return "aaa.$encoded.bbb"
}
