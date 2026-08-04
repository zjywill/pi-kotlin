package works.earendil.pi.codingagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput

class ModelReferenceTest {
    @Test
    fun `ambiguous exact model prefers the sole authenticated provider`() {
        val first = model("alpha", "org/shared")
        val second = model("beta", "org/shared")

        assertEquals(
            second,
            selectExactModelReference(
                requested = "org/shared",
                exactMatches = listOf(first, second),
                authenticatedProviders = setOf("beta"),
            ),
        )
    }

    @Test
    fun `ambiguous exact model requires an explicit provider without one authenticated match`() {
        val first = model("alpha", "org/shared")
        val second = model("beta", "org/shared")

        val failure =
            assertFailsWith<IllegalStateException> {
                selectExactModelReference(
                    requested = "org/shared",
                    exactMatches = listOf(first, second),
                    authenticatedProviders = emptySet(),
                )
            }

        assertTrue(failure.message.orEmpty().contains("alpha/org/shared, beta/org/shared"))
        assertTrue(failure.message.orEmpty().contains("No matching provider is authenticated."))
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
            baseUrl = "https://example.invalid/v1",
            reasoning = false,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 4_096,
            maxTokens = 1_024,
        )
}
