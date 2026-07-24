package works.earendil.pi.ai.http

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SseEvent(
    val event: String?,
    val data: String,
)

class ProviderHttpException(
    val status: Int,
    val headers: Map<String, List<String>>,
    message: String,
) : IllegalStateException(message)

internal suspend fun postSse(
    client: HttpClient,
    url: String,
    body: String,
    headers: Map<String, String>,
    timeoutMs: Long?,
    maxRetries: Int? = null,
    maxRetryDelayMs: Long? = null,
    onEvent: (SseEvent) -> Unit,
): Map<String, List<String>> =
    postSse(
        client = client,
        url = url,
        body = body.toByteArray(StandardCharsets.UTF_8),
        headers = headers,
        timeoutMs = timeoutMs,
        maxRetries = maxRetries,
        maxRetryDelayMs = maxRetryDelayMs,
        onEvent = onEvent,
    )

internal suspend fun postSse(
    client: HttpClient,
    url: String,
    body: ByteArray,
    headers: Map<String, String>,
    timeoutMs: Long?,
    maxRetries: Int? = null,
    maxRetryDelayMs: Long? = null,
    shouldStop: () -> Boolean = { false },
    onEvent: (SseEvent) -> Unit,
): Map<String, List<String>> =
    withContext(Dispatchers.IO) {
        val builder =
            HttpRequest
                .newBuilder(URI.create(url))
                .header("accept", "text/event-stream")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        timeoutMs?.let { builder.timeout(Duration.ofMillis(it)) }
        headers.forEach(builder::header)
        val request = builder.build()
        val response =
            retryProviderRequest(maxRetries, maxRetryDelayMs) {
                val candidate =
                    client.send(
                        request,
                        HttpResponse.BodyHandlers.ofInputStream(),
                    )
                if (candidate.statusCode() !in 200..299) {
                    val errorBody = candidate.body().readBytes().toString(StandardCharsets.UTF_8)
                    throw ProviderHttpException(
                        candidate.statusCode(),
                        candidate.headers().map(),
                        "Provider returned HTTP ${candidate.statusCode()}: ${errorBody.take(4000)}",
                    )
                }
                candidate
            }

        BufferedReader(InputStreamReader(response.body(), StandardCharsets.UTF_8)).use { reader ->
            var eventName: String? = null
            val data = mutableListOf<String>()

            fun flush() {
                if (eventName != null || data.isNotEmpty()) {
                    onEvent(SseEvent(eventName, data.joinToString("\n")))
                }
                eventName = null
                data.clear()
            }

            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        flush()
                        if (shouldStop()) break
                    }
                    line.startsWith(":") -> Unit
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trimStart()
                    line.startsWith("data:") -> data += line.removePrefix("data:").trimStart()
                }
            }
            if (!shouldStop()) {
                flush()
            }
        }
        response.headers().map()
    }

private suspend fun <T> retryProviderRequest(
    maxRetries: Int?,
    maxRetryDelayMs: Long?,
    request: suspend () -> T,
): T {
    val retries = (maxRetries ?: 0).coerceAtLeast(0)
    repeat(retries + 1) { attempt ->
        currentCoroutineContext().ensureActive()
        try {
            return request()
        } catch (error: ProviderHttpException) {
            if (attempt >= retries || !error.isRetryable()) {
                throw error
            }
            delay(error.retryDelayMs(attempt, maxRetryDelayMs))
        } catch (error: java.io.IOException) {
            if (attempt >= retries) {
                throw error
            }
            delay(retryBackoffDelayMs(attempt))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }
    }
    error("Provider retry loop exhausted")
}

private fun ProviderHttpException.isRetryable(): Boolean {
    when (header("x-should-retry")) {
        "true" -> return true
        "false" -> return false
    }
    return status == 408 || status == 409 || status == 429 || status >= 500
}

private fun ProviderHttpException.retryDelayMs(
    retryIndex: Int,
    maxRetryDelayMs: Long?,
): Long {
    val serverDelay =
        header("retry-after-ms")?.toDoubleOrNull()?.toLong()
            ?: header("retry-after")?.let { value ->
                value.toDoubleOrNull()?.times(1_000)?.toLong()
                    ?: runCatching {
                        ZonedDateTime
                            .parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant()
                            .toEpochMilli() - System.currentTimeMillis()
                    }.getOrNull()
            }
    if (serverDelay != null) {
        val bounded = serverDelay.coerceAtLeast(0)
        val limit = maxRetryDelayMs ?: DEFAULT_MAX_RETRY_DELAY_MS
        check(limit <= 0 || bounded <= limit) {
            "Server requested ${(bounded + 999) / 1_000}s retry delay " +
                "(max: ${(limit + 999) / 1_000}s). ${message.orEmpty()}"
        }
        return bounded
    }
    return retryBackoffDelayMs(retryIndex)
}

private fun ProviderHttpException.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

private fun retryBackoffDelayMs(retryIndex: Int): Long {
    val exponential = (500L shl retryIndex.coerceAtMost(4)).coerceAtMost(8_000)
    return (exponential * ThreadLocalRandom.current().nextDouble(0.75, 1.0)).toLong()
}

private const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L
