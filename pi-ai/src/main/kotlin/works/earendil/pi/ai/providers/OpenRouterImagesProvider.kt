package works.earendil.pi.ai.providers

import java.math.BigDecimal
import java.net.http.HttpClient
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import works.earendil.pi.ai.AssistantImages
import works.earendil.pi.ai.ConfigurableImagesProvider
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.ImagesContext
import works.earendil.pi.ai.ImagesFunction
import works.earendil.pi.ai.ImagesModel
import works.earendil.pi.ai.ImagesOptions
import works.earendil.pi.ai.ImagesProvider
import works.earendil.pi.ai.ImagesStopReason
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.ProviderResponse
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.Cost
import works.earendil.pi.ai.http.ProviderHttpException
import works.earendil.pi.ai.http.postJson
import works.earendil.pi.ai.imageFailure

class OpenRouterImagesProvider(
    private val client: HttpClient = defaultOpenRouterImagesHttpClient,
    models: List<ImagesModel> = builtInImageModels("openrouter"),
) : ImagesProvider {
    private val delegate =
        ConfigurableImagesProvider(
            id = "openrouter",
            name = "OpenRouter",
            apiKeyEnvNames = listOf("OPENROUTER_API_KEY"),
            oauth = OpenRouterOAuth(),
            initialModels = models,
            generate =
                ImagesFunction { model, context, options ->
                    generateOpenRouterImages(model, context, options, client)
                },
        )

    override val id: String
        get() = delegate.id
    override val name: String
        get() = delegate.name
    override val apiKeyEnvNames: List<String>
        get() = delegate.apiKeyEnvNames
    override val oauth
        get() = delegate.oauth

    override fun getModels(): List<ImagesModel> = delegate.getModels()

    override suspend fun generateImages(
        model: ImagesModel,
        context: ImagesContext,
        options: ImagesOptions,
    ): AssistantImages = delegate.generateImages(model, context, options)
}

fun openRouterImagesProvider(): ImagesProvider = OpenRouterImagesProvider()

suspend fun generateOpenRouterImages(
    model: ImagesModel,
    context: ImagesContext,
    options: ImagesOptions,
): AssistantImages =
    generateOpenRouterImages(
        model = model,
        context = context,
        options = options,
        client = defaultOpenRouterImagesHttpClient,
    )

internal suspend fun generateOpenRouterImages(
    model: ImagesModel,
    context: ImagesContext,
    options: ImagesOptions,
    client: HttpClient,
): AssistantImages {
    val output =
        AssistantImages(
            api = model.api,
            provider = model.provider,
            model = model.id,
            output = emptyList(),
        )
    return try {
        check(model.api == OPENROUTER_IMAGES_API) {
            "Mismatched api: ${model.api} expected $OPENROUTER_IMAGES_API"
        }
        val apiKey =
            options.apiKey?.takeIf(String::isNotBlank)
                ?: error("No API key for provider: ${model.provider}")
        var payload: kotlinx.serialization.json.JsonElement =
            buildOpenRouterImagesRequestBody(model, context)
        options.onPayload?.invoke(payload, model)?.let { replacement ->
            payload = replacement
        }
        val response =
            postJson(
                client = client,
                url = "${model.baseUrl.trimEnd('/')}/chat/completions",
                body = providerJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), payload),
                headers =
                    mergedHeaders(
                        base =
                            mapOf(
                                "authorization" to "Bearer $apiKey",
                            ),
                        model = model.headers,
                        override = options.headers,
                    ),
                timeoutMs = options.timeoutMs,
                maxRetries = options.maxRetries,
                maxRetryDelayMs = options.maxRetryDelayMs,
                fetch = options.fetch,
            )
        options.onResponse?.invoke(
            ProviderResponse(
                status = response.status,
                headers =
                    response.headers.mapValues { (_, values) ->
                        values.joinToString(", ")
                    },
            ),
            model,
        )
        parseOpenRouterImagesResponse(model, output, response.body)
    } catch (error: CancellationException) {
        imageFailure(model, ImagesStopReason.ABORTED, error.message ?: "Request aborted")
    } catch (error: Throwable) {
        imageFailure(model, ImagesStopReason.ERROR, formatOpenRouterImagesError(error))
    }
}

internal fun buildOpenRouterImagesRequestBody(
    model: ImagesModel,
    context: ImagesContext,
): JsonObject =
    buildJsonObject {
        put("model", model.id)
        put(
            "messages",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                context.input.forEach { content ->
                                    when (content) {
                                        is TextContent ->
                                            add(
                                                buildJsonObject {
                                                    put("type", "text")
                                                    put("text", sanitizeImageText(content.text))
                                                },
                                            )

                                        is ImageContent ->
                                            add(
                                                buildJsonObject {
                                                    put("type", "image_url")
                                                    put(
                                                        "image_url",
                                                        buildJsonObject {
                                                            put(
                                                                "url",
                                                                "data:${content.mimeType};base64,${content.data}",
                                                            )
                                                        },
                                                    )
                                                },
                                            )
                                    }
                                }
                            },
                        )
                    },
                )
            },
        )
        put("stream", false)
        put(
            "modalities",
            buildJsonArray {
                add(JsonPrimitive("image"))
                if (ModelInput.TEXT in model.output) {
                    add(JsonPrimitive("text"))
                }
            },
        )
    }

