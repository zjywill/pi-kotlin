package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.ApiKeyCredential
import works.earendil.pi.ai.AssistantImages
import works.earendil.pi.ai.AuthResult
import works.earendil.pi.ai.Cost
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.ImagesContext
import works.earendil.pi.ai.ImagesModel
import works.earendil.pi.ai.ImagesModels
import works.earendil.pi.ai.ImagesOptions
import works.earendil.pi.ai.InMemoryCredentialStore
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.ProviderResponse
import works.earendil.pi.ai.TextContent

fun main() =
    runBlocking {
        val requests = mutableListOf<OpenRouterImagesOracleRequest>()
        val retryAttempts = AtomicInteger()
        val fixture =
            HttpServer
                .create(InetSocketAddress("127.0.0.1", 0), 0)
                .apply {
                    createContext("/") { exchange ->
                        requests += exchange.captureOpenRouterImagesRequest()
                        when (exchange.requestHeaders.firstValue("x-oracle-case")) {
                            "error" ->
                                exchange.respondJson(
                                    status = 403,
                                    body = """{"error":{"message":"blocked by image fixture"}}""",
                                )

                            "retry" if retryAttempts.getAndIncrement() == 0 -> {
                                exchange.responseHeaders.add("retry-after-ms", "1")
                                exchange.respondJson(
                                    status = 429,
                                    body = """{"error":{"message":"retry image request"}}""",
                                )
                            }

                            else ->
                                exchange.respondJson(
                                    status = 200,
                                    body = openRouterImagesSuccessBody(),
                                    headers = mapOf("x-fixture" to "openrouter-images"),
                                )
                        }
                    }
                    start()
                }
        try {
            val baseUrl = "http://127.0.0.1:${fixture.address.port}/v1"
            val client = HttpClient.newHttpClient()
            val model = openRouterImagesOracleModel(baseUrl)
            val context =
                ImagesContext(
                    input =
                        listOf(
                            TextContent("Generate \uD83D image"),
                            ImageContent(mimeType = "image/png", data = "aW5wdXQ="),
                        ),
                )
            var payloadCallbackModel = ""
            var responseCallback: OpenRouterImagesResponseCallback? = null
            val direct =
                generateOpenRouterImages(
                    model = model,
                    context = context,
                    options =
                        ImagesOptions(
                            apiKey = "direct-key",
                            headers =
                                mapOf(
                                    "X-Model" to "request-value",
                                    "X-Remove" to null,
                                    "X-Request" to "request-only",
                                    "X-Oracle-Case" to "retry",
                                ),
                            maxRetries = 1,
                            onPayload = { payload, callbackModel ->
                                payloadCallbackModel = callbackModel.id
                                JsonObject(
                                    payload.jsonObject +
                                        ("payload_tag" to kotlinx.serialization.json.JsonPrimitive("replaced")),
                                )
                            },
                            onResponse = { response, callbackModel ->
                                responseCallback =
                                    OpenRouterImagesResponseCallback(
                                        status = response.status,
                                        fixtureHeader = response.headers["x-fixture"],
                                        model = callbackModel.id,
                                    )
                            },
                        ),
                    client = client,
                )
            val directRequest = requests.last()
            val errorResult =
                generateOpenRouterImages(
                    model = model.copy(headers = mapOf("X-Oracle-Case" to "error")),
                    context = context,
                    options = ImagesOptions(apiKey = "error-key", maxRetries = 0),
                    client = client,
                )
            val missingKeyResult =
                generateOpenRouterImages(
                    model = model,
                    context = context,
                    options = ImagesOptions(),
                    client = client,
                )

            val credentials =
                InMemoryCredentialStore(
                    mapOf(
                        "openrouter" to
                            OAuthCredential(
                                access = "stored-openrouter-key",
                                refresh = "",
                                expires = Long.MAX_VALUE,
                            ),
                    ),
                )
            val imagesModels =
                ImagesModels(
                    providers =
                        listOf(
                            OpenRouterImagesProvider(
                                client = client,
                                models = builtInImageModels("openrouter"),
                            ),
                        ),
                    credentials = credentials,
                )
            val storedAuth = imagesModels.getAuth("openrouter")
            val oauthResult =
                imagesModels.generateImages(
                    model.copy(headers = mapOf("X-Oracle-Case" to "oauth")),
                    context,
                )
            val oauthRequest = requests.last()
            val explicitResult =
                imagesModels.generateImages(
                    model.copy(headers = mapOf("X-Oracle-Case" to "explicit")),
                    context,
                    ImagesOptions(apiKey = "explicit-key"),
                )
            val explicitRequest = requests.last()

            val catalog = builtInImageModels("openrouter")
            val output =
                buildJsonObject {
                    put(
                        "catalog",
                        buildJsonObject {
                            put("providers", buildJsonArray { addString("openrouter") })
                            put("count", catalog.size)
                            put("hash", builtInImageCatalogHash())
                            put("first", imageModelProjection(catalog.first()))
                            put("last", imageModelProjection(catalog.last()))
                        },
                    )
                    put(
                        "direct",
                        buildJsonObject {
                            put("request", directRequest.toJson())
                            put("retryAttempts", retryAttempts.get())
                            put("payloadCallbackModel", payloadCallbackModel)
                            put(
                                "responseCallback",
                                requireNotNull(responseCallback).toJson(),
                            )
                            put("result", imageResultProjection(direct))
                        },
                    )
                    put(
                        "errors",
                        buildJsonObject {
                            put("http", imageResultProjection(errorResult))
                            put("missingKey", imageResultProjection(missingKeyResult))
                        },
                    )
                    put(
                        "auth",
                        buildJsonObject {
                            put("stored", authProjection(requireNotNull(storedAuth)))
                            put("oauthAuthorization", oauthRequest.authorization)
                            put("oauthResult", imageResultProjection(oauthResult))
                            put("explicitAuthorization", explicitRequest.authorization)
                            put("explicitResult", imageResultProjection(explicitResult))
                        },
                    )
                }
            println(providerJson.encodeToString(JsonObject.serializer(), output))
        } finally {
            fixture.stop(0)
        }
    }

