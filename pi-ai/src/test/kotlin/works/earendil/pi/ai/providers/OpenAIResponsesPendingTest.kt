package works.earendil.pi.ai.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.AssistantStart
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.TextEnd
import works.earendil.pi.ai.TextStart
import works.earendil.pi.ai.createAssistantMessageEventStream
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAIResponsesPendingTest {
    @Test
    fun `final answer phase provisionally resolves pending stop reason`() =
        runTest {
            val stream = createAssistantMessageEventStream()
            val state = OpenAIResponsesEventState(model(), stream, emptyMap())

            state.handle(messageEvent("response.output_item.added", "commentary", emptyContent = true))
            state.handle(messageEvent("response.output_item.done", "final_answer"))
            state.handle(terminalEvent("completed"))
            state.finish()

            val events = stream.events.toList()
            assertEquals(StopReason.PENDING, events.filterIsInstance<AssistantStart>().single().partial.stopReason)
            assertEquals(StopReason.PENDING, events.filterIsInstance<TextStart>().single().partial.stopReason)
            assertEquals(StopReason.STOP, events.filterIsInstance<TextEnd>().single().partial.stopReason)
            assertEquals(StopReason.STOP, events.filterIsInstance<AssistantDone>().single().reason)
        }

    @Test
    fun `incomplete terminal event overrides provisional final answer stop`() =
        runTest {
            val stream = createAssistantMessageEventStream()
            val state = OpenAIResponsesEventState(model(), stream, emptyMap())

            state.handle(messageEvent("response.output_item.added", "final_answer", emptyContent = true))
            state.handle(messageEvent("response.output_item.done", "final_answer"))
            state.handle(terminalEvent("incomplete"))
            state.finish()

            val events = stream.events.toList()
            assertEquals(StopReason.PENDING, events.filterIsInstance<AssistantStart>().single().partial.stopReason)
            assertEquals(StopReason.STOP, events.filterIsInstance<TextStart>().single().partial.stopReason)
            assertEquals(StopReason.LENGTH, events.filterIsInstance<AssistantDone>().single().reason)
        }

    private fun messageEvent(
        type: String,
        phase: String,
        emptyContent: Boolean = false,
    ) = buildJsonObject {
        put("type", type)
        put("output_index", 0)
        put(
            "item",
            buildJsonObject {
                put("type", "message")
                put("id", "msg-1")
                put("phase", phase)
                put(
                    "content",
                    if (emptyContent) {
                        buildJsonArray {}
                    } else {
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "output_text")
                                    put("text", "answer")
                                },
                            )
                        }
                    },
                )
            },
        )
    }

    private fun terminalEvent(status: String) =
        buildJsonObject {
            put("type", if (status == "incomplete") "response.incomplete" else "response.completed")
            put(
                "response",
                buildJsonObject {
                    put("id", "resp-1")
                    put("status", status)
                },
            )
        }

    private fun model() =
        Model(
            id = "fixture",
            name = "Fixture",
            api = "openai-responses",
            provider = "fixture",
            baseUrl = "https://example.invalid/v1",
            reasoning = false,
            input = listOf(ModelInput.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = 10_000,
            maxTokens = 1_000,
        )
}
