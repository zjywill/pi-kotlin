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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsAuthTest {
    @Test
    fun `login persists OAuth and logout removes only that provider`() =
        runTest {
            val credential =
                OAuthCredential(
                    access = "access",
                    refresh = "refresh",
                    expires = 1_000_000,
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
                                expires = 1_000_000,
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
                            expires = 1_000_000,
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
                    expires = 1_000_000,
                ),
                store.read("faux"),
            )
        }

    @Test
    fun `OAuth refreshes with less than five minutes remaining`() =
        runTest {
            val refreshes = AtomicInteger()
            val store =
                InMemoryCredentialStore(
                    mapOf(
                        "faux" to
                            OAuthCredential(
                                access = "old-access",
                                refresh = "refresh",
                                expires = 1_060_000,
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
                        return credential.copy(
                            access = "fresh-access",
                            expires = 4_600_000,
                        )
                    }

                    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
                        ModelAuth(apiKey = credential.access)
                }
            val models = Models(listOf(FauxProvider(oauth = oauth)), InMemoryModelsStore(), store) { 1_000_000 }

            assertEquals("fresh-access", models.getAuth("faux")?.auth?.apiKey)
            assertEquals(1, refreshes.get())
            assertEquals("fresh-access", (store.read("faux") as OAuthCredential).access)
        }

    @Test
    fun `OAuth refresh is bounded to fifteen seconds`() =
        runTest {
            val store =
                InMemoryCredentialStore(
                    mapOf(
                        "faux" to
                            OAuthCredential(
                                access = "old-access",
                                refresh = "refresh",
                                expires = 1_060_000,
                            ),
                    ),
                )
            val oauth =
                object : OAuthAuth {
                    override val name: String = "Slow OAuth"

                    override suspend fun login(interaction: AuthInteraction): OAuthCredential =
                        error("not used")

                    override suspend fun refresh(credential: OAuthCredential): OAuthCredential {
                        delay(Long.MAX_VALUE)
                        return credential
                    }

                    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
                        ModelAuth(apiKey = credential.access)
                }
            val models = Models(listOf(FauxProvider(oauth = oauth)), InMemoryModelsStore(), store) { 1_000_000 }

            val error =
                kotlin.test.assertFailsWith<ModelsAuthException> {
                    models.getAuth("faux")
                }

            assertTrue(error.message.orEmpty().contains("timed out after 15000ms"))
        }

    @Test
    fun `explicit OAuth minimum validity is enforced after refresh`() =
        runTest {
            val store =
                InMemoryCredentialStore(
                    mapOf(
                        "faux" to
                            OAuthCredential(
                                access = "old-access",
                                refresh = "refresh",
                                expires = 1_600_000,
                            ),
                    ),
                )
            val oauth =
                object : OAuthAuth {
                    override val name: String = "Test OAuth"

                    override suspend fun login(interaction: AuthInteraction): OAuthCredential =
                        error("not used")

                    override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
                        credential.copy(
                            access = "still-too-short",
                            expires = 2_200_000,
                        )

                    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
                        ModelAuth(apiKey = credential.access)
                }
            val models = Models(listOf(FauxProvider(oauth = oauth)), InMemoryModelsStore(), store) { 1_000_000 }

            val error =
                kotlin.test.assertFailsWith<ModelsAuthException> {
                    models.getAuth(
                        "faux",
                        AuthResolutionOverrides(minOAuthValidityMs = 30 * 60_000),
                    )
                }

            assertEquals("oauth", error.code)
            assertEquals(
                "OAuth refresh returned a token that expires too soon for faux",
                error.message,
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
            assertEquals("OAuth refresh failed for faux: invalid_grant", result.errorMessage)
            assertEquals(0, provider.state.callCount)
            assertIs<OAuthCredential>(store.read("faux"))
        }

    @Test
    fun `provider API key login check and resolve own stored and request scoped auth`() =
        runTest {
            val credentialStore = InMemoryCredentialStore()
            val delegate = FauxProvider(id = "native")
            val prompts = mutableListOf<AuthPrompt>()
            val apiKey =
                object : ApiKeyAuth {
                    override val name: String = "Native setup"
                    override val supportsLogin: Boolean = true

                    override suspend fun login(interaction: AuthInteraction): ApiKeyCredential =
                        ApiKeyCredential(
                            key =
                                interaction.prompt(
                                    AuthPrompt.Text(
                                        message = "API key",
                                        secret = true,
                                    ),
                                ),
                        )

                    override suspend fun check(
                        context: AuthContext,
                        credential: ApiKeyCredential?,
                    ): AuthCheck? =
                        credential?.key?.let {
                            AuthCheck(
                                source = "stored native key",
                                type = AuthType.API_KEY,
                            )
                        }

                    override suspend fun resolve(
                        context: AuthContext,
                        credential: ApiKeyCredential?,
                    ): AuthResult? {
                        val key = credential?.key ?: return null
                        val account = credential.env["ACCOUNT"] ?: context.env("ACCOUNT") ?: return null
                        if (!context.fileExists("~/native-auth")) {
                            return null
                        }
                        return AuthResult(
                            auth =
                                ModelAuth(
                                    apiKey = key,
                                    headers = mapOf("X-Account" to account),
                                    baseUrl = "https://native.invalid/$account",
                                ),
                            source = "stored native key",
                            env = mapOf("ACCOUNT" to account),
                        )
                    }
                }
            val provider =
                object : Provider by delegate {
                    override val apiKey: ApiKeyAuth = apiKey
                }
            val context =
                object : AuthContext {
                    override suspend fun env(name: String): String? =
                        if (name == "ACCOUNT") "ambient-account" else null

                    override suspend fun fileExists(path: String): Boolean =
                        path == "~/native-auth"
                }
            val models =
                Models(
                    providers = listOf(provider),
                    credentials = credentialStore,
                    authContext = context,
                )

            assertNull(models.checkAuth("native"))
            assertEquals(emptyList(), models.getAvailable("native"))

            val loggedIn =
                models.login(
                    "native",
                    AuthType.API_KEY,
                    object : AuthInteraction {
                        override suspend fun prompt(prompt: AuthPrompt): String {
                            prompts += prompt
                            return "stored-secret"
                        }

                        override fun notify(event: AuthEvent) = Unit
                    },
                )

            assertEquals(ApiKeyCredential("stored-secret"), loggedIn)
            assertEquals(true, (prompts.single() as AuthPrompt.Text).secret)
            assertEquals(
                AuthCheck(
                    source = "stored native key",
                    type = AuthType.API_KEY,
                ),
                models.checkAuth("native"),
            )
            assertEquals(listOf("faux-1"), models.getAvailable("native").map(Model::id))

            val stored = assertNotNull(models.getAuth("native"))
            assertEquals("stored-secret", stored.auth.apiKey)
            assertEquals("https://native.invalid/ambient-account", stored.auth.baseUrl)
            assertEquals("ambient-account", stored.auth.headers["X-Account"])

            val explicit =
                assertNotNull(
                    models.getAuth(
                        "native",
                        AuthResolutionOverrides(
                            apiKey = "request-secret",
                            env = mapOf("ACCOUNT" to "request-account"),
                        ),
                    ),
                )
            assertEquals("request-secret", explicit.auth.apiKey)
            assertEquals("https://native.invalid/request-account", explicit.auth.baseUrl)
            assertEquals(mapOf("ACCOUNT" to "request-account"), explicit.env)
        }

    private fun staticOAuth(
        loginCredential: OAuthCredential =
            OAuthCredential(
                access = "access",
                refresh = "refresh",
                expires = 1_000_000,
            ),
    ): OAuthAuth =
        object : OAuthAuth {
            override val name: String = "Test OAuth"

            override suspend fun login(interaction: AuthInteraction): OAuthCredential = loginCredential

            override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
                credential.copy(expires = 1_000_000)

            override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
                ModelAuth(apiKey = credential.access)
        }

    private object NoOpInteraction : AuthInteraction {
        override suspend fun prompt(prompt: AuthPrompt): String = error("not used")

        override fun notify(event: AuthEvent) = Unit
    }
}
