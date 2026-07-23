package works.earendil.pi.codingagent

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
