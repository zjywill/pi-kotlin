package works.earendil.pi.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

fun validateToolCall(
    tools: List<ToolDefinition>,
    toolCall: ToolCall,
): JsonObject {
    val tool =
        tools.firstOrNull { it.name == toolCall.name }
            ?: error("Tool \"${toolCall.name}\" not found")
    return validateToolArguments(tool, toolCall)
}

fun validateToolArguments(
    tool: ToolDefinition,
    toolCall: ToolCall,
): JsonObject {
    val coerced = coerceObject(toolCall.arguments, tool.parameters)
    val errors = mutableListOf<String>()
    validateValue(coerced, tool.parameters, "", errors)
    if (errors.isNotEmpty()) {
        val details = errors.joinToString("\n") { "  - $it" }
        error(
            "Validation failed for tool \"${toolCall.name}\":\n" +
                "$details\n\nReceived arguments:\n${toolCall.arguments}",
        )
    }
    return coerced
}

private fun coerceObject(
    value: JsonObject,
    schema: JsonObject,
): JsonObject {
    val properties = schema["properties"] as? JsonObject ?: return value
    return buildJsonObject {
        for ((key, element) in value) {
            val propertySchema = properties[key] as? JsonObject
            put(key, if (propertySchema == null) element else coerceValue(element, propertySchema))
        }
    }
}

private fun coerceValue(
    value: JsonElement,
    schema: JsonObject,
): JsonElement {
    val unionSchemas = unionSchemas(schema)
    if (unionSchemas.isNotEmpty()) {
        if (unionSchemas.any { valueMatchesSchema(value, it) }) {
            return value
        }
        unionSchemas.forEach { branch ->
            val candidate = coerceValue(value, branch)
            if (valueMatchesSchema(candidate, branch)) {
                return candidate
            }
        }
        return value
    }

    val schemaTypes = schemaTypes(schema)
    if (schemaTypes.any { matchesType(value, it) }) {
        return coerceNested(value, schema, schemaTypes)
    }

    val coerced =
        schemaTypes.firstNotNullOfOrNull { type ->
            when (type) {
                "number" ->
                    when {
                        value is JsonNull -> JsonPrimitive(0)
                        value is JsonPrimitive && value.isString -> value.content.toDoubleOrNull()?.let(::JsonPrimitive)
                        value.jsonPrimitive.booleanOrNull != null ->
                            JsonPrimitive(if (value.jsonPrimitive.boolean) 1 else 0)
                        else -> null
                    }

                "integer" ->
                    when {
                        value is JsonNull -> JsonPrimitive(0)
                        value is JsonPrimitive && value.isString -> value.content.toLongOrNull()?.let(::JsonPrimitive)
                        value.jsonPrimitive.booleanOrNull != null ->
                            JsonPrimitive(if (value.jsonPrimitive.boolean) 1 else 0)
                        else -> null
                    }

                "boolean" ->
                    when {
                        value is JsonNull -> JsonPrimitive(false)
                        value is JsonPrimitive && value.isString && value.content == "true" -> JsonPrimitive(true)
                        value is JsonPrimitive && value.isString && value.content == "false" -> JsonPrimitive(false)
                        value.jsonPrimitive.longOrNull == 1L -> JsonPrimitive(true)
                        value.jsonPrimitive.longOrNull == 0L -> JsonPrimitive(false)
                        else -> null
                    }

                "string" ->
                    when {
                        value is JsonNull -> JsonPrimitive("")
                        value is JsonPrimitive && !value.isString -> JsonPrimitive(value.content)
                        else -> null
                    }

                "null" ->
                    when {
                        value is JsonPrimitive && value.isString && value.content.isEmpty() -> JsonNull
                        value.jsonPrimitive.longOrNull == 0L -> JsonNull
                        value.jsonPrimitive.booleanOrNull == false -> JsonNull
                        else -> null
                    }

                else -> null
            }
        } ?: value

    return coerceNested(coerced, schema, schemaTypes)
}

private fun coerceNested(
    value: JsonElement,
    schema: JsonObject,
    schemaTypes: List<String>,
): JsonElement =
    when {
        "object" in schemaTypes && value is JsonObject -> coerceObject(value, schema)
        "array" in schemaTypes && value is JsonArray -> {
            val itemSchema = schema["items"] as? JsonObject ?: return value
            JsonArray(value.map { coerceValue(it, itemSchema) })
        }

        else -> value
    }

private fun validateValue(
    value: JsonElement,
    schema: JsonObject,
    path: String,
    errors: MutableList<String>,
) {
    val unionSchemas = unionSchemas(schema)
    if (unionSchemas.isNotEmpty()) {
        if (unionSchemas.none { valueMatchesSchema(value, it) }) {
            errors += "${path.ifEmpty { "root" }}: expected a value matching one union branch"
        }
        return
    }

    val types = schemaTypes(schema)
    if (types.isNotEmpty() && types.none { matchesType(value, it) }) {
        errors += "${path.ifEmpty { "root" }}: expected ${types.joinToString(" or ")}"
        return
    }

    if (value is JsonObject) {
        val required =
            (schema["required"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
        required.filterNot(value::containsKey).forEach { key ->
            errors += "${childPath(path, key)}: required property is missing"
        }

        val properties = schema["properties"] as? JsonObject
        if (properties != null) {
            for ((key, childValue) in value) {
                val childSchema = properties[key] as? JsonObject ?: continue
                validateValue(childValue, childSchema, childPath(path, key), errors)
            }
        }
    }

    if (value is JsonArray) {
        val itemSchema = schema["items"] as? JsonObject
        if (itemSchema != null) {
            value.forEachIndexed { index, child ->
                validateValue(child, itemSchema, childPath(path, index.toString()), errors)
            }
        }
    }
}

private fun childPath(
    parent: String,
    child: String,
): String = if (parent.isEmpty()) child else "$parent.$child"

private fun schemaTypes(schema: JsonObject): List<String> =
    when (val type = schema["type"]) {
        is JsonPrimitive -> listOf(type.content)
        is JsonArray -> type.map { it.jsonPrimitive.content }
        else -> emptyList()
    }

private fun unionSchemas(schema: JsonObject): List<JsonObject> =
    listOf("anyOf", "oneOf")
        .firstNotNullOfOrNull { keyword ->
            (schema[keyword] as? JsonArray)?.mapNotNull { it as? JsonObject }
        }.orEmpty()

private fun valueMatchesSchema(
    value: JsonElement,
    schema: JsonObject,
): Boolean {
    val errors = mutableListOf<String>()
    validateValue(value, schema, "", errors)
    return errors.isEmpty()
}

private fun matchesType(
    value: JsonElement,
    type: String,
): Boolean =
    when (type) {
        "number" -> value is JsonPrimitive && value.doubleOrNull != null
        "integer" -> value is JsonPrimitive && value.longOrNull != null
        "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
        "string" -> value is JsonPrimitive && value.isString
        "null" -> value is JsonNull
        "array" -> value is JsonArray
        "object" -> value is JsonObject
        else -> false
    }
