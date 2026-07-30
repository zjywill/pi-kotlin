package works.earendil.pi.codingagent

import java.util.concurrent.CompletableFuture
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.tui.AutocompleteItem
import works.earendil.pi.tui.AutocompleteProvider
import works.earendil.pi.tui.AutocompleteRequest
import works.earendil.pi.tui.AutocompleteSuggestions
import works.earendil.pi.tui.CompletionResult

internal class HostedAutocompleteProvider(
    private val base: AutocompleteProvider,
    private val runtime: RpcRuntime,
) : AutocompleteProvider {
    override val triggerCharacters: List<String> =
        (
            base.triggerCharacters +
                invoke(
                    method = "metadata",
                    payload = JsonObject(emptyMap()),
                )?.jsonObject
                    ?.get("triggerCharacters")
                    ?.jsonArray
                    .orEmpty()
                    .mapNotNull { value -> value.jsonPrimitive.contentOrNull }
        ).distinct()

    override fun getSuggestions(
        lines: List<String>,
        cursorLine: Int,
        cursorColumn: Int,
        request: AutocompleteRequest,
    ): CompletableFuture<AutocompleteSuggestions?> =
        CompletableFuture.supplyAsync {
            invoke(
                method = "getSuggestions",
                payload =
                    buildJsonObject {
                        put("lines", JsonArray(lines.map(::JsonPrimitive)))
                        put("cursorLine", cursorLine)
                        put("cursorColumn", cursorColumn)
                        put(
                            "options",
                            buildJsonObject {
                                put("force", request.force)
                            },
                        )
                    },
            )?.toAutocompleteSuggestions()
                ?: base.getSuggestions(lines, cursorLine, cursorColumn, request).join()
        }

    override fun applyCompletion(
        lines: List<String>,
        cursorLine: Int,
        cursorColumn: Int,
        item: AutocompleteItem,
        prefix: String,
    ): CompletionResult =
        invoke(
            method = "applyCompletion",
            payload =
                buildJsonObject {
                    put("lines", JsonArray(lines.map(::JsonPrimitive)))
                    put("cursorLine", cursorLine)
                    put("cursorColumn", cursorColumn)
                    put("item", item.toJson())
                    put("prefix", prefix)
                },
        )?.toCompletionResult()
            ?: base.applyCompletion(lines, cursorLine, cursorColumn, item, prefix)

    override fun shouldTriggerFileCompletion(
        lines: List<String>,
        cursorLine: Int,
        cursorColumn: Int,
    ): Boolean =
        invoke(
            method = "shouldTriggerFileCompletion",
            payload =
                buildJsonObject {
                    put("lines", JsonArray(lines.map(::JsonPrimitive)))
                    put("cursorLine", cursorLine)
                    put("cursorColumn", cursorColumn)
                },
        )?.jsonPrimitive
            ?.booleanOrNull
            ?: base.shouldTriggerFileCompletion(lines, cursorLine, cursorColumn)

    private fun invoke(
        method: String,
        payload: JsonObject,
    ): JsonElement? =
        runtime
            .invokeExtensionAutocomplete(
                method = method,
                payload = payload,
                baseTriggerCharacters = base.triggerCharacters,
                onBaseRequest = ::handleBaseRequest,
            )
            ?.takeIf { response -> response.stringValue("error") == null }
            ?.get("result")
            ?.takeUnless { value -> value is JsonNull }

    private fun handleBaseRequest(request: JsonObject): JsonElement {
        val method = request.stringValue("method")
            ?: error("Autocomplete base request is missing method")
        val payload = request["payload"]?.jsonObject
            ?: error("Autocomplete base request is missing payload")
        val lines = payload.stringList("lines")
        val cursorLine = payload.intValue("cursorLine")
        val cursorColumn = payload.intValue("cursorColumn")
        return when (method) {
            "getSuggestions" -> {
                val force =
                    payload["options"]
                        ?.jsonObject
                        ?.get("force")
                        ?.jsonPrimitive
                        ?.booleanOrNull
                        ?: false
                base
                    .getSuggestions(
                        lines,
                        cursorLine,
                        cursorColumn,
                        AutocompleteRequest(force),
                    ).join()
                    ?.toJson()
                    ?: JsonNull
            }

            "applyCompletion" -> {
                val item = payload["item"]?.jsonObject?.toAutocompleteItem()
                    ?: error("Autocomplete base completion is missing item")
                base
                    .applyCompletion(
                        lines,
                        cursorLine,
                        cursorColumn,
                        item,
                        payload.stringValue("prefix").orEmpty(),
                    ).toJson()
            }

            "shouldTriggerFileCompletion" ->
                JsonPrimitive(
                    base.shouldTriggerFileCompletion(
                        lines,
                        cursorLine,
                        cursorColumn,
                    ),
                )

            else -> error("Unknown autocomplete base operation: $method")
        }
    }
}

private fun AutocompleteItem.toJson(): JsonObject =
    buildJsonObject {
        put("value", value)
        put("label", label)
        description?.let { put("description", it) }
    }

private fun JsonObject.toAutocompleteItem(): AutocompleteItem =
    AutocompleteItem(
        value = stringValue("value").orEmpty(),
        label = stringValue("label") ?: stringValue("value").orEmpty(),
        description = stringValue("description"),
    )

private fun AutocompleteSuggestions.toJson(): JsonObject =
    buildJsonObject {
        put("items", JsonArray(items.map(AutocompleteItem::toJson)))
        put("prefix", prefix)
    }

private fun JsonElement.toAutocompleteSuggestions(): AutocompleteSuggestions? {
    val value = this as? JsonObject ?: return null
    return AutocompleteSuggestions(
        items =
            value["items"]
                ?.jsonArray
                .orEmpty()
                .mapNotNull { item ->
                    (item as? JsonObject)?.toAutocompleteItem()
                },
        prefix = value.stringValue("prefix").orEmpty(),
    )
}

private fun CompletionResult.toJson(): JsonObject =
    buildJsonObject {
        put("lines", JsonArray(lines.map(::JsonPrimitive)))
        put("cursorLine", cursorLine)
        put("cursorColumn", cursorColumn)
    }

private fun JsonElement.toCompletionResult(): CompletionResult? {
    val value = this as? JsonObject ?: return null
    return CompletionResult(
        lines = value.stringList("lines"),
        cursorLine = value.intValue("cursorLine"),
        cursorColumn = value.intValue("cursorColumn"),
    )
}

private fun JsonObject.stringList(name: String): List<String> =
    this[name]
        ?.jsonArray
        .orEmpty()
        .mapNotNull { value -> value.jsonPrimitive.contentOrNull }

private fun JsonObject.intValue(name: String): Int =
    this[name]
        ?.jsonPrimitive
        ?.intOrNull
        ?: 0
