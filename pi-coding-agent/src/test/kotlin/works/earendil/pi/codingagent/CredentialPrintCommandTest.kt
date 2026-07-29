package works.earendil.pi.codingagent

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.InMemoryCredentialStore
import works.earendil.pi.ai.InMemoryModelsStore
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.providers.builtInProviders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CredentialPrintCommandTest {
    @Test
    fun `parses credential commands and durations`() {
        assertEquals(
            CredentialPrintCommand(
                kind = CredentialPrintKind.API_KEY,
                arguments = listOf("--provider", "openai"),
            ),
            parseCredentialPrintCommand(
                listOf("auth", "print-api-key", "--provider", "openai"),
            ),
        )
        assertEquals(
            CredentialPrintCommand(
                kind = CredentialPrintKind.BEARER_TOKEN,
                arguments = emptyList(),
                minExpiryMs = 30 * 60_000,
            ),
            parseCredentialPrintCommand(
                listOf("auth", "print-bearer-token", "--min-expiry", "30m"),
            ),
        )
        assertTrue(isCredentialPrintHelp(listOf("auth", "--help")))
        assertFailsWith<CredentialPrintException> {
            parseCredentialPrintCommand(
                listOf("auth", "print-api-key", "--min-expiry", "30m"),
            )
        }
        assertFailsWith<CredentialPrintException> {
            parseCredentialPrintCommand(listOf("auth", "unknown"))
        }
    }

    @Test
    fun `resolves API keys and reports ambiguous configured providers`() =
        runTest {
            val credentials =
                InMemoryCredentialStore(
                    mapOf(
                        "first" to works.earendil.pi.ai.ApiKeyCredential("first-key"),
                        "second" to works.earendil.pi.ai.ApiKeyCredential("second-key"),
                    ),
                )
            val models =
                Models(
                    providers =
                        listOf(
                            FauxProvider(id = "first"),
                            FauxProvider(id = "second"),
                        ),
                    modelsStore = InMemoryModelsStore(),
                    credentials = credentials,
                )

            assertEquals(
                "first-key",
                resolveCredentialForPrint(
                    parseArgs(listOf("--provider", "first", "--model", "faux-1")),
                    models,
                    CredentialPrintKind.API_KEY,
                ),
            )
            val error =
                assertFailsWith<CredentialPrintException> {
                    resolveCredentialForPrint(
                        parseArgs(listOf("--model", "faux-1")),
                        models,
                        CredentialPrintKind.API_KEY,
                    )
                }
            assertTrue(error.message.orEmpty().contains("multiple configured providers"))
        }

    @Test
    fun `prints bearer token after enforcing the default thirty minute validity`() =
        runTest {
            val refreshes = AtomicInteger()
            val credentials =
                InMemoryCredentialStore(
                    mapOf(
                        "header-oauth" to
                            OAuthCredential(
                                access = "old-token",
                                refresh = "refresh-token",
                                expires = 1_600_000,
                            ),
                    ),
                )
            val provider = headerOAuthProvider(refreshes)
            val models =
                Models(
                    providers = listOf(provider),
                    modelsStore = InMemoryModelsStore(),
                    credentials = credentials,
                    currentTimeMillis = { 1_000_000 },
                )
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()

            val exit =
                runCredentialPrintCommand(
                    arguments =
                        listOf(
                            "auth",
                            "print-bearer-token",
                            "--provider",
                            "header-oauth",
                            "--model",
                            "shared-model",
                        ),
                    loadModels = { models },
                    output = PrintStream(stdout),
                    errorOutput = PrintStream(stderr),
                )

            assertEquals(0, exit)
            assertEquals("fresh-token\n", stdout.toString(StandardCharsets.UTF_8))
            assertEquals("", stderr.toString(StandardCharsets.UTF_8))
            assertEquals(1, refreshes.get())
            assertEquals(
                "fresh-token",
                (credentials.read("header-oauth") as OAuthCredential).access,
            )
        }

    @Test
    fun `prints ambient API keys from built in catalog providers`() =
        runTest {
            val models =
                Models(
                    providers = builtInProviders(),
                    environment = { name ->
                        if (name == "OPENAI_API_KEY") "ambient-openai-key" else null
                    },
                )

            assertEquals(
                "ambient-openai-key",
                resolveCredentialForPrint(
                    parseArgs(listOf("--provider", "openai", "--model", "gpt-5.5")),
                    models,
                    CredentialPrintKind.API_KEY,
                ),
            )
        }

    @Test
    fun `rejects credential type mismatches without printing a secret`() =
        runTest {
            val models =
                Models(
                    providers = listOf(FauxProvider()),
                    modelsStore = InMemoryModelsStore(),
                    credentials =
                        InMemoryCredentialStore(
                            mapOf(
                                "faux" to
                                    OAuthCredential(
                                        access = "must-not-print",
                                        refresh = "refresh",
                                        expires = Long.MAX_VALUE,
                                    ),
                            ),
                        ),
                )
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()

            val exit =
                runCredentialPrintCommand(
                    arguments =
                        listOf(
                            "auth",
                            "print-api-key",
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                        ),
                    loadModels = { models },
                    output = PrintStream(stdout),
                    errorOutput = PrintStream(stderr),
                )

            assertEquals(1, exit)
            assertEquals("", stdout.toString(StandardCharsets.UTF_8))
            assertTrue(
                stderr
                    .toString(StandardCharsets.UTF_8)
                    .contains("configured with OAuth, not an API key"),
            )
        }

    private fun headerOAuthProvider(refreshes: AtomicInteger): Provider {
        val model = FauxProvider(id = "header-oauth").getModels().single().copy(id = "shared-model")
        return object : Provider {
            override val id: String = "header-oauth"
            override val name: String = "Header OAuth"
            override val oauth: OAuthAuth =
                object : OAuthAuth {
                    override val name: String = "Header OAuth"

                    override suspend fun login(interaction: AuthInteraction): OAuthCredential =
                        error("not used")

                    override suspend fun refresh(credential: OAuthCredential): OAuthCredential {
                        refreshes.incrementAndGet()
                        return credential.copy(
                            access = "fresh-token",
                            expires = 4_600_000,
                        )
                    }

                    override suspend fun toAuth(credential: OAuthCredential): ModelAuth =
                        ModelAuth(
                            headers = mapOf("Authorization" to "Bearer ${credential.access}"),
                        )
                }

            override fun getModels(): List<Model> = listOf(model)

            override suspend fun stream(
                model: Model,
                context: Context,
                options: StreamOptions,
            ) = error("not used")
        }
    }
}
