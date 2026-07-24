package works.earendil.pi.ai.providers

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelsStoreEntry
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.RefreshModelsContext

const val REMOTE_CATALOG_REFRESH_INTERVAL_MS: Long = 4 * 60 * 60 * 1_000

fun Provider.withRemoteCatalog(
    catalogBaseUrl: String = DEFAULT_CATALOG_BASE_URL,
    localGeneratedAt: Long? = null,
    userAgent: String = DEFAULT_USER_AGENT,
    client: HttpClient = defaultRemoteCatalogHttpClient(),
    currentTimeMillis: () -> Long = System::currentTimeMillis,
): Provider =
    RemoteCatalogProvider(
        provider = this,
        catalogBaseUrl = catalogBaseUrl,
        localGeneratedAt = localGeneratedAt,
        userAgent = userAgent,
        client = client,
        currentTimeMillis = currentTimeMillis,
    )

private class RemoteCatalogProvider(
    private val provider: Provider,
    private val catalogBaseUrl: String,
    private val localGeneratedAt: Long?,
    private val userAgent: String,
    private val client: HttpClient,
    private val currentTimeMillis: () -> Long,
) : Provider by provider {
    @Volatile
    private var dynamicModels: List<Model> = emptyList()

    private val refreshMutex = Mutex()
    private var inflightRefresh: CompletableDeferred<Unit>? = null

    override val supportsModelRefresh: Boolean = true

    override fun getModels(): List<Model> = mergeModels(provider.getModels(), dynamicModels)

    override suspend fun refreshModels(context: RefreshModelsContext) {
        var ownsRefresh = false
        val refresh =
            refreshMutex.withLock {
                inflightRefresh
                    ?: CompletableDeferred<Unit>().also {
                        inflightRefresh = it
                        ownsRefresh = true
                    }
            }
        if (!ownsRefresh) {
            refresh.await()
            return
        }

        try {
            refreshCatalog(context)
            refresh.complete(Unit)
        } catch (error: Throwable) {
            refresh.completeExceptionally(error)
            throw error
        } finally {
            refreshMutex.withLock {
                if (inflightRefresh === refresh) {
                    inflightRefresh = null
                }
            }
        }
    }

    private suspend fun refreshCatalog(context: RefreshModelsContext) {
        val stored = context.store.read()
        dynamicModels =
            remoteModels(stored)
                .filter { it.provider == provider.id }
        currentCoroutineContext().ensureActive()
        if (!context.allowNetwork) {
            return
        }
        if (
            !context.force &&
            stored?.checkedAt != null &&
            stored.lastModified != null &&
            currentTimeMillis() - stored.checkedAt < REMOTE_CATALOG_REFRESH_INTERVAL_MS
        ) {
            return
        }

        val response =
            client
                .sendAsync(
                    HttpRequest
                        .newBuilder(catalogUri())
                        .header("accept", "application/json")
                        .header("User-Agent", userAgent)
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
                ).await()
        currentCoroutineContext().ensureActive()
        val checkedAt = currentTimeMillis()
        when (response.statusCode()) {
            404, 501 -> {
                context.store.write(
                    (stored ?: ModelsStoreEntry(models = emptyList())).copy(
                        checkedAt = checkedAt,
                        lastModified = 0,
                    ),
                )
                return
            }
        }
        if (response.statusCode() !in 200..299) {
            context.store.write(
                (stored ?: ModelsStoreEntry(models = emptyList())).copy(checkedAt = checkedAt),
            )
            error("Model catalog request failed for ${provider.id}: ${response.statusCode()}")
        }

        val refreshed = parseCatalog(provider.id, response.body())
        val entry =
            ModelsStoreEntry(
                models = refreshed,
                checkedAt = checkedAt,
                lastModified = parseLastModified(response.headers().firstValue("last-modified").orElse(null)),
            )
        currentCoroutineContext().ensureActive()
        dynamicModels = remoteModels(entry)
        context.store.write(entry)
    }

    private fun remoteModels(entry: ModelsStoreEntry?): List<Model> {
        if (entry == null) {
            return emptyList()
        }
        if (
            localGeneratedAt != null &&
            (entry.lastModified == null || entry.lastModified <= localGeneratedAt)
        ) {
            return emptyList()
        }
        return entry.models
    }

    private fun catalogUri(): URI {
        val providerSegment =
            URLEncoder
                .encode(provider.id, StandardCharsets.UTF_8)
                .replace("+", "%20")
        return URI.create(
            "${catalogBaseUrl.trimEnd('/')}/api/models/providers/$providerSegment",
        )
    }
}

private fun mergeModels(
    baseline: List<Model>,
    dynamic: List<Model>,
): List<Model> {
    val merged = baseline.toMutableList()
    dynamic.forEach { model ->
        val index = merged.indexOfFirst { it.id == model.id }
        if (index >= 0) {
            merged[index] = model
        } else {
            merged += model
        }
    }
    return merged
}

private fun parseCatalog(
    providerId: String,
    content: String,
): List<Model> {
    val root = providerJson.parseToJsonElement(content)
    val entries =
        when (root) {
            is JsonArray -> root
            is JsonObject -> {
                val nested = root["models"]
                if (nested is JsonArray) {
                    nested
                } else {
                    JsonArray(root.values.toList())
                }
            }

            else -> error("Invalid model catalog for provider \"$providerId\"")
        }
    return entries.mapNotNull { entry ->
        val objectEntry = entry as? JsonObject ?: return@mapNotNull null
        if ("id" !in objectEntry) {
            return@mapNotNull null
        }
        providerJson
            .decodeFromJsonElement<Model>(objectEntry)
            .copy(provider = providerId)
    }
}

private fun parseLastModified(value: String?): Long =
    value
        ?.let {
            runCatching {
                ZonedDateTime
                    .parse(it, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        } ?: 0

private const val DEFAULT_CATALOG_BASE_URL = "https://pi.dev"
private const val DEFAULT_USER_AGENT = "pi/0.1.0-SNAPSHOT"

internal fun defaultRemoteCatalogHttpClient(): HttpClient =
    HttpClient
        .newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(10))
        .build()
