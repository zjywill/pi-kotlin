package works.earendil.pi.server

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.FauxResponseStep
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.fauxAssistantMessage
import works.earendil.pi.codingagent.RpcRuntime
import works.earendil.pi.codingagent.RpcRuntimeOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerIntegrationTest {
    @Test
    fun `supervisor persists lifecycle and fans out rpc events`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-server-supervisor")
            val config = ServerConfig(root.resolve("server"))
            val provider = FauxProvider()
            provider.setResponses(listOf(FauxResponseStep.Message(fauxAssistantMessage("done"))))
            val supervisor =
                ServerSupervisor(
                    ServerStorage(config),
                    RpcRuntimeFactory { cwd, _, _ ->
                        RpcRuntime(
                            Models(listOf(provider)),
                            RpcRuntimeOptions(
                                cwd = cwd,
                                noSession = true,
                                provider = "faux",
                                model = "faux-1",
                            ),
                        )
                    },
                )
            val instance = supervisor.spawnInstance(root, label = "test")
            val settled = CompletableDeferred<Unit>()
            val eventTypes = mutableListOf<String>()
            val unsubscribe =
                requireNotNull(
                    supervisor.subscribe(instance.id) { event ->
                        val type = event.string("type").orEmpty()
                        eventTypes += type
                        if (type == "agent_settled") settled.complete(Unit)
                    },
                )

            val prompt =
                requireNotNull(
                    supervisor.handleRpc(
                        instance.id,
                        buildJsonObject {
                            put("id", "prompt-1")
                            put("type", "prompt")
                            put("message", "hello")
                        },
                    ),
                )
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { settled.await() }
            }
            val state =
                requireNotNull(
                    supervisor.handleRpc(
                        instance.id,
                        buildJsonObject { put("type", "get_state") },
                    ),
                )

            assertEquals(InstanceStatus.ONLINE, supervisor.getInstance(instance.id)?.status)
            assertTrue(prompt["success"]?.jsonPrimitive?.boolean ?: false)
            assertTrue("message_update" in eventTypes)
            assertEquals("agent_settled", eventTypes.last())
            assertEquals(2, state["data"]?.jsonObject?.get("messageCount")?.jsonPrimitive?.content?.toInt())
            assertEquals(1, supervisor.listInstances().size)

            unsubscribe()
            val stopped = supervisor.stopInstance(instance.id)
            assertEquals(InstanceStatus.STOPPED, stopped?.status)
            assertTrue(supervisor.listInstances().isEmpty())
        }

    @Test
    fun `unix socket serves request response and rpc stream protocols`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-server-socket")
            val config = ServerConfig(root.resolve("server"))
            val supervisor =
                ServerSupervisor(
                    ServerStorage(config),
                    RpcRuntimeFactory { cwd, _, _ ->
                        RpcRuntime(
                            Models(listOf(FauxProvider())),
                            RpcRuntimeOptions(
                                cwd = cwd,
                                noSession = true,
                                provider = "faux",
                                model = "faux-1",
                            ),
                        )
                    },
                )
            val socketPath = Path.of("/tmp", "pi-kotlin-${UUID.randomUUID()}.sock")
            val server = IpcServer(socketPath, ServerService(supervisor))
            server.start()
            try {
                val spawn =
                    sendIpcRequest(
                        socketPath,
                        buildJsonObject {
                            put("type", "spawn")
                            put("cwd", root.toString())
                            put("label", "socket")
                        },
                    )
                val instanceId =
                    requireNotNull(
                        spawn["instance"]?.jsonObject?.get("id")?.jsonPrimitive?.content,
                    )
                val listed =
                    sendIpcRequest(
                        socketPath,
                        buildJsonObject { put("type", "list") },
                    )
                val status =
                    sendIpcRequest(
                        socketPath,
                        buildJsonObject {
                            put("type", "status")
                            put("instanceId", instanceId)
                        },
                    )
                val rpc =
                    sendIpcRequest(
                        socketPath,
                        buildJsonObject {
                            put("type", "rpc")
                            put("instanceId", instanceId)
                            put("command", buildJsonObject { put("type", "get_state") })
                        },
                    )

                assertTrue(spawn["ok"]?.jsonPrimitive?.boolean ?: false)
                assertEquals(1, listed["instances"]?.jsonArray?.size)
                assertEquals("online", status["instance"]?.jsonObject
                    ?.get("status")?.jsonPrimitive?.content)
                assertEquals("get_state", rpc["response"]?.jsonObject
                    ?.get("command")?.jsonPrimitive?.content)

                socketStream(socketPath, instanceId).use { stream ->
                    val ready = stream.read()
                    stream.write(buildJsonObject { put("type", "get_state") })
                    val response = stream.read()
                    assertEquals("rpc_ready", ready.string("type"))
                    assertEquals("response", response.string("type"))
                    assertEquals("get_state", response.string("command"))
                }

                val stop =
                    sendIpcRequest(
                        socketPath,
                        buildJsonObject {
                            put("type", "stop")
                            put("instanceId", instanceId)
                        },
                    )
                assertTrue(stop["ok"]?.jsonPrimitive?.boolean ?: false)
                val missing =
                    sendIpcRequest(
                        socketPath,
                        buildJsonObject {
                            put("type", "status")
                            put("instanceId", instanceId)
                        },
                    )
                assertFalse(missing["ok"]?.jsonPrimitive?.boolean ?: true)
            } finally {
                server.close()
                supervisor.shutdown()
            }
        }

    @Test
    fun `rpc stream cli half closes after piped input`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-server-pipe")
            val config = ServerConfig(root.resolve("server"))
            val supervisor =
                ServerSupervisor(
                    ServerStorage(config),
                    RpcRuntimeFactory { cwd, _, _ ->
                        RpcRuntime(
                            Models(listOf(FauxProvider())),
                            RpcRuntimeOptions(
                                cwd = cwd,
                                noSession = true,
                                provider = "faux",
                                model = "faux-1",
                            ),
                        )
                    },
                )
            val socketPath = Path.of("/tmp", "pi-kotlin-${UUID.randomUUID()}.sock")
            val server = IpcServer(socketPath, ServerService(supervisor))
            server.start()
            try {
                val spawn =
                    sendIpcRequest(
                        socketPath,
                        buildJsonObject {
                            put("type", "spawn")
                            put("cwd", root.toString())
                        },
                    )
                val instanceId =
                    requireNotNull(
                        spawn["instance"]?.jsonObject?.get("id")?.jsonPrimitive?.content,
                    )
                val output = StringWriter()
                withContext(Dispatchers.IO) {
                    withTimeout(5_000) {
                        rpcStream(
                            socketPath,
                            instanceId,
                            StringReader("""{"type":"get_state"}""" + "\n").buffered(),
                            PrintWriter(output, true),
                        )
                    }
                }

                val messages =
                    output
                        .toString()
                        .lineSequence()
                        .filter(String::isNotBlank)
                        .map(::parseMessage)
                        .toList()
                assertEquals(listOf("rpc_ready", "response"), messages.map { it.string("type") })
                assertEquals("get_state", messages.last().string("command"))
            } finally {
                server.close()
                supervisor.shutdown()
            }
        }

    private class SocketStream(
        private val socket: SocketChannel,
        private val reader: java.io.BufferedReader,
        private val writer: java.io.Writer,
    ) : AutoCloseable {
        fun write(value: JsonObject) {
            writer.write(encodeMessage(value))
            writer.flush()
        }

        fun read(): JsonObject = parseMessage(requireNotNull(reader.readLine()))

        override fun close() {
            socket.close()
        }
    }

    private fun socketStream(
        socketPath: java.nio.file.Path,
        instanceId: String,
    ): SocketStream {
        val socket = SocketChannel.open(StandardProtocolFamily.UNIX)
        socket.connect(UnixDomainSocketAddress.of(socketPath))
        val reader = Channels.newReader(socket, StandardCharsets.UTF_8).buffered()
        val writer = Channels.newWriter(socket, StandardCharsets.UTF_8)
        return SocketStream(socket, reader, writer).also { stream ->
            stream.write(
                buildJsonObject {
                    put("type", "rpc_stream")
                    put("instanceId", instanceId)
                },
            )
        }
    }
}
