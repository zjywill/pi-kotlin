package works.earendil.pi.ai

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import works.earendil.pi.ai.providers.builtInImageCatalogHash
import works.earendil.pi.ai.providers.builtInImagesProviders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImagesModelsTest {
    @Test
    fun `registers providers and reads last known models best effort`() {
        val models = ImagesModels()
        models.setProvider(testProvider("p1", listOf(imageModel("p1", "m1"), imageModel("p1", "m2"))))
        models.setProvider(testProvider("p2", listOf(imageModel("p2", "m3"))))
        models.setProvider(
            object : ImagesProvider {
                override val id: String = "broken"
                override val name: String = "Broken"
                override fun getModels(): List<ImagesModel> = error("broken catalog")
                override suspend fun generateImages(
                    model: ImagesModel,
                    context: ImagesContext,
                    options: ImagesOptions,
                ): AssistantImages = okImages(model)
            },
        )

        assertEquals(listOf("p1", "p2", "broken"), models.getProviders().map(ImagesProvider::id))
        assertEquals(listOf("m1", "m2", "m3"), models.getModels().map(ImagesModel::id))
        assertEquals(listOf("m1", "m2"), models.getModels("p1").map(ImagesModel::id))
        assertEquals(emptyList(), models.getModels("broken"))
        assertEquals("m3", models.getModel("p2", "m3")?.id)
        assertNull(models.getModel("p2", "missing"))

        models.deleteProvider("p1")
        assertNull(models.getProvider("p1"))
        models.clearProviders()
        assertEquals(emptyList(), models.getProviders())
    }

    @Test
    fun `resolves stored and ambient API keys with request precedence`() =
        runTest {
            val calls = mutableListOf<ImageCall>()
            val store =
                InMemoryCredentialStore(
                    mapOf(
                        "p1" to
                            ApiKeyCredential(
                                key = "stored-key",
                                env =
                                    mapOf(
                                        "PROVIDER_ONLY" to "provider",
                                        "SHARED" to "provider",
                                    ),
                            ),
                    ),
                )
            val models =
                ImagesModels(
                    providers = listOf(testProvider("p1", calls = calls)),
                    credentials = store,
                    environment = { name -> if (name == "TEST_KEY") "ambient-key" else null },
                )
            val model = requireNotNull(models.getModel("p1", "model-a"))

            assertEquals("stored-key", models.getAuth(model)?.auth?.apiKey)
            models.generateImages(
                model,
                IMAGE_CONTEXT,
                ImagesOptions(
                    env =
                        mapOf(
                            "REQUEST_ONLY" to "request",
                            "SHARED" to "request",
                        ),
                ),
            )
            assertEquals("stored-key", calls[0].options.apiKey)
            assertEquals(
                mapOf(
                    "PROVIDER_ONLY" to "provider",
                    "REQUEST_ONLY" to "request",
                    "SHARED" to "request",
                ),
                calls[0].options.env,
            )

            models.generateImages(
                model,
                IMAGE_CONTEXT,
                ImagesOptions(apiKey = "explicit-key"),
            )
            assertEquals("explicit-key", calls[1].options.apiKey)

            store.delete("p1")
            assertEquals("ambient-key", models.getAuth("p1")?.auth?.apiKey)
        }

    @Test
    fun `applies OAuth base URL and merges auth headers below request headers`() =
        runTest {
            val calls = mutableListOf<ImageCall>()
            val oauth =
                object : OAuthAuth {
                    override val name: String = "Images OAuth"

                    override suspend fun login(interaction: AuthInteraction): OAuthCredential =
                        error("not used")

                    override suspend fun refresh(credential: OAuthCredential): OAuthCredential = credential

                    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
                        ModelAuth(
                            apiKey = credential.access,
                            baseUrl = "https://oauth.example/v1",
                            headers =
                                mapOf(
                                    "X-Auth" to "oauth",
                                    "X-Shared" to "oauth",
                                    "X-Remove" to "oauth",
                                ),
                        )
                }
            val provider =
                ConfigurableImagesProvider(
                    id = "p1",
                    apiKeyEnvNames = listOf("TEST_KEY"),
                    oauth = oauth,
                    initialModels = listOf(imageModel("p1", "model-a")),
                    generate =
                        ImagesFunction { model, _, options ->
                            calls += ImageCall(model, options)
                            okImages(model)
                        },
                )
            val models =
                ImagesModels(
                    providers = listOf(provider),
                    credentials =
                        InMemoryCredentialStore(
                            mapOf(
                                "p1" to
                                    OAuthCredential(
                                        access = "oauth-key",
                                        refresh = "",
                                        expires = Long.MAX_VALUE,
                                    ),
                            ),
                        ),
                )
            val model = requireNotNull(models.getModel("p1", "model-a"))

            models.generateImages(
                model,
                IMAGE_CONTEXT,
                ImagesOptions(
                    headers =
                        mapOf(
                            "x-shared" to "request",
                            "X-Remove" to null,
                            "X-Request" to "request",
                        ),
                ),
            )

            assertEquals("https://oauth.example/v1", calls.single().model.baseUrl)
            assertEquals("oauth-key", calls.single().options.apiKey)
            assertEquals(
                mapOf(
                    "X-Auth" to "oauth",
                    "x-shared" to "request",
                    "X-Remove" to null,
                    "X-Request" to "request",
                ),
                calls.single().options.headers,
            )
        }

    @Test
    fun `unknown unconfigured and cancelled providers return terminal results`() =
        runTest {
            val models = ImagesModels()
            val ghost = models.generateImages(imageModel("ghost", "m"), IMAGE_CONTEXT)
            assertEquals(ImagesStopReason.ERROR, ghost.stopReason)
            assertEquals("Unknown provider: ghost", ghost.errorMessage)

            val unconfiguredCalls = mutableListOf<ImageCall>()
            models.setProvider(testProvider("p1", calls = unconfiguredCalls))
            val unconfigured =
                models.generateImages(
                    requireNotNull(models.getModel("p1", "model-a")),
                    IMAGE_CONTEXT,
                )
            assertEquals(ImagesStopReason.STOP, unconfigured.stopReason)
            assertNull(unconfiguredCalls.single().options.apiKey)

            models.setProvider(
                ConfigurableImagesProvider(
                    id = "cancelled",
                    initialModels = listOf(imageModel("cancelled", "model-a")),
                    generate =
                        ImagesFunction { _, _, _ ->
                            throw CancellationException("Request aborted")
                        },
                ),
            )
            val aborted =
                models.generateImages(
                    requireNotNull(models.getModel("cancelled", "model-a")),
                    IMAGE_CONTEXT,
                )
            assertEquals(ImagesStopReason.ABORTED, aborted.stopReason)
            assertEquals("Request aborted", aborted.errorMessage)
        }

    @Test
    fun `dynamic refresh deduplicates in flight work and isolates failures`() =
        runTest {
            val fetches = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val provider =
                ConfigurableImagesProvider(
                    id = "dynamic",
                    initialModels = emptyList(),
                    refreshSource = {
                        fetches.incrementAndGet()
                        gate.await()
                        listOf(imageModel("dynamic", "listed"))
                    },
                    generate = ImagesFunction { model, _, _ -> okImages(model) },
                )
            val models = ImagesModels(listOf(provider))
            val refreshes =
                listOf(
                    async { models.refresh("dynamic") },
                    async { models.refresh("dynamic") },
                )
            while (fetches.get() == 0) {
                yield()
            }
            gate.complete(Unit)
            refreshes.awaitAll()

            assertEquals(1, fetches.get())
            assertNotNull(models.getModel("dynamic", "listed"))

            models.setProvider(
                ConfigurableImagesProvider(
                    id = "flaky",
                    refreshSource = { error("fetch failed") },
                    generate = ImagesFunction { model, _, _ -> okImages(model) },
                ),
            )
            val error = assertFailsWith<ImagesModelsException> { models.refresh("flaky") }
            assertEquals("model_source", error.code)
            models.refresh()
        }

    @Test
    fun `cancelled dynamic refresh clears in flight state for a later retry`() =
        runTest {
            val fetches = AtomicInteger()
            val firstStarted = CompletableDeferred<Unit>()
            val provider =
                ConfigurableImagesProvider(
                    id = "dynamic",
                    refreshSource = {
                        if (fetches.incrementAndGet() == 1) {
                            firstStarted.complete(Unit)
                            CompletableDeferred<Unit>().await()
                        }
                        listOf(imageModel("dynamic", "recovered"))
                    },
                    generate = ImagesFunction { model, _, _ -> okImages(model) },
                )
            val models = ImagesModels(listOf(provider))
            val first = launch { models.refresh("dynamic") }
            firstStarted.await()
            first.cancelAndJoin()

            models.refresh("dynamic")

            assertEquals(2, fetches.get())
            assertNotNull(models.getModel("dynamic", "recovered"))
        }

    @Test
    fun `built in image collection exposes exact OpenRouter catalog and auth`() =
        runTest {
            val providers = builtInImagesProviders()
            assertEquals(listOf("openrouter"), providers.map(ImagesProvider::id))
            assertEquals("OpenRouter", providers.single().name)
            assertNotNull(providers.single().oauth)
            assertEquals(39, providers.single().getModels().size)
            assertTrue(providers.single().getModels().all { it.api == "openrouter-images" })
            assertEquals(
                "8d32873b3425e4f0dd82327440ce56dedcbf6cc0313319ecd96478b81e078ecc",
                builtInImageCatalogHash(),
            )

            val models =
                ImagesModels(
                    providers = providers,
                    environment = { name -> if (name == "OPENROUTER_API_KEY") "env-key" else null },
                )
            assertEquals("env-key", models.getAuth("openrouter")?.auth?.apiKey)
            assertIs<TextContent>(IMAGE_CONTEXT.input.single())
        }

    private fun testProvider(
        id: String,
        models: List<ImagesModel> = listOf(imageModel(id, "model-a")),
        calls: MutableList<ImageCall>? = null,
    ): ImagesProvider =
        ConfigurableImagesProvider(
            id = id,
            apiKeyEnvNames = listOf("TEST_KEY"),
            initialModels = models,
            generate =
                ImagesFunction { model, _, options ->
                    calls?.add(ImageCall(model, options))
                    okImages(model)
                },
        )

    private data class ImageCall(
        val model: ImagesModel,
        val options: ImagesOptions,
    )

    companion object {
        private val IMAGE_CONTEXT =
            ImagesContext(
                input = listOf(TextContent("a red circle")),
            )
    }
}

private fun imageModel(
    provider: String,
    id: String,
): ImagesModel =
    ImagesModel(
        id = id,
        name = id,
        api = "test-images",
        provider = provider,
        baseUrl = "https://example.test/v1",
        input = listOf(ModelInput.TEXT),
        output = listOf(ModelInput.IMAGE),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
    )

private fun okImages(model: ImagesModel): AssistantImages =
    AssistantImages(
        api = model.api,
        provider = model.provider,
        model = model.id,
        output = listOf(ImageContent(data = "aGk=", mimeType = "image/png")),
    )
