package works.earendil.pi.ai.providers

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.InMemoryCredentialStore
import works.earendil.pi.ai.InMemoryModelsStore
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.ModelsRefreshOptions
import works.earendil.pi.ai.ModelsStoreEntry
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadiusProviderTest {
    @Test
    fun `fetches persists and streams a dynamic catalog with stored OAuth`() =
        runTest {
            val configAuthorization = AtomicReference<String>()
            val messageAuthorization = AtomicReference<String>()
            val messagePath = AtomicReference<String>()
            val server =
                server { exchange ->
                    when (exchange.requestURI.path) {
                        "/v1/config" -> {
                            configAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
                            exchange.respondJson(200, radiusConfig(baseUrl(exchange)))
                        }

                        "/v1/messages" -> {
                            messageAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
                            messagePath.set(exchange.requestURI.toString())
                            exchange.requestBody.readAllBytes()
                            exchange.respondSse(
                                """
                                data: {"type":"start"}

                                data: {"type":"text_start","contentIndex":0}

                                data: {"type":"text_delta","contentIndex":0,"delta":"Radius"}

                                data: {"type":"text_end","contentIndex":0,"content":"Radius"}

                                data: {"type":"done","reason":"stop","usage":{"input":2,"output":1,"cacheRead":0,"cacheWrite":0,"totalTokens":3,"cost":{"input":0.0,"output":0.0,"cacheRead":0.0,"cacheWrite":0.0,"total":0.0}}}

                                """.trimIndent(),
                            )
                        }

                        else -> exchange.respondJson(404, "{}")
                    }
                }
            try {
                val credential =
                    OAuthCredential(
                        access = "stored-access",
                        refresh = "stored-refresh",
                        expires = 100_000,
                    )
                val credentials = InMemoryCredentialStore(mapOf("radius" to credential))
                val store = InMemoryModelsStore()
                val provider =
                    RadiusProvider(
                        gateway = baseUrl(server),
                        client = HttpClient.newHttpClient(),
                        environment = { null },
                    )
                val models = Models(listOf(provider), store, credentials) { 1_000 }

                val refresh = models.refresh(ModelsRefreshOptions(allowNetwork = true))
                val model = assertNotNull(models.getModel("radius", "auto"))
                val result =
                    models.complete(
                        model,
                        Context(messages = mutableListOf(UserMessage("hello", timestamp = 1))),
                    )

                assertTrue(refresh.errors.isEmpty())
                assertEquals("pi-messages", model.api)
                assertEquals("Bearer stored-access", configAuthorization.get())
                assertEquals("Bearer stored-access", messageAuthorization.get())
                assertEquals("/v1/messages", messagePath.get())
                assertEquals(StopReason.STOP, result.stopReason)
                assertEquals(TextContent("Radius"), result.content.single())
                assertEquals(listOf("auto"), store.read("radius")?.models?.map { it.id })
                assertEquals(listOf("auto"), models.getAvailable("radius").map { it.id })
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `restores persisted and legacy credential catalogs without network`() =
        runTest {
            val cachedStore = InMemoryModelsStore()
            val cachedModel =
                getRadiusModelsFromConfig(
                    "radius",
                    radiusConfigObject("https://cached.example/v1"),
                ).single()
            cachedStore.write(
                "radius",
                ModelsStoreEntry(models = listOf(cachedModel), checkedAt = 100),
            )
            val credentials =
                InMemoryCredentialStore(
                    mapOf(
                        "radius" to
                            OAuthCredential(
                                access = "access",
                                refresh = "refresh",
                                expires = 100_000,
                            ),
                    ),
                )
            val cached =
                Models(
                    listOf(
                        RadiusProvider(
                            gateway = "http://127.0.0.1:1",
                            environment = { null },
                        ),
                    ),
                    cachedStore,
                    credentials,
                ) { 1_000 }

            assertTrue(cached.refresh(ModelsRefreshOptions(allowNetwork = false)).errors.isEmpty())
            assertEquals("https://cached.example/v1", cached.getModel("radius", "auto")?.baseUrl)

            val legacyStore = InMemoryModelsStore()
            val legacyCredential =
                OAuthCredential(
                    access = "access",
                    refresh = "refresh",
                    expires = 100_000,
                    gatewayConfig =
                        providerJson.encodeToJsonElement(
                            RadiusGatewayConfig.serializer(),
                            radiusConfigObject("https://legacy.example/v1"),
                        ).jsonObject,
                )
            val legacy =
                Models(
                    listOf(
                        RadiusProvider(
                            gateway = "http://127.0.0.1:1",
                            environment = { null },
                        ),
                    ),
                    legacyStore,
                    InMemoryCredentialStore(mapOf("radius" to legacyCredential)),
                ) { 1_000 }

            assertTrue(legacy.refresh(ModelsRefreshOptions(allowNetwork = false)).errors.isEmpty())
            assertEquals("https://legacy.example/v1", legacy.getModel("radius", "auto")?.baseUrl)
            assertEquals(listOf("auto"), legacyStore.read("radius")?.models?.map { it.id })
        }

    @Test
    fun `does not fetch or expose models without configured auth`() =
        runTest {
            val requests = AtomicInteger()
            val server =
                server { exchange ->
                    requests.incrementAndGet()
                    exchange.respondJson(200, radiusConfig(baseUrl(exchange)))
                }
            try {
                val models =
                    Models(
                        providers =
                            listOf(
                                RadiusProvider(
                                    gateway = baseUrl(server),
                                    environment = { null },
                                ),
                            ),
                    )

                assertTrue(models.refresh(ModelsRefreshOptions(allowNetwork = true)).errors.isEmpty())
                assertEquals(0, requests.get())
                assertEquals(emptyList(), models.getModels("radius"))
                assertEquals(emptyList(), models.getAvailable("radius"))
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `refreshes expired OAuth before loading the Radius catalog`() =
        runTest {
            val authorization = AtomicReference<String>()
            val server =
                server { exchange ->
                    authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                    exchange.respondJson(200, radiusConfig(baseUrl(exchange)))
                }
            try {
                val oauth =
                    object : OAuthAuth {
                        override val name: String = "Radius"

                        override suspend fun login(interaction: AuthInteraction): OAuthCredential =
                            error("not used")

                        override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
                            credential.copy(
                                access = "fresh-access",
                                refresh = "fresh-refresh",
                                expires = 100_000,
                            )

                        override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
                            ModelAuth(apiKey = credential.access)
                    }
                val credentials =
                    InMemoryCredentialStore(
                        mapOf(
                            "radius" to
                                OAuthCredential(
                                    access = "expired",
                                    refresh = "old-refresh",
                                    expires = 0,
                                ),
                        ),
                    )
                val models =
                    Models(
                        listOf(
                            RadiusProvider(
                                gateway = baseUrl(server),
                                environment = { null },
                                oauth = oauth,
                            ),
                        ),
                        InMemoryModelsStore(),
                        credentials,
                    ) { 1_000 }

                assertTrue(models.refresh(ModelsRefreshOptions(allowNetwork = true)).errors.isEmpty())
                assertEquals("Bearer fresh-access", authorization.get())
                assertEquals("fresh-refresh", (credentials.read("radius") as OAuthCredential).refresh)
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `normalizes gateways and sanitizes invalid model entries`() {
        assertEquals("https://radius.example", normalizeRadiusGatewayUrl("radius.example///"))
        assertEquals("http://localhost:8788", normalizeRadiusGatewayUrl("http://localhost:8788/"))

        val config =
            sanitizeRadiusGatewayConfig(
                buildJsonObject {
                    put("baseUrl", "https://radius.example/v1")
                    put(
                        "models",
                        buildJsonArray {
                            add(radiusModelJson())
                            add(buildJsonObject { put("id", "invalid") })
                            add(JsonPrimitive("not-an-object"))
                        },
                    )
                },
            )

        assertEquals(listOf("auto"), config?.models?.map { it.id })
        assertNull(sanitizeRadiusGatewayConfig(JsonArray(emptyList())))
    }

    private fun radiusConfig(baseUrl: String): String =
        buildJsonObject {
            put("baseUrl", "$baseUrl/v1")
            put("models", buildJsonArray { add(radiusModelJson()) })
        }.toString()

    private fun radiusConfigObject(baseUrl: String): RadiusGatewayConfig =
        RadiusGatewayConfig(
            baseUrl = baseUrl,
            models =
                listOf(
                    RadiusGatewayModel(
                        id = "auto",
                        name = "Radius Auto",
                        reasoning = false,
                        input = listOf(works.earendil.pi.ai.ModelInput.TEXT),
                        cost = works.earendil.pi.ai.ModelCost(1.0, 2.0, 0.1, 0.2),
                        contextWindow = 128_000,
                        maxTokens = 16_384,
                    ),
                ),
        )

    private fun radiusModelJson(): JsonObject =
        buildJsonObject {
            put("id", "auto")
            put("name", "Radius Auto")
            put("reasoning", false)
            put("input", buildJsonArray { add(JsonPrimitive("text")) })
            put(
                "cost",
                buildJsonObject {
                    put("input", 1.0)
                    put("output", 2.0)
                    put("cacheRead", 0.1)
                    put("cacheWrite", 0.2)
                },
            )
            put("contextWindow", 128_000)
            put("maxTokens", 16_384)
        }

    private fun server(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer
            .create(InetSocketAddress("127.0.0.1", 0), 0)
            .apply {
                createContext("/", handler)
                start()
            }

    private fun baseUrl(server: HttpServer): String = "http://127.0.0.1:${server.address.port}"

    private fun baseUrl(exchange: HttpExchange): String =
        "http://127.0.0.1:${exchange.localAddress.port}"

    private fun HttpExchange.respondJson(
        status: Int,
        body: String,
    ) {
        requestBody.readAllBytes()
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("content-type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.respondSse(body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("content-type", "text/event-stream")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
