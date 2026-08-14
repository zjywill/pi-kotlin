package works.earendil.pi.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

class ModelsRefreshGenerationTest {
    @Test
    fun `new provider refresh publishes without waiting for stale work`() =
        runTest {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            var networkCalls = 0
            var current = model("initial")
            val provider =
                refreshProvider("dynamic") { context ->
                    if (!context.allowNetwork) {
                        return@refreshProvider
                    }
                    networkCalls += 1
                    val id = if (networkCalls == 1) "stale" else "fresh"
                    if (networkCalls == 1) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                    context.publish(
                        ModelsPublication(
                            update = { current = model(id) },
                        ),
                    )
                }.also { it.models = { listOf(current) } }
            val models = Models(listOf(provider))

            val first =
                async {
                    models.refresh(
                        ModelsRefreshOptions(
                            allowNetwork = true,
                            providers = setOf(provider.id),
                        ),
                    )
                }
            firstStarted.await()
            val second =
                models.refresh(
                    ModelsRefreshOptions(
                        allowNetwork = true,
                        providers = setOf(provider.id),
                    ),
                )
            releaseFirst.complete(Unit)
            val stale = first.await()

            assertTrue(second.errors.isEmpty())
            assertTrue(stale.errors.isEmpty())
            assertEquals("fresh", models.getModels(provider.id).single().id)
        }

    @Test
    fun `provider scoped cache refresh makes committed login available`() =
        runTest {
            val storedModel = model("cached").copy(provider = "login-provider")
            val store =
                InMemoryModelsStore().also {
                    it.write("login-provider", ModelsStoreEntry(models = listOf(storedModel)))
                }
            var current = emptyList<Model>()
            val provider =
                refreshProvider("login-provider") { context ->
                    val restored = context.stored?.models.orEmpty()
                    context.publish(ModelsPublication(update = { current = restored }))
                }.also {
                    it.models = { current }
                    it.apiKeyMethod =
                        object : ApiKeyAuth {
                            override val name = "API key"
                            override val supportsLogin = true

                            override suspend fun login(interaction: AuthInteraction): ApiKeyCredential =
                                ApiKeyCredential("secret")

                            override suspend fun resolve(
                                context: AuthContext,
                                credential: ApiKeyCredential?,
                            ): AuthResult? =
                                credential?.key?.let {
                                    AuthResult(ModelAuth(apiKey = it), "stored")
                                }
                        }
                }
            val models = Models(listOf(provider), modelsStore = store)

            models.login(provider.id, AuthType.API_KEY, NoopAuthInteraction)
            models.refresh(
                ModelsRefreshOptions(
                    allowNetwork = false,
                    providers = setOf(provider.id),
                ),
            )

            assertEquals(listOf("cached"), models.getAvailable(provider.id).map(Model::id))
        }

    @Test
    fun `concurrent all-catalog refreshes share one in-flight operation`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var networkCalls = 0
            val provider =
                refreshProvider("shared") { context ->
                    if (!context.allowNetwork) {
                        return@refreshProvider
                    }
                    networkCalls++
                    started.complete(Unit)
                    release.await()
                    context.publish(ModelsPublication())
                }
            val models = Models(listOf(provider))
            val options = ModelsRefreshOptions(allowNetwork = true)

            val first = async { models.refresh(options) }
            started.await()
            val second = async { models.refresh(options) }
            kotlinx.coroutines.yield()

            assertEquals(1, networkCalls)
            release.complete(Unit)
            assertTrue(first.await().errors.isEmpty())
            assertTrue(second.await().errors.isEmpty())
        }

    private class MutableRefreshProvider(
        override val id: String,
        private val refresh: suspend (RefreshModelsContext) -> Unit,
    ) : Provider {
        override val name: String = id
        var models: () -> List<Model> = { emptyList() }
        var apiKeyMethod: ApiKeyAuth? = null

        override val apiKey: ApiKeyAuth?
            get() = apiKeyMethod

        override val supportsModelRefresh: Boolean = true

        override fun getModels(): List<Model> = models()

        override suspend fun refreshModels(context: RefreshModelsContext) = refresh(context)

        override suspend fun stream(
            model: Model,
            context: Context,
            options: StreamOptions,
        ): AssistantMessageEventStream = createAssistantMessageEventStream()
    }

    private fun refreshProvider(
        id: String,
        refresh: suspend (RefreshModelsContext) -> Unit,
    ): MutableRefreshProvider = MutableRefreshProvider(id, refresh)

    private fun model(id: String): Model =
        Model(
            id = id,
            name = id,
            api = "openai-completions",
            provider = "dynamic",
            baseUrl = "https://example.invalid/v1",
            reasoning = false,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 8_192,
            maxTokens = 1_024,
        )

    private data object NoopAuthInteraction : AuthInteraction {
        override suspend fun prompt(prompt: AuthPrompt): String = ""

        override fun notify(event: AuthEvent) = Unit
    }
}
