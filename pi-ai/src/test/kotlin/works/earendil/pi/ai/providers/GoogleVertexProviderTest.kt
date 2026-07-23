package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ThinkingContent
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GoogleVertexProviderTest {
    @Test
    fun `streams express mode with API key payload headers and events`() =
        runTest {
            val captured = AtomicReference<CapturedRequest>()
            val server = vertexFixtureServer(captured)
            server.start()
            try {
                val baseUrl = "http://127.0.0.1:${server.address.port}/proxy/v1"
                val model = vertexModel(baseUrl)
                val tokenCalls = AtomicInteger()
                val provider =
                    GoogleVertexProvider(
                        id = "google-vertex",
                        name = "Google Vertex AI",
                        models = listOf(model),
                        environment = { null },
                        accessTokenProvider = {
                            tokenCalls.incrementAndGet()
                            "unused"
                        },
                    )
                val result =
                    provider
                        .stream(
                            model,
                            Context(
                                systemPrompt = "system",
                                messages = mutableListOf(UserMessage("hello")),
                                tools =
                                    listOf(
                                        ToolDefinition(
                                            name = "echo",
                                            description = "Echo",
                                            parameters =
                                                buildJsonObject {
                                                    put("type", "object")
                                                    put(
                                                        "properties",
                                                        buildJsonObject {
                                                            put(
                                                                "value",
                                                                buildJsonObject { put("type", "string") },
                                                            )
                                                        },
                                                    )
                                                },
                                        ),
                                    ),
                            ),
                            StreamOptions(
                                apiKey = "AIzaFixture",
                                temperature = 0.25,
                                maxTokens = 123,
                                reasoning = ThinkingLevel.MEDIUM,
                                headers = mapOf("x-fixture" to "yes"),
                            ),
                        ).result()

                assertEquals(0, tokenCalls.get())
                assertEquals(StopReason.TOOL_USE, result.stopReason)
                assertEquals("think", (result.content[0] as ThinkingContent).thinking)
                assertEquals("answer", (result.content[1] as TextContent).text)
                assertEquals("echo", (result.content[2] as ToolCall).name)
                assertEquals("vertex-1", result.responseId)
                assertEquals(6, result.usage.input)
                assertEquals(3, result.usage.output)
                assertEquals(3, result.usage.cacheRead)
                assertEquals(1, result.usage.reasoning)

                val request = captured.get()
                assertEquals(
                    "/proxy/v1/publishers/google/models/gemini-3-flash-preview:streamGenerateContent",
                    request.path,
                )
                assertEquals("alt=sse", request.query)
                assertEquals("AIzaFixture", request.headers["x-goog-api-key"])
                assertEquals("yes", request.headers["x-fixture"])
                assertFalse("authorization" in request.headers)
                val system = request.body.getValue("systemInstruction").jsonObject
                assertEquals("user", system.getValue("role").jsonPrimitive.content)
                val generation = request.body.getValue("generationConfig").jsonObject
                assertEquals("MEDIUM", generation.getValue("thinkingConfig").jsonObject
                    .getValue("thinkingLevel").jsonPrimitive.content)
                assertTrue("tools" in request.body)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `streams ADC against custom collection base with bearer token`() =
        runTest {
            val captured = AtomicReference<CapturedRequest>()
            val server = vertexFixtureServer(captured)
            server.start()
            try {
                val model = vertexModel("http://127.0.0.1:${server.address.port}/collection")
                val provider =
                    GoogleVertexProvider(
                        id = "google-vertex",
                        name = "Google Vertex AI",
                        models = listOf(model),
                        environment = { null },
                        accessTokenProvider = { "adc-token" },
                    )
                provider
                    .stream(
                        model,
                        Context(messages = mutableListOf(UserMessage("hello"))),
                        StreamOptions(
                            apiKey = "gcp-vertex-credentials",
                            project = "project-id",
                            location = "us-central1",
                        ),
                    ).result()

                val request = captured.get()
                assertEquals(
                    "/collection/v1/publishers/google/models/gemini-3-flash-preview:streamGenerateContent",
                    request.path,
                )
                assertEquals("Bearer adc-token", request.headers["authorization"])
                assertFalse("x-goog-api-key" in request.headers)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `resolves express regional global multi-region and versioned custom endpoints`() {
        val model = vertexModel("https://{location}-aiplatform.googleapis.com")
        val noEnvironment: (String) -> String? = { null }

        val express =
            resolveGoogleVertexRequest(
                model,
                StreamOptions(apiKey = "vertex-key"),
                noEnvironment,
                { error("ADC must not be used") },
            )
        assertEquals(
            "https://aiplatform.googleapis.com/v1/publishers/google/models/" +
                "gemini-3-flash-preview:streamGenerateContent?alt=sse",
            express.url,
        )
        assertEquals("vertex-key", express.headers["x-goog-api-key"])

        val regional =
            resolveGoogleVertexRequest(
                model,
                StreamOptions(
                    apiKey = "<authenticated>",
                    project = "project id",
                    location = "us-central1",
                ),
                noEnvironment,
                { "token" },
            )
        assertEquals(
            "https://us-central1-aiplatform.googleapis.com/v1/projects/project%20id/locations/us-central1/" +
                "publishers/google/models/gemini-3-flash-preview:streamGenerateContent?alt=sse",
            regional.url,
        )

        val global =
            resolveGoogleVertexRequest(
                model,
                StreamOptions(project = "project", location = "global"),
                noEnvironment,
                { "token" },
            )
        assertTrue(global.url.startsWith("https://aiplatform.googleapis.com/v1/projects/project/locations/global/"))

        val multiRegion =
            resolveGoogleVertexRequest(
                model,
                StreamOptions(project = "project", location = "eu"),
                noEnvironment,
                { "token" },
            )
        assertTrue(multiRegion.url.startsWith("https://aiplatform.eu.rep.googleapis.com/v1/projects/"))

        val versionedCustom =
            resolveGoogleVertexRequest(
                model.copy(baseUrl = "https://proxy.example.com/v1/projects/proxy/locations/global"),
                StreamOptions(project = "project", location = "global"),
                noEnvironment,
                { "token" },
            )
        assertFalse("/v1/v1/" in versionedCustom.url)
        assertTrue(versionedCustom.url.startsWith("https://proxy.example.com/v1/projects/proxy/locations/global/"))
    }

    @Test
    fun `uses scoped config before ambient values and rejects incomplete ADC`() {
        val model = vertexModel("https://{location}-aiplatform.googleapis.com")
        val ambient =
            mapOf(
                "GOOGLE_CLOUD_API_KEY" to "<authenticated>",
                "GOOGLE_CLOUD_PROJECT" to "ambient-project",
                "GOOGLE_CLOUD_LOCATION" to "ambient-location",
            )
        val resolved =
            resolveGoogleVertexRequest(
                model,
                StreamOptions(
                    env =
                        mapOf(
                            "GOOGLE_CLOUD_PROJECT" to "scoped-project",
                            "GOOGLE_CLOUD_LOCATION" to "scoped-location",
                        ),
                ),
                ambient::get,
                { "token" },
            )
        assertTrue("/projects/scoped-project/locations/scoped-location/" in resolved.url)

        val missingProject =
            assertFailsWith<IllegalStateException> {
                resolveGoogleVertexRequest(
                    model,
                    StreamOptions(apiKey = "gcp-vertex-credentials", location = "global"),
                    { null },
                    { "token" },
                )
            }
        assertTrue(missingProject.message.orEmpty().contains("GOOGLE_CLOUD_PROJECT"))

        val missingLocation =
            assertFailsWith<IllegalStateException> {
                resolveGoogleVertexRequest(
                    model,
                    StreamOptions(apiKey = "<authenticated>", project = "project"),
                    { null },
                    { "token" },
                )
            }
        assertTrue(missingLocation.message.orEmpty().contains("GOOGLE_CLOUD_LOCATION"))
    }

    @Test
    fun `builds Vertex params with disabled and enabled thinking`() {
        val model = vertexModel("https://{location}-aiplatform.googleapis.com")
        val context = Context(messages = mutableListOf(UserMessage("hello")))

        val disabled = buildGoogleVertexParams(model, context, StreamOptions())
        assertEquals(
            "MINIMAL",
            disabled.getValue("config").jsonObject
                .getValue("thinkingConfig").jsonObject
                .getValue("thinkingLevel").jsonPrimitive.content,
        )

        val enabled =
            buildGoogleVertexParams(
                model.copy(id = "gemini-2.5-pro"),
                context,
                StreamOptions(reasoning = ThinkingLevel.HIGH),
            )
        assertEquals(
            32_768,
            enabled.getValue("config").jsonObject
                .getValue("thinkingConfig").jsonObject
                .getValue("thinkingBudget").jsonPrimitive.content.toInt(),
        )
        assertEquals("gemini-2.5-pro", enabled.getValue("model").jsonPrimitive.content)
        assertEquals("user", enabled.getValue("contents").jsonArray.single().jsonObject
            .getValue("role").jsonPrimitive.content)
    }

    private fun vertexModel(baseUrl: String): Model =
        model(
            id = "gemini-3-flash-preview",
            api = "google-vertex",
            provider = "google-vertex",
            baseUrl = baseUrl,
            reasoning = true,
        )

    private fun vertexFixtureServer(captured: AtomicReference<CapturedRequest>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            captured.set(
                CapturedRequest(
                    path = exchange.requestURI.path,
                    query = exchange.requestURI.rawQuery,
                    headers =
                        exchange.requestHeaders.entries.associate { (name, values) ->
                            name.lowercase() to values.single()
                        },
                    body =
                        providerJson.parseToJsonElement(
                            exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8),
                        ).jsonObject,
                ),
            )
            val bytes = VERTEX_RESPONSE.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("content-type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        return server
    }

    private data class CapturedRequest(
        val path: String,
        val query: String?,
        val headers: Map<String, String>,
        val body: JsonObject,
    )

    private companion object {
        val VERTEX_RESPONSE =
            """
            data: {"responseId":"vertex-1","candidates":[{"content":{"parts":[{"text":"think","thought":true,"thoughtSignature":"c2ln"}]}}]}

            data: {"candidates":[{"content":{"parts":[{"text":"answer"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":2,"thoughtsTokenCount":1,"cachedContentTokenCount":3,"totalTokenCount":12}}

            data: {"candidates":[{"content":{"parts":[{"functionCall":{"id":"call-1","name":"echo","args":{"value":"ok"}}}]},"finishReason":"STOP"}]}

            """.trimIndent()
    }
}
