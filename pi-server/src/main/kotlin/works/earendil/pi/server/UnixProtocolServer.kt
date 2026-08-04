package works.earendil.pi.server

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import works.earendil.pi.protocol.DEFAULT_MAX_FRAME_LENGTH

data class UnixServerOptions(
    val path: Path,
    val serverId: String = java.util.UUID.randomUUID().toString(),
    val maxFrameLength: Int = DEFAULT_MAX_FRAME_LENGTH,
    val maxPendingBytes: Int = DEFAULT_MAX_FRAME_LENGTH * 4,
    val handshakeTimeoutMs: Long = 5_000,
    val gracefulCloseTimeoutMs: Long = 1_000,
    val onError: ((Throwable) -> Unit)? = null,
) {
    init {
        val pathText = path.toString()
        require(pathText.isNotEmpty()) { "Unix server path must not be empty" }
        val maxPathBytes = unixSocketPathLimit()
        require(pathText.toByteArray(StandardCharsets.UTF_8).size <= maxPathBytes) {
            "Unix server path is too long; maximum is $maxPathBytes UTF-8 bytes"
        }
        require(maxPendingBytes >= maxFrameLength + 4) {
            "Unix server maxPendingBytes must fit at least one maximum frame"
        }
        require(gracefulCloseTimeoutMs in 0..2_147_483_647L) {
            "Unix server gracefulCloseTimeoutMs must be between 0 and 2147483647"
        }
    }
}

fun createUnixServer(
    backend: PiSessionBackend,
    options: UnixServerOptions,
): PiServer {
    val listener =
        UnixProtocolListener(
            path = options.path,
            maxPendingBytes = options.maxPendingBytes,
            gracefulCloseTimeoutMs = options.gracefulCloseTimeoutMs,
            onError = options.onError,
        )
    return PiServer(
        backend,
        PiServerOptions(
            listeners = listOf(listener),
            serverId = options.serverId,
            maxFrameLength = options.maxFrameLength,
            handshakeTimeoutMs = options.handshakeTimeoutMs,
            onError = options.onError,
        ),
    )
}

class UnixProtocolListener(
    private val path: Path,
    private val maxPendingBytes: Int,
    private val gracefulCloseTimeoutMs: Long,
    private val onError: ((Throwable) -> Unit)? = null,
) : PiServerListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap.newKeySet<UnixByteConnection>()
    private var server: ServerSocketChannel? = null
    private var acceptJob: Job? = null
    private var bound = false

    override val address: String?
        get() = path.toString().takeIf { bound }

    override suspend fun start(handler: (ByteConnection) -> ByteConnectionHandler) {
        check(server == null) { "Unix listener is already started" }
        withContext(Dispatchers.IO) {
            val normalized = path.toAbsolutePath().normalize()
            normalized.parent?.let(Files::createDirectories)
            removeStaleSocket(normalized)
            val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                channel.bind(UnixDomainSocketAddress.of(normalized))
                server = channel
                bound = true
                acceptJob =
                    scope.launch {
                        while (channel.isOpen) {
                            val socket = runCatching(channel::accept).getOrNull() ?: break
                            val connection =
                                UnixByteConnection(
                                    socket = socket,
                                    maxPendingBytes = maxPendingBytes,
                                    gracefulCloseTimeoutMs = gracefulCloseTimeoutMs,
                                    onTerminal = { connections -= it },
                                )
                            connections += connection
                            connection.start(handler(connection))
                        }
                    }
            } catch (error: Throwable) {
                runCatching(channel::close)
                throw error
            }
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) {
            val current = server
            server = null
            bound = false
            runCatching { current?.close() }.onFailure(::reportError)
            acceptJob?.cancel()
            acceptJob = null
            connections.toList().forEach { connection ->
                runCatching { connection.close() }.onFailure(::reportError)
            }
            connections.clear()
            Files.deleteIfExists(path.toAbsolutePath().normalize())
        }
        scope.cancel()
    }

    private fun removeStaleSocket(path: Path) {
        if (!Files.exists(path)) {
            return
        }
        val live =
            runCatching {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
                    socket.connect(UnixDomainSocketAddress.of(path))
                }
                true
            }.getOrDefault(false)
        check(!live) { "server is already running: $path" }
        Files.deleteIfExists(path)
    }

    private fun reportError(error: Throwable) {
        try {
            onError?.invoke(error)
        } catch (_: Throwable) {
            // Listener diagnostics cannot alter lifecycle.
        }
    }
}

private class UnixByteConnection(
    private val socket: SocketChannel,
    private val maxPendingBytes: Int,
    private val gracefulCloseTimeoutMs: Long,
    private val onTerminal: (UnixByteConnection) -> Unit,
) : ByteConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val pendingLock = Any()
    private val terminal = AtomicBoolean(false)
    private var pendingBytes = 0
    private lateinit var handlers: ByteConnectionHandler

    override val closed: Boolean
        get() = terminal.get() || !socket.isOpen

    fun start(handlers: ByteConnectionHandler) {
        this.handlers = handlers
        scope.launch {
            val buffer = ByteBuffer.allocate(64 * 1024)
            try {
                while (!terminal.get()) {
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
                if (!terminal.get()) {
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
                writeBytes(bytes)
            }
        } finally {
            synchronized(pendingLock) {
                pendingBytes -= bytes.size
            }
        }
    }

    override suspend fun close(finalChunk: ByteArray?) {
        if (!terminal.compareAndSet(false, true)) {
            return
        }
        try {
            if (finalChunk != null && socket.isOpen) {
                withContext(Dispatchers.IO) {
                    val buffer = ByteBuffer.wrap(finalChunk)
                    while (buffer.hasRemaining()) {
                        socket.write(buffer)
                    }
                    runCatching(socket::shutdownOutput)
                    if (gracefulCloseTimeoutMs > 0) {
                        socket.configureBlocking(false)
                        val deadline = System.nanoTime() + gracefulCloseTimeoutMs * 1_000_000
                        val scratch = ByteBuffer.allocate(1)
                        while (System.nanoTime() < deadline) {
                            scratch.clear()
                            if (socket.read(scratch) < 0) {
                                break
                            }
                            Thread.sleep(1)
                        }
                    }
                }
            }
        } finally {
            scope.cancel()
            runCatching(socket::close)
            onTerminal(this)
        }
    }

    private suspend fun writeBytes(bytes: ByteArray) {
        if (closed) {
            throw IllegalStateException("Unix connection is closed")
        }
        withContext(Dispatchers.IO) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                val count = socket.write(buffer)
                if (count < 0) {
                    throw IllegalStateException("Unix connection closed during write")
                }
            }
        }
    }

    private fun reserve(size: Int) {
        synchronized(pendingLock) {
            if (closed) {
                throw IllegalStateException("Unix connection is closed")
            }
            if (pendingBytes + size > maxPendingBytes) {
                throw IllegalStateException("Unix connection exceeded its pending byte limit")
            }
            pendingBytes += size
        }
    }

    private fun notifyClose() {
        if (terminal.compareAndSet(false, true)) {
            runCatching(handlers.onClose)
            scope.cancel()
            runCatching(socket::close)
            onTerminal(this)
        }
    }

    private fun notifyError(error: Throwable) {
        if (terminal.compareAndSet(false, true)) {
            runCatching { handlers.onError(error) }
            scope.cancel()
            runCatching(socket::close)
            onTerminal(this)
        }
    }
}

private fun unixSocketPathLimit(): Int =
    if (System.getProperty("os.name").lowercase().contains("linux")) 107 else 103
