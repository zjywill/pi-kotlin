package works.earendil.pi.codingagent

import kotlin.test.Test
import kotlin.test.assertNotNull
import works.earendil.pi.ai.providers.builtInModels

class DefaultModelsTest {
    @Test
    fun `ZAI defaults exist in the generated catalog`() {
        listOf("zai", "zai-coding-cn").forEach { provider ->
            val defaultId = defaultModelId(provider)
            assertNotNull(
                builtInModels(provider).singleOrNull { it.id == defaultId },
                "$provider default $defaultId should exist in the generated catalog",
            )
        }
    }
}
