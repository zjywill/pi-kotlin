package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.InMemoryModelsStore
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.ModelsStoreEntry
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.ProviderModelsStore
import works.earendil.pi.ai.RefreshModelsContext
import works.earendil.pi.ai.StreamOptions

fun main() =
    runBlocking {
        val bundledAt = Instant.parse("2026-07-23T10:00:00Z").toEpochMilli()
        val request = AtomicInteger()
        val validator = AtomicReference<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            when (request.getAndIncrement()) {
                0 -> {
                    exchange.responseHeaders.add("Last-Modified", httpDate(bundledAt - 60_000))
                    respond(exchange, 200, keyedCatalog(model("old")))
                }

                1 -> {
                    exchange.responseHeaders.add("Last-Modified", httpDate(bundledAt + 60_000))
                    respond(exchange, 200, keyedCatalog(model("newer")))
                }

                2 -> respond(exchange, 501, "not implemented")
                3 -> {
                    exchange.responseHeaders.add("ETag", "\"catalog-1\"")
                    respond(exchange, 200, keyedCatalog(model("etagged")))
                }

                else -> {
                    validator.set(exchange.requestHeaders.getFirst("If-None-Match"))
                    respond(exchange, 304, "")
                }
            }
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val selectionStore = InMemoryModelsStore()
            val selectionProvider =
                StaticProvider()
                    .withRemoteCatalog(
                        catalogBaseUrl = baseUrl,
                        localGeneratedAt = bundledAt,
                    )
            selectionProvider.refreshModels(refreshContext(selectionStore, allowNetwork = true))
            val older = selectionProvider.getModels().map(Model::id)
            selectionProvider.refreshModels(refreshContext(selectionStore, allowNetwork = true, force = true))
            val newer = selectionProvider.getModels().map(Model::id)

            val offlineStore = InMemoryModelsStore()
            offlineStore.write(
                "test-provider",
                ModelsStoreEntry(
                    models = listOf(model("cached")),
                    lastModified = bundledAt + 1,
                    checkedAt = 100,
                ),
            )
            val offlineProvider =
                StaticProvider()
                    .withRemoteCatalog(
                        catalogBaseUrl = baseUrl,
                        localGeneratedAt = bundledAt,
                    )
            offlineProvider.refreshModels(refreshContext(offlineStore, allowNetwork = false))

            val unavailableStore = InMemoryModelsStore()
            val unavailableProvider =
                StaticProvider()
                    .withRemoteCatalog(
                        catalogBaseUrl = baseUrl,
                        localGeneratedAt = bundledAt,
                    )
            unavailableProvider.refreshModels(refreshContext(unavailableStore, allowNetwork = true))
            val unavailableEntry = requireNotNull(unavailableStore.read("test-provider"))

            val etagStore = InMemoryModelsStore()
            val etagProvider =
                StaticProvider()
                    .withRemoteCatalog(catalogBaseUrl = baseUrl)
            etagProvider.refreshModels(refreshContext(etagStore, allowNetwork = true))
            etagProvider.refreshModels(
                refreshContext(etagStore, allowNetwork = true, force = true),
            )
            val etagEntry = requireNotNull(etagStore.read("test-provider"))

            println(
                buildJsonObject {
                    put("older", strings(older))
                    put("newer", strings(newer))
                    put("offline", strings(offlineProvider.getModels().map(Model::id)))
                    put(
                        "unimplemented",
                        buildJsonObject {
                            put("models", strings(unavailableProvider.getModels().map(Model::id)))
                            put("lastModified", unavailableEntry.lastModified)
                            put("hasCheckedAt", unavailableEntry.checkedAt != null)
                        },
                    )
                    put(
                        "etag",
                        buildJsonObject {
                            validator.get()?.let { put("sent", it) }
                            put("models", strings(etagProvider.getModels().map(Model::id)))
                            put("storedModels", strings(etagEntry.models.map(Model::id)))
                            etagEntry.etag?.let { put("storedEtag", it) }
                            put("hasCheckedAt", etagEntry.checkedAt != null)
                        },
                    )
                },
            )
        } finally {
            server.stop(0)
        }
    }

private fun refreshContext(
    store: InMemoryModelsStore,
    allowNetwork: Boolean,
    force: Boolean = false,
): RefreshModelsContext =
    RefreshModelsContext(
        store =
            object : ProviderModelsStore {
                override suspend fun read(): ModelsStoreEntry? = store.read("test-provider")

                override suspend fun write(entry: ModelsStoreEntry) {
                    store.write("test-provider", entry)
                }

                override suspend fun delete() {
                    store.delete("test-provider")
                }
            },
        allowNetwork = allowNetwork,
        force = force,
    )

private fun keyedCatalog(model: Model): String =
    buildJsonObject {
        put("dynamic", providerJson.encodeToJsonElement(Model.serializer(), model))
    }.toString()

private fun strings(values: List<String>) =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

private fun respond(
    exchange: HttpExchange,
    status: Int,
    body: String,
) {
    if (status == 304) {
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
        return
    }
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    exchange.responseHeaders.add("content-type", "application/json")
    exchange.sendResponseHeaders(status, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private fun httpDate(epochMillis: Long): String =
    DateTimeFormatter.RFC_1123_DATE_TIME.format(
        Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC),
    )

private class StaticProvider : Provider {
    override val id: String = "test-provider"
    override val name: String = "Test Provider"

    override fun getModels(): List<Model> = listOf(model("static"))

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions,
    ): AssistantMessageEventStream = error("not used")
}

private fun model(id: String): Model =
    Model(
        id = id,
        name = id,
        api = "openai-completions",
        provider = "test-provider",
        baseUrl = "https://example.test/v1",
        reasoning = false,
        input = listOf(ModelInput.TEXT),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 1_000,
        maxTokens = 100,
        compat = JsonObject(emptyMap()),
    )
