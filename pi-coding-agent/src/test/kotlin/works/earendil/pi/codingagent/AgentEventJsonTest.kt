package works.earendil.pi.codingagent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import works.earendil.pi.agent.AgentEvent
import works.earendil.pi.ai.TextDelta
import works.earendil.pi.ai.fauxAssistantMessage

class AgentEventJsonTest {
    @Test
    fun `linear message updates omit cumulative message and partial snapshots`() {
        val message = fauxAssistantMessage("hello")
        val event =
            AgentEvent.MessageUpdate(
                message = message,
                assistantMessageEvent = TextDelta(0, "o", message),
            )

        val linear = encodeAgentEvent(event, linearStreaming = true)
        assertFalse("message" in linear)
        assertFalse("partial" in linear.getValue("assistantMessageEvent").let { it as kotlinx.serialization.json.JsonObject })

        val internal = encodeAgentEvent(event)
        assertTrue("message" in internal)
        assertTrue("partial" in internal.getValue("assistantMessageEvent").let { it as kotlinx.serialization.json.JsonObject })
    }
}
