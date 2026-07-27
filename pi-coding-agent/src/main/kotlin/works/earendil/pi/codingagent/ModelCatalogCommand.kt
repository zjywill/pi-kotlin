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
    val invalid =
        arguments
            .drop(1)
            .firstOrNull { argument ->
                argument !in
                    setOf(
                        "--models",
                        "--force",
                        "-a",
                        "--approve",
                        "-na",
                        "--no-approve",
                    )
            }
    if (invalid != null) {
        errorOutput.println("--models cannot be combined with $invalid")
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
