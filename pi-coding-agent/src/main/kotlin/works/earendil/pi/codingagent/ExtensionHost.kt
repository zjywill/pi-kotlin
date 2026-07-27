package works.earendil.pi.codingagent

import java.io.BufferedReader
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.agent.AgentTool
import works.earendil.pi.agent.AgentToolResult
import works.earendil.pi.agent.AgentToolUpdateCallback
import works.earendil.pi.agent.ToolExecutionMode
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.Usage

enum class ExtensionMode(
    val wireName: String,
) {
    PRINT("print"),
    RPC("rpc"),
    TUI("tui"),
}

internal data class ExtensionSource(
    val path: Path,
    val sourceInfo: ResourceSourceInfo,
)

internal data class ExtensionDiagnostic(
    val extensionPath: String,
    val event: String,
    val error: String,
    val stack: String? = null,
)

internal data class ExtensionAction(
    val type: String,
    val data: JsonObject,
)

internal data class ExtensionToolRegistration(
    val id: String,
    val name: String,
    val label: String,
    val description: String,
    val parameters: JsonObject,
    val executionMode: ToolExecutionMode?,
    val promptSnippet: String?,
    val promptGuidelines: List<String>,
    val extensionPath: Path,
    val sourceInfo: ResourceSourceInfo,
)

internal data class ExtensionCommandRegistration(
    val id: String,
    val name: String,
    val invocationName: String,
    val description: String?,
    val extensionPath: Path,
    val sourceInfo: ResourceSourceInfo,
)

internal data class ExtensionFlagRegistration(
    val name: String,
    val description: String?,
    val type: String,
    val defaultValue: JsonElement?,
    val extensionPath: Path,
)

internal data class ExtensionRegistration(
    val path: Path,
    val events: Set<String>,
    val shortcuts: List<String>,
    val messageRenderers: List<String>,
    val entryRenderers: List<String>,
)

internal data class ExtensionRegistrations(
    val version: Int = 0,
    val extensions: List<ExtensionRegistration> = emptyList(),
    val tools: List<ExtensionToolRegistration> = emptyList(),
    val commands: List<ExtensionCommandRegistration> = emptyList(),
    val flags: List<ExtensionFlagRegistration> = emptyList(),
    val providers: List<JsonObject> = emptyList(),
)

internal data class ExtensionInvocation(
    val result: JsonElement?,
    val actions: List<ExtensionAction>,
    val resources: ExtensionResourcePaths? = null,
)

internal data class ExtensionResourcePath(
    val path: String,
    val extensionPath: Path,
)

internal data class ExtensionResourcePaths(
    val skillPaths: List<ExtensionResourcePath> = emptyList(),
    val promptPaths: List<ExtensionResourcePath> = emptyList(),
    val themePaths: List<ExtensionResourcePath> = emptyList(),
)

