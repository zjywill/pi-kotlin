package works.earendil.pi.client

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import works.earendil.pi.protocol.DEFAULT_MAX_FRAME_LENGTH

data class UnixTransportOptions(
    val path: Path,
    val maxPendingBytes: Int = DEFAULT_MAX_FRAME_LENGTH * 4,
) {
    init {
        val normalized = path.toString()
        require(normalized.isNotEmpty()) { "Unix transport path must not be empty" }
        val maxPathBytes = if (System.getProperty("os.name").lowercase().contains("linux")) 107 else 103
        require(normalized.toByteArray(StandardCharsets.UTF_8).size <= maxPathBytes) {
            "Unix transport path is too long; maximum is $maxPathBytes UTF-8 bytes"
        }
        require(maxPendingBytes > 0) { "Unix transport maxPendingBytes must be positive" }
        require(!System.getProperty("os.name").lowercase().contains("windows")) {
            "Unix transport is not supported on Windows"
        }
    }
}

fun createUnixTransportFactory(options: UnixTransportOptions): ByteTransportFactory =
    ByteTransportFactory { handlers ->
        withContext(Dispatchers.IO) {
            val socket = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                socket.connect(UnixDomainSocketAddress.of(options.path))
                UnixByteTransport(socket, options.maxPendingBytes, handlers).also(UnixByteTransport::start)
            } catch (error: Throwable) {
                runCatching(socket::close)
                throw error
            }
        }
    }

private class UnixByteTransport(
    private val socket: SocketChannel,
    private val maxPendingBytes: Int,
    private val handlers: ByteTransportHandlers,
) : ByteTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val terminalNotified = AtomicBoolean(false)
    private val pendingLock = Any()
    private var pendingBytes = 0

    fun start() {
        scope.launch {
            val buffer = ByteBuffer.allocate(64 * 1024)
            try {
                while (!closed.get()) {
                    buffer.clear()
                    val count = socket.read(buffer)
                    if (count < 0) {
                        notifyClose()
                        return@launch
                    }
                    if (count == 0) {
                        continue
                    }
                    buffer.flip()
                    ByteArray(count).also(buffer::get).let(handlers.onData)
                }
            } catch (error: Throwable) {
                if (!closed.get()) {
                    notifyError(error)
                }
            }
        }
    }

    override suspend fun send(chunk: ByteArray) {
        reserve(chunk.size)
        val bytes = chunk.copyOf()
        try {
            writeMutex.withLock {
                if (closed.get()) {
                    throw IllegalStateException("Unix transport is closed")
                }
                withContext(Dispatchers.IO) {
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) {
                        val written = socket.write(buffer)
                        if (written < 0) {
                            throw IllegalStateException("Unix transport closed during write")
                        }
                    }
                }
            }
        } finally {
            synchronized(pendingLock) {
                pendingBytes -= bytes.size
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        scope.cancel()
        runCatching(socket::close)
    }

    private fun reserve(size: Int) {
        synchronized(pendingLock) {
            if (closed.get()) {
                throw IllegalStateException("Unix transport is closed")
            }
            if (pendingBytes + size > maxPendingBytes) {
                throw IllegalStateException("Unix transport exceeded its pending byte limit")
            }
            pendingBytes += size
        }
    }

    private fun notifyClose() {
        if (terminalNotified.compareAndSet(false, true)) {
            handlers.onClose()
        }
    }

    private fun notifyError(error: Throwable) {
        if (terminalNotified.compareAndSet(false, true)) {
            handlers.onError(error)
        }
    }
}
