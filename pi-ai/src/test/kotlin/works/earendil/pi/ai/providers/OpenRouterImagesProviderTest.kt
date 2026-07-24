package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.ImagesContext
import works.earendil.pi.ai.ImagesModel
import works.earendil.pi.ai.ImagesOptions
import works.earendil.pi.ai.ImagesStopReason
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.generateImages

class OpenRouterImagesProviderTest {
    @Test
    fun `posts OpenRouter image payload and parses text images usage and callbacks`() =
        runTest {
            val attempts = AtomicInteger()
            val capturedBody = AtomicReference<JsonObject>()
            val capturedHeaders = AtomicReference<Map<String, String>>()
            imageServer { exchange ->
                capturedBody.set(exchange.readJsonBody())
                capturedHeaders.set(exchange.requestHeaders.normalized())
                if (attempts.incrementAndGet() == 1) {
                    exchange.responseHeaders.add("retry-after-ms", "1")
                    exchange.respondJson(429, """{"error":{"message":"retry"}}""")
                } else {
                    exchange.respondJson(
                        200,
                        """
                        {
                          "id":"img-1",
                          "usage":{
                            "prompt_tokens":20,
                            "completion_tokens":7,
                            "prompt_tokens_details":{"cached_tokens":8,"cache_write_tokens":3}
                          },
                          "choices":[{
                            "message":{
                              "content":"Rendered",
                              "images":[
                                {"image_url":"data:image/png;base64,cG5n"},
                                "malformed",
                                {"image_url":{"url":"data:image/jpeg;base64,anBlZw=="}},
                                {"image_url":{"url":42}},
                                {"image_url":"https://example.test/no.png"}
                              ]
                            }
                          }]
                        }
                        """.trimIndent(),
                        mapOf("x-fixture" to "yes"),
                    )
                }
            }.use { server ->
                var responseStatus = 0
                val result =
                    generateOpenRouterImages(
                        model =
                            imageModel(server.baseUrl).copy(
                                headers =
                                    mapOf(
                                        "X-Model" to "model",
                                        "X-Remove" to "remove",
                                    ),
                            ),
                        context =
                            ImagesContext(
                                input =
                                    listOf(
                                        TextContent("Generate \uD83D image"),
                                        ImageContent("aW5wdXQ=", "image/png"),
                                    ),
                            ),
                        options =
                            ImagesOptions(
                                apiKey = "test-key",
                                headers =
                                    mapOf(
                                        "x-model" to "request",
                                        "X-Remove" to null,
                                    ),
                                maxRetries = 1,
                                onPayload = { payload, _ ->
                                    JsonObject(payload.jsonObject + ("tag" to JsonPrimitive("changed")))
                                },
                                onResponse = { response, _ ->
                                    responseStatus = response.status
                                    assertEquals("yes", response.headers["x-fixture"])
                                },
                            ),
                        client = HttpClient.newHttpClient(),
                    )

                assertEquals(2, attempts.get())
                assertEquals(200, responseStatus)
                assertEquals("Bearer test-key", capturedHeaders.get()["authorization"])
                assertEquals("request", capturedHeaders.get()["x-model"])
                assertNull(capturedHeaders.get()["x-remove"])
                val body = capturedBody.get()
                assertEquals("oracle/image", body["model"]?.let { (it as JsonPrimitive).content })
                assertEquals(false, (body["stream"] as JsonPrimitive).content.toBoolean())
                assertEquals(
                    listOf("image", "text"),
                    body.getValue("modalities").jsonArray.map { (it as JsonPrimitive).content },
                )
                assertEquals("changed", (body["tag"] as JsonPrimitive).content)
                val content =
                    body
                        .getValue("messages")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("content")
                        .jsonArray
                assertEquals("Generate  image", content[0].jsonObject["text"]?.let { (it as JsonPrimitive).content })
                assertEquals(
                    "data:image/png;base64,aW5wdXQ=",
                    content[1].jsonObject["image_url"]?.jsonObject?.get("url")?.let {
                        (it as JsonPrimitive).content
                    },
                )

                assertEquals(ImagesStopReason.STOP, result.stopReason)
                assertEquals("img-1", result.responseId)
                assertEquals(
                    listOf(
                        TextContent("Rendered"),
                        ImageContent("cG5n", "image/png"),
                        ImageContent("anBlZw==", "image/jpeg"),
                    ),
                    result.output,
                )
                assertEquals(12, result.usage?.input)
                assertEquals(7, result.usage?.output)
                assertEquals(5, result.usage?.cacheRead)
                assertEquals(3, result.usage?.cacheWrite)
                assertEquals(27, result.usage?.totalTokens)
                assertEquals(0.000053, result.usage?.cost?.total)
            }
        }

