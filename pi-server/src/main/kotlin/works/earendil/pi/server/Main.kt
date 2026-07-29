package works.earendil.pi.server

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.io.BufferedReader
import java.io.PrintWriter
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val VERSION = "0.1.0-SNAPSHOT"

fun main(rawArguments: Array<String>) =
    runBlocking {
        val arguments = rawArguments.toList()
        when (arguments.firstOrNull()) {
            null, "--help", "-h" -> printHelp()
            "--version", "-v" -> println(VERSION)
            "serve" -> serve()
            "list" -> printResponse(sendIpcRequest(ServerConfig().socketPath, buildJsonObject { put("type", "list") }))
            "spawn" -> {
                val cwd = flagValue(arguments, "--cwd") ?: Path.of("").toAbsolutePath().normalize().toString()
                val label = flagValue(arguments, "--label")
                printResponse(
                    sendIpcRequest(
                        ServerConfig().socketPath,
                        buildJsonObject {
                            put("type", "spawn")
                            put("cwd", cwd)
                            label?.let { put("label", it) }
                        },
                    ),
                )
            }

            "status", "stop" -> {
                val id = arguments.getOrNull(1) ?: error("Usage: pi-server ${arguments.first()} <instance-id>")
                printResponse(
                    sendIpcRequest(
                        ServerConfig().socketPath,
                        buildJsonObject {
                            put("type", arguments.first())
                            put("instanceId", id)
                        },
                    ),
                )
            }

            "rpc" -> {
                val id = arguments.getOrNull(1) ?: error("Usage: pi-server rpc <instance-id> <json-command>")
                val command = arguments.getOrNull(2) ?: error("Usage: pi-server rpc <instance-id> <json-command>")
                printResponse(
                    sendIpcRequest(
                        ServerConfig().socketPath,
                        buildJsonObject {
                            put("type", "rpc")
                            put("instanceId", id)
                            put("command", parseMessage(command))
                        },
                    ),
                )
            }

            "rpc-stream" -> {
                val id = arguments.getOrNull(1) ?: error("Usage: pi-server rpc-stream <instance-id>")
                rpcStream(ServerConfig().socketPath, id)
            }

            else -> error("Unknown command: ${arguments.first()}")
        }
    }

private suspend fun serve() {
    val config = ServerConfig()
    val supervisor = ServerSupervisor(ServerStorage(config))
    val server = IpcServer(config.socketPath, ServerService(supervisor))
    supervisor.recoverAfterRestart()
    server.start()
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking {
                server.close()
                supervisor.shutdown()
            }
        },
    )
    println("server listening on ${config.socketPath}")
    awaitCancellation()
}

private suspend fun rpcStream(
    socketPath: Path,
    instanceId: String,
) = rpcStream(
    socketPath = socketPath,
    instanceId = instanceId,
    input = System.`in`.bufferedReader(),
    output = PrintWriter(System.out, true),
)

internal suspend fun rpcStream(
    socketPath: Path,
    instanceId: String,
    input: BufferedReader,
    output: PrintWriter,
) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { socket ->
            socket.connect(UnixDomainSocketAddress.of(socketPath))
            val writer = Channels.newWriter(socket, StandardCharsets.UTF_8)
            val reader = Channels.newReader(socket, StandardCharsets.UTF_8)
            writer.write(
                encodeMessage(
                    buildJsonObject {
                        put("type", "rpc_stream")
                        put("instanceId", instanceId)
                    },
                ),
            )
            writer.flush()
            val outputThread =
                Thread {
                    val buffer = CharArray(8192)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        synchronized(output) {
                            output.write(buffer, 0, read)
                            output.flush()
                        }
                    }
                }.also(Thread::start)
            input.forEachLine { line ->
                writer.write(line)
                writer.write("\n")
                writer.flush()
            }
            socket.shutdownOutput()
            outputThread.join()
        }
    }
}

private fun printHelp() {
    println(
        """
        pi-server v$VERSION

        Usage:
          pi-server serve
          pi-server list
          pi-server spawn [--cwd <path>] [--label <label>]
          pi-server status <instance-id>
          pi-server stop <instance-id>
          pi-server rpc <instance-id> <json-command>
          pi-server rpc-stream <instance-id>
          pi-server --help
          pi-server --version

        rpc-stream stdin accepts JSONL RPC commands and extension_ui_response messages.
        """.trimIndent(),
    )
}

private fun flagValue(
    arguments: List<String>,
    flag: String,
): String? {
    val index = arguments.indexOf(flag)
    return arguments.getOrNull(index + 1).takeIf { index >= 0 }
}

private fun printResponse(response: kotlinx.serialization.json.JsonObject) {
    val prettyJson =
        kotlinx.serialization.json.Json(serverJson) {
            prettyPrint = true
        }
    println(
        prettyJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), response),
    )
}
