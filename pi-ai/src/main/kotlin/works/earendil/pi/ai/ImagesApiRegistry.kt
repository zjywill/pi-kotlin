package works.earendil.pi.ai

import java.util.concurrent.ConcurrentHashMap
import works.earendil.pi.ai.providers.generateOpenRouterImages

data class ImagesApiProvider(
    val api: String,
    val generateImages: ImagesFunction,
)

private data class RegisteredImagesApiProvider(
    val provider: ImagesApiProvider,
    val sourceId: String?,
)

private val imagesApiProviderRegistry =
    ConcurrentHashMap<String, RegisteredImagesApiProvider>().apply {
        put(
            "openrouter-images",
            RegisteredImagesApiProvider(
                provider =
                    ImagesApiProvider(
                        api = "openrouter-images",
                        generateImages = ImagesFunction(::generateOpenRouterImages),
                    ),
                sourceId = "builtin",
            ),
        )
    }

fun registerImagesApiProvider(
    provider: ImagesApiProvider,
    sourceId: String? = null,
) {
    val checked =
        ImagesApiProvider(
            api = provider.api,
            generateImages =
                ImagesFunction { model, context, options ->
                    check(model.api == provider.api) {
                        "Mismatched api: ${model.api} expected ${provider.api}"
                    }
                    provider.generateImages.generateImages(model, context, options)
                },
        )
    imagesApiProviderRegistry[provider.api] = RegisteredImagesApiProvider(checked, sourceId)
}

fun getImagesApiProvider(api: String): ImagesApiProvider? = imagesApiProviderRegistry[api]?.provider

suspend fun generateImages(
    model: ImagesModel,
    context: ImagesContext,
    options: ImagesOptions = ImagesOptions(),
): AssistantImages {
    val provider =
        getImagesApiProvider(model.api)
            ?: error("No API provider registered for api: ${model.api}")
    return provider.generateImages.generateImages(model, context, options)
}