    @Test
    fun `surfaces status and error body and handles missing API key`() =
        runTest {
            imageServer { exchange ->
                exchange.readJsonBody()
                exchange.respondJson(
                    403,
                    """{"error":{"message":"blocked by gateway WAF"}}""",
                )
            }.use { server ->
                val model = imageModel(server.baseUrl)
                val error =
                    generateOpenRouterImages(
                        model,
                        ImagesContext(listOf(TextContent("draw"))),
                        ImagesOptions(apiKey = "test", maxRetries = 0),
                        HttpClient.newHttpClient(),
                    )
                assertEquals(ImagesStopReason.ERROR, error.stopReason)
                assertEquals("""403: {"message":"blocked by gateway WAF"}""", error.errorMessage)

                val missing =
                    generateOpenRouterImages(
                        model,
                        ImagesContext(listOf(TextContent("draw"))),
                        ImagesOptions(),
                        HttpClient.newHttpClient(),
                    )
                assertEquals(ImagesStopReason.ERROR, missing.stopReason)
                assertEquals("No API key for provider: openrouter", missing.errorMessage)
            }
        }

    @Test
    fun `cache write tokens are removed from cached input before costing`() {
        val usage =
            parseOpenRouterImagesUsage(
                providerJson.parseToJsonElement(
                    """
                    {
                      "prompt_tokens":5,
                      "completion_tokens":2,
                      "prompt_tokens_details":{"cached_tokens":2,"cache_write_tokens":3}
                    }
                    """.trimIndent(),
                ).jsonObject,
                imageModel("https://example.test"),
            )

        assertEquals(2, usage.input)
        assertEquals(0, usage.cacheRead)
        assertEquals(3, usage.cacheWrite)
        assertEquals(7, usage.totalTokens)
    }

    @Test
    fun `global image API registry dispatches built in OpenRouter implementation`() =
        runTest {
            imageServer { exchange ->
                exchange.readJsonBody()
                exchange.respondJson(
                    200,
                    """
                    {
                      "choices":[{
                        "message":{
                          "content":"",
                          "images":[{"image_url":"data:image/png;base64,b2s="}]
                        }
                      }]
                    }
                    """.trimIndent(),
                )
            }.use { server ->
                val result =
                    generateImages(
                        imageModel(server.baseUrl),
                        ImagesContext(listOf(TextContent("draw"))),
                        ImagesOptions(apiKey = "test"),
                    )

                assertEquals(ImagesStopReason.STOP, result.stopReason)
                assertTrue(result.output.single() is ImageContent)
            }
        }

    private fun imageModel(baseUrl: String): ImagesModel =
        ImagesModel(
            id = "oracle/image",
            name = "Oracle Image",
            api = "openrouter-images",
            provider = "openrouter",
            baseUrl = baseUrl,
            input = listOf(ModelInput.TEXT, ModelInput.IMAGE),
            output = listOf(ModelInput.IMAGE, ModelInput.TEXT),
            cost = ModelCost(1.0, 2.0, 3.0, 4.0),
        )

    private fun imageServer(handler: (HttpExchange) -> Unit): ImageFixture {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", handler)
        server.start()
        return ImageFixture(server)
    }

    private class ImageFixture(
        private val server: HttpServer,
    ) : AutoCloseable {
        val baseUrl: String = "http://127.0.0.1:${server.address.port}/v1"

        override fun close() {
            server.stop(0)
        }
    }
}

private fun HttpExchange.readJsonBody(): JsonObject =
    providerJson
        .parseToJsonElement(
            requestBody.readBytes().toString(StandardCharsets.UTF_8),
        ).jsonObject

private fun HttpExchange.respondJson(
    status: Int,
    body: String,
    headers: Map<String, String> = emptyMap(),
) {
    responseHeaders.add("content-type", "application/json")
    headers.forEach(responseHeaders::add)
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun com.sun.net.httpserver.Headers.normalized(): Map<String, String> =
    entries.associate { (name, values) ->
        name.lowercase() to values.joinToString(",")
    }
