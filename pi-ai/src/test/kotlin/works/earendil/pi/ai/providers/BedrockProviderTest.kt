package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.BedrockThinkingDisplay
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ThinkingBudgets
import works.earendil.pi.ai.ThinkingContent
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BedrockProviderTest {
    @Test
    fun `builds Bedrock messages with sanitization tool normalization and grouped results`() {
        val model = bedrockModel("us.anthropic.claude-sonnet-4-5-20250929-v1:0")
        val longToolId = "call|${"x".repeat(80)}"
        val context =
            Context(
                messages =
                    mutableListOf(
                        UserMessage(" \uD83D "),
                        AssistantMessage(
                            content =
                                listOf(
                                    ThinkingContent("private reasoning"),
                                    TextContent("answer"),
                                    ToolCall(
                                        id = longToolId,
                                        name = "echo",
                                        arguments = buildJsonObject { put("value", "ok") },
                                    ),
                                ),
                            api = "openai-responses",
                            provider = "openai",
                            model = "gpt-5",
                            stopReason = StopReason.TOOL_USE,
                        ),
                        ToolResultMessage(
                            toolCallId = longToolId,
                            toolName = "echo",
                            content = listOf(TextContent("")),
                            isError = false,
                        ),
                        ToolResultMessage(
                            toolCallId = "second",
                            toolName = "second",
                            content = listOf(TextContent("done")),
                            isError = true,
                        ),
                    ),
            )

        val body =
            buildBedrockRequestBody(
                model,
                context,
                StreamOptions(cacheRetention = CacheRetention.NONE),
                { null },
            )
        val messages = body.getValue("messages").jsonArray

        assertEquals("<empty>", messages[0].jsonObject.getValue("content").jsonArray
            .single().jsonObject.getValue("text").jsonPrimitive.content)
        val assistant = messages[1].jsonObject.getValue("content").jsonArray
        assertEquals("private reasoning", assistant[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("answer", assistant[1].jsonObject.getValue("text").jsonPrimitive.content)
        val normalizedId =
            assistant[2].jsonObject.getValue("toolUse").jsonObject
                .getValue("toolUseId").jsonPrimitive.content
        assertEquals(64, normalizedId.length)
        assertFalse("|" in normalizedId)
        val results = messages[2].jsonObject.getValue("content").jsonArray
        assertEquals(2, results.size)
        assertEquals(
            normalizedId,
            results[0].jsonObject.getValue("toolResult").jsonObject
                .getValue("toolUseId").jsonPrimitive.content,
        )
        assertEquals(
            "<empty>",
            results[0].jsonObject.getValue("toolResult").jsonObject
                .getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content,
        )
        assertEquals(
            "error",
            results[1].jsonObject.getValue("toolResult").jsonObject
                .getValue("status").jsonPrimitive.content,
        )
    }

    @Test
    fun `downgrades images for text models and preserves supported images`() {
        val context =
            Context(
                messages =
                    mutableListOf(
                        UserMessage(
                            listOf(
                                ImageContent("aGVsbG8=", "image/png"),
                                ImageContent("aGVsbG8=", "image/png"),
                                TextContent("caption"),
                            ),
                        ),
                    ),
            )
        val textBody =
            buildBedrockRequestBody(
                bedrockModel("text-model", input = listOf(ModelInput.TEXT)),
                context,
                StreamOptions(cacheRetention = CacheRetention.NONE),
                { null },
            )
        val textContent =
            textBody.getValue("messages").jsonArray.single().jsonObject
                .getValue("content").jsonArray
        assertEquals(2, textContent.size)
        assertEquals(
            "(image omitted: model does not support images)",
            textContent[0].jsonObject.getValue("text").jsonPrimitive.content,
        )

        val imageBody =
            buildBedrockRequestBody(
                bedrockModel("vision-model"),
                context,
                StreamOptions(cacheRetention = CacheRetention.NONE),
                { null },
            )
        val image =
            imageBody.getValue("messages").jsonArray.single().jsonObject
                .getValue("content").jsonArray.first().jsonObject
                .getValue("image").jsonObject
        assertEquals("png", image.getValue("format").jsonPrimitive.content)
        assertEquals("aGVsbG8=", image.getValue("source").jsonObject.getValue("bytes").jsonPrimitive.content)
    }

    @Test
    fun `builds tools request metadata and cache points`() {
        val model =
            bedrockModel(
                "arn:aws:bedrock:us-east-1:123456789012:application-inference-profile/example",
                name = "Claude Sonnet 4.6",
            )
        val body =
            buildBedrockRequestBody(
                model,
                Context(
                    systemPrompt = "system",
                    messages = mutableListOf(UserMessage("hello")),
                    tools =
                        listOf(
                            ToolDefinition(
                                "echo",
                                "Echo",
                                buildJsonObject { put("type", "object") },
                            ),
                        ),
                ),
                StreamOptions(
                    toolChoice =
                        buildJsonObject {
                            put("type", "tool")
                            put("name", "echo")
                        },
                    cacheRetention = CacheRetention.LONG,
                    requestMetadata = mapOf("team" to "migration"),
                ),
                { null },
            )

        val system = body.getValue("system").jsonArray
        assertEquals("1h", system[1].jsonObject.getValue("cachePoint").jsonObject
            .getValue("ttl").jsonPrimitive.content)
        val lastContent =
            body.getValue("messages").jsonArray.last().jsonObject
                .getValue("content").jsonArray
        assertTrue("cachePoint" in lastContent.last().jsonObject)
        assertEquals(
            "echo",
            body.getValue("toolConfig").jsonObject
                .getValue("toolChoice").jsonObject
                .getValue("tool").jsonObject
                .getValue("name").jsonPrimitive.content,
        )
        assertEquals(
            "migration",
            body.getValue("requestMetadata").jsonObject.getValue("team").jsonPrimitive.content,
        )
    }

    @Test
    fun `builds adaptive fixed and GovCloud thinking payloads`() {
        val context = Context(messages = mutableListOf(UserMessage("hello")))
        val adaptive =
            buildBedrockRequestBody(
                bedrockModel("global.anthropic.claude-opus-4-8-v1", name = "Claude Opus 4.8"),
                context,
                StreamOptions(
                    reasoning = ThinkingLevel.XHIGH,
                    thinkingDisplay = BedrockThinkingDisplay.OMITTED,
                ),
                { null },
            ).getValue("additionalModelRequestFields").jsonObject
        assertEquals("adaptive", adaptive.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("omitted", adaptive.getValue("thinking").jsonObject.getValue("display").jsonPrimitive.content)
        assertEquals("xhigh", adaptive.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content)
        assertFalse("anthropic_beta" in adaptive)

        val fixed =
            buildBedrockRequestBody(
                bedrockModel("us.anthropic.claude-sonnet-4-5-20250929-v1:0"),
                context,
                StreamOptions(reasoning = ThinkingLevel.MEDIUM),
                { null },
            ).getValue("additionalModelRequestFields").jsonObject
        assertEquals(8_192, fixed.getValue("thinking").jsonObject
            .getValue("budget_tokens").jsonPrimitive.content.toInt())
        assertEquals(
            "interleaved-thinking-2025-05-14",
            fixed.getValue("anthropic_beta").jsonArray.single().jsonPrimitive.content,
        )

        val gov =
            buildBedrockRequestBody(
                bedrockModel(
                    "arn:aws-us-gov:bedrock:us-gov-west-1:123456789012:" +
                        "application-inference-profile/example",
                    name = "Claude Opus 4.8",
                ),
                context,
                StreamOptions(reasoning = ThinkingLevel.HIGH),
                { null },
            ).getValue("additionalModelRequestFields").jsonObject
        assertFalse("display" in gov.getValue("thinking").jsonObject)
    }

    @Test
    fun `streamSimple reserves fixed thinking budget and leaves adaptive output cap unchanged`() {
        val context = Context(messages = mutableListOf(UserMessage("hello")))
        val fixed =
            bedrockSimpleStreamOptions(
                bedrockModel("us.anthropic.claude-sonnet-4-5-20250929-v1:0"),
                context,
                SimpleStreamOptions(
                    stream = StreamOptions(maxTokens = 2_000),
                    reasoning = ThinkingLevel.MEDIUM,
                    thinkingBudgets = ThinkingBudgets(medium = 5_000),
                ),
            )
        assertEquals(7_000, fixed.maxTokens)
        assertEquals(5_000, fixed.thinkingBudgets?.medium)

        val adaptive =
            bedrockSimpleStreamOptions(
                bedrockModel("global.anthropic.claude-opus-4-8-v1", name = "Claude Opus 4.8"),
                context,
                SimpleStreamOptions(
                    stream = StreamOptions(maxTokens = 2_000),
                    reasoning = ThinkingLevel.HIGH,
                ),
            )
        assertEquals(2_000, adaptive.maxTokens)
        assertEquals(ThinkingLevel.HIGH, adaptive.reasoning)
    }

    @Test
    fun `resolves ARN region endpoint profiles bearer keys and skip auth`() {
        val standard = bedrockModel("model").copy(baseUrl = "https://bedrock-runtime.eu-central-1.amazonaws.com")
        val endpoint =
            resolveBedrockClientConfiguration(standard, StreamOptions(), { null })
        assertEquals("eu-central-1", endpoint.region)
        assertEquals(standard.baseUrl, endpoint.endpoint)
        assertEquals(BedrockAuthMode.DEFAULT, endpoint.authMode)

        val configured =
            resolveBedrockClientConfiguration(
                standard,
                StreamOptions(env = mapOf("AWS_REGION" to "us-east-2")),
                { null },
            )
        assertEquals("us-east-2", configured.region)
        assertNull(configured.endpoint)

        val scopedProfile =
            resolveBedrockClientConfiguration(
                standard,
                StreamOptions(env = mapOf("AWS_PROFILE" to "bedrock-profile")),
                { null },
            )
        assertEquals(BedrockAuthMode.PROFILE, scopedProfile.authMode)
        assertEquals("bedrock-profile", scopedProfile.profile)
        assertEquals("eu-central-1", scopedProfile.region)
        assertEquals(standard.baseUrl, scopedProfile.endpoint)

        val ambientProfile =
            resolveBedrockClientConfiguration(
                standard,
                StreamOptions(),
                mapOf("AWS_PROFILE" to "ambient-profile")::get,
            )
        assertEquals(BedrockAuthMode.PROFILE, ambientProfile.authMode)
        assertNull(ambientProfile.region)
        assertNull(ambientProfile.endpoint)

        val arn =
            resolveBedrockClientConfiguration(
                standard.copy(
                    id = "arn:aws-us-gov:bedrock:us-gov-west-1:123456789012:" +
                        "application-inference-profile/example",
                ),
                StreamOptions(env = mapOf("AWS_REGION" to "us-east-1")),
                { null },
            )
        assertEquals("us-gov-west-1", arn.region)

        val bearer =
            resolveBedrockClientConfiguration(
                standard,
                StreamOptions(
                    apiKey = "generic",
                    bearerToken = "explicit",
                    env = mapOf("AWS_BEARER_TOKEN_BEDROCK" to "ambient"),
                ),
                { null },
            )
        assertEquals(BedrockAuthMode.BEARER, bearer.authMode)
        assertEquals("explicit", bearer.bearerToken)

        val accessKeys =
            resolveBedrockClientConfiguration(
                standard,
                StreamOptions(
                    env =
                        mapOf(
                            "AWS_ACCESS_KEY_ID" to "access",
                            "AWS_SECRET_ACCESS_KEY" to "secret",
                            "AWS_SESSION_TOKEN" to "session",
                        ),
                ),
                { null },
            )
        assertEquals(BedrockAuthMode.ACCESS_KEY, accessKeys.authMode)
        assertEquals("session", accessKeys.sessionToken)

        val skip =
            resolveBedrockClientConfiguration(
                standard,
                StreamOptions(
                    apiKey = "ignored",
                    env = mapOf("AWS_BEDROCK_SKIP_AUTH" to "1"),
                ),
                { null },
            )
        assertEquals(BedrockAuthMode.SKIP, skip.authMode)
        assertNull(skip.bearerToken)

        val proxied =
            resolveBedrockClientConfiguration(
                standard,
                StreamOptions(
                    env =
                        mapOf(
                            "AWS_BEDROCK_FORCE_HTTP1" to "1",
                            "HTTPS_PROXY" to "http://proxy.example:8080",
                        ),
                ),
                { null },
            )
        assertTrue(proxied.forceHttp1)
        assertEquals("http://proxy.example:8080", proxied.proxyUrl)

        val bypassed =
            resolveBedrockClientConfiguration(
                standard,
                StreamOptions(
                    env =
                        mapOf(
                            "HTTPS_PROXY" to "http://proxy.example:8080",
                            "NO_PROXY" to ".amazonaws.com",
                        ),
                ),
                { null },
            )
        assertNull(bypassed.proxyUrl)
    }

    @Test
    fun `filters reserved custom headers case insensitively`() {
        val headers =
            bedrockCustomHeaders(
                mapOf("x-model" to "model"),
                mapOf(
                    "Authorization" to "evil",
                    "HOST" to "evil",
                    "X-Amz-Date" to "evil",
                    "x-allowed" to "ok",
                    "x-model" to null,
                ),
            )

        assertEquals(mapOf("x-allowed" to "ok"), headers)
    }

    @Test
    fun `streams Bedrock reasoning text tools usage and request through transport`() =
        runTest {
            val captured = AtomicReference<BedrockInvocation>()
            val transport =
                BedrockRuntimeTransport { invocation, onEvent ->
                    captured.set(invocation)
                    onEvent(BedrockStreamEvent.MessageStart("assistant"))
                    onEvent(BedrockStreamEvent.ContentDelta(0, reasoningText = "think"))
                    onEvent(BedrockStreamEvent.ContentDelta(0, reasoningSignature = "sig"))
                    onEvent(BedrockStreamEvent.ContentStop(0))
                    onEvent(BedrockStreamEvent.ContentDelta(1, text = "answer"))
                    onEvent(BedrockStreamEvent.ContentStop(1))
                    onEvent(BedrockStreamEvent.ContentStart(2, "tool-1", "echo"))
                    onEvent(BedrockStreamEvent.ContentDelta(2, toolInput = "{\"value\":"))
                    onEvent(BedrockStreamEvent.ContentDelta(2, toolInput = "\"ok\"}"))
                    onEvent(BedrockStreamEvent.ContentStop(2))
                    onEvent(BedrockStreamEvent.MessageStop("tool_use"))
                    onEvent(BedrockStreamEvent.Metadata(10, 5, 3, 2, 20))
                }
            val model = bedrockModel("us.anthropic.claude-opus-4-8")
            val provider =
                BedrockProvider(
                    id = "amazon-bedrock",
                    name = "Amazon Bedrock",
                    models = listOf(model),
                    environment = { null },
                    transport = transport,
                )
            val stream =
                provider.stream(
                    model,
                    Context(messages = mutableListOf(UserMessage("hello"))),
                    StreamOptions(
                        apiKey = "token",
                        reasoning = ThinkingLevel.HIGH,
                        headers = mapOf("x-fixture" to "yes"),
                    ),
                )
            val events = stream.events.toList()
            val result = stream.result()

            assertEquals(StopReason.TOOL_USE, result.stopReason)
            assertEquals("think", (result.content[0] as ThinkingContent).thinking)
            assertEquals("sig", (result.content[0] as ThinkingContent).thinkingSignature)
            assertEquals("answer", (result.content[1] as TextContent).text)
            assertEquals(
                "ok",
                (result.content[2] as ToolCall).arguments.getValue("value").jsonPrimitive.content,
            )
            assertEquals(10, result.usage.input)
            assertEquals(5, result.usage.output)
            assertEquals(3, result.usage.cacheRead)
            assertEquals(2, result.usage.cacheWrite)
            assertEquals(20, result.usage.totalTokens)
            assertTrue(events.last() is AssistantDone)
            assertEquals(
                listOf(
                    "AssistantStart",
                    "ThinkingStart",
                    "ThinkingDelta",
                    "ThinkingEnd",
                    "TextStart",
                    "TextDelta",
                    "TextEnd",
                    "ToolCallStart",
                    "ToolCallDelta",
                    "ToolCallDelta",
                    "ToolCallEnd",
                    "AssistantDone",
                ),
                events.map { it::class.simpleName },
            )
            assertEquals(BedrockAuthMode.BEARER, captured.get().client.authMode)
            assertEquals("yes", captured.get().headers["x-fixture"])
            assertEquals(model.id, captured.get().request.getValue("modelId").jsonPrimitive.content)
        }

    @Test
    fun `AWS SDK sends signed ConverseStream request to a custom endpoint`() =
        runTest {
            val capturedPath = AtomicReference<String>()
            val capturedBody = AtomicReference<JsonObject>()
            val capturedHeaders = AtomicReference<Map<String, String>>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                capturedPath.set(exchange.requestURI.path)
                capturedBody.set(
                    providerJson.parseToJsonElement(
                        exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8),
                    ).jsonObject,
                )
                capturedHeaders.set(
                    exchange.requestHeaders.entries.associate { (name, values) ->
                        name.lowercase() to values.single()
                    },
                )
                val response = """{"message":"fixture stop"}""".toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("content-type", "application/json")
                exchange.responseHeaders.add("x-amzn-errortype", "ValidationException")
                exchange.sendResponseHeaders(400, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            try {
                val model =
                    bedrockModel("fixture").copy(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                    )
                val provider =
                    BedrockProvider(
                        id = "amazon-bedrock",
                        name = "Amazon Bedrock",
                        models = listOf(model),
                        environment = { null },
                    )
                val result =
                    provider
                        .stream(
                            model,
                            Context(messages = mutableListOf(UserMessage("hello"))),
                            StreamOptions(
                                cacheRetention = CacheRetention.NONE,
                                env =
                                    mapOf(
                                        "AWS_BEDROCK_SKIP_AUTH" to "1",
                                        "AWS_BEDROCK_FORCE_HTTP1" to "1",
                                    ),
                                headers =
                                    mapOf(
                                        "Authorization" to "blocked",
                                        "x-fixture" to "yes",
                                    ),
                            ),
                        ).result()

                assertEquals(StopReason.ERROR, result.stopReason)
                assertTrue(
                    result.errorMessage.orEmpty().contains("fixture stop"),
                    result.errorMessage,
                )
                assertEquals("/model/fixture/converse-stream", capturedPath.get())
                assertEquals(
                    "hello",
                    capturedBody.get().getValue("messages").jsonArray.single().jsonObject
                        .getValue("content").jsonArray.single().jsonObject
                        .getValue("text").jsonPrimitive.content,
                )
                assertEquals("yes", capturedHeaders.get()["x-fixture"])
                val authorization = capturedHeaders.get().getValue("authorization")
                assertTrue(authorization.startsWith("AWS4-HMAC-SHA256"))
                assertTrue(authorization.contains("x-fixture"))
                assertFalse(authorization.contains("blocked"))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `AWS SDK sends Bedrock bearer token without SigV4 credentials`() =
        runTest {
            val capturedHeaders = AtomicReference<Map<String, String>>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.requestBody.readAllBytes()
                capturedHeaders.set(
                    exchange.requestHeaders.entries.associate { (name, values) ->
                        name.lowercase() to values.single()
                    },
                )
                val response = """{"message":"fixture stop"}""".toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("content-type", "application/json")
                exchange.responseHeaders.add("x-amzn-errortype", "ValidationException")
                exchange.sendResponseHeaders(400, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            try {
                val model =
                    bedrockModel("fixture").copy(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                    )
                val provider =
                    BedrockProvider(
                        id = "amazon-bedrock",
                        name = "Amazon Bedrock",
                        models = listOf(model),
                        environment = { null },
                    )
                val result =
                    provider
                        .stream(
                            model,
                            Context(messages = mutableListOf(UserMessage("hello"))),
                            StreamOptions(
                                apiKey = "bedrock-token",
                                cacheRetention = CacheRetention.NONE,
                                env = mapOf("AWS_BEDROCK_FORCE_HTTP1" to "1"),
                            ),
                        ).result()

                assertEquals(StopReason.ERROR, result.stopReason)
                assertEquals("Bearer bedrock-token", capturedHeaders.get()["authorization"])
                assertFalse("x-amz-date" in capturedHeaders.get())
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `inserts synthetic results for orphaned tool calls`() {
        val model = bedrockModel("model")
        val body =
            buildBedrockRequestBody(
                model,
                Context(
                    messages =
                        mutableListOf(
                            AssistantMessage(
                                content =
                                    listOf(
                                        ToolCall(
                                            id = "tool-1",
                                            name = "echo",
                                            arguments = JsonObject(emptyMap()),
                                        ),
                                    ),
                                api = model.api,
                                provider = model.provider,
                                model = model.id,
                                stopReason = StopReason.TOOL_USE,
                            ),
                            UserMessage("continue"),
                        ),
                ),
                StreamOptions(cacheRetention = CacheRetention.NONE),
                { null },
            )
        val messages = body.getValue("messages").jsonArray
        assertEquals(3, messages.size)
        val synthetic =
            messages[1].jsonObject.getValue("content").jsonArray.single().jsonObject
                .getValue("toolResult").jsonObject
        assertEquals("tool-1", synthetic.getValue("toolUseId").jsonPrimitive.content)
        assertEquals("error", synthetic.getValue("status").jsonPrimitive.content)
        assertEquals(
            "No result provided",
            synthetic.getValue("content").jsonArray.single().jsonObject
                .getValue("text").jsonPrimitive.content,
        )
    }

    private fun bedrockModel(
        id: String,
        name: String = id,
        input: List<ModelInput> = listOf(ModelInput.TEXT, ModelInput.IMAGE),
    ): Model =
        model(
            id = id,
            name = name,
            api = "bedrock-converse-stream",
            provider = "amazon-bedrock",
            baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com",
            reasoning = true,
            input = input,
            contextWindow = 200_000,
            maxTokens = 64_000,
        )
}
