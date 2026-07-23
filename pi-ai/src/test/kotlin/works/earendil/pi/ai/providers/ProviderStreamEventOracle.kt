package works.earendil.pi.ai.providers

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.AssistantError
import works.earendil.pi.ai.AssistantMessageEvent
import works.earendil.pi.ai.AssistantStart
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextDelta
import works.earendil.pi.ai.TextEnd
import works.earendil.pi.ai.TextStart
import works.earendil.pi.ai.ThinkingDelta
import works.earendil.pi.ai.ThinkingEnd
import works.earendil.pi.ai.ThinkingStart
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.ToolCallDelta
import works.earendil.pi.ai.ToolCallEnd
import works.earendil.pi.ai.ToolCallStart
import works.earendil.pi.ai.UserMessage

private val streamOracleJson =
    Json {
        explicitNulls = false
        encodeDefaults = true
    }

fun main(args: Array<String>) =
    runBlocking {
        val fixtureDir = Path.of(requireNotNull(args.firstOrNull()) { "Fixture directory is required" })
        val output =
            buildJsonObject {
                put("openai-completions", captureEvents("openai-completions", fixtureDir).events)
                put("anthropic-messages", captureEvents("anthropic-messages", fixtureDir).events)
                put("openai-responses", captureEvents("openai-responses", fixtureDir).events)
                val azure = captureEvents("azure-openai-responses", fixtureDir)
                put("azure-openai-responses", azure.events)
                put("azure-openai-responses-request", azure.request)
                put("google-generative-ai", captureEvents("google-generative-ai", fixtureDir).events)
                val mistral = captureEvents("mistral-conversations", fixtureDir)
                put("mistral-conversations", mistral.events)
                put("mistral-conversations-request", mistral.request)
            }
        println(streamOracleJson.encodeToString(JsonObject.serializer(), output))
    }

private suspend fun captureEvents(
    api: String,
    fixtureDir: Path,
): StreamCapture {
    val fixture = if (api == "azure-openai-responses") "openai-responses" else api
    val response = Files.readString(fixtureDir.resolve("$fixture.sse")) + "\n"
    return fixtureServer(response).use { fixture ->
        val model = fixtureModel(api, fixture.baseUrl)
        val provider = fixtureProvider(api, model, fixture.baseUrl)
        val stream =
            provider.stream(
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
                    apiKey = "test",
                    cacheRetention = works.earendil.pi.ai.CacheRetention.NONE,
                    maxRetries = 0,
                    azureBaseUrl =
                        "${fixture.baseUrl}/proxy?tenant=one"
                            .takeIf { api == "azure-openai-responses" },
                    azureApiVersion = "2026-07-01-preview".takeIf { api == "azure-openai-responses" },
                    azureDeploymentName = "fixture-deployment".takeIf { api == "azure-openai-responses" },
                    sessionId = "session-123".takeIf { api == "mistral-conversations" },
                ),
            )
        val events =
            stream.events
                .toList()
                .map(::canonicalEvent)
        stream.result()
        fixture.awaitResponse()
        StreamCapture(
            events = JsonArray(events),
            request =
                buildJsonObject {
                    put("url", fixture.requestUrl)
                    val apiKey = fixture.apiKey
                    if (apiKey == null) {
                        put("apiKey", JsonNull)
                    } else {
                        put("apiKey", apiKey)
                    }
                    put("hasAuthorization", fixture.hasAuthorization)
                    val authorization = fixture.authorization
                    if (authorization == null) {
                        put("authorization", JsonNull)
                    } else {
                        put("authorization", authorization)
                    }
                    val xAffinity = fixture.xAffinity
                    if (xAffinity == null) {
                        put("xAffinity", JsonNull)
                    } else {
                        put("xAffinity", xAffinity)
                    }
                },
        )
    }
}

private data class StreamCapture(
    val events: JsonArray,
    val request: JsonObject,
)

private fun canonicalEvent(event: AssistantMessageEvent): JsonObject =
    buildJsonObject {
        when (event) {
            is AssistantStart -> put("type", "start")
            is TextStart -> {
                put("type", "text_start")
                put("contentIndex", event.contentIndex)
            }

            is TextDelta -> {
                put("type", "text_delta")
                put("contentIndex", event.contentIndex)
                put("delta", event.delta)
            }

            is TextEnd -> {
                put("type", "text_end")
                put("contentIndex", event.contentIndex)
                put("content", event.content)
            }

            is ThinkingStart -> {
                put("type", "thinking_start")
                put("contentIndex", event.contentIndex)
            }

            is ThinkingDelta -> {
                put("type", "thinking_delta")
                put("contentIndex", event.contentIndex)
                put("delta", event.delta)
            }

            is ThinkingEnd -> {
                put("type", "thinking_end")
                put("contentIndex", event.contentIndex)
                put("content", event.content)
            }

            is ToolCallStart -> {
                put("type", "toolcall_start")
                put("contentIndex", event.contentIndex)
            }

            is ToolCallDelta -> {
                put("type", "toolcall_delta")
                put("contentIndex", event.contentIndex)
                put("delta", event.delta)
            }

            is ToolCallEnd -> {
                put("type", "toolcall_end")
                put("contentIndex", event.contentIndex)
                put(
                    "toolCall",
                    streamOracleJson.encodeToJsonElement(
                        ContentBlock.serializer(),
                        event.toolCall,
                    ),
                )
            }

            is AssistantDone -> {
                put("type", "done")
                put("reason", event.reason.name.lowercaseStopReason())
                put(
                    "message",
                    normalizeDynamicValues(
                        streamOracleJson.encodeToJsonElement(
                            works.earendil.pi.ai.Message.serializer(),
                            event.message,
                        ),
                    ),
                )
            }

            is AssistantError -> {
                put("type", "error")
                put("reason", event.reason.name.lowercaseStopReason())
                put(
                    "error",
                    normalizeDynamicValues(
                        streamOracleJson.encodeToJsonElement(
                            works.earendil.pi.ai.Message.serializer(),
                            event.error,
                        ),
                    ),
                )
            }
        }
    }