private data class OpenRouterImagesOracleRequest(
    val path: String,
    val authorization: String?,
    val modelHeader: String?,
    val removedHeader: String?,
    val requestHeader: String?,
    val body: JsonObject,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("path", path)
            authorization?.let { put("authorization", it) }
            modelHeader?.let { put("modelHeader", it) }
            removedHeader?.let { put("removedHeader", it) }
            requestHeader?.let { put("requestHeader", it) }
            put("body", body)
        }
}

private data class OpenRouterImagesResponseCallback(
    val status: Int,
    val fixtureHeader: String?,
    val model: String,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("status", status)
            putNullable("fixtureHeader", fixtureHeader)
            put("model", model)
        }
}

private fun HttpExchange.captureOpenRouterImagesRequest(): OpenRouterImagesOracleRequest =
    OpenRouterImagesOracleRequest(
        path = requestURI.toString(),
        authorization = requestHeaders.firstValue("authorization"),
        modelHeader = requestHeaders.firstValue("x-model"),
        removedHeader = requestHeaders.firstValue("x-remove"),
        requestHeader = requestHeaders.firstValue("x-request"),
        body =
            providerJson
                .parseToJsonElement(
                    requestBody.readBytes().toString(StandardCharsets.UTF_8),
                ).jsonObject,
    )

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

private fun com.sun.net.httpserver.Headers.firstValue(name: String): String? =
    entries.firstOrNull { (header, _) -> header.equals(name, ignoreCase = true) }?.value?.joinToString(",")

private fun openRouterImagesOracleModel(baseUrl: String): ImagesModel =
    ImagesModel(
        id = "oracle/image-model",
        name = "Oracle Image Model",
        api = "openrouter-images",
        provider = "openrouter",
        baseUrl = baseUrl,
        input = listOf(ModelInput.TEXT, ModelInput.IMAGE),
        output = listOf(ModelInput.IMAGE, ModelInput.TEXT),
        cost =
            ModelCost(
                input = 1.0,
                output = 2.0,
                cacheRead = 3.0,
                cacheWrite = 4.0,
            ),
        headers =
            mapOf(
                "X-Model" to "model-value",
                "X-Remove" to "remove-me",
            ),
    )

