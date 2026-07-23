package works.earendil.pi.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

open class EventStream<T, R>(
    private val isComplete: (T) -> Boolean,
    private val extractResult: (T) -> R,
) {
    private val channel = Channel<T>(Channel.UNLIMITED)
    private val finalResult = CompletableDeferred<R>()
    private val lock = Any()
    private var done = false

    val events: Flow<T> = channel.receiveAsFlow()

    fun push(event: T): Boolean =
        synchronized(lock) {
            if (done) {
                return false
            }

            val sent = channel.trySend(event).isSuccess
            if (sent && isComplete(event)) {
                done = true
                finalResult.complete(extractResult(event))
                channel.close()
            }
            sent
        }

    fun end(result: R? = null) {
        synchronized(lock) {
            if (done) {
                return
            }
            done = true
            if (result != null) {
                finalResult.complete(result)
            }
            channel.close()
        }
    }

    suspend fun result(): R = finalResult.await()
}

class AssistantMessageEventStream :
    EventStream<AssistantMessageEvent, AssistantMessage>(
        isComplete = { event -> event is AssistantDone || event is AssistantError },
        extractResult = { event ->
            requireNotNull(event.finalMessage) {
                "Terminal assistant event did not contain a final message"
            }
        },
    )

fun createAssistantMessageEventStream(): AssistantMessageEventStream = AssistantMessageEventStream()
