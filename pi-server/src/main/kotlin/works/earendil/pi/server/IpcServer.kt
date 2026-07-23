package works.earendil.pi.server

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IpcServer(
    private val socketPath: Path,
    private val service: ServerService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var channel: ServerSocketChannel? = null
    private var acceptJob: Job? = null

    suspend fun start() {
        withContext(Dispatchers.IO) {
            Files.createDirectories(socketPath.toAbsolutePath().normalize().parent)
            removeStaleSocket(socketPath)
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            server.bind(UnixDomainSocketAddress.of(socketPath))
            channel = server
            acceptJob =
                scope.launch {
                    while (isActive && server.isOpen) {
                        val client = runCatching(server::accept).getOrNull() ?: break
                        launch { handleClient(client) }
                    }
                }
        }
    }

    suspend fun close() {
        withContext(Dispatchers.IO) {
            channel?.close()
            acceptJob?.cancel()
            scope.cancel()
            Files.deleteIfExists(socketPath)
        }
    }

    private suspend fun handleClient(socket: SocketChannel) {
        socket.use { client ->
            val reader = Channels.newReader(client, StandardCharsets.UTF_8)
            val writer = Channels.newWriter(client, StandardCharsets.UTF_8)
            val writerLock = Any()
            fun send(value: kotlinx.serialization.json.JsonObject) {
                synchronized(writerLock) {
                    writer.write(encodeMessage(value))
                    writer.flush()
                }
            }
            val firstLine = reader.readLine() ?: return
            val request =
                runCatching { parseMessage(firstLine) }.getOrElse { error ->
                    send(errorResponse(error.message ?: "Invalid request"))
                    return
                }
            if (request.string("type") != "rpc_stream") {
                send(service.handle(request))
                return
            }
            val ready = service.handle(request)
            send(ready)
            if (ready.string("type") != "rpc_ready") {
                return
            }
            val instanceId = request.instanceId()
            val unsubscribe =
                service.subscribe(instanceId, ::send)
                    ?: run {
                        send(errorResponse("Unknown instance: $instanceId"))
                        return
                    }
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val command =
                        runCatching { parseMessage(line) }.getOrElse { error ->
                            send(errorResponse(error.message ?: "Invalid RPC command"))
                            continue
                        }
                    service.handleStreamCommand(instanceId, command)?.let(::send)
                }
            } finally {
                unsubscribe()
            }
        }
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
}

suspend fun sendIpcRequest(
    socketPath: Path,
    request: kotlinx.serialization.json.JsonObject,
): kotlinx.serialization.json.JsonObject =
    withContext(Dispatchers.IO) {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
            socket.connect(UnixDomainSocketAddress.of(socketPath))
            val writer = Channels.newWriter(socket, StandardCharsets.UTF_8)
            val reader = Channels.newReader(socket, StandardCharsets.UTF_8)
            writer.write(encodeMessage(request))
            writer.flush()
            val line = reader.readLine() ?: error("Server socket closed before a response was received: $socketPath")
            parseMessage(line)
        }
    }

private fun java.io.Reader.readLine(): String? {
    val result = StringBuilder()
    while (true) {
        val value = read()
        if (value < 0) {
            return result.takeIf(StringBuilder::isNotEmpty)?.toString()
        }
        if (value.toChar() == '\n') {
            return result.toString()
        }
        result.append(value.toChar())
    }
}
