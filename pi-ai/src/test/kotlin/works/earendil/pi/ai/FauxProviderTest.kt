package works.earendil.pi.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FauxProviderTest {
    @Test
    fun `queued response is rewritten and estimates usage`() =
        runTest {
            val provider = FauxProvider()
            val model = requireNotNull(provider.getModel())
            provider.setResponses(listOf(FauxResponseStep.Message(fauxAssistantMessage("hello world"))))

            val stream =
                provider.stream(
                    model,
                    Context(
                        systemPrompt = "Be concise.",
                        messages = mutableListOf(UserMessage("hi there")),
                    ),
                )
            val events = stream.events.toList()
            val response = stream.result()

            assertEquals("faux", response.provider)
            assertEquals("faux-1", response.model)
            assertTrue(response.usage.input > 0)
            assertTrue(response.usage.output > 0)
            assertEquals(StopReason.PENDING, (events.first() as AssistantStart).partial.stopReason)
            assertTrue(events.last() is AssistantDone)
            assertEquals(1, provider.state.callCount)
        }

    @Test
    fun `supports thinking and tool blocks`() =
        runTest {
            val provider = FauxProvider()
            val model = requireNotNull(provider.getModel())
            val toolCall =
                fauxToolCall(
                    name = "echo",
                    arguments = buildJsonObject { put("text", "hi") },
                    id = "tool-1",
                )
            provider.setResponses(
                listOf(
                    FauxResponseStep.Message(
                        fauxAssistantMessage(
                            listOf(fauxThinking("think"), toolCall, fauxText("done")),
                            StopReason.TOOL_USE,
                        ),
                    ),
                ),
            )

            val response = provider.stream(model, Context()).result()

            assertEquals(StopReason.TOOL_USE, response.stopReason)
            assertEquals(listOf("think", "echo", "done"), response.content.map {
                when (it) {
                    is ThinkingContent -> it.thinking
                    is ToolCall -> it.name
                    is TextContent -> it.text
                    is ImageContent -> it.mimeType
                }
            })
        }

    @Test
    fun `simple stream forwards reasoning and thinking budgets to providers`() =
        runTest {
            val provider = FauxProvider()
            val model = requireNotNull(provider.getModel())
            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { _, options, _, _ ->
                        assertEquals(ThinkingLevel.HIGH, options.reasoning)
                        assertEquals(12_345, options.thinkingBudgets?.high)
                        fauxAssistantMessage("configured")
                    },
                ),
            )

            val response =
                provider.streamSimple(
                    model,
                    Context(),
                    SimpleStreamOptions(
                        reasoning = ThinkingLevel.HIGH,
                        thinkingBudgets = ThinkingBudgets(high = 12_345),
                    ),
                ).result()

            assertEquals("configured", contentText(response.content))
        }

    @Test
    fun `exhausted queue produces protocol error`() =
        runTest {
            val provider = FauxProvider()
            val model = requireNotNull(provider.getModel())

            val stream = provider.stream(model, Context())
            val events = stream.events.toList()
            val response = stream.result()

            assertEquals(1, events.size)
            assertTrue(events.single() is AssistantError)
            assertEquals(StopReason.ERROR, response.stopReason)
            assertEquals("No more faux responses queued", response.errorMessage)
        }

    @Test
    fun `queued response without a terminal stop reason fails`() =
        runTest {
            val provider = FauxProvider()
            val model = requireNotNull(provider.getModel())
            provider.setResponses(
                listOf(
                    FauxResponseStep.Message(
                        fauxAssistantMessage("partial", StopReason.PENDING),
                    ),
                ),
            )

            val stream = provider.stream(model, Context())
            val events = stream.events.toList()
            val response = stream.result()

            assertTrue(events.none { it is AssistantDone })
            assertTrue(events.last() is AssistantError)
            assertEquals(StopReason.ERROR, response.stopReason)
            assertEquals("Faux response ended without a stop reason", response.errorMessage)
        }

    @Test
    fun `models poll deferred responses until the scripted result is ready`() =
        runTest {
            val provider =
                FauxProvider(
                    deferredPendingFetches = 1,
                    deferredPollAfterMs = 250,
                )
            val model = requireNotNull(provider.getModel())
            val models = Models(listOf(provider))
            provider.setResponses(
                listOf(
                    FauxResponseStep.Message(fauxAssistantMessage("ready")),
                ),
            )

            val submitted =
                models.completeSimple(
                    model,
                    Context(messages = mutableListOf(UserMessage("start"))),
                    SimpleStreamOptions(deferred = DeferredRequest()),
                )
            val handle = assertNotNull(submitted.deferred)
            assertEquals(StopReason.DEFERRED, submitted.stopReason)
            assertEquals(250, handle.pollAfterMs)

            val pending = models.fetchDeferred(model, handle)
            assertEquals(StopReason.DEFERRED, pending.stopReason)
            assertEquals(handle, pending.deferred)

            val ready = models.fetchDeferred(model, handle)
            assertEquals(StopReason.STOP, ready.stopReason)
            assertEquals("ready", contentText(ready.content))
            assertEquals(2, provider.state.deferredFetchCount)
        }

    @Test
    fun `deferred cancellation is recorded and prevents later fetches`() =
        runTest {
            val provider = FauxProvider()
            val model = requireNotNull(provider.getModel())
            val models = Models(listOf(provider))
            provider.setResponses(
                listOf(
                    FauxResponseStep.Message(fauxAssistantMessage("must not be returned")),
                ),
            )
            val submitted =
                models.completeSimple(
                    model,
                    Context(),
                    SimpleStreamOptions(deferred = DeferredRequest()),
                )
            val handle = assertNotNull(submitted.deferred)

            models.cancelDeferred(model, handle)
            val cancelled = models.fetchDeferred(model, handle)

            assertEquals(listOf(handle), provider.state.cancelledDeferred)
            assertEquals(StopReason.ERROR, cancelled.stopReason)
            assertTrue(cancelled.errorMessage.orEmpty().contains("was cancelled"))
        }

    @Test
    fun `models reject deferred operations for unsupported providers`() =
        runTest {
            val provider =
                object : Provider by FauxProvider() {
                    override val id: String = "plain"
                    override val supportsDeferredResponses: Boolean = false
                }
            val model = provider.getModels().first().copy(provider = provider.id)
            val models = Models(listOf(provider))
            val handle =
                DeferredHandle(
                    provider = provider.id,
                    modelId = model.id,
                    api = model.api,
                    id = "missing",
                )

            val error =
                assertFailsWith<ModelsAuthException> {
                    models.fetchDeferred(model, handle)
                }

            assertEquals("provider", error.code)
            assertTrue(error.message.orEmpty().contains("does not support deferred responses"))
        }
}