internal class ExtensionHost private constructor(
    private val process: Process,
    private val input: BufferedReader,
    private val output: PrintWriter,
    private val stderrLines: ArrayDeque<String>,
    private val stderrThread: Thread,
    private val sourceInfoByPath: MutableMap<Path, ResourceSourceInfo>,
    private val onDiagnostic: (ExtensionDiagnostic) -> Unit,
) : AutoCloseable {
    private val requestIds = AtomicLong()
    private val startupActions = mutableListOf<ExtensionAction>()
    private var closed = false

    lateinit var registrations: ExtensionRegistrations
        private set

    fun emit(
        event: JsonObject,
        context: JsonObject,
    ): ExtensionInvocation =
        invoke(
            buildJsonObject {
                put("type", "emit")
                put("event", event)
                put("context", context)
            },
        )

    fun invokeTool(
        toolId: String,
        toolCallId: String,
        params: JsonObject,
        context: JsonObject,
    ): ExtensionInvocation =
        invoke(
            buildJsonObject {
                put("type", "invoke_tool")
                put("toolId", toolId)
                put("toolCallId", toolCallId)
                put("params", params)
                put("context", context)
            },
        )

    fun invokeCommand(
        name: String,
        args: String,
        context: JsonObject,
    ): ExtensionInvocation =
        invoke(
            buildJsonObject {
                put("type", "invoke_command")
                put("name", name)
                put("args", args)
                put("context", context)
            },
        )

    fun refreshRegistrations(): ExtensionRegistrations {
        val response =
            request(
                buildJsonObject {
                    put("type", "registrations")
                },
            )
        registrations = parseRegistrations(response["result"]?.jsonObject ?: JsonObject(emptyMap()))
        return registrations
    }

    fun drainStartupActions(): List<ExtensionAction> =
        startupActions.toList().also { startupActions.clear() }

    fun loadAdditional(
        sources: List<ExtensionSource>,
        context: JsonObject,
    ): ExtensionRegistrations {
        val normalizedSources =
            sources
                .distinctBy { canonicalExtensionPath(it.path) }
                .filter { Files.isRegularFile(it.path) }
        if (normalizedSources.isEmpty()) {
            return registrations
        }
        normalizedSources.forEach { source ->
            sourceInfoByPath[canonicalExtensionPath(source.path)] = source.sourceInfo
        }
        val response =
            request(
                buildJsonObject {
                    put("type", "load_more")
                    put(
                        "paths",
                        JsonArray(normalizedSources.map { JsonPrimitive(it.path.toString()) }),
                    )
                    put("context", context)
                },
            )
        response["errors"]
            ?.jsonArray
            .orEmpty()
            .mapNotNull(::parseDiagnostic)
            .forEach(onDiagnostic)
        startupActions += parseActions(response)
        registrations =
            parseRegistrations(
                response["registrations"]?.jsonObject ?: JsonObject(emptyMap()),
            )
        return registrations
    }

    @Synchronized
    override fun close() {
        if (closed) {
            return
        }
        closed = true
        runCatching {
            val id = requestIds.incrementAndGet().toString()
            output.println(
                protocolJson.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("id", id)
                        put("type", "close")
                    },
                ),
            )
            output.flush()
            input.readLine()
        }
        output.close()
        input.close()
        if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroy()
        }
        if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
        stderrThread.join(1_000)
    }

    private fun invoke(request: JsonObject): ExtensionInvocation {
        val response = request(request)
        response["errors"]
            ?.jsonArray
            .orEmpty()
            .mapNotNull(::parseDiagnostic)
            .forEach(onDiagnostic)
        val registrationsChanged = refreshRegistrationsIfNeeded(response)
        val actions =
            buildList {
                addAll(parseActions(response))
                if (registrationsChanged) {
                    add(
                        ExtensionAction(
                            type = "registrations_changed",
                            data = buildJsonObject { put("version", registrations.version) },
                        ),
                    )
                }
            }
        return ExtensionInvocation(
            result = response["result"]?.takeUnless { it is JsonNull },
            actions = actions,
            resources = response["resources"]?.let(::parseResourcePaths),
        )
    }

    private fun refreshRegistrationsIfNeeded(response: JsonObject): Boolean {
        val responseVersion =
            response["registrationVersion"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.toIntOrNull()
                ?: return false
        if (responseVersion == registrations.version) {
            return false
        }
        refreshRegistrations()
        return true
    }

    private fun parseActions(response: JsonObject): List<ExtensionAction> =
        response["actions"]
            ?.jsonArray
            .orEmpty()
            .mapNotNull { element ->
                val action = element as? JsonObject ?: return@mapNotNull null
                val type = action.string("type") ?: return@mapNotNull null
                ExtensionAction(type, JsonObject(action - "type"))
            }

    private fun parseResourcePaths(value: JsonElement): ExtensionResourcePaths {
        val resources = value.jsonObject
        fun entries(name: String): List<ExtensionResourcePath> =
            resources[name]
                ?.jsonArray
                .orEmpty()
                .mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val path = item.string("path") ?: return@mapNotNull null
                    val extensionPath = item.string("extensionPath") ?: return@mapNotNull null
                    ExtensionResourcePath(path, Path.of(extensionPath).toAbsolutePath().normalize())
                }
        return ExtensionResourcePaths(
            skillPaths = entries("skillPaths"),
            promptPaths = entries("promptPaths"),
            themePaths = entries("themePaths"),
        )
    }

    @Synchronized
    private fun request(request: JsonObject): JsonObject {
        check(!closed) { "Extension host is closed" }
        val id = requestIds.incrementAndGet().toString()
        val payload =
            buildJsonObject {
                request.forEach { (name, value) -> put(name, value) }
                put("id", id)
            }
        output.println(protocolJson.encodeToString(JsonObject.serializer(), payload))
        output.flush()
        val line =
            input.readLine()
                ?: error(
                    buildString {
                        append("Extension host exited unexpectedly")
                        val stderr = synchronized(stderrLines) { stderrLines.joinToString("\n") }
                        if (stderr.isNotBlank()) {
                            append(": ")
                            append(stderr)
                        }
                    },
                )
        val response =
            try {
                protocolJson.parseToJsonElement(line).jsonObject
            } catch (error: Exception) {
                throw IllegalStateException("Extension host returned invalid JSON: $line", error)
            }
        check(response.string("id") == id) {
            "Extension host response id mismatch: expected $id, received ${response.string("id")}"
        }
        if (response["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            error(response.string("error") ?: "Extension host request failed")
        }
        return response
    }

    private fun parseRegistrations(value: JsonObject): ExtensionRegistrations {
        val extensions =
            value["extensions"]
                ?.jsonArray
                .orEmpty()
                .map { element ->
                    val item = element.jsonObject
                    ExtensionRegistration(
                        path = Path.of(requireNotNull(item.string("path"))).toAbsolutePath().normalize(),
                        events = item.stringList("events").toSet(),
                        shortcuts =
                            item["shortcuts"]
                                ?.jsonArray
                                .orEmpty()
                                .mapNotNull { it.jsonObject.string("shortcut") },
                        messageRenderers = item.stringList("messageRenderers"),
                        entryRenderers = item.stringList("entryRenderers"),
                    )
                }
        val tools =
            value["tools"]
                ?.jsonArray
                .orEmpty()
                .map { element ->
                    val item = element.jsonObject
                    val path = Path.of(requireNotNull(item.string("extensionPath"))).toAbsolutePath().normalize()
                    ExtensionToolRegistration(
                        id = requireNotNull(item.string("id")),
                        name = requireNotNull(item.string("name")),
                        label = item.string("label") ?: requireNotNull(item.string("name")),
                        description = item.string("description").orEmpty(),
                        parameters = item["parameters"] as? JsonObject ?: emptyObjectSchema(),
                        executionMode =
                            when (item.string("executionMode")) {
                                "sequential" -> ToolExecutionMode.SEQUENTIAL
                                "parallel" -> ToolExecutionMode.PARALLEL
                                else -> null
                            },
                        promptSnippet = item.string("promptSnippet"),
                        promptGuidelines = item.stringList("promptGuidelines"),
                        extensionPath = path,
                        sourceInfo = sourceInfo(path),
                    )
                }
        val commands =
            value["commands"]
                ?.jsonArray
                .orEmpty()
                .map { element ->
                    val item = element.jsonObject
                    val path = Path.of(requireNotNull(item.string("extensionPath"))).toAbsolutePath().normalize()
                    ExtensionCommandRegistration(
                        id = requireNotNull(item.string("id")),
                        name = requireNotNull(item.string("name")),
                        invocationName = requireNotNull(item.string("invocationName")),
                        description = (item["description"] as? JsonPrimitive)?.content,
                        extensionPath = path,
                        sourceInfo = sourceInfo(path),
                    )
                }
        val flags =
            value["flags"]
                ?.jsonArray
                .orEmpty()
                .map { element ->
                    val item = element.jsonObject
                    val path = Path.of(requireNotNull(item.string("extensionPath"))).toAbsolutePath().normalize()
                    ExtensionFlagRegistration(
                        name = requireNotNull(item.string("name")),
                        description = (item["description"] as? JsonPrimitive)?.content,
                        type = item.string("type") ?: "boolean",
                        defaultValue = item["default"],
                        extensionPath = path,
                    )
                }
        return ExtensionRegistrations(
            version = value["version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            extensions = extensions,
            tools = tools,
            commands = commands,
            flags = flags,
            providers = value["providers"]?.jsonArray.orEmpty().map(JsonElement::jsonObject),
        )
    }

    private fun sourceInfo(path: Path): ResourceSourceInfo =
        sourceInfoByPath[canonicalExtensionPath(path)]
            ?: ResourceSourceInfo(
                path = path,
                source = "local",
                scope = "temporary",
                origin = "extension",
                baseDir = path.parent,
            )

    companion object {
        fun start(
            sources: List<ExtensionSource>,
            agentDir: Path,
            cwd: Path,
            mode: ExtensionMode,
            projectTrusted: Boolean,
            flagValues: Map<String, Any>,
            context: JsonObject,
            hasUI: Boolean = false,
            environment: Map<String, String> = System.getenv(),
            onDiagnostic: (ExtensionDiagnostic) -> Unit = {},
            onLog: (String) -> Unit = {},
        ): ExtensionHost? {
            if (sources.isEmpty()) {
                return null
            }
            val normalizedSources =
                sources
                    .distinctBy { canonicalExtensionPath(it.path) }
                    .filter { Files.isRegularFile(it.path) }
            if (normalizedSources.isEmpty()) {
                return null
            }
            val script = extractHostScript(agentDir)
            val node = environment["PI_NODE"]?.takeIf(String::isNotBlank) ?: "node"
            val process =
                try {
                    ProcessBuilder(node, "--no-warnings", script.toString())
                        .directory(cwd.toFile())
                        .apply { environment().putAll(environment) }
                        .start()
                } catch (error: Exception) {
                    onDiagnostic(
                        ExtensionDiagnostic(
                            extensionPath = script.toString(),
                            event = "host_start",
                            error = error.message ?: "Failed to start Node.js",
                        ),
                    )
                    return null
                }
            val stderrLines = ArrayDeque<String>()
            val stderrThread =
                thread(
                    name = "pi-extension-host-stderr",
                    isDaemon = true,
                ) {
                    process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            synchronized(stderrLines) {
                                stderrLines.addLast(line)
                                while (stderrLines.size > MAX_STDERR_LINES) {
                                    stderrLines.removeFirst()
                                }
                            }
                            onLog(line)
                        }
                    }
                }
            val host =
                ExtensionHost(
                    process = process,
                    input = process.inputStream.bufferedReader(StandardCharsets.UTF_8),
                    output = PrintWriter(process.outputStream.writer(StandardCharsets.UTF_8), true),
                    stderrLines = stderrLines,
                    stderrThread = stderrThread,
                    sourceInfoByPath =
                        normalizedSources.associate { source ->
                            canonicalExtensionPath(source.path) to source.sourceInfo
                        }.toMutableMap(),
                    onDiagnostic = onDiagnostic,
                )
            return try {
                val response =
                    host.request(
                        buildJsonObject {
                            put("type", "load")
                            put(
                                "paths",
                                JsonArray(normalizedSources.map { JsonPrimitive(it.path.toString()) }),
                            )
                            put("flags", extensionFlagValuesJson(flagValues))
                            put(
                                "context",
                                JsonObject(
                                    context +
                                        mapOf(
                                            "cwd" to JsonPrimitive(cwd.toString()),
                                            "mode" to JsonPrimitive(mode.wireName),
                                            "hasUI" to JsonPrimitive(hasUI),
                                            "projectTrusted" to JsonPrimitive(projectTrusted),
                                        ),
                                ),
                            )
                        },
                    )
                response["errors"]
                    ?.jsonArray
                    .orEmpty()
                    .mapNotNull(::parseDiagnostic)
                    .forEach(onDiagnostic)
                host.startupActions += host.parseActions(response)
                host.registrations =
                    host.parseRegistrations(
                        response["registrations"]?.jsonObject ?: JsonObject(emptyMap()),
                    )
                host
            } catch (error: Exception) {
                onDiagnostic(
                    ExtensionDiagnostic(
                        extensionPath = script.toString(),
                        event = "host_start",
                        error = error.message ?: error::class.simpleName.orEmpty(),
                    ),
                )
                host.close()
                null
            }
        }

        private fun extractHostScript(agentDir: Path): Path {
            val bytes =
                checkNotNull(
                    ExtensionHost::class.java.getResourceAsStream(
                        "/works/earendil/pi/codingagent/extension-host.mjs",
                    ),
                ) {
                    "Bundled extension host is missing"
                }.use { it.readAllBytes() }
            val hash =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                    .take(16)
            val directory = agentDir.resolve("tmp").resolve("extension-host")
            Files.createDirectories(directory)
            val target = directory.resolve("extension-host-$hash.mjs")
            if (!Files.exists(target) || !Files.readAllBytes(target).contentEquals(bytes)) {
                val temporary = Files.createTempFile(directory, "extension-host-", ".tmp")
                Files.write(temporary, bytes)
                try {
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            return target
        }
    }
}

internal class HostedExtensionTool(
    private val registration: ExtensionToolRegistration,
    private val host: ExtensionHost,
    private val context: () -> JsonObject,
    private val onActions: suspend (List<ExtensionAction>) -> Unit,
) : AgentTool {
    override val name: String = registration.name
    override val label: String = registration.label
    override val description: String = registration.description
    override val parameters: JsonObject = registration.parameters
    override val executionMode: ToolExecutionMode? = registration.executionMode

    override suspend fun execute(
        toolCallId: String,
        params: JsonObject,
        onUpdate: AgentToolUpdateCallback?,
    ): AgentToolResult {
        val invocation =
            withContext(Dispatchers.IO) {
                host.invokeTool(
                    toolId = registration.id,
                    toolCallId = toolCallId,
                    params = params,
                    context = context(),
                )
            }
        invocation.actions
            .filter { it.type == "tool_update" }
            .forEach { action ->
                val partial = action.data["result"] as? JsonObject ?: return@forEach
                onUpdate?.update(decodeExtensionToolResult(partial))
            }
        onActions(invocation.actions.filterNot { it.type == "tool_update" })
        return decodeExtensionToolResult(
            invocation.result as? JsonObject
                ?: error("Extension tool ${registration.name} returned no result"),
        )
    }
}

internal fun extensionContextJson(
    cwd: Path,
    mode: ExtensionMode,
    projectTrusted: Boolean,
    model: Model?,
    thinkingLevel: String,
    systemPrompt: String,
    activeTools: List<String>,
    allTools: List<AgentTool>,
    sessionName: String?,
    sessionId: String?,
    sessionFile: Path?,
    isIdle: Boolean,
    hasPendingMessages: Boolean,
    flagValues: Map<String, Any>,
): JsonObject =
    buildJsonObject {
        put("cwd", cwd.toString())
        put("mode", mode.wireName)
        put("hasUI", mode == ExtensionMode.TUI)
        put("projectTrusted", projectTrusted)
        model?.let { put("model", protocolJson.encodeToJsonElement(Model.serializer(), it)) }
        put("thinkingLevel", thinkingLevel)
        put("systemPrompt", systemPrompt)
        put("activeTools", JsonArray(activeTools.map(::JsonPrimitive)))
        put(
            "allTools",
            JsonArray(
                allTools.map { tool ->
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                    }
                },
            ),
        )
        sessionName?.let { put("sessionName", it) }
        sessionId?.let { put("sessionId", it) }
        sessionFile?.let { put("sessionFile", it.toString()) }
        put("isIdle", isIdle)
        put("hasPendingMessages", hasPendingMessages)
        put("flags", extensionFlagValuesJson(flagValues))
    }

