package works.earendil.pi.codingagent

import works.earendil.pi.ai.Model
import works.earendil.pi.ai.Models

internal data class ModelReference(
    val provider: String?,
    val modelId: String?,
    val thinking: AgentThinkingLevel?,
)

internal fun parseModelReference(
    explicitProvider: String?,
    rawModel: String?,
): ModelReference {
    if (rawModel.isNullOrBlank()) {
        return ModelReference(explicitProvider, null, null)
    }
    val suffixSeparator = rawModel.lastIndexOf(':')
    val suffix =
        if (suffixSeparator > 0 && suffixSeparator < rawModel.lastIndex) {
            parseThinkingLevel(rawModel.substring(suffixSeparator + 1))
        } else {
            null
        }
    val modelWithoutThinking =
        if (suffix != null) {
            rawModel.substring(0, suffixSeparator)
        } else {
            rawModel
        }
    val provider =
        explicitProvider
            ?: modelWithoutThinking.substringBefore('/', missingDelimiterValue = "")
                .takeIf(String::isNotEmpty)
    val modelId =
        when {
            explicitProvider != null && modelWithoutThinking.startsWith("$explicitProvider/") ->
                modelWithoutThinking.removePrefix("$explicitProvider/")

            explicitProvider != null -> modelWithoutThinking
            provider != null -> modelWithoutThinking.substringAfter('/')
            else -> modelWithoutThinking
    }
    return ModelReference(provider, modelId, suffix)
}

internal suspend fun resolveExactModelReference(
    models: Models,
    explicitProvider: String?,
    rawModel: String?,
): Model? {
    if (explicitProvider != null || rawModel.isNullOrBlank()) {
        return null
    }
    val requested = rawModel.withoutThinkingSuffix()
    val exactMatches =
        models.getModels().filter { model ->
            model.id.equals(requested, ignoreCase = true) ||
                "${model.provider}/${model.id}".equals(requested, ignoreCase = true)
        }
    val authenticatedProviders =
        exactMatches
            .map(Model::provider)
            .distinct()
            .filterTo(linkedSetOf()) { provider ->
                models.checkAuth(provider) != null
            }
    return selectExactModelReference(requested, exactMatches, authenticatedProviders)
}

internal fun selectExactModelReference(
    requested: String,
    exactMatches: List<Model>,
    authenticatedProviders: Set<String>,
): Model? {
    if (exactMatches.size <= 1) {
        return exactMatches.singleOrNull()
    }
    val authenticatedMatches =
        exactMatches.filter { model -> model.provider in authenticatedProviders }
    if (authenticatedMatches.size == 1) {
        return authenticatedMatches.single()
    }
    val matches =
        exactMatches
            .map { model -> "${model.provider}/${model.id}" }
            .sorted()
            .joinToString(", ")
    val authHint =
        if (authenticatedMatches.isEmpty()) {
            "No matching provider is authenticated."
        } else {
            "More than one matching provider is authenticated."
        }
    error(
        "Model \"$requested\" is ambiguous across providers: $matches. " +
            "$authHint Use --provider or provider/model.",
    )
}

private fun String.withoutThinkingSuffix(): String {
    val separator = lastIndexOf(':')
    return if (
        separator > 0 &&
        separator < lastIndex &&
        parseThinkingLevel(substring(separator + 1)) != null
    ) {
        substring(0, separator)
    } else {
        this
    }
}
