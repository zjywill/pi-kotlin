package works.earendil.pi.ai.http

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SseEvent(
    val event: String?,
    val data: String,
)

class ProviderHttpException(
    val status: Int,
    message: String,
) : IllegalStateException(message)

internal suspend fun postSse(
    client: HttpClient,
    url: String,
    body: String,
    headers: Map<String, String>,
    timeoutMs: Long?,
    onEvent: (SseEvent) -> Unit,
): Map<String, List<String>> =
    withContext(Dispatchers.IO) {
        val builder =
            HttpRequest
                .newBuilder(URI.create(url))
                .header("accept", "text/event-stream")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        timeoutMs?.let { builder.timeout(Duration.ofMillis(it)) }
        headers.forEach(builder::header)
        val response =
            client.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
        if (response.statusCode() !in 200..299) {
            val errorBody = response.body().readBytes().toString(StandardCharsets.UTF_8)
            throw ProviderHttpException(
                response.statusCode(),
                "Provider returned HTTP ${response.statusCode()}: ${errorBody.take(4000)}",
            )
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
                    line.isEmpty() -> flush()
                    line.startsWith(":") -> Unit
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trimStart()
                    line.startsWith("data:") -> data += line.removePrefix("data:").trimStart()
                }
            }
            flush()
        }
        response.headers().map()
    }
