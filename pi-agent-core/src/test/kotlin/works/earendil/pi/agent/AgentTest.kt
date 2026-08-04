package works.earendil.pi.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.FauxResponseStep
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamFunction
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ToolCall
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

    @Test
    fun `forwards should stop after turn through agent options`() =
        runTest {
            val provider = FauxProvider()
            val model = requireNotNull(provider.getModel())
            provider.setResponses(
                listOf(
                    FauxResponseStep.Message(
                        fauxAssistantMessage(
                            listOf(ToolCall("tool-1", "noop", buildJsonObject {})),
                            StopReason.TOOL_USE,
                        ),
                    ),
                    FauxResponseStep.Message(fauxAssistantMessage("should not run")),
                ),
            )
            val tool =
                object : AgentTool {
                    override val name = "noop"
                    override val label = "Noop"
                    override val description = "Noop"
                    override val parameters = buildJsonObject {}

                    override suspend fun execute(
                        toolCallId: String,
                        params: kotlinx.serialization.json.JsonObject,
                        onUpdate: AgentToolUpdateCallback?,
                    ): AgentToolResult = AgentToolResult(listOf(TextContent("tool complete")))
                }
            var callbackRoles = emptyList<String>()
            val agent =
                Agent(
                    AgentOptions(
                        streamFunction =
                            StreamFunction { requestModel, context: Context, options: SimpleStreamOptions ->
                                provider.streamSimple(requestModel, context, options)
                            },
                        initialState = AgentInitialState(model = model, tools = listOf(tool)),
                        shouldStopAfterTurn = { context ->
                            callbackRoles = context.context.messages.map(::messageRole)
                            true
                        },
                    ),
                )

            agent.prompt("start")

            assertEquals(listOf("user", "assistant", "toolResult"), callbackRoles)
            assertEquals(3, agent.state.messages.size)
        }
}

private fun messageRole(message: works.earendil.pi.ai.Message): String =
    when (message) {
        is works.earendil.pi.ai.UserMessage -> "user"
        is works.earendil.pi.ai.AssistantMessage -> "assistant"
        is works.earendil.pi.ai.ToolResultMessage -> "toolResult"
        else -> "other"
    }