private fun parseOpenRouterImagesResponse(
    model: ImagesModel,
    base: AssistantImages,
    body: String,
): AssistantImages {
    val root = providerJson.parseToJsonElement(body).jsonObject
    val output = mutableListOf<works.earendil.pi.ai.ImagesContent>()
    val message =
        (root["choices"] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("message")
            ?.let { it as? JsonObject }
    message
        ?.get("content")
        ?.let { it as? JsonPrimitive }
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
        ?.takeIf(String::isNotEmpty)
        ?.let { output += TextContent(it) }
    val dataUrl = Regex("^data:([^;]+);base64,(.+)$")
    (message?.get("images") as? JsonArray).orEmpty().forEach { rawImage ->
        val image = rawImage as? JsonObject ?: return@forEach
        val imageUrl =
            when (val rawUrl = image["image_url"]) {
                is JsonPrimitive -> rawUrl.takeIf(JsonPrimitive::isString)?.contentOrNull
                is JsonObject ->
                    (rawUrl["url"] as? JsonPrimitive)
                        ?.takeIf(JsonPrimitive::isString)
                        ?.contentOrNull
                else -> null
            }
        val match = imageUrl?.let(dataUrl::matchEntire) ?: return@forEach
        output +=
            ImageContent(
                mimeType = match.groupValues[1],
                data = match.groupValues[2],
            )
    }
    val usage =
        root["usage"]
            ?.let { it as? JsonObject }
            ?.let { parseOpenRouterImagesUsage(it, model) }
    return base.copy(
        output = output,
        responseId = root.string("id"),
        usage = usage,
    )
}

internal fun parseOpenRouterImagesUsage(
    rawUsage: JsonObject,
    model: ImagesModel,
): Usage {
    val promptTokens = rawUsage.int("prompt_tokens") ?: 0
    val promptDetails = rawUsage.obj("prompt_tokens_details")
    val reportedCachedTokens = promptDetails?.int("cached_tokens") ?: 0
    val cacheWriteTokens = promptDetails?.int("cache_write_tokens") ?: 0
    val cacheReadTokens =
        if (cacheWriteTokens > 0) {
            (reportedCachedTokens - cacheWriteTokens).coerceAtLeast(0)
        } else {
            reportedCachedTokens
        }
    val input = (promptTokens - cacheReadTokens - cacheWriteTokens).coerceAtLeast(0)
    val output = rawUsage.int("completion_tokens") ?: 0
    val cost =
        Cost(
            input = model.cost.input / 1_000_000 * input,
            output = model.cost.output / 1_000_000 * output,
            cacheRead = model.cost.cacheRead / 1_000_000 * cacheReadTokens,
            cacheWrite = model.cost.cacheWrite / 1_000_000 * cacheWriteTokens,
        )
    return Usage(
        input = input,
        output = output,
        cacheRead = cacheReadTokens,
        cacheWrite = cacheWriteTokens,
        totalTokens = input + output + cacheReadTokens + cacheWriteTokens,
        cost =
            cost.copy(
                total = cost.input + cost.output + cost.cacheRead + cost.cacheWrite,
            ),
    )
}

fun builtInImageModels(provider: String): List<ImagesModel> =
    if (provider == "openrouter") {
        ImageCatalogHolder.models
    } else {
        emptyList()
    }

internal fun builtInImageCatalogHash(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(imageCatalogCanonicalBytes(ImageCatalogHolder.models))
        .joinToString("") { byte -> "%02x".format(byte) }

private object ImageCatalogHolder {
    val models: List<ImagesModel> by lazy {
        val resource = "/works/earendil/pi/ai/providers/data/image-models.json"
        val text =
            requireNotNull(ImageCatalogHolder::class.java.getResourceAsStream(resource)) {
                "Missing image model catalog resource: $resource"
            }.bufferedReader().use { it.readText() }
        providerJson.decodeFromString(ListSerializer(ImagesModel.serializer()), text)
    }
}

private fun imageCatalogCanonicalBytes(models: List<ImagesModel>): ByteArray =
    models
        .joinToString("\n") { model ->
            listOf(
                model.id,
                model.name,
                model.api,
                model.provider,
                model.baseUrl,
                model.input.joinToString(",") { it.serialValue() },
                model.output.joinToString(",") { it.serialValue() },
                canonicalNumber(model.cost.input),
                canonicalNumber(model.cost.output),
                canonicalNumber(model.cost.cacheRead),
                canonicalNumber(model.cost.cacheWrite),
            ).joinToString("\t")
        }.toByteArray(Charsets.UTF_8)

private fun ModelInput.serialValue(): String =
    when (this) {
        ModelInput.TEXT -> "text"
        ModelInput.IMAGE -> "image"
    }

private fun canonicalNumber(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private fun sanitizeImageText(value: String): String {
    val result = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val current = value[index]
        when {
            current.isHighSurrogate() &&
                index + 1 < value.length &&
                value[index + 1].isLowSurrogate() -> {
                result.append(current)
                result.append(value[index + 1])
                index += 2
            }

            current.isSurrogate() -> index += 1
            else -> {
                result.append(current)
                index += 1
            }
        }
    }
    return result.toString()
}

private fun formatOpenRouterImagesError(error: Throwable): String =
    when (error) {
        is ProviderHttpException -> {
            val body = error.body?.trim()?.take(4000)
            if (body.isNullOrEmpty()) {
                error.message.orEmpty()
            } else {
                val detail =
                    runCatching {
                        val root = providerJson.parseToJsonElement(body).jsonObject
                        val projected = root["error"] ?: root
                        providerJson.encodeToString(
                            kotlinx.serialization.json.JsonElement.serializer(),
                            projected,
                        )
                    }.getOrDefault(body)
                "${error.status}: $detail"
            }
        }

        else -> error.message ?: error::class.simpleName.orEmpty()
    }

private const val OPENROUTER_IMAGES_API = "openrouter-images"

private val defaultOpenRouterImagesHttpClient: HttpClient = HttpClient.newHttpClient()
