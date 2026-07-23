package works.earendil.pi.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventStreamTest {
    @Test
    fun `terminal event resolves result and closes events`() =
        runTest {
            val stream = createAssistantMessageEventStream()
            val events = async { stream.events.toList() }
            val message = fauxAssistantMessage("done")

            assertTrue(stream.push(AssistantStart(message.copy(content = emptyList()))))
            assertTrue(stream.push(AssistantDone(StopReason.STOP, message)))
            assertFalse(stream.push(AssistantDone(StopReason.STOP, message)))

            assertEquals(message, stream.result())
            assertEquals(2, events.await().size)
        }
}
