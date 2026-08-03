package works.earendil.pi.ai.providers

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OpenAIChatZaiCompatTest {
    @Test
    fun `generated ZAI models send max tokens`() {
        listOf("zai", "zai-coding-cn").forEach { provider ->
            listOf("glm-5-turbo", "glm-5.2").forEach { modelId ->
                val model = builtInModels(provider).single { it.id == modelId }
                assertEquals(
                    "max_tokens",
                    model.compat?.get("maxTokensField")?.jsonPrimitive?.content,
                )
                assertMaxTokensPayload(model)
            }
        }
    }

    @Test
    fun `runtime fallback recognizes ZAI providers and base urls`() {
        val base = builtInModels("zai").single { it.id == "glm-5.2" }
        val cases =
            listOf(
                base.copy(provider = "zai", baseUrl = "https://custom.invalid/v1", compat = null),
                base.copy(provider = "zai-coding-cn", baseUrl = "https://custom.invalid/v1", compat = null),
                base.copy(provider = "custom", baseUrl = "https://api.z.ai/v1", compat = null),
                base.copy(provider = "custom", baseUrl = "https://open.bigmodel.cn/api/paas/v4", compat = null),
            )

        cases.forEach(::assertMaxTokensPayload)
    }

    private fun assertMaxTokensPayload(model: works.earendil.pi.ai.Model) {
        val payload =
            buildOpenAIChatRequestBody(
                model,
                Context(messages = mutableListOf(UserMessage("hello"))),
                StreamOptions(maxTokens = 123),
            )

        assertEquals(123, payload.getValue("max_tokens").jsonPrimitive.int)
        assertFalse("max_completion_tokens" in payload)
    }
}