internal fun decodeExtensionToolResult(value: JsonObject): AgentToolResult =
    AgentToolResult(
        content =
            value["content"]
                ?.jsonArray
                .orEmpty()
                .map { protocolJson.decodeFromJsonElement(ContentBlock.serializer(), it) },
        details = value["details"] ?: JsonObject(emptyMap()),
        usage = value["usage"]?.let { protocolJson.decodeFromJsonElement(Usage.serializer(), it) },
        addedToolNames = value.stringList("addedToolNames"),
        terminate = value["terminate"]?.jsonPrimitive?.booleanOrNull == true,
    )

internal fun resolveExtensionSources(
    cwd: Path,
    explicitPaths: List<String>,
    packageResources: ResolvedPackageResources,
    noExtensions: Boolean,
): List<ExtensionSource> {
    val result = linkedMapOf<Path, ExtensionSource>()
    if (!noExtensions) {
        packageResources.extensions
            .filter(ResolvedResource::enabled)
            .forEach { resource ->
                val path = resource.path.toAbsolutePath().normalize()
                result.putIfAbsent(
                    canonicalExtensionPath(path),
                    ExtensionSource(path, resource.sourceInfo.copy(path = path)),
                )
            }
    }
    explicitPaths.forEach { raw ->
        val resolved = resolveExtensionPath(cwd, raw)
        discoverExplicitExtensions(resolved).forEach { path ->
            val normalized = path.toAbsolutePath().normalize()
            result.putIfAbsent(
                canonicalExtensionPath(normalized),
                ExtensionSource(
                    path = normalized,
                    sourceInfo =
                        ResourceSourceInfo(
                            path = normalized,
                            source = "local",
                            scope = "temporary",
                            origin = "top-level",
                            baseDir = normalized.parent,
                        ),
                ),
            )
        }
    }
    return result.values.toList()
}

