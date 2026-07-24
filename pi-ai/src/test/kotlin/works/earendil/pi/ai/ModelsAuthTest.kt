package works.earendil.pi.ai

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ModelsAuthTest {
    @Test
    fun `login persists OAuth and logout removes only that provider`() =
        runTest {
            val credential =
                OAuthCredential(
                    access = "access",
                    refresh = "refresh",
                    expires = 10_000,
                    accountId = "account",
                )
            val store =
                InMemoryCredentialStore(
                    mapOf(
                        "other" to ApiKeyCredential("other-key"),
                    ),
                )
            val provider = FauxProvider(oauth = staticOAuth(loginCredential = credential))
            val models = Models(listOf(provider), InMemoryModelsStore(), store) { 1_000 }

            val loggedIn = models.login("faux", AuthType.OAUTH, NoOpInteraction)

            assertEquals(credential, loggedIn)
            assertEquals("access", models.getAuth("faux")?.auth?.apiKey)
            assertEquals(
                listOf("faux" to "oauth", "other" to "api_key"),
                models.listCredentials().map { it.providerId to it.type },
            )

            models.logout("faux")

            assertEquals(null, store.read("faux"))
            assertEquals(ApiKeyCredential("other-key"), store.read("other"))
        }

    @Test
    fun `stored OAuth is injected ahead of explicit and ambient provider values`() =
        runTest {
            val store =
                InMemoryCredentialStore(
                    mapOf(
                        "faux" to
                            OAuthCredential(
                                access = "stored-access",
                                refresh = "refresh",
                                expires = 10_000,
                            ),
                    ),
                )
            val provider = FauxProvider(oauth = staticOAuth())
            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { _, options, _, _ ->
                        assertEquals("stored-access", options.apiKey)
                        assertEquals("ambient-access", options.env["FAUX_TOKEN"])
                        fauxAssistantMessage("authenticated")
                    },
                ),
            )
            val models = Models(listOf(provider), InMemoryModelsStore(), store) { 1_000 }

            val result =
                models.completeSimple(
                    requireNotNull(provider.getModel()),
                    Context(messages = mutableListOf(UserMessage("hello"))),
                    SimpleStreamOptions(
                        stream =
                            StreamOptions(
                                apiKey = "explicit-access",
                                env = mapOf("FAUX_TOKEN" to "ambient-access"),
                            ),
                    ),
                )

            assertEquals(StopReason.STOP, result.stopReason)
            assertEquals("authenticated", contentText(result.content))
        }

    @Test
    fun `concurrent expired OAuth requests refresh once and persist rotation`() =
        runTest {
            val refreshes = AtomicInteger()
            val store =
                InMemoryCredentialStore(
                    mapOf(
                        "faux" to
                            OAuthCredential(
                                access = "expired",
                                refresh = "old-refresh",
                                expires = 0,
                            ),
                    ),
                )
            val oauth =
                object : OAuthAuth {
                    override val name: String = "Test OAuth"

                    override suspend fun login(interaction: AuthInteraction): OAuthCredential =
                        error("not used")

                    override suspend fun refresh(credential: OAuthCredential): OAuthCredential {
                        refreshes.incrementAndGet()
                        delay(25)
                        return credential.copy(
                            access = "rotated-access",
                            refresh = "rotated-refresh",
                            expires = 20_000,
                        )
                    }

                    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
                        ModelAuth(apiKey = credential.access)
                }
            val models = Models(listOf(FauxProvider(oauth = oauth)), InMemoryModelsStore(), store) { 1_000 }

            val results =
                coroutineScope {
                    List(20) {
                        async { models.getAuth("faux")?.auth?.apiKey }
                    }.awaitAll()
                }

            assertEquals(List(20) { "rotated-access" }, results)
            assertEquals(1, refreshes.get())
            assertEquals(
                OAuthCredential(
                    access = "rotated-access",
                    refresh = "rotated-refresh",
                    expires = 20_000,
                ),
                store.read("faux"),
            )
        }

    @Test
    fun `failed stored OAuth refresh does not fall back to ambient auth`() =
        runTest {
            val store =
                InMemoryCredentialStore(
                    mapOf(
                        "faux" to
                            OAuthCredential(
                                access = "expired",
                                refresh = "invalid",
                                expires = 0,
                            ),
                    ),
                )
            val oauth =
                object : OAuthAuth {
                    override val name: String = "Test OAuth"

                    override suspend fun login(interaction: AuthInteraction): OAuthCredential =
                        error("not used")

                    override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
                        error("invalid_grant")

                    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
                        ModelAuth(apiKey = credential.access)
                }
            val provider = FauxProvider(oauth = oauth)
            provider.setResponses(listOf(FauxResponseStep.Message(fauxAssistantMessage("must not run"))))
            val models = Models(listOf(provider), InMemoryModelsStore(), store) { 1_000 }

            val result =
                models.completeSimple(
                    requireNotNull(provider.getModel()),
                    Context(messages = mutableListOf(UserMessage("hello"))),
                    SimpleStreamOptions(
                        stream = StreamOptions(env = mapOf("FAUX_TOKEN" to "ambient-access")),
                    ),
                )

            assertEquals(StopReason.ERROR, result.stopReason)
            assertEquals("OAuth refresh failed for faux", result.errorMessage)
            assertEquals(0, provider.state.callCount)
            assertIs<OAuthCredential>(store.read("faux"))
        }

    private fun staticOAuth(
        loginCredential: OAuthCredential =
            OAuthCredential(
                access = "access",
                refresh = "refresh",
                expires = 10_000,
            ),
    ): OAuthAuth =
        object : OAuthAuth {
            override val name: String = "Test OAuth"

            override suspend fun login(interaction: AuthInteraction): OAuthCredential = loginCredential

            override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
                credential.copy(expires = 10_000)

            override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
                ModelAuth(apiKey = credential.access)
        }

    private object NoOpInteraction : AuthInteraction {
        override suspend fun prompt(prompt: AuthPrompt): String = error("not used")

        override fun notify(event: AuthEvent) = Unit
    }
}
