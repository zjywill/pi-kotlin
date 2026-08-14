package works.earendil.pi.agent.harness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class RunStartEvent(
    val lane: String,
    val runId: String,
)

data class RunEndEvent(
    val lane: String,
    val runId: String,
    val outcome: String,
    val leafId: String?,
)

sealed interface HarnessEvent {
    val type: String
    val lane: String
    val runId: String

    data class RunStart(
        override val lane: String,
        override val runId: String,
    ) : HarnessEvent {
        override val type: String = "run_start"
    }

    data class RunEnd(
        override val lane: String,
        override val runId: String,
        val outcome: String,
        val leafId: String?,
    ) : HarnessEvent {
        override val type: String = "run_end"
    }
}

fun interface HarnessEventListener {
    suspend fun onEvent(event: HarnessEvent)
}

interface HarnessWatchHandle<TSnapshot> {
    val snapshot: TSnapshot

    fun start(listener: HarnessEventListener)

    fun unsubscribe()
}

interface HarnessEvents {
    fun on(
        type: String,
        listener: HarnessEventListener,
    ): HarnessRegistration

    fun <TSnapshot> watch(captureSnapshot: () -> TSnapshot): HarnessWatchHandle<TSnapshot>
}

class HarnessEventBus(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val isClosed: () -> Boolean = { false },
) : HarnessEvents {
    private val listeners = linkedMapOf<String, LinkedHashSet<HarnessEventListener>>()
    private val watchListeners = linkedSetOf<(HarnessEvent) -> Unit>()

    override fun on(
        type: String,
        listener: HarnessEventListener,
    ): HarnessRegistration {
        if (isClosed()) {
            throw HarnessClosed()
        }
        require(type == "*" || type == "run_start" || type == "run_end") {
            "Unknown harness event type: $type"
        }
        val registered = listeners.getOrPut(type) { linkedSetOf() }
        registered += listener
        var closed = false
        return HarnessRegistration {
            if (!closed) {
                closed = true
                registered -= listener
                if (registered.isEmpty()) {
                    listeners.remove(type)
                }
            }
        }
    }

    override fun <TSnapshot> watch(captureSnapshot: () -> TSnapshot): HarnessWatchHandle<TSnapshot> {
        if (isClosed()) {
            throw HarnessClosed()
        }
        var activeListener: HarnessEventListener? = null
        var buffered = mutableListOf<HarnessEvent>()
        val receive: (HarnessEvent) -> Unit = { event ->
            val active = activeListener
            if (active == null) {
                buffered += event
            } else {
                scope.launch { active.onEvent(event) }
            }
        }
        watchListeners += receive
        val snapshot = captureSnapshot()
        var unsubscribed = false
        return object : HarnessWatchHandle<TSnapshot> {
            override val snapshot: TSnapshot = snapshot

            override fun start(listener: HarnessEventListener) {
                if (unsubscribed) {
                    return
                }
                while (buffered.isNotEmpty()) {
                    val pending = buffered
                    buffered = mutableListOf()
                    pending.forEach { event ->
                        scope.launch { listener.onEvent(event) }
                    }
                }
                activeListener = listener
            }

            override fun unsubscribe() {
                if (unsubscribed) {
                    return
                }
                unsubscribed = true
                watchListeners -= receive
                buffered = mutableListOf()
                activeListener = null
            }
        }
    }

    fun emit(event: HarnessEvent) {
        val recipients =
            (listeners[event.type].orEmpty() + listeners["*"].orEmpty())
                .toList()
                .distinct()
        recipients.forEach { listener ->
            scope.launch {
                listener.onEvent(event)
            }
        }
        watchListeners.toList().forEach { watcher -> watcher(event) }
    }

    fun close() {
        listeners.clear()
        watchListeners.clear()
        scope.coroutineContext.cancel()
    }
}
