package works.earendil.pi.codingagent

import java.io.PrintStream
import java.nio.file.Path
import kotlinx.coroutines.withTimeoutOrNull
import works.earendil.pi.ai.JsonFileModelsStore
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.ModelsRefreshOptions
import works.earendil.pi.ai.providers.BuiltInModelsOptions
import works.earendil.pi.ai.providers.builtInModelsCollection

internal suspend fun runModelCatalogCommand(
    arguments: List<String>,
    agentDir: Path = defaultAgentDirectory(),
    catalogBaseUrl: String = "https://pi.dev",
    output: PrintStream = System.out,
    errorOutput: PrintStream = System.err,
    timeoutMs: Long = MODEL_CATALOG_REFRESH_TIMEOUT_MS,
): Int? {
    if (arguments.firstOrNull() != "update" || "--models" !in arguments) {
        return null
    }
    val validation = validateModelCatalogArguments(arguments.drop(1))
    if (validation != null) {
        errorOutput.println(validation.message)
        errorOutput.println(
            if (validation.unknownOption) {
                """Use "pi --help" or "${packageCommandUsage("update")}"."""
            } else {
                "Usage: ${packageCommandUsage("update")}"
            },
        )
        return 1
    }

    val models =
        loadBuiltInModels(
            agentDir = agentDir,
            catalogBaseUrl = catalogBaseUrl,
        )
    val result =
        withTimeoutOrNull(timeoutMs) {
            models.refresh(
                ModelsRefreshOptions(
                    allowNetwork = true,
                    force = true,
                ),
            )
        }
    if (result == null) {
        errorOutput.println("Error: Model catalog refresh timed out.")
        return 1
    }
    if (result.errors.isNotEmpty()) {
        val details =
            result.errors.entries.joinToString("; ") { (provider, failure) ->
                "$provider: ${failure.message ?: failure::class.simpleName.orEmpty()}"
            }
        errorOutput.println("Error: Could not refresh model catalogs: $details")
        return 1
    }

    output.println("Model catalogs refreshed")
    return 0
}

private fun validateModelCatalogArguments(arguments: List<String>): ModelCatalogValidationError? {
    var self = false
    var extensions = false
    var all = false
    var extensionSource: String? = null
    var source: String? = null
    var invalidOption: String? = null
    var missingOptionValue: String? = null
    var invalidArgument: String? = null
    var extensionCount = 0
    var index = 0
    while (index < arguments.size) {
        val argument = arguments[index]
        when (argument) {
            "--self" -> self = true
            "--extensions" -> extensions = true
            "--models",
            "--force",
            "-a",
            "--approve",
            "-na",
            "--no-approve",
            -> Unit

            "--all" -> all = true
            "--extension" -> {
                val value = arguments.getOrNull(index + 1)
                if (value == null || value.startsWith("-")) {
                    missingOptionValue = missingOptionValue ?: argument
                } else {
                    extensionCount++
                    if (extensionCount == 1) {
                        extensionSource = value
                    }
                    index++
                }
            }

            else ->
                when {
                    argument.startsWith("-") -> invalidOption = invalidOption ?: argument
                    source == null -> source = argument
                    else -> invalidArgument = invalidArgument ?: argument
                }
        }
        index++
    }
    invalidOption?.let {
        return ModelCatalogValidationError(
            message = "Unknown option $it for \"update\".",
            unknownOption = true,
        )
    }
    missingOptionValue?.let {
        return ModelCatalogValidationError("Missing value for $it.")
    }
    invalidArgument?.let {
        return ModelCatalogValidationError("Unexpected argument $it.")
    }
    if (extensionCount > 1) {
        return ModelCatalogValidationError("--extension can only be provided once")
    }
    if (all) {
        return ModelCatalogValidationError(
            "--all cannot be combined with --self, --extensions, --models, or --extension",
        )
    }
    if (self || extensions || extensionSource != null) {
        return ModelCatalogValidationError(
            "--models cannot be combined with --self, --extensions, --all, or --extension",
        )
    }
    if (source != null) {
        return ModelCatalogValidationError("--models cannot be combined with a positional source")
    }
    return null
}

private data class ModelCatalogValidationError(
    val message: String,
    val unknownOption: Boolean = false,
)

suspend fun loadBuiltInModels(
    agentDir: Path = defaultAgentDirectory(),
    catalogBaseUrl: String = "https://pi.dev",
): Models =
    builtInModelsCollection(
        BuiltInModelsOptions(
            modelsStore = JsonFileModelsStore(agentDir.resolve("models-store.json")),
            credentialStore = JsonFileCredentialStore(agentDir.resolve("auth.json")),
            catalogBaseUrl = catalogBaseUrl,
            allowNetwork = false,
        ),
    )

private const val MODEL_CATALOG_REFRESH_TIMEOUT_MS = 15_000L
