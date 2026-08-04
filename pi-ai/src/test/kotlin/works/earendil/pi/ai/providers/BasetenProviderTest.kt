package works.earendil.pi.ai.providers

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.ModelThinkingLevel
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.ThinkingLevel

class BasetenProviderTest {
    @Test
    fun `catalog exposes Baseten reasoning models and defaults`() {
        val models = builtInModels("baseten")
        val glm = models.single { it.id == "zai-org/GLM-5.2" }
        val kimi = models.single { it.id == "moonshotai/Kimi-K2.6" }

        assertEquals("https://inference.baseten.co/v1", glm.baseUrl)
        assertEquals(1_048_576, glm.contextWindow)
        assertEquals(262_144, glm.maxTokens)
        assertEquals(1.4, glm.cost.input)
        assertEquals(4.4, glm.cost.output)
        assertEquals(0.3, glm.cost.cacheRead)
        assertEquals("none", glm.thinkingLevelMap[ModelThinkingLevel.OFF])
        assertEquals("max", glm.thinkingLevelMap[ModelThinkingLevel.MAX])
        assertEquals(listOf(works.earendil.pi.ai.ModelInput.TEXT, works.earendil.pi.ai.ModelInput.IMAGE), kimi.input)
    }

    @Test
    fun `Baseten request sends toggle and mapped reasoning effort`() {
        val glm = builtInModels("baseten").single { it.id == "zai-org/GLM-5.2" }

        val enabled =
            buildOpenAIChatRequestBody(
                glm,
                Context(),
                StreamOptions(reasoning = ThinkingLevel.HIGH),
            )
        assertEquals(
            true,
            enabled.getValue("chat_template_args")
                .jsonObject
                .getValue("enable_thinking")
                .jsonPrimitive
                .boolean,
        )
        assertEquals("high", enabled.getValue("reasoning_effort").jsonPrimitive.content)

        val disabled = buildOpenAIChatRequestBody(glm, Context(), StreamOptions())
        assertFalse(
            disabled.getValue("chat_template_args")
                .jsonObject
                .getValue("enable_thinking")
                .jsonPrimitive
                .boolean,
        )
        assertEquals("none", disabled.getValue("reasoning_effort").jsonPrimitive.content)
    }

    @Test
    fun `Baseten toggle-only model omits reasoning effort`() {
        val kimi = builtInModels("baseten").single { it.id == "moonshotai/Kimi-K2.6" }

        val body =
            buildOpenAIChatRequestBody(
                kimi,
                Context(),
                StreamOptions(reasoning = ThinkingLevel.HIGH),
            )

        assertEquals(
            true,
            body.getValue("chat_template_args")
                .jsonObject
                .getValue("enable_thinking")
                .jsonPrimitive
                .boolean,
        )
        assertFalse("reasoning_effort" in body)
    }
}
