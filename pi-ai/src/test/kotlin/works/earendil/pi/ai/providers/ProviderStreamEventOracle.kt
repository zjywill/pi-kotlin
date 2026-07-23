package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
                put("openai-completions", captureEvents("openai-completions", fixtureDir))
                put("anthropic-messages", captureEvents("anthropic-messages", fixtureDir))
                put("openai-responses", captureEvents("openai-responses", fixtureDir))
                put("google-generative-ai", captureEvents("google-generative-ai", fixtureDir))
            }
        println(streamOracleJson.encodeToString(JsonObject.serializer(), output))
    }

private suspend fun captureEvents(
    api: String,
    fixtureDir: Path,
): JsonArray {
    val response = Files.readString(fixtureDir.resolve("$api.sse")) + "\n"
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
                ),
            )
        val events =
            stream.events
                .toList()
                .map(::canonicalEvent)
        stream.result()
        JsonArray(events)
    }
}

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

        "google-generative-ai" ->
            GoogleProvider("fixture", "Fixture", baseUrl, listOf(model), listOf("UNUSED"))

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
        provider = "fixture",
        baseUrl = baseUrl,
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
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val fixture = FixtureServer(server)
    server.createContext("/") { exchange ->
        exchange.requestBody.readBytes()
        val bytes = response.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("content-type", "text/event-stream")
        exchange.responseHeaders.add("cache-control", "no-cache")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    return fixture
}

private class FixtureServer(
    private val server: HttpServer,
) : AutoCloseable {
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    override fun close() {
        server.stop(0)
    }
}
