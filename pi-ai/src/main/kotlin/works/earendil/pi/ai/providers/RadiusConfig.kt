package works.earendil.pi.ai.providers

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.ModelThinkingLevel
import works.earendil.pi.ai.OAuthCredential

const val DEFAULT_RADIUS_GATEWAY = "https://radius.pi.dev"

@Serializable
data class RadiusGatewayConfig(
    val baseUrl: String,
    val models: List<RadiusGatewayModel>,
)

@Serializable
data class RadiusGatewayModel(
    val id: String,
    val name: String,
    val reasoning: Boolean,
    val thinkingLevelMap: Map<ModelThinkingLevel, String?> = emptyMap(),
    val input: List<ModelInput>,
    val cost: ModelCost,
    val contextWindow: Int,
    val maxTokens: Int,
)

fun normalizeRadiusGatewayUrl(value: String): String {
    val withScheme =
        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
            value
        } else {
            "https://$value"
        }
    return withScheme.trimEnd('/')
}

internal fun getRadiusCredentialConfig(credential: OAuthCredential?): RadiusGatewayConfig? =
    credential?.gatewayConfig?.let(::sanitizeRadiusGatewayConfig)

internal fun getRadiusModels(
    providerId: String,
    credential: OAuthCredential?,
): List<Model> =
    getRadiusCredentialConfig(credential)
        ?.let { getRadiusModelsFromConfig(providerId, it) }
        .orEmpty()

internal fun getRadiusModelsFromConfig(
    providerId: String,
    config: RadiusGatewayConfig,
): List<Model> =
    config.models.map { model ->
        Model(
            id = model.id,
            name = model.name,
            api = "pi-messages",
            provider = providerId,
            baseUrl = config.baseUrl,
            reasoning = model.reasoning,
            thinkingLevelMap = model.thinkingLevelMap,
            input = model.input,
            cost = model.cost,
            contextWindow = model.contextWindow,
            maxTokens = model.maxTokens,
        )
    }

internal suspend fun loadRadiusGatewayConfig(
    gateway: String,
    apiKey: String?,
    client: HttpClient = defaultRadiusHttpClient(),
): RadiusGatewayConfig {
    val request =
        HttpRequest
            .newBuilder(URI.create(normalizeRadiusGatewayUrl(gateway)).resolve("/v1/config"))
            .header("accept", "application/json")
            .apply {
                apiKey?.takeIf(String::isNotBlank)?.let { header("authorization", "Bearer $it") }
            }.GET()
            .build()
    val response =
        withContext(Dispatchers.IO) {
            client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }
    if (response.statusCode() !in 200..299) {
        error(
            "Could not load Radius config from ${normalizeRadiusGatewayUrl(gateway)}: " +
                "${response.statusCode()}: ${truncateRadiusHttpBody(response.body())}",
        )
    }
    return sanitizeRadiusGatewayConfig(
        runCatching { providerJson.parseToJsonElement(response.body()) }.getOrNull(),
    ) ?: error("Invalid Radius config from ${normalizeRadiusGatewayUrl(gateway)}")
}

internal fun sanitizeRadiusGatewayConfig(config: JsonElement?): RadiusGatewayConfig? {
    val root = config as? JsonObject ?: return null
    val baseUrl = root.stringValue("baseUrl") ?: return null
    val rawModels = root["models"] as? JsonArray ?: return null
    return RadiusGatewayConfig(
        baseUrl = baseUrl,
        models = rawModels.mapNotNull(::sanitizeRadiusGatewayModel),
    )
}

private fun sanitizeRadiusGatewayModel(value: JsonElement): RadiusGatewayModel? {
    val model = value as? JsonObject ?: return null
    val id = model.stringValue("id") ?: return null
    val name = model.stringValue("name") ?: return null
    val reasoning = model.booleanValue("reasoning") ?: return null
    val input =
        (model["input"] as? JsonArray)
            ?.mapNotNull { raw ->
                when ((raw as? JsonPrimitive)?.contentOrNull) {
                    "text" -> ModelInput.TEXT
                    "image" -> ModelInput.IMAGE
                    else -> null
                }
            } ?: return null
    val cost =
        runCatching {
            providerJson.decodeFromJsonElement<ModelCost>(
                model["cost"] as? JsonObject ?: return null,
            )
        }.getOrNull() ?: return null
    val contextWindow = model.numberValue("contextWindow")?.toInt() ?: return null
    val maxTokens = model.numberValue("maxTokens")?.toInt() ?: return null
    val thinkingLevelMap =
        (model["thinkingLevelMap"] as? JsonObject)
            ?.mapNotNull { (key, rawValue) ->
                val level =
                    when (key) {
                        "off" -> ModelThinkingLevel.OFF
                        "minimal" -> ModelThinkingLevel.MINIMAL
                        "low" -> ModelThinkingLevel.LOW
                        "medium" -> ModelThinkingLevel.MEDIUM
                        "high" -> ModelThinkingLevel.HIGH
                        "xhigh" -> ModelThinkingLevel.XHIGH
                        "max" -> ModelThinkingLevel.MAX
                        else -> null
                    } ?: return@mapNotNull null
                val mapped =
                    when (rawValue) {
                        is JsonPrimitive -> rawValue.contentOrNull
                        else -> null
                    }
                level to mapped
            }?.toMap()
            .orEmpty()
    return RadiusGatewayModel(
        id = id,
        name = name,
        reasoning = reasoning,
        thinkingLevelMap = thinkingLevelMap,
        input = input,
        cost = cost,
        contextWindow = contextWindow,
        maxTokens = maxTokens,
    )
}

private fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull

private fun JsonObject.booleanValue(name: String): Boolean? =
    (this[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.booleanOrNull

private fun JsonObject.numberValue(name: String): Double? {
    val value =
        (this[name] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?: return null
    return value.intOrNull?.toDouble() ?: value.doubleOrNull
}

private fun truncateRadiusHttpBody(body: String): String {
    val trimmed = body.trim()
    return if (trimmed.length > MAX_RADIUS_HTTP_BODY_LENGTH) {
        trimmed.take(MAX_RADIUS_HTTP_BODY_LENGTH) + "\u2026"
    } else {
        trimmed
    }
}

internal fun defaultRadiusHttpClient(): HttpClient =
    HttpClient
        .newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

private const val MAX_RADIUS_HTTP_BODY_LENGTH = 512
