package works.earendil.pi.ai.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SseHttpTest {
    @Test
    fun `explicit headers replace SSE defaults case insensitively`() =
        runTest {
            val captured = AtomicReference<Map<String, List<String>>>()
            fixtureServer { exchange ->
                captured.set(
                    exchange.requestHeaders.entries.associate { (name, values) ->
                        name.lowercase() to values
                    },
                )
                exchange.requestBody.readAllBytes()
                val body = "data: ok\n\n".toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }.use { server ->
                postSse(
                    client = HttpClient.newHttpClient(),
                    url = server.url,
                    body = "{}",
                    headers =
                        mapOf(
                            "Accept" to "application/json",
                            "CONTENT-TYPE" to "application/custom+json",
                        ),
                    timeoutMs = null,
                    onEvent = {},
                )

                assertEquals(listOf("application/json"), captured.get().getValue("accept"))
                assertEquals(
                    listOf("application/custom+json"),
                    captured.get().getValue("content-type"),
                )
            }
        }

    @Test
    fun `retries retryable provider responses`() =
        runTest {
            val attempts = AtomicInteger()
            fixtureServer { exchange ->
                if (attempts.incrementAndGet() == 1) {
                    exchange.responseHeaders.add("retry-after-ms", "1")
                    exchange.sendResponseHeaders(429, 0)
                    exchange.responseBody.close()
                } else {
                    val body = "data: ok\n\n".toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }.use { server ->
                val events = mutableListOf<SseEvent>()

                postSse(
                    client = HttpClient.newHttpClient(),
                    url = server.url,
                    body = "{}",
                    headers = emptyMap(),
                    timeoutMs = null,
                    maxRetries = 1,
                    onEvent = events::add,
                )

                assertEquals(2, attempts.get())
                assertEquals(listOf("ok"), events.map(SseEvent::data))
            }
        }

    @Test
    fun `respects provider retry opt out`() =
        runTest {
            val attempts = AtomicInteger()
            fixtureServer { exchange ->
                attempts.incrementAndGet()
                exchange.responseHeaders.add("x-should-retry", "false")
                exchange.sendResponseHeaders(429, 0)
                exchange.responseBody.close()
            }.use { server ->
                assertFailsWith<ProviderHttpException> {
                    postSse(
                        client = HttpClient.newHttpClient(),
                        url = server.url,
                        body = "{}",
                        headers = emptyMap(),
                        timeoutMs = null,
                        maxRetries = 2,
                        onEvent = {},
                    )
                }
                assertEquals(1, attempts.get())
            }
        }

    @Test
    fun `rejects provider delays above the configured limit`() =
        runTest {
            fixtureServer { exchange ->
                exchange.responseHeaders.add("retry-after", "120")
                exchange.sendResponseHeaders(429, 0)
                exchange.responseBody.close()
            }.use { server ->
                val error =
                    assertFailsWith<IllegalStateException> {
                        postSse(
                            client = HttpClient.newHttpClient(),
                            url = server.url,
                            body = "{}",
                            headers = emptyMap(),
                            timeoutMs = null,
                            maxRetries = 1,
                            maxRetryDelayMs = 1_000,
                            onEvent = {},
                        )
                    }
                assertTrue(error.message.orEmpty().contains("Server requested 120s retry delay"))
            }
        }

    @Test
    fun `retry backoff is cancellable`() =
        runTest {
            val attempts = AtomicInteger()
            fixtureServer { exchange ->
                attempts.incrementAndGet()
                exchange.responseHeaders.add("retry-after", "120")
                exchange.sendResponseHeaders(429, 0)
                exchange.responseBody.close()
            }.use { server ->
                val request =
                    launch {
                        postSse(
                            client = HttpClient.newHttpClient(),
                            url = server.url,
                            body = "{}",
                            headers = emptyMap(),
                            timeoutMs = null,
                            maxRetries = 2,
                            maxRetryDelayMs = 0,
                            onEvent = {},
                        )
                    }
                while (attempts.get() == 0) {
                    kotlinx.coroutines.yield()
                }
                request.cancelAndJoin()
                assertEquals(1, attempts.get())
            }
        }

    private fun fixtureServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): FixtureServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", handler)
        server.start()
        return FixtureServer(server)
    }

    private class FixtureServer(
        private val server: HttpServer,
    ) : AutoCloseable {
        val url: String = "http://127.0.0.1:${server.address.port}/"

        override fun close() {
            server.stop(0)
        }
    }
}
