package works.earendil.pi.server

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.earendil.pi.codingagent.RpcRuntime

internal interface RpcProcess {
    suspend fun send(command: JsonObject): JsonObject

    suspend fun sendUiResponse(response: JsonObject)

    fun subscribe(listener: (JsonObject) -> Unit): () -> Unit

    fun onExit(listener: (Throwable) -> Unit): () -> Unit

    suspend fun close()
}

internal fun interface RpcProcessFactory {
    suspend fun create(
        cwd: Path,
        provider: String?,
        model: String?,
    ): RpcProcess
}

fun interface RpcRuntimeFactory {
    suspend fun create(
        cwd: Path,
        provider: String?,
        model: String?,
    ): RpcRuntime
}

internal class InProcessRpcProcess(
    private val runtime: RpcRuntime,
) : RpcProcess {
    private val listeners = CopyOnWriteArrayList<(JsonObject) -> Unit>()
    private val unsubscribe =
        runtime.subscribe { event ->
            listeners.forEach { listener -> listener(event) }
        }

    override suspend fun send(command: JsonObject): JsonObject =
        requireNotNull(runtime.handle(command)) {
            "RPC command did not produce a response: ${command.string("type")}"
        }

    override suspend fun sendUiResponse(response: JsonObject) {
        runtime.handle(response)
    }

    override fun subscribe(listener: (JsonObject) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    override fun onExit(listener: (Throwable) -> Unit): () -> Unit = {}

    override suspend fun close() {
        unsubscribe()
        runtime.close()
    }
}

internal class ChildRpcProcess(
    cwd: Path,
    command: List<String> = defaultRpcProcessCommand(),
    provider: String? = null,
    model: String? = null,
) : RpcProcess {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val listeners = CopyOnWriteArrayList<(JsonObject) -> Unit>()
    private val exitListeners = CopyOnWriteArrayList<(Throwable) -> Unit>()
    private val nextRequestId = AtomicLong()
    private val exited = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val writerLock = Any()
    private val stderrLock = Any()
    private val stderr = StringBuilder()
    private val exitError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    private val process =
        ProcessBuilder(
            buildList {
                addAll(command)
                add("--mode")
                add("rpc")
                provider?.let {
                    add("--provider")
                    add(it)
                }
                model?.let {
                    add("--model")
                    add(it)
                }
            },
        ).directory(cwd.toAbsolutePath().normalize().toFile())
            .start()
    private val writer: BufferedWriter =
        process.outputStream.bufferedWriter(StandardCharsets.UTF_8)

    init {
        scope.launch {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        runCatching { handleLine(line) }
                            .onFailure { error ->
                                process.destroyForcibly()
                                handleExit(
                                    IllegalStateException(
                                        "Failed to parse RPC process output: $line",
                                        error,
                                    ),
                                )
                            }
                    }
                }
            }
        }
        scope.launch {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(4_096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    synchronized(stderrLock) {
                        stderr.append(buffer, 0, read)
                    }
                }
            }
        }
        scope.launch {
            val exitCode = process.waitFor()
            val details = stderrText()
            handleExit(
                IllegalStateException(
                    buildString {
                        append("RPC process exited (code=")
                        append(exitCode)
                        append(")")
                        if (details.isNotBlank()) {
                            append(". Stderr: ")
                            append(details)
                        }
                    },
                ),
            )
        }
    }

    override suspend fun send(command: JsonObject): JsonObject {
        ensureRunning()
        val id =
            command.string("id")
                ?: "server_${nextRequestId.incrementAndGet()}_${UUID.randomUUID()}"
        val request =
            if (command.string("id") == id) {
                command
            } else {
                JsonObject(command + ("id" to JsonPrimitive(id)))
            }
        val response = CompletableDeferred<JsonObject>()
        check(pending.putIfAbsent(id, response) == null) {
            "Duplicate RPC request id: $id"
        }
        try {
            write(request)
            return response.await()
        } finally {
            pending.remove(id, response)
        }
    }

    override suspend fun sendUiResponse(response: JsonObject) {
        ensureRunning()
        write(response)
    }

    override fun subscribe(listener: (JsonObject) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    override fun onExit(listener: (Throwable) -> Unit): () -> Unit {
        val error = exitError.get()
        if (error != null) {
            listener(error)
            return {}
        }
        exitListeners += listener
        exitError.get()?.let { raced ->
            if (exitListeners.remove(listener)) {
                listener(raced)
            }
        }
        return { exitListeners -= listener }
    }

    override suspend fun close() {
        if (!closing.compareAndSet(false, true)) {
            return
        }
        pending.values.forEach { deferred ->
            deferred.completeExceptionally(IllegalStateException("RPC process disposed"))
        }
        pending.clear()
        runCatching {
            synchronized(writerLock) {
                writer.close()
            }
        }
        if (process.isAlive) {
            process.destroy()
            if (
                withTimeoutOrNull(5_000) {
                    withContext(Dispatchers.IO) { process.waitFor() }
                } == null
            ) {
                process.destroyForcibly()
                withContext(Dispatchers.IO) { process.waitFor() }
            }
        }
        scope.cancel()
    }

    private fun handleLine(line: String) {
        val value = parseMessage(line)
        if (value.string("type") == "response") {
            val id = value.string("id") ?: return
            pending.remove(id)?.complete(value)
            return
        }
        listeners.forEach { listener -> listener(value) }
    }

    private suspend fun write(value: JsonObject) {
        withContext(Dispatchers.IO) {
            try {
                synchronized(writerLock) {
                    writer.write(encodeMessage(value))
                    writer.flush()
                }
            } catch (error: Exception) {
                throw processFailure("Failed to write to RPC process", error)
            }
        }
    }

    private fun ensureRunning() {
        exitError.get()?.let { throw processFailure("RPC process is not running", it) }
        check(!closing.get()) { "RPC process is closing" }
    }

    private fun handleExit(error: Throwable) {
        if (!exited.compareAndSet(false, true)) {
            return
        }
        exitError.set(error)
        pending.values.forEach { deferred ->
            deferred.completeExceptionally(error)
        }
        pending.clear()
        exitListeners.toList().forEach { listener ->
            runCatching { listener(error) }
        }
        exitListeners.clear()
    }

    private fun processFailure(
        message: String,
        cause: Throwable,
    ): IllegalStateException =
        IllegalStateException(
            buildString {
                append(message)
                val details = stderrText()
                if (details.isNotBlank()) {
                    append(". Stderr: ")
                    append(details)
                }
            },
            cause,
        )

    private fun stderrText(): String =
        synchronized(stderrLock) {
            stderr.toString().trim()
        }
}

internal fun defaultRpcProcessCommand(): List<String> {
    val javaExecutable =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "java.exe"
            } else {
                "java"
            },
        )
    return listOf(
        javaExecutable.toString(),
        "-cp",
        System.getProperty("java.class.path"),
        "works.earendil.pi.codingagent.MainKt",
    )
}
