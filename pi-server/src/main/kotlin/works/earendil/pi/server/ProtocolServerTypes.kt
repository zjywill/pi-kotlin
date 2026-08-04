package works.earendil.pi.server

import kotlinx.serialization.json.JsonObject

interface ByteConnection {
    val closed: Boolean

    suspend fun send(chunk: ByteArray)

    suspend fun close(finalChunk: ByteArray? = null)
}

data class ByteConnectionHandler(
    val onData: (ByteArray) -> Unit,
    val onClose: () -> Unit,
    val onError: (Throwable) -> Unit,
)

interface PiServerListener {
    val address: String?

    suspend fun start(handler: (ByteConnection) -> ByteConnectionHandler)

    suspend fun close()
}

class PiServerException(
    val code: String,
    message: String,
    val details: kotlinx.serialization.json.JsonElement? = null,
) : RuntimeException(message)

data class CreateProtocolSessionOptions(
    val id: String,
    val cwd: String? = null,
    val name: String? = null,
    val model: JsonObject? = null,
    val thinkingLevel: String? = null,
)

sealed interface PiSessionRuntimeEvent {
    data class Progress(
        val progress: JsonObject,
    ) : PiSessionRuntimeEvent

    data object Snapshot : PiSessionRuntimeEvent

    data class Error(
        val error: PiServerException,
    ) : PiSessionRuntimeEvent
}

fun interface ServerUnsubscribe {
    fun unsubscribe()
}

interface PiSessionRuntime {
    fun getPhase(): String

    suspend fun snapshot(): JsonObject

    fun subscribe(listener: (PiSessionRuntimeEvent) -> Unit): ServerUnsubscribe

    suspend fun prompt(text: String)

    suspend fun steer(text: String)

    suspend fun abort()

    suspend fun setModel(model: JsonObject)

    suspend fun setThinking(thinkingLevel: String)

    suspend fun dispose()
}

interface PiSessionBackend {
    suspend fun listSessions(): List<JsonObject>

    suspend fun listModels(): List<JsonObject>

    suspend fun createSession(options: CreateProtocolSessionOptions): PiSessionRuntime

    suspend fun openSession(sessionId: String): PiSessionRuntime
}

data class PiServerOptions(
    val listeners: List<PiServerListener>,
    val serverId: String = java.util.UUID.randomUUID().toString(),
    val maxFrameLength: Int = works.earendil.pi.protocol.DEFAULT_MAX_FRAME_LENGTH,
    val handshakeTimeoutMs: Long = 5_000,
    val onError: ((Throwable) -> Unit)? = null,
) {
    init {
        require(maxFrameLength > 0) { "PiServer maxFrameLength must be positive" }
        require(handshakeTimeoutMs in 1..2_147_483_647L) {
            "PiServer handshakeTimeoutMs must be between 1 and 2147483647"
        }
    }
}