private fun discoverExplicitExtensions(path: Path): List<Path> =
    when {
        Files.isRegularFile(path) -> listOf(path)
        !Files.isDirectory(path) -> emptyList()
        else -> {
            resolveExplicitExtensionEntries(path)?.let { return it }
            Files.list(path).use { entries ->
                entries
                    .sorted()
                    .flatMap { entry ->
                        when {
                            Files.isRegularFile(entry) && entry.isExtensionFile() ->
                                java.util.stream.Stream.of(entry.toAbsolutePath().normalize())

                            Files.isDirectory(entry) ->
                                resolveExplicitExtensionEntries(entry)
                                    ?.stream()
                                    ?: java.util.stream.Stream.empty()

                            else -> java.util.stream.Stream.empty()
                        }
                    }.toList()
            }
        }
    }

private fun resolveExplicitExtensionEntries(directory: Path): List<Path>? {
    val manifest = directory.resolve("package.json")
    if (Files.isRegularFile(manifest)) {
        val declared =
            runCatching {
                protocolJson
                    .parseToJsonElement(Files.readString(manifest))
                    .jsonObject["pi"]
                    ?.jsonObject
                    ?.get("extensions")
                    ?.jsonArray
                    .orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                    .map(directory::resolve)
                    .filter(Files::exists)
                    .map { it.toAbsolutePath().normalize() }
            }.getOrDefault(emptyList())
        if (declared.isNotEmpty()) {
            return declared
        }
    }
    listOf("index.ts", "index.js").forEach { name ->
        val candidate = directory.resolve(name)
        if (Files.isRegularFile(candidate)) {
            return listOf(candidate.toAbsolutePath().normalize())
        }
    }
    return null
}

