package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.InMemoryModelsStore
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.ModelsRefreshOptions
import works.earendil.pi.ai.ModelsStoreEntry
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.ProviderModelsStore
import works.earendil.pi.ai.StreamOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RemoteCatalogProviderTest {
    @Test
    fun `refresh parses keyed catalogs sends version headers and honors ttl plus force`() =
        runTest {
            val requests = AtomicInteger()
            val userAgent = AtomicReference<String>()
            val server =
                catalogServer { exchange ->
                    requests.incrementAndGet()
                    userAgent.set(exchange.requestHeaders.getFirst("User-Agent"))
                    respond(exchange, 200, keyedCatalog(model("dynamic")))
                }
            try {
                val provider =
                    StaticProvider()
                        .withRemoteCatalog(
                            catalogBaseUrl = baseUrl(server),
                            userAgent = "pi/test-version",
                        )
                val store = InMemoryModelsStore()
                val context = refreshContext(store)

                provider.refreshModels(context)
                provider.refreshModels(context)
                provider.refreshModels(context.copy(force = true))

                assertEquals(listOf("static", "dynamic"), provider.getModels().map(Model::id))
                assertEquals(listOf("dynamic"), store.read(provider.id)?.models?.map(Model::id))
                assertEquals(2, requests.get())
                assertEquals("pi/test-version", userAgent.get())
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `refresh prefers the newer of bundled and remote catalogs`() =
        runTest {
            val bundledAt = Instant.parse("2026-07-23T10:00:00Z").toEpochMilli()
            val request = AtomicInteger()
            val server =
                catalogServer { exchange ->
                    val newer = request.getAndIncrement() > 0
                    val modifiedAt = bundledAt + if (newer) 60_000 else -60_000
                    exchange.responseHeaders.add("Last-Modified", httpDate(modifiedAt))
                    respond(
                        exchange,
                        200,
                        keyedCatalog(model(if (newer) "newer" else "old")),
                    )
                }
            try {
                val provider =
                    StaticProvider()
                        .withRemoteCatalog(
                            catalogBaseUrl = baseUrl(server),
                            localGeneratedAt = bundledAt,
                        )
                val store = InMemoryModelsStore()
                val context = refreshContext(store)

                provider.refreshModels(context)
                assertEquals(listOf("static"), provider.getModels().map(Model::id))

                provider.refreshModels(context.copy(force = true))
                assertEquals(listOf("static", "newer"), provider.getModels().map(Model::id))
                assertEquals(bundledAt + 60_000, store.read(provider.id)?.lastModified)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `models restore a newer persisted overlay without network access`() =
        runTest {
            val bundledAt = Instant.parse("2026-07-23T10:00:00Z").toEpochMilli()
            val store = InMemoryModelsStore()
            store.write(
                "test-provider",
                ModelsStoreEntry(
                    models = listOf(model("cached")),
                    lastModified = bundledAt + 1,
                    checkedAt = 100,
                ),
            )
            val provider =
                StaticProvider()
                    .withRemoteCatalog(
                        catalogBaseUrl = "http://127.0.0.1:1",
                        localGeneratedAt = bundledAt,
                    )
            val models = Models(listOf(provider), store)

            val result = models.refresh(ModelsRefreshOptions(allowNetwork = false))

            assertTrue(result.errors.isEmpty())
            assertNotNull(models.getModel("test-provider", "cached"))
        }

    @Test
    fun `unimplemented catalog routes keep the bundled catalog available`() =
        runTest {
            val server =
                catalogServer { exchange ->
                    respond(exchange, 501, "not implemented")
                }
            try {
                val provider =
                    StaticProvider()
                        .withRemoteCatalog(catalogBaseUrl = baseUrl(server))
                val store = InMemoryModelsStore()

                provider.refreshModels(refreshContext(store))

                assertEquals(listOf("static"), provider.getModels().map(Model::id))
                assertEquals(0, store.read(provider.id)?.lastModified)
                assertNotNull(store.read(provider.id)?.checkedAt)
            } finally {
                server.stop(0)
            }
        }

    private fun refreshContext(store: InMemoryModelsStore) =
        works.earendil.pi.ai.RefreshModelsContext(
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
            allowNetwork = true,
        )

    private fun catalogServer(handler: com.sun.net.httpserver.HttpHandler): HttpServer =
        HttpServer
            .create(InetSocketAddress("127.0.0.1", 0), 0)
            .apply {
                createContext("/", handler)
                start()
            }

    private fun baseUrl(server: HttpServer): String = "http://127.0.0.1:${server.address.port}"

    private fun respond(
        exchange: com.sun.net.httpserver.HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("content-type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun keyedCatalog(model: Model): String =
        buildJsonObject {
            put("dynamic", providerJson.encodeToJsonElement(Model.serializer(), model))
        }.toString()

    private fun httpDate(epochMillis: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC),
        )

    private class StaticProvider : Provider {
        private val models = listOf(model("static"))

        override val id: String = "test-provider"
        override val name: String = "Test Provider"

        override fun getModels(): List<Model> = models

        override suspend fun stream(
            model: Model,
            context: Context,
            options: StreamOptions,
        ): AssistantMessageEventStream = error("not used")
    }

    private companion object {
        fun model(id: String): Model =
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
    }
}
