package works.earendil.pi.agent

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.FauxResponseStep
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamFunction
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.ai.fauxAssistantMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentLoopTest {
    @Test
    fun `emits lifecycle events and returns messages`() =
        runTest {
            val provider = FauxProvider()
            val model = requireNotNull(provider.getModel())
            provider.setResponses(listOf(FauxResponseStep.Message(fauxAssistantMessage("Hi there!"))))
            val events = mutableListOf<AgentEvent>()

            val messages =
                runAgentLoop(
                    prompts = listOf(UserMessage("Hello")),
                    context = AgentContext(systemPrompt = "You are helpful."),
                    config = config(provider, model),
                    emit = AgentEventSink(events::add),
                )

            assertEquals(2, messages.size)
            assertTrue(events.first() is AgentEvent.AgentStart)
            assertTrue(events.last() is AgentEvent.AgentEnd)
            assertTrue(events.any { it is AgentEvent.TurnEnd })
        }

    @Test
    fun `executes a tool and continues to a final response`() =
        runTest {
            val provider = FauxProvider()
            val model = requireNotNull(provider.getModel())
            val toolCall =
                ToolCall(
                    id = "tool-1",
                    name = "echo",
                    arguments = buildJsonObject { put("value", "hello") },
                )
            provider.setResponses(
                listOf(
                    FauxResponseStep.Message(
                        fauxAssistantMessage(listOf(toolCall), StopReason.TOOL_USE),
                    ),
                    FauxResponseStep.Message(fauxAssistantMessage("done")),
                ),
            )
            val executed = mutableListOf<String>()
            val tool =
                object : AgentTool {
                    override val name = "echo"
                    override val label = "Echo"
                    override val description = "Echo a value"
                    override val parameters =
                        buildJsonObject {
                            put("type", "object")
                            put(
                                "properties",
                                buildJsonObject {
                                    put("value", buildJsonObject { put("type", "string") })
                                },
                            )
                            put("required", JsonArray(listOf(JsonPrimitive("value"))))
                        }

                    override suspend fun execute(
                        toolCallId: String,
                        params: kotlinx.serialization.json.JsonObject,
                        onUpdate: AgentToolUpdateCallback?,
                    ): AgentToolResult {
                        val value = params.getValue("value").jsonPrimitive.content
                        executed += value
                        return AgentToolResult(content = listOf(TextContent("echoed: $value")))
                    }
                }
            val context = AgentContext("", tools = listOf(tool))

            val messages =
                runAgentLoop(
                    prompts = listOf(UserMessage("echo something")),
                    context = context,
                    config = config(provider, model),
                )

            assertEquals(listOf("hello"), executed)
            assertTrue(messages.any { it is ToolResultMessage })
            assertEquals("done", (messages.last() as works.earendil.pi.ai.AssistantMessage).content
                .filterIsInstance<TextContent>().single().text)
        }

    @Test
    fun `length stop fails tool calls without execution`() =
        runTest {
            val provider = FauxProvider()
            val model = requireNotNull(provider.getModel())
            provider.setResponses(
                listOf(
                    FauxResponseStep.Message(
                        fauxAssistantMessage(
                            listOf(ToolCall("tool-1", "echo", buildJsonObject {})),
                            StopReason.LENGTH,
                        ),
                    ),
                ),
            )
            var executed = false
            val tool =
                object : AgentTool {
                    override val name = "echo"
                    override val label = "Echo"
                    override val description = "Echo"
                    override val parameters = buildJsonObject { put("type", "object") }

                    override suspend fun execute(
                        toolCallId: String,
                        params: kotlinx.serialization.json.JsonObject,
                        onUpdate: AgentToolUpdateCallback?,
                    ): AgentToolResult {
                        executed = true
                        return AgentToolResult(emptyList())
                    }
                }

            val messages =
                runAgentLoop(
                    prompts = listOf(UserMessage("run")),
                    context = AgentContext("", tools = listOf(tool)),
                    config = config(provider, model),
                )

            assertFalse(executed)
            val result = messages.filterIsInstance<ToolResultMessage>().single()
            assertTrue(result.isError)
            assertTrue((result.content.single() as TextContent).text.contains("output token limit"))
        }

    private fun config(
        provider: FauxProvider,
        model: Model,
    ): AgentLoopConfig =
        AgentLoopConfig(
            model = model,
            streamFunction =
                StreamFunction { requestModel, context: Context, options: SimpleStreamOptions ->
                    provider.streamSimple(requestModel, context, options)
                },
        )
}
