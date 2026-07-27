package works.earendil.pi.codingagent

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import works.earendil.pi.ai.JsonFileModelsStore
import works.earendil.pi.ai.ModelsStoreEntry
import works.earendil.pi.ai.providers.builtInModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelCatalogCommandTest {
    @Test
    fun `update models rejects conflicting targets before network access`() =
        runBlocking {
            val stderr = ByteArrayOutputStream()

            val exitCode =
                runModelCatalogCommand(
                    arguments = listOf("update", "--models", "--extensions"),
                    catalogBaseUrl = "http://127.0.0.1:1",
                    output = PrintStream(ByteArrayOutputStream()),
                    errorOutput = PrintStream(stderr),
                )

            assertEquals(1, exitCode)
            assertTrue(stderr.toString().contains("--models cannot be combined with --extensions"))
        }

    @Test
    fun `update models refreshes every catalog route and persists check results`() =
        runBlocking {
            val directory = Files.createTempDirectory("pi-kotlin-model-update")
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                val bytes = "not implemented".toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(501, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            try {
                val exitCode =
                    runModelCatalogCommand(
                        arguments = listOf("update", "--models"),
                        agentDir = directory,
                        catalogBaseUrl = "http://127.0.0.1:${server.address.port}",
                        output = PrintStream(stdout),
                        errorOutput = PrintStream(stderr),
                    )

                assertEquals(0, exitCode, stderr.toString())
                assertEquals("Model catalogs refreshed", stdout.toString().trim())
                assertEquals("", stderr.toString().trim())
                val stored = JsonFileModelsStore(directory.resolve("models-store.json"))
                assertNotNull(stored.read("openai")?.checkedAt)
                assertEquals(0, stored.read("openai")?.lastModified)
            } finally {
                server.stop(0)
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun `ordinary startup restores a newer persisted catalog without network access`() =
        runBlocking {
            val directory = Files.createTempDirectory("pi-kotlin-model-restore")
            try {
                val remote =
                    builtInModels("openai")
                        .first()
                        .copy(
                            id = "remote-only-test-model",
                            name = "Remote only test model",
                        )
                JsonFileModelsStore(directory.resolve("models-store.json"))
                    .write(
                        "openai",
                        ModelsStoreEntry(
                            models = listOf(remote),
                            lastModified = Long.MAX_VALUE,
                            checkedAt = 100,
                        ),
                    )

                val models =
                    loadBuiltInModels(
                        agentDir = directory,
                        catalogBaseUrl = "http://127.0.0.1:1",
                    )

                assertNotNull(models.getModel("openai", "remote-only-test-model"))
                assertTrue(models.getModels("openai").any { it.id == remote.id })
            } finally {
                directory.toFile().deleteRecursively()
            }
        }
}
