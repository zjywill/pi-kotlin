package works.earendil.pi.agent.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class HarnessEventsTest {
    @Test
    fun `delivers typed and wildcard events and supports unsubscribe`() =
        runBlocking {
            val bus = HarnessEventBus()
            val received = mutableListOf<String>()
            val completed = CompletableDeferred<Unit>()
            val typed =
                bus.on("run_start", HarnessEventListener { event ->
                    received += "typed:${event.type}"
                    completed.complete(Unit)
                })
            bus.on("*", HarnessEventListener { event -> received += "wildcard:${event.type}" })

            bus.emit(HarnessEvent.RunStart("main", "run-1"))
            withTimeout(2_000) { completed.await() }
            assertEquals(listOf("typed:run_start", "wildcard:run_start"), received)

            typed.close()
            bus.emit(HarnessEvent.RunStart("main", "run-2"))
            withTimeout(2_000) {
                while (received.size < 3) {
                    kotlinx.coroutines.delay(1)
                }
            }
            assertFalse(received.contains("typed:run_start") && received.count { it == "typed:run_start" } > 1)
            assertEquals("wildcard:run_start", received.last())
            bus.close()
        }

    @Test
    fun `watch captures a snapshot and buffers events until start`() =
        runBlocking {
            val bus = HarnessEventBus()
            val received = mutableListOf<String>()
            val watch =
                bus.watch {
                    "snapshot"
                }
            assertEquals("snapshot", watch.snapshot)

            bus.emit(HarnessEvent.RunStart("main", "run-1"))
            bus.emit(HarnessEvent.RunEnd("main", "run-1", "completed", "leaf-1"))
            assertEquals(emptyList(), received)

            watch.start(HarnessEventListener { event ->
                received += event.type
            })
            withTimeout(2_000) {
                while (received.size < 2) {
                    kotlinx.coroutines.delay(1)
                }
            }
            assertEquals(listOf("run_start", "run_end"), received)

            watch.unsubscribe()
            bus.emit(HarnessEvent.RunStart("main", "run-2"))
            kotlinx.coroutines.delay(10)
            assertEquals(listOf("run_start", "run_end"), received)
            bus.close()
        }

    @Test
    fun `watch keeps reentrant events buffered in order while flushing`() =
        runBlocking {
            val bus = HarnessEventBus()
            val received = mutableListOf<String>()
            val watch = bus.watch { Unit }
            bus.emit(HarnessEvent.RunStart("main", "run-1"))
            watch.start(
                HarnessEventListener { event ->
                    received += event.runId
                    if (event.runId == "run-1") {
                        bus.emit(HarnessEvent.RunEnd("main", "run-2", "completed", "leaf-2"))
                    }
                },
            )
            withTimeout(2_000) {
                while (received.size < 2) {
                    kotlinx.coroutines.delay(1)
                }
            }
            assertEquals(listOf("run-1", "run-2"), received)
            assertNotNull(watch.snapshot)
            bus.close()
        }
}
