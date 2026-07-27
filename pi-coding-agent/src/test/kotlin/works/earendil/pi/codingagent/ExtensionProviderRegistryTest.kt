package works.earendil.pi.codingagent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.test.runTest
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.Models
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionProviderRegistryTest {
    @Test
    fun `registration composes models and invalid replacement preserves prior config`() {
        val base = FauxProvider()
        val models = Models(listOf(base))
        val registry = ExtensionProviderRegistry(models)
        registry.unregister("faux")
        assertEquals(base, models.getProvider("faux"))

        registry.register("faux", providerConfig("extension-faux", contextWindow = 8_192))

        assertEquals("Extension Faux", models.getProvider("faux")?.name)
        assertNotNull(models.getModel("faux", "extension-faux"))
        assertNull(models.getModel("faux", "faux-1"))

        registry.register("faux", buildJsonObject { put("name", "Renamed Faux") })
        assertEquals("Renamed Faux", models.getProvider("faux")?.name)
        assertNotNull(models.getModel("faux", "extension-faux"))

        registry.unregister("faux")
        assertEquals(base, models.getProvider("faux"))
        assertNotNull(models.getModel("faux", "faux-1"))
    }

    @Test
    fun `new serializable provider uses a migrated protocol delegate`() {
        val models = Models()
        val registry = ExtensionProviderRegistry(models)
        registry.register(
            "fixture",
            buildJsonObject {
                put("name", "Fixture")
                put("api", "openai-responses")
                put("baseUrl", "https://fixture.invalid/v1")
                put("apiKey", "\$FIXTURE_API_KEY")
                put(
                    "models",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("id", "fixture-model")
                                put("name", "Fixture Model")
                                put("reasoning", false)
                                put("input", JsonArray(listOf(JsonPrimitive("text"))))
                                put(
                                    "cost",
                                    buildJsonObject {
                                        put("input", 0)
                                        put("output", 0)
                                        put("cacheRead", 0)
                                        put("cacheWrite", 0)
                                    },
                                )
                                put("contextWindow", 8_192)
                                put("maxTokens", 1_024)
                            },
                        ),
                    ),
                )
            },
        )

        assertEquals("Fixture", models.getProvider("fixture")?.name)
        assertEquals("openai-responses", models.getModel("fixture", "fixture-model")?.api)
        assertEquals("https://fixture.invalid/v1", models.getModel("fixture", "fixture-model")?.baseUrl)

        assertFailsWith<IllegalStateException> {
            registry.register(
                "fixture",
                buildJsonObject {
                    put(
                        "models",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("id", "fixture-model")
                                    put("name", "Fixture Model")
                                    put("reasoning", false)
                                    put("input", JsonArray(listOf(JsonPrimitive("text"))))
                                    put("cost", buildJsonObject {})
                                    put("contextWindow", 8_192)
                                    put("maxTokens", 1_024)
                                },
                            ),
                        ),
                    )
                },
            )
        }
        assertNotNull(models.getModel("fixture", "fixture-model"))
    }

    @Test
    fun `provider auth resolves configured values and bearer headers lazily`() =
        runTest {
            val models = Models()
            val registry =
                ExtensionProviderRegistry(
                    models,
                    environment =
                        mapOf(
                            "FIXTURE_API_KEY" to "secret",
                            "FIXTURE_HEADER" to "header-value",
                        ),
                )
            registry.register(
                "fixture",
                JsonObject(
                    providerConfig("fixture-model", contextWindow = 8_192) +
                        mapOf(
                            "api" to JsonPrimitive("openai-responses"),
                            "baseUrl" to JsonPrimitive("https://fixture.invalid/v1"),
                            "apiKey" to JsonPrimitive("\$FIXTURE_API_KEY"),
                            "authHeader" to JsonPrimitive(true),
                            "headers" to
                                buildJsonObject {
                                    put("X-Fixture", "\$FIXTURE_HEADER")
                                },
                        ),
                ),
            )

            val auth = models.getAuth("fixture")
            assertEquals("secret", auth?.auth?.apiKey)
            assertEquals("header-value", auth?.auth?.headers?.get("X-Fixture"))
            assertEquals("Bearer secret", auth?.auth?.headers?.get("Authorization"))
            assertTrue(models.getProvider("fixture")?.headers.orEmpty().isEmpty())
        }

    private fun providerConfig(
        modelId: String,
        contextWindow: Int,
    ): JsonObject =
        buildJsonObject {
            put("name", "Extension Faux")
            put("api", "faux")
            put("baseUrl", "http://localhost:0")
            put(
                "models",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", modelId)
                            put("name", modelId)
                            put("reasoning", false)
                            put("input", JsonArray(listOf(JsonPrimitive("text"))))
                            put(
                                "cost",
                                buildJsonObject {
                                    put("input", 0)
                                    put("output", 0)
                                    put("cacheRead", 0)
                                    put("cacheWrite", 0)
                                },
                            )
                            put("contextWindow", contextWindow)
                            put("maxTokens", 1_024)
                        },
                    ),
                ),
            )
        }
}
