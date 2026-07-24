package works.earendil.pi.ai

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelsStoreTest {
    @Test
    fun `json store persists providers independently and deletes without replacing neighbors`() =
        runTest {
            val directory = Files.createTempDirectory("pi-kotlin-models-store")
            try {
                val path = directory.resolve("models-store.json")
                val store = JsonFileModelsStore(path)

                store.write("one", ModelsStoreEntry(listOf(model("one", "m1")), checkedAt = 100))
                store.write("two", ModelsStoreEntry(listOf(model("two", "m2")), checkedAt = 200))

                val reloaded = JsonFileModelsStore(path)
                assertEquals(listOf("m1"), reloaded.read("one")?.models?.map(Model::id))
                assertEquals(100, reloaded.read("one")?.checkedAt)
                assertEquals(listOf("m2"), reloaded.read("two")?.models?.map(Model::id))
                assertTrue("\"one\"" in path.readText())
                assertTrue("\"two\"" in path.readText())

                reloaded.delete("one")
                assertEquals(null, reloaded.read("one"))
                assertEquals(listOf("m2"), reloaded.read("two")?.models?.map(Model::id))
                assertFalse("\"one\"" in path.readText())
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    private fun model(
        provider: String,
        id: String,
    ): Model =
        Model(
            id = id,
            name = id,
            api = "openai-completions",
            provider = provider,
            baseUrl = "https://example.test/v1",
            reasoning = false,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 1_000,
            maxTokens = 100,
            compat = JsonObject(emptyMap()),
        )
}
