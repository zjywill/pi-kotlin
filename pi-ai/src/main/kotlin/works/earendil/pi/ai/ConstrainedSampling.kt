package works.earendil.pi.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
sealed interface ConstrainedSamplingConfig

@Serializable
@SerialName("json_schema")
data class JsonSchemaConstrainedSampling(
    val strict: ConstrainedSamplingStrict,
) : ConstrainedSamplingConfig

@Serializable
enum class ConstrainedSamplingStrict {
    @SerialName("prefer")
    PREFER,

    @SerialName("require")
    REQUIRE,
}

@Serializable
@SerialName("grammar")
data class GrammarConstrainedSamplingConfig(
    val variants: GrammarVariants,
) : ConstrainedSamplingConfig

@Serializable
data class GrammarVariants(
    @SerialName("openai_lark")
    val openAILark: String? = null,
    @SerialName("openai_regex")
    val openAIRegex: String? = null,
)

data class ResolvedGrammarConstrainedSampling(
    val format: String,
    val definition: String,
    val inputProperty: String,
)

data class GrammarToolInputJsonBuffer(
    var input: String = "",
    var started: Boolean = false,
    var closed: Boolean = false,
)

fun getGrammarToolInput(
    toolName: String,
    arguments: JsonObject,
    inputProperty: String,
): String =
    arguments[inputProperty]
        ?.jsonPrimitive
        ?.takeIf(JsonPrimitive::isString)
        ?.content
        ?: error("Grammar tool call \"$toolName\" requires argument \"$inputProperty\" to be a string.")

fun appendGrammarToolInputJsonDelta(
    buffer: GrammarToolInputJsonBuffer,
    inputProperty: String,
    nextInput: String,
    close: Boolean,
): String? {
    if (buffer.closed) {
        if (close && nextInput == buffer.input) {
            return null
        }
        error("grammar tool input for property \"$inputProperty\" changed after it was closed")
    }
    require(nextInput.startsWith(buffer.input)) {
        "grammar tool input for property \"$inputProperty\" changed non-monotonically"
    }

    val inputDelta = nextInput.removePrefix(buffer.input)
    if (!close && inputDelta.isEmpty()) {
        return null
    }

    val delta =
        buildString {
            if (!buffer.started) {
                append('{')
                append(JsonPrimitive(inputProperty))
                append(":\"")
                buffer.started = true
            }
            val encoded = JsonPrimitive(inputDelta).toString()
            append(encoded.substring(1, encoded.lastIndex))
            buffer.input = nextInput
            if (close) {
                append("\"}")
                buffer.closed = true
            }
        }
    return delta
}

fun resolveJsonSchemaStrictSampling(
    tool: ToolDefinition,
    supportsStrictMode: Boolean,
): Boolean? {
    val config = tool.constrainedSampling as? JsonSchemaConstrainedSampling ?: return null
    if (supportsStrictMode) {
        return true
    }
    check(config.strict != ConstrainedSamplingStrict.REQUIRE) {
        "Tool \"${tool.name}\" requires JSON-schema constrained sampling, but strict tools are unsupported."
    }
    return null
}

fun resolveGrammarConstrainedSampling(
    tool: ToolDefinition,
    supportsOpenAIGrammarTools: Boolean,
): ResolvedGrammarConstrainedSampling? {
    val config = tool.constrainedSampling as? GrammarConstrainedSamplingConfig ?: return null
    if (!supportsOpenAIGrammarTools) {
        return null
    }

    val lark = config.variants.openAILark?.takeIf { it.isNotBlank() }
    val regex = config.variants.openAIRegex?.takeIf { it.isNotBlank() }
    check(lark != null || regex != null) {
        "Tool \"${tool.name}\" cannot use grammar constrained sampling: no supported grammar variant was provided."
    }

    return try {
        ResolvedGrammarConstrainedSampling(
            format = if (lark != null) "lark" else "regex",
            definition = lark ?: requireNotNull(regex),
            inputProperty = inferGrammarInputProperty(tool),
        )
    } catch (error: IllegalStateException) {
        throw IllegalStateException(
            "Tool \"${tool.name}\" cannot use grammar constrained sampling: ${error.message}.",
            error,
        )
    } catch (error: IllegalArgumentException) {
        throw IllegalStateException(
            "Tool \"${tool.name}\" cannot use grammar constrained sampling: ${error.message}.",
            error,
        )
    }
}

fun createGrammarToolInputProperties(
    tools: List<ToolDefinition>,
    supportsOpenAIGrammarTools: Boolean,
): Map<String, String> =
    buildMap {
        tools.forEach { tool ->
            resolveGrammarConstrainedSampling(tool, supportsOpenAIGrammarTools)?.let { grammar ->
                put(tool.name, grammar.inputProperty)
            }
        }
    }

private fun inferGrammarInputProperty(tool: ToolDefinition): String {
    val schema = tool.parameters
    check(schema["type"]?.jsonPrimitive?.contentOrNull == "object") {
        "grammar constrained sampling requires an object parameter schema"
    }
    val required = schema["required"] as? kotlinx.serialization.json.JsonArray
    check(required?.size == 1) {
        "grammar constrained sampling requires exactly one required string property"
    }
    val inputProperty =
        required.single().jsonPrimitive.contentOrNull
            ?: error("grammar constrained sampling requires exactly one required string property")
    val properties = schema["properties"]?.jsonObject
    val property =
        properties?.get(inputProperty)
            ?: error("grammar constrained sampling requires a properties entry for $inputProperty")
    check(property.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "string") {
        "grammar constrained sampling property $inputProperty must have type string"
    }
    return inputProperty
}