private fun openRouterImagesSuccessBody(): String =
    """
    {
      "id":"img-response-1",
      "usage":{
        "prompt_tokens":20,
        "completion_tokens":7,
        "prompt_tokens_details":{"cached_tokens":8,"cache_write_tokens":3}
      },
      "choices":[{
        "message":{
          "content":"Rendered image",
          "images":[
            {"image_url":"data:image/png;base64,cG5n"},
            {"image_url":{"url":"data:image/jpeg;base64,anBlZw=="}},
            {"image_url":"https://example.test/not-data.png"},
            {"image_url":"data:image/webp;not-base64,d2VicA=="}
          ]
        }
      }]
    }
    """.trimIndent()

private fun imageResultProjection(result: AssistantImages): JsonObject =
    buildJsonObject {
        put("api", result.api)
        put("provider", result.provider)
        put("model", result.model)
        put(
            "output",
            buildJsonArray {
                result.output.forEach { content ->
                    add(
                        when (content) {
                            is TextContent ->
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", content.text)
                                }

                            is ImageContent ->
                                buildJsonObject {
                                    put("type", "image")
                                    put("mimeType", content.mimeType)
                                    put("data", content.data)
                                }
                        },
                    )
                }
            },
        )
        result.responseId?.let { put("responseId", it) }
        result.usage?.let { usage ->
            put(
                "usage",
                buildJsonObject {
                    put("input", usage.input)
                    put("output", usage.output)
                    put("cacheRead", usage.cacheRead)
                    put("cacheWrite", usage.cacheWrite)
                    put("totalTokens", usage.totalTokens)
                    put("cost", costProjection(usage.cost))
                },
            )
        }
        put("stopReason", result.stopReason.serialValue())
        result.errorMessage?.let { put("errorMessage", it) }
        put("timestampPositive", result.timestamp > 0)
    }

private fun imageModelProjection(model: ImagesModel): JsonObject =
    buildJsonObject {
        put("id", model.id)
        put("name", model.name)
        put("api", model.api)
        put("provider", model.provider)
        put("baseUrl", model.baseUrl)
        put(
            "input",
            buildJsonArray {
                model.input.forEach { addString(it.serialValue()) }
            },
        )
        put(
            "output",
            buildJsonArray {
                model.output.forEach { addString(it.serialValue()) }
            },
        )
        put(
            "cost",
            buildJsonObject {
                putCanonicalNumber("input", model.cost.input)
                putCanonicalNumber("output", model.cost.output)
                putCanonicalNumber("cacheRead", model.cost.cacheRead)
                putCanonicalNumber("cacheWrite", model.cost.cacheWrite)
            },
        )
    }

private fun costProjection(cost: Cost): JsonObject =
    buildJsonObject {
        put("input", cost.input)
        put("output", cost.output)
        put("cacheRead", cost.cacheRead)
        put("cacheWrite", cost.cacheWrite)
        put("total", cost.total)
    }

private fun authProjection(result: AuthResult): JsonObject =
    buildJsonObject {
        put(
            "auth",
            buildJsonObject {
                result.auth.apiKey?.let { put("apiKey", it) }
            },
        )
        put("source", result.source)
    }

private fun works.earendil.pi.ai.ImagesStopReason.serialValue(): String =
    when (this) {
        works.earendil.pi.ai.ImagesStopReason.STOP -> "stop"
        works.earendil.pi.ai.ImagesStopReason.ERROR -> "error"
        works.earendil.pi.ai.ImagesStopReason.ABORTED -> "aborted"
    }

private fun ModelInput.serialValue(): String =
    when (this) {
        ModelInput.TEXT -> "text"
        ModelInput.IMAGE -> "image"
    }

private fun kotlinx.serialization.json.JsonArrayBuilder.addString(value: String) {
    add(kotlinx.serialization.json.JsonPrimitive(value))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    name: String,
    value: String?,
) {
    if (value == null) {
        put(name, JsonNull)
    } else {
        put(name, value)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putCanonicalNumber(
    name: String,
    value: Double,
) {
    if (value % 1.0 == 0.0) {
        put(name, value.toLong())
    } else {
        put(name, value)
    }
}
