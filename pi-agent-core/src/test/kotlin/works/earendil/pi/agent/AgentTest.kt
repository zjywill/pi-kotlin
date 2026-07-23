package works.earendil.pi.agent

import kotlinx.coroutines.test.runTest
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.FauxResponseStep
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StreamFunction
import works.earendil.pi.ai.fauxAssistantMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentTest {
    @Test
    fun `prompt updates state and awaits subscribers`() =
        runTest {
            val provider = FauxProvider()
            val model = requireNotNull(provider.getModel())
            provider.setResponses(listOf(FauxResponseStep.Message(fauxAssistantMessage("ok"))))
            val events = mutableListOf<AgentEvent>()
            val agent =
                Agent(
                    AgentOptions(
                        streamFunction =
                            StreamFunction { requestModel, context: Context, options: SimpleStreamOptions ->
                                provider.streamSimple(requestModel, context, options)
                            },
                        initialState = AgentInitialState(model = model),
                    ),
                )
            agent.subscribe(events::add)

            agent.prompt("hello")

            assertFalse(agent.state.isStreaming)
            assertEquals(2, agent.state.messages.size)
            assertTrue(events.last() is AgentEvent.AgentEnd)
        }
}