private fun String.lowercaseStopReason(): String =
    when (this) {
        "TOOL_USE" -> "toolUse"
        else -> lowercase()
    }

private fun fixtureProvider(
    api: String,
    model: Model,
    baseUrl: String,
): Provider =
    when (api) {
        "openai-completions" ->
            OpenAIChatProvider("fixture", "Fixture", baseUrl, listOf(model), listOf("UNUSED"))

        "anthropic-messages" ->
            AnthropicProvider("fixture", "Fixture", baseUrl, listOf(model), listOf("UNUSED"))

        "openai-responses" ->
            OpenAIResponsesProvider("fixture", "Fixture", baseUrl, listOf(model), listOf("UNUSED"))

        "azure-openai-responses" ->
            AzureOpenAIResponsesProvider(
                "azure-openai-responses",
                "Azure OpenAI",
                listOf(model),
                listOf("UNUSED"),
            )

        "google-generative-ai" ->
            GoogleProvider("fixture", "Fixture", baseUrl, listOf(model), listOf("UNUSED"))

        "mistral-conversations" ->
            MistralProvider("mistral", "Mistral", baseUrl, listOf(model), listOf("UNUSED"))

        else -> error("Unsupported fixture API: $api")
    }

private fun fixtureModel(
    api: String,
    baseUrl: String,
): Model =
    Model(
        id = "fixture",
        name = "Fixture",
        api = api,
        provider =
            when (api) {
                "azure-openai-responses" -> "azure-openai-responses"
                "mistral-conversations" -> "mistral"
                else -> "fixture"
            },
        baseUrl = if (api == "azure-openai-responses") "" else baseUrl,
        reasoning = false,
        input = listOf(ModelInput.TEXT),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 128_000,
        maxTokens = 16_384,
    )

private fun normalizeDynamicValues(
    value: JsonElement,
    key: String? = null,
): JsonElement =
    when {
        key == "timestamp" && value is JsonPrimitive -> JsonPrimitive(0)
        value is JsonArray -> JsonArray(value.map(::normalizeDynamicValues))
        value is JsonObject ->
            JsonObject(
                value.mapValues { (entryKey, entryValue) ->
                    normalizeDynamicValues(entryValue, entryKey)
                },
            )

        else -> value
    }

private fun fixtureServer(response: String): FixtureServer {
    val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    return FixtureServer(server, response).also(FixtureServer::start)
}

private class FixtureServer(
    private val server: ServerSocket,
    private val response: String,
) : AutoCloseable {
    private val worker =
        thread(start = false, isDaemon = true, name = "provider-stream-oracle") {
            runCatching(::serve).onFailure { failure = it }
        }

    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    @Volatile
    var requestUrl: String = ""

    @Volatile
    var apiKey: String? = null

    @Volatile
    var hasAuthorization: Boolean = false

    @Volatile
    var authorization: String? = null

    @Volatile
    var xAffinity: String? = null

    @Volatile
    private var failure: Throwable? = null

    fun start() {
        worker.start()
    }

    fun awaitResponse() {
        worker.join(5_000)
        check(!worker.isAlive) { "Fixture server did not finish the response" }
        failure?.let { throw it }
    }

    private fun serve() {
        server.accept().use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = requireNotNull(input.readHttpLine()) { "Missing HTTP request line" }
            requestUrl = requestLine.split(' ').getOrNull(1).orEmpty()
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = input.readHttpLine() ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }
            apiKey = headers["api-key"]
            hasAuthorization = "authorization" in headers
            authorization = headers["authorization"]
            xAffinity = headers["x-affinity"]
            headers["content-length"]?.toIntOrNull()?.let(input::readNBytes)

            val bytes = response.toByteArray(StandardCharsets.UTF_8)
            val output = socket.getOutputStream()
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"
                ).toByteArray(StandardCharsets.US_ASCII),
            )
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        server.close()
        worker.join(1_000)
    }
}

private fun BufferedInputStream.readHttpLine(): String? {
    val output = ByteArrayOutputStream()
    while (true) {
        val byte = read()
        if (byte < 0) {
            return output.takeIf { it.size() > 0 }?.toString(StandardCharsets.ISO_8859_1)
        }
        if (byte == '\n'.code) {
            return output.toString(StandardCharsets.ISO_8859_1)
        }
        if (byte != '\r'.code) {
            output.write(byte)
        }
    }
}
