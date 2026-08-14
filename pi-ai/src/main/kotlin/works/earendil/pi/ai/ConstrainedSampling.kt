package works.earendil.pi.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

private class UnsupportedStrictJsonSchemaException(
    message: String,
) : IllegalArgumentException(message)

private val unsupportedStrictSchemaKeys =
    setOf(
        "\$ref",
        "\$defs",
        "definitions",
        "allOf",
        "oneOf",
        "patternProperties",
        "dependentSchemas",
        "dependencies",
        "unevaluatedProperties",
        "propertyNames",
        "contains",
        "prefixItems",
        "not",
        "if",
        "then",
        "else",
    )

private fun isStructuredSchema(schema: JsonElement): Boolean {
    val objectSchema = schema as? JsonObject ?: return false
    val types =
        when (val type = objectSchema["type"]) {
            is JsonPrimitive -> listOfNotNull(type.contentOrNull)
            is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
    return "object" in types ||
        "array" in types ||
        "properties" in objectSchema ||
        "items" in objectSchema
}

private fun schemaAllowsNull(schema: JsonElement): Boolean {
    val objectSchema = schema as? JsonObject ?: return false
    val typeAllowsNull =
        when (val type = objectSchema["type"]) {
            is JsonPrimitive -> type.contentOrNull == "null"
            is JsonArray -> type.any { (it as? JsonPrimitive)?.contentOrNull == "null" }
            else -> false
        }
    if (typeAllowsNull || objectSchema["const"] == JsonNull) return true
    if ((objectSchema["enum"] as? JsonArray)?.any { it == JsonNull } == true) return true
    return (objectSchema["anyOf"] as? JsonArray)?.any(::schemaAllowsNull) == true
}

private fun makeJsonSchemaNodeStrict(schema: JsonElement): JsonObject {
    val objectSchema =
        schema as? JsonObject
            ?: throw UnsupportedStrictJsonSchemaException("boolean schemas are unsupported")
    unsupportedStrictSchemaKeys.firstOrNull { it in objectSchema }?.let { key ->
        throw UnsupportedStrictJsonSchemaException("$key schemas are unsupported")
    }

    val convertedAnyOf =
        if ("anyOf" in objectSchema) {
        val variants =
            objectSchema["anyOf"] as? JsonArray
                ?: throw UnsupportedStrictJsonSchemaException("anyOf must contain at least one schema")
        if (variants.isEmpty()) {
            throw UnsupportedStrictJsonSchemaException("anyOf must contain at least one schema")
        }
        variants.forEach { variant ->
            if (isStructuredSchema(variant)) {
                throw UnsupportedStrictJsonSchemaException("object and array unions are unsupported")
            }
        }
            JsonArray(variants.map(::makeJsonSchemaNodeStrict))
        } else {
            null
        }

    val convertedItems = objectSchema["items"]?.let { items ->
        if (items is JsonArray) {
            throw UnsupportedStrictJsonSchemaException("tuple schemas are unsupported")
        }
        makeJsonSchemaNodeStrict(items)
    }

    val type = (objectSchema["type"] as? JsonPrimitive)?.contentOrNull
    if ("properties" in objectSchema && type != "object") {
        throw UnsupportedStrictJsonSchemaException("properties require type object")
    }
    if (type != "object") {
        return buildJsonObject {
            objectSchema.forEach { (key, value) ->
                when (key) {
                    "anyOf" -> put(key, requireNotNull(convertedAnyOf))
                    "items" -> put(key, requireNotNull(convertedItems))
                    else -> put(key, value)
                }
            }
        }
    }

    objectSchema["additionalProperties"]?.let { additionalProperties ->
        val isFalse =
            additionalProperties is JsonPrimitive &&
                additionalProperties.booleanOrNull == false
        if (!isFalse) {
            throw UnsupportedStrictJsonSchemaException(
                "schema-valued or true additionalProperties is unsupported",
            )
        }
    }

    val properties =
        when (val rawProperties = objectSchema["properties"]) {
            null -> emptyMap()
            is JsonObject -> rawProperties
            else -> throw UnsupportedStrictJsonSchemaException("object properties must be a schema map")
        }
    val required =
        when (val rawRequired = objectSchema["required"]) {
            null -> emptySet()
            is JsonArray ->
                rawRequired.map {
                    (it as? JsonPrimitive)?.contentOrNull
                        ?: throw UnsupportedStrictJsonSchemaException("object required must be a string array")
                }.toSet()
            else -> throw UnsupportedStrictJsonSchemaException("object required must be a string array")
        }
    if (!properties.keys.containsAll(required)) {
        throw UnsupportedStrictJsonSchemaException("required contains an unknown property")
    }

    val convertedProperties =
        buildJsonObject {
            properties.forEach { (name, property) ->
                val converted = makeJsonSchemaNodeStrict(property)
                put(
                    name,
                    if (name !in required && !schemaAllowsNull(property)) {
                        buildJsonObject {
                            put(
                                "anyOf",
                                buildJsonArray {
                                    add(converted)
                                    add(buildJsonObject { put("type", "null") })
                                },
                            )
                        }
                    } else {
                        converted
                    },
                )
            }
        }
    return buildJsonObject {
        objectSchema.forEach { (key, value) ->
            if (key != "properties" && key != "required" && key != "additionalProperties") {
                when (key) {
                    "anyOf" -> put(key, requireNotNull(convertedAnyOf))
                    "items" -> put(key, requireNotNull(convertedItems))
                    else -> put(key, value)
                }
            }
        }
        put("properties", convertedProperties)
        put("required", JsonArray(properties.keys.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }
}

fun makeStrictJsonSchema(parameters: JsonObject): JsonObject {
    val converted = makeJsonSchemaNodeStrict(parameters)
    if ((converted["type"] as? JsonPrimitive)?.contentOrNull != "object") {
        throw UnsupportedStrictJsonSchemaException("root schema must have type object")
    }
    return converted
}

fun getJsonSchemaToolParameters(
    tool: ToolDefinition,
    strict: Boolean?,
): JsonObject = if (strict == true) makeStrictJsonSchema(tool.parameters) else tool.parameters

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
        return try {
            makeStrictJsonSchema(tool.parameters)
            true
        } catch (error: UnsupportedStrictJsonSchemaException) {
            if (config.strict != ConstrainedSamplingStrict.REQUIRE) {
                null
            } else {
                error(
                    "Tool \"${tool.name}\" requires JSON-schema constrained sampling, but ${error.message}.",
                )
            }
        }
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
