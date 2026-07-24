package works.earendil.pi.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

sealed interface ImagesContent

data class ImagesContext(
    val input: List<ImagesContent>,
)

@Serializable
enum class ImagesStopReason {
    @SerialName("stop")
    STOP,

    @SerialName("error")
    ERROR,

    @SerialName("aborted")
    ABORTED,
}

data class AssistantImages(
    val api: String,
    val provider: String,
    val model: String,
    val output: List<ImagesContent>,
    val responseId: String? = null,
    val usage: Usage? = null,
    val stopReason: ImagesStopReason = ImagesStopReason.STOP,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class ImagesModel(
    val id: String,
    val name: String,
    val api: String,
    val provider: String,
    val baseUrl: String,
    val input: List<ModelInput>,
    val output: List<ModelInput>,
    val cost: ModelCost,
    val headers: Map<String, String> = emptyMap(),
)

data class ImagesOptions(
    val apiKey: String? = null,
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String?> = emptyMap(),
    val timeoutMs: Long? = null,
    val maxRetries: Int? = null,
    val maxRetryDelayMs: Long? = null,
    val metadata: JsonElement? = null,
    val onPayload: (suspend (JsonElement, ImagesModel) -> JsonElement?)? = null,
    val onResponse: (suspend (ProviderResponse, ImagesModel) -> Unit)? = null,
)

fun interface ImagesFunction {
    suspend fun generateImages(
        model: ImagesModel,
        context: ImagesContext,
        options: ImagesOptions,
    ): AssistantImages
}
