package works.earendil.pi.ai.providers

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.StopReason
import kotlin.test.Test
import kotlin.test.assertEquals

class RawStopReasonTest {
    @Test
    fun `provider stop mappings preserve descriptive errors`() {
        assertEquals(
            StopReason.ERROR to "Provider stopped with: sensitive",
            mapAnthropicStopReason("sensitive"),
        )
        assertEquals(
            StopReason.ERROR to "policy explanation",
            mapAnthropicStopReason(
                "refusal",
                buildJsonObject { put("explanation", "policy explanation") },
            ),
        )
        assertEquals(
            StopReason.ERROR to "Provider stopped with: guardrail_intervened",
            mapBedrockStopReason("guardrail_intervened"),
        )
        assertEquals(StopReason.ERROR, mapGoogleStopReason("MALFORMED_FUNCTION_CALL"))
        assertEquals(
            StopReason.ERROR to "Provider finish_reason: content_filter",
            mapOpenAIChatStopReason("content_filter"),
        )
        assertEquals(
            StopReason.ERROR to "Provider stopped with: unmapped_error",
            mapMistralStopReason("unmapped_error"),
        )
    }

    @Test
    fun `known successful stop mappings remain stable`() {
        assertEquals(StopReason.STOP to null, mapAnthropicStopReason("end_turn"))
        assertEquals(StopReason.TOOL_USE to null, mapBedrockStopReason("tool_use"))
        assertEquals(StopReason.LENGTH, mapGoogleStopReason("MAX_TOKENS"))
        assertEquals(StopReason.STOP to null, mapOpenAIChatStopReason("end"))
        assertEquals(StopReason.LENGTH to null, mapMistralStopReason("model_length"))
    }
}
