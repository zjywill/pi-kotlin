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
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerIntegrationTest {
    @Test
    fun `child rpc process rejects an in-flight request when it exits`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-rpc-child-exit")
            val process =
                ChildRpcProcess(
                    cwd = root,
                    command = listOf("/bin/sh", "-c", "read line; exit 43"),
                )
            try {
                val error =
                    runCatching {
                        process.send(
                            buildJsonObject {
                                put("id", "pending")
                                put("type", "get_state")
                            },
                        )
                    }.exceptionOrNull()
                assertNotNull(error)
                assertContains(error.message.orEmpty(), "code=43")
            } finally {
                process.close()
            }
        }

    @Test
    fun `supervisor persists error state after an unexpected rpc process exit`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-server-process-exit")
            val config = ServerConfig(root.resolve("server"))
            val process = ControllableRpcProcess()
            val supervisor =
                ServerSupervisor(
                    ServerStorage(config),
                    RpcProcessFactory { _, _, _ -> process },
                )
            val instance = supervisor.spawnInstance(root, label = "exit")
            val pending =
                async(Dispatchers.Default) {
                    runCatching {
                        supervisor.handleRpc(
                            instance.id,
                            buildJsonObject {
                                put("id", "pending")
                                put("type", "wait")
                            },
                        )
                    }.exceptionOrNull()
                }
            withTimeout(5_000) { process.requestStarted.await() }

            process.exit(IllegalStateException("fixture process exited"))

            val error = withTimeout(5_000) { pending.await() }
            assertNotNull(error)
            assertContains(error.message.orEmpty(), "fixture process exited")
            assertEquals(InstanceStatus.ERROR, supervisor.getInstance(instance.id)?.status)
            assertEquals(InstanceStatus.ERROR, supervisor.listInstances().single().status)
            assertFalse(supervisor.handleUiResponse(instance.id, buildJsonObject { put("type", "ignored") }))
        }

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
    fun `rpc stream routes extension UI responses while commands are awaiting them`() =
        runTest {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                nodeAvailable(),
                "Node.js 22+ is required for extension runtime tests",
            )
            val root = Files.createTempDirectory("pi-kotlin-server-extension-ui")
            val extension =
                root.resolve("choose.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        export default function(pi) {
                          pi.registerCommand("choose", {
                            async handler(_args, ctx) {
                              const choice = await ctx.ui.select("Choose", ["one", "two"]);
                              ctx.ui.notify(`selected:${'$'}{choice}`, "info");
                            },
                          });
                        }
                        """.trimIndent(),
                    )
                }
            val config = ServerConfig(root.resolve("server"))
            val supervisor =
                ServerSupervisor(
                    ServerStorage(config),
                    RpcRuntimeFactory { cwd, _, _ ->
                        RpcRuntime(
                            Models(listOf(FauxProvider())),
                            RpcRuntimeOptions(
                                cwd = cwd,
                                agentDir = Files.createDirectories(root.resolve("agent")),
                                noSession = true,
                                provider = "faux",
                                model = "faux-1",
                                extensionPaths = listOf(extension.toString()),
                            ),
                        )
                    },
                )
            val socketPath = Path.of("/tmp", "pi-kotlin-${UUID.randomUUID()}.sock")
            val server = IpcServer(socketPath, ServerService(supervisor))
            server.start()
            try {
                val instance = supervisor.spawnInstance(root)
                socketStream(socketPath, instance.id).use { stream ->
                    assertEquals("rpc_ready", stream.read().string("type"))
                    stream.write(
                        buildJsonObject {
                            put("id", "choose-command")
                            put("type", "prompt")
                            put("message", "/choose")
                        },
                    )
                    val request = stream.read()
                    assertEquals("extension_ui_request", request.string("type"))
                    assertEquals("select", request.string("method"))
                    stream.write(
                        buildJsonObject {
                            put("type", "extension_ui_response")
                            put("id", requireNotNull(request.string("id")))
                            put("value", "two")
                        },
                    )
                    val afterResponse = mutableListOf<JsonObject>()
                    withContext(Dispatchers.IO) {
                        withTimeout(5_000) {
                            while (
                                afterResponse.none {
                                    it.string("type") == "response" &&
                                        it.string("id") == "choose-command"
                                } ||
                                afterResponse.none {
                                    it.string("type") == "extension_ui_request" &&
                                        it.string("method") == "notify"
                                }
                            ) {
                                afterResponse += stream.read()
                            }
                        }
                    }
                    val response =
                        afterResponse.firstOrNull {
                            it.string("type") == "response" &&
                                it.string("id") == "choose-command"
                        } ?: error("Missing command response in $afterResponse")
                    val notification =
                        afterResponse.firstOrNull {
                            it.string("type") == "extension_ui_request" &&
                                it.string("method") == "notify"
                        } ?: error("Missing extension notification in $afterResponse")

                    assertTrue(response["success"]?.jsonPrimitive?.boolean ?: false)
                    assertEquals("selected:two", notification.string("message"))
                }
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

    private fun nodeAvailable(): Boolean =
        runCatching {
            val process = ProcessBuilder("node", "--version").start()
            process.waitFor()
            process.exitValue() == 0 &&
                process.inputStream.bufferedReader().readText().trim().removePrefix("v").substringBefore('.').toInt() >= 22
        }.getOrDefault(false)

    private class ControllableRpcProcess : RpcProcess {
        val requestStarted = CompletableDeferred<Unit>()
        private val pending = CompletableDeferred<JsonObject>()
        private val listeners = CopyOnWriteArrayList<(JsonObject) -> Unit>()
        private val exitListeners = CopyOnWriteArrayList<(Throwable) -> Unit>()
        private var exitError: Throwable? = null

        override suspend fun send(command: JsonObject): JsonObject {
            if (command.string("type") == "get_state") {
                return buildJsonObject {
                    put("id", command.string("id").orEmpty())
                    put("type", "response")
                    put("command", "get_state")
                    put("success", true)
                    put(
                        "data",
                        buildJsonObject {
                            put("sessionId", "fixture-session")
                        },
                    )
                }
            }
            exitError?.let { throw it }
            requestStarted.complete(Unit)
            return pending.await()
        }

        override suspend fun sendUiResponse(response: JsonObject) {
            exitError?.let { throw it }
        }

        override fun subscribe(listener: (JsonObject) -> Unit): () -> Unit {
            listeners += listener
            return { listeners -= listener }
        }

        override fun onExit(listener: (Throwable) -> Unit): () -> Unit {
            exitError?.let {
                listener(it)
                return {}
            }
            exitListeners += listener
            return { exitListeners -= listener }
        }

        override suspend fun close() = Unit

        fun exit(error: Throwable) {
            exitError = error
            pending.completeExceptionally(error)
            exitListeners.toList().forEach { listener -> listener(error) }
            exitListeners.clear()
        }
    }
}
