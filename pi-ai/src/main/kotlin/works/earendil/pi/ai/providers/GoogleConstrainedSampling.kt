package works.earendil.pi.ai.providers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.resolveJsonSchemaStrictSampling

internal fun resolveGoogleFunctionCallingMode(
    modelId: String,
    tools: List<ToolDefinition>,
    toolChoice: JsonElement?,
): String? {
    val explicit = (toolChoice as? JsonPrimitive)?.content?.lowercase()
    if (explicit == "none") {
        return "NONE"
    }
    if (explicit == "any") {
        return "ANY"
    }
    val supportsStrictMode = supportsGoogleStrictToolSampling(modelId)
    val strict =
        tools.any { tool ->
            resolveJsonSchemaStrictSampling(tool, supportsStrictMode) == true
        }
    return when {
        strict -> "VALIDATED"
        explicit != null -> "AUTO"
        else -> null
    }
}

internal fun supportsGoogleStrictToolSampling(modelId: String): Boolean {
    val match =
        Regex("""(?:^|[^a-z0-9])gemini-(\d+)(?:[.\-]|$)""")
            .find(modelId.lowercase())
            ?: return false
    return match.groupValues[1].toIntOrNull()?.let { it >= 3 } == true
}