private fun Path.isExtensionFile(): Boolean {
    val name = fileName.toString()
    return name.endsWith(".ts") || name.endsWith(".js")
}

private fun resolveExtensionPath(
    cwd: Path,
    value: String,
): Path {
    val path =
        when {
            value == "~" -> defaultHomeDirectory()
            value.startsWith("~/") -> defaultHomeDirectory().resolve(value.removePrefix("~/"))
            else -> Path.of(value)
        }
    return (if (path.isAbsolute) path else cwd.resolve(path)).toAbsolutePath().normalize()
}

private fun canonicalExtensionPath(path: Path): Path =
    runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }

private fun extensionFlagValuesJson(values: Map<String, Any>): JsonObject =
    buildJsonObject {
        values.forEach { (name, value) ->
            when (value) {
                is Boolean -> put(name, value)
                is String -> put(name, value)
                is Number -> put(name, value)
                else -> put(name, value.toString())
            }
        }
    }

private fun parseDiagnostic(element: JsonElement): ExtensionDiagnostic? {
    val value = element as? JsonObject ?: return null
    return ExtensionDiagnostic(
        extensionPath = value.string("extensionPath") ?: return null,
        event = value.string("event") ?: "unknown",
        error = value.string("error") ?: return null,
        stack = value.string("stack"),
    )
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.stringList(name: String): List<String> =
    this[name]
        ?.jsonArray
        .orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun emptyObjectSchema(): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(emptyMap()))
    }

private const val MAX_STDERR_LINES = 100
