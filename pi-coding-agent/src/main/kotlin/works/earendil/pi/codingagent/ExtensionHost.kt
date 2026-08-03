package works.earendil.pi.codingagent

import java.io.BufferedReader
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
import works.earendil.pi.ai.AuthContext
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthOption
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelsStoreEntry
import works.earendil.pi.ai.ProviderModelsStore
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
    val hasRenderCall: Boolean,
    val hasRenderResult: Boolean,
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

internal data class ExtensionShortcutRegistration(
    val id: String,
    val shortcut: String,
    val description: String?,
    val extensionPath: Path,
)

internal data class ExtensionRendererRegistration(
    val id: String,
    val customType: String,
    val extensionPath: Path,
)

internal data class ExtensionRegistration(
    val path: Path,
    val events: Set<String>,
    val shortcuts: List<ExtensionShortcutRegistration>,
    val messageRenderers: List<ExtensionRendererRegistration>,
    val entryRenderers: List<ExtensionRendererRegistration>,
    val hasMarkdownTransformer: Boolean = false,
)

internal data class ExtensionRegistrations(
    val version: Int = 0,
    val extensions: List<ExtensionRegistration> = emptyList(),
    val tools: List<ExtensionToolRegistration> = emptyList(),
    val commands: List<ExtensionCommandRegistration> = emptyList(),
    val flags: List<ExtensionFlagRegistration> = emptyList(),
    val providers: List<JsonObject> = emptyList(),
    val autocompleteProviderCount: Int = 0,
    val markdownTransformerCount: Int = 0,
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

private sealed interface ExtensionHostRead {
    data class Message(
        val value: JsonObject,
    ) : ExtensionHostRead

    data class Failure(
        val error: Throwable,
    ) : ExtensionHostRead
}

internal class ExtensionHost private constructor(
    private val process: Process,
    private val input: BufferedReader,
    private val output: PrintWriter,
    private val stderrLines: ArrayDeque<String>,
    private val stderrThread: Thread,
    private val sourceInfoByPath: MutableMap<Path, ResourceSourceInfo>,
    private val onDiagnostic: (ExtensionDiagnostic) -> Unit,
    private val onUiRequest: (JsonObject, (JsonObject) -> Unit) -> Unit,
    private val onUiCancelled: (String) -> Unit,
    private val onUiControl: (JsonObject) -> Unit,
) : AutoCloseable {
    private val requestIds = AtomicLong()
    private val outputLock = Any()
    private val startupActions = mutableListOf<ExtensionAction>()
    private val activeProviderRequests = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var closed = false
    @Volatile
    private var closing = false
    private val registrationLock = Any()
    private val backgroundActionLock = Any()
    private val pendingBackgroundActions = ArrayDeque<ExtensionAction>()
    private val pendingTerminalInputRequests = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()
    private val pendingEditorComponentRequests = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()
    private val pendingAutocompleteRequests = ConcurrentHashMap<String, CompletableFuture<JsonObject>>()
    private val autocompleteBaseHandlers = ConcurrentHashMap<String, (JsonObject) -> JsonElement>()
    private val responseQueue = LinkedBlockingQueue<ExtensionHostRead>()
    @Volatile
    private var backgroundActionHandler: ((List<ExtensionAction>) -> Unit)? = null
    private val stdoutThread =
        thread(
            name = "pi-extension-host-stdout",
            isDaemon = true,
        ) {
            readHostOutput()
        }

    @Volatile
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

    fun emitUserBash(
        event: JsonObject,
        context: JsonObject,
        onOperationStart: (String) -> Unit,
        onUpdate: (String) -> Unit,
    ): ExtensionInvocation =
        invoke(
            buildJsonObject {
                put("type", "emit")
                put("event", event)
                put("context", context)
            },
            onIntermediate = { message ->
                when (message.string("type")) {
                    "bash_start" ->
                        message.string("id")?.let(onOperationStart)

                    "bash_update" ->
                        message.string("data")
                            ?.let(onUpdate)
                }
            },
        )

    fun abortBashOperation(id: String) {
        if (closed) {
            return
        }
        writePayload(
            buildJsonObject {
                put("type", "bash_abort")
                put("id", id)
            },
        )
    }

    fun streamProvider(
        callbackToken: String,
        method: String,
        model: JsonObject,
        context: JsonObject,
        options: JsonObject,
        onOperationStart: (String) -> Unit,
        onEvent: (JsonObject) -> Unit,
    ): JsonObject {
        var operationId: String? = null
        return try {
            request(
                buildJsonObject {
                    put("type", "provider_stream")
                    put("callbackToken", callbackToken)
                    put("method", method)
                    put("model", model)
                    put("context", context)
                    put("options", options)
                },
                onIntermediate = { message ->
                    if (message.string("type") == "provider_stream_event") {
                        message["event"]?.jsonObject?.let(onEvent)
                    }
                },
                onRequestId = { id ->
                    operationId = id
                    activeProviderRequests += id
                    onOperationStart(id)
                    if (closing) {
                        abortProviderOperation(id)
                    }
                },
            )
        } finally {
            operationId?.let(activeProviderRequests::remove)
        }
    }

    fun invokeProviderCallback(
        callbackToken: String,
        method: String,
        arguments: JsonObject = JsonObject(emptyMap()),
        interaction: AuthInteraction? = null,
        authContext: AuthContext? = null,
        store: ProviderModelsStore? = null,
        onOperationStart: (String) -> Unit = {},
    ): JsonElement? {
        var operationId: String? = null
        return try {
            val response =
                request(
                    buildJsonObject {
                        put("type", "provider_callback")
                        put("callbackToken", callbackToken)
                        put("method", method)
                        put("arguments", arguments)
                    },
                    onIntermediate = { message ->
                        if (
                            !handleProviderIntermediate(
                                message = message,
                                interaction = interaction,
                                authContext = authContext,
                                store = store,
                            )
                        ) {
                            error("Unexpected extension provider callback message: $message")
                        }
                    },
                    onRequestId = { id ->
                        operationId = id
                        activeProviderRequests += id
                        onOperationStart(id)
                        if (closing) {
                            abortProviderOperation(id)
                        }
                    },
                )
            response["result"]?.takeUnless { it is JsonNull }
        } finally {
            operationId?.let(activeProviderRequests::remove)
        }
    }

    fun abortProviderOperation(id: String) {
        if (closed) {
            return
        }
        writePayload(
            buildJsonObject {
                put("type", "provider_abort")
                put("id", id)
            },
        )
    }

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

    fun invokeToolRenderer(
        toolId: String,
        phase: String,
        toolCallId: String,
        args: JsonObject? = null,
        content: JsonArray? = null,
        details: JsonElement? = null,
        isError: Boolean = false,
        expanded: Boolean = false,
        width: Int = 100,
        context: JsonObject,
    ): ExtensionInvocation =
        invoke(
            buildJsonObject {
                put("type", "invoke_tool_renderer")
                put("toolId", toolId)
                put("phase", phase)
                put("toolCallId", toolCallId)
                args?.let { put("args", it) }
                content?.let { put("content", it) }
                details?.let { put("details", it) }
                put("isError", isError)
                put("expanded", expanded)
                put("width", width)
                put("context", context)
            },
        )

    fun invokeShortcut(
        id: String,
        context: JsonObject,
    ): ExtensionInvocation =
        invoke(
            buildJsonObject {
                put("type", "invoke_shortcut")
                put("shortcutId", id)
                put("context", context)
            },
        )

    fun invokeTerminalInput(
        listenerId: String,
        data: String,
    ): JsonObject? {
        if (closed) {
            return null
        }
        val requestId = "terminal-${requestIds.incrementAndGet()}"
        val response = CompletableFuture<JsonObject>()
        pendingTerminalInputRequests[requestId] = response
        return try {
            writePayload(
                buildJsonObject {
                    put("type", "terminal_input")
                    put("requestId", requestId)
                    put("listenerId", listenerId)
                    put("data", data)
                },
            )
            response.get(TERMINAL_INPUT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        } finally {
            pendingTerminalInputRequests.remove(requestId, response)
        }
    }

    fun invokeEditorComponent(
        componentId: String,
        operation: String,
        width: Int,
        data: String? = null,
        text: String? = null,
    ): JsonObject? {
        if (closed) {
            return null
        }
        val requestId = "editor-${requestIds.incrementAndGet()}"
        val response = CompletableFuture<JsonObject>()
        pendingEditorComponentRequests[requestId] = response
        return try {
            writePayload(
                buildJsonObject {
                    put("type", "editor_component")
                    put("requestId", requestId)
                    put("componentId", componentId)
                    put("operation", operation)
                    put("width", width)
                    data?.let { put("data", it) }
                    text?.let { put("text", it) }
                },
            )
            response.get(EDITOR_COMPONENT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        } finally {
            pendingEditorComponentRequests.remove(requestId, response)
        }
    }

    fun invokeAutocomplete(
        method: String,
        payload: JsonObject,
        baseTriggerCharacters: List<String>,
        onBaseRequest: (JsonObject) -> JsonElement,
    ): JsonObject? {
        if (closed) {
            return null
        }
        val requestId = "autocomplete-${requestIds.incrementAndGet()}"
        val response = CompletableFuture<JsonObject>()
        pendingAutocompleteRequests[requestId] = response
        autocompleteBaseHandlers[requestId] = onBaseRequest
        return try {
            writePayload(
                buildJsonObject {
                    put("type", "autocomplete")
                    put("requestId", requestId)
                    put("method", method)
                    put("payload", payload)
                    put(
                        "baseTriggerCharacters",
                        JsonArray(baseTriggerCharacters.map(::JsonPrimitive)),
                    )
                },
            )
            response.get(AUTOCOMPLETE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        } finally {
            pendingAutocompleteRequests.remove(requestId, response)
            autocompleteBaseHandlers.remove(requestId)
        }
    }

    fun invokeRenderer(
        kind: String,
        rendererId: String,
        value: JsonObject,
        width: Int,
        expanded: Boolean,
        outputPad: Int,
        context: JsonObject = JsonObject(emptyMap()),
    ): ExtensionInvocation =
        invoke(
            buildJsonObject {
                put("type", "invoke_renderer")
                put("kind", kind)
                put("rendererId", rendererId)
                put("value", value)
                put("width", width)
                put("expanded", expanded)
                if (kind == "message") {
                    put("outputPad", outputPad)
                }
                put("context", context)
            },
        )

    fun invokeMarkdownTransform(
        markdown: String,
        messageType: String,
        isStreaming: Boolean,
        availableWidth: Int,
        context: JsonObject = JsonObject(emptyMap()),
    ): String {
        val invocation =
            invoke(
                buildJsonObject {
                    put("type", "invoke_markdown_transform")
                    put("markdown", markdown)
                    put("messageType", messageType)
                    put("isStreaming", isStreaming)
                    put("availableWidth", availableWidth.coerceAtLeast(1))
                    put("context", context)
                },
            )
        return (invocation.result as? JsonObject)?.string("markdown") ?: markdown
    }

    fun refreshRegistrations(): ExtensionRegistrations {
        val response =
            request(
                buildJsonObject {
                    put("type", "registrations")
                },
            )
        updateRegistrations(
            parseRegistrations(response["result"]?.jsonObject ?: JsonObject(emptyMap())),
        )
        return registrations
    }

    fun drainStartupActions(): List<ExtensionAction> =
        startupActions.toList().also { startupActions.clear() }

    fun bindBackgroundActions(handler: (List<ExtensionAction>) -> Unit) {
        check(!closed) { "Extension host is closed" }
        val pending =
            synchronized(backgroundActionLock) {
                backgroundActionHandler = handler
                pendingBackgroundActions.toList().also { pendingBackgroundActions.clear() }
            }
        if (pending.isNotEmpty()) {
            deliverBackgroundActions(handler, pending)
        }
    }

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
        updateRegistrations(
            parseRegistrations(
                response["registrations"]?.jsonObject ?: JsonObject(emptyMap()),
            ),
        )
        return registrations
    }

    override fun close() {
        if (closed) {
            return
        }
        closing = true
        activeProviderRequests.toList().forEach(::abortProviderOperation)
        pendingTerminalInputRequests.values.forEach { request ->
            request.complete(JsonObject(emptyMap()))
        }
        pendingTerminalInputRequests.clear()
        pendingEditorComponentRequests.values.forEach { request ->
            request.complete(JsonObject(emptyMap()))
        }
        pendingEditorComponentRequests.clear()
        pendingAutocompleteRequests.values.forEach { request ->
            request.complete(JsonObject(emptyMap()))
        }
        pendingAutocompleteRequests.clear()
        autocompleteBaseHandlers.clear()
        synchronized(this) {
            if (closed) {
                return
            }
            runCatching {
                writePayload(
                    buildJsonObject {
                        put("type", "close")
                        put("id", requestIds.incrementAndGet().toString())
                    },
                )
            }
            closed = true
        }
        if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroy()
        }
        if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
        output.close()
        input.close()
        stdoutThread.join(1_000)
        stderrThread.join(1_000)
    }

    private fun invoke(
        request: JsonObject,
        onIntermediate: (JsonObject) -> Unit = {},
    ): ExtensionInvocation {
        val response = request(request, onIntermediate)
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
        response["registrations"]
            ?.jsonObject
            ?.let(::parseRegistrations)
            ?.let(::updateRegistrations)
            ?: refreshRegistrations()
        return true
    }

    private fun handleBackgroundActions(message: JsonObject) {
        val previousVersion =
            synchronized(registrationLock) {
                if (this::registrations.isInitialized) registrations.version else null
            }
        message["registrations"]
            ?.jsonObject
            ?.let(::parseRegistrations)
            ?.let(::updateRegistrations)
        val actions =
            buildList {
                addAll(parseActions(message))
                val responseVersion =
                    message["registrationVersion"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.toIntOrNull()
                if (responseVersion != null && responseVersion != previousVersion) {
                    add(
                        ExtensionAction(
                            type = "registrations_changed",
                            data = buildJsonObject { put("version", responseVersion) },
                        ),
                    )
                }
            }
        if (actions.isEmpty()) {
            return
        }
        val handler =
            synchronized(backgroundActionLock) {
                backgroundActionHandler
                    ?: run {
                        pendingBackgroundActions.addAll(actions)
                        null
                    }
            }
        if (handler != null) {
            deliverBackgroundActions(handler, actions)
        }
    }

    private fun deliverBackgroundActions(
        handler: (List<ExtensionAction>) -> Unit,
        actions: List<ExtensionAction>,
    ) {
        runCatching {
            handler(actions)
        }.onFailure { error ->
            onDiagnostic(
                ExtensionDiagnostic(
                    extensionPath = "<host>",
                    event = "background_actions",
                    error = error.message ?: "Extension background action failed",
                ),
            )
        }
    }

    private fun updateRegistrations(candidate: ExtensionRegistrations) {
        synchronized(registrationLock) {
            if (!this::registrations.isInitialized || candidate.version >= registrations.version) {
                registrations = candidate
            }
        }
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
    private fun request(
        request: JsonObject,
        onIntermediate: (JsonObject) -> Unit = {},
        onRequestId: (String) -> Unit = {},
    ): JsonObject {
        check(!closed) { "Extension host is closed" }
        val id = requestIds.incrementAndGet().toString()
        val payload =
            buildJsonObject {
                request.forEach { (name, value) -> put(name, value) }
                if (this@ExtensionHost::registrations.isInitialized) {
                    put("knownRegistrationVersion", registrations.version)
                }
                put("id", id)
            }
        writePayload(payload)
        onRequestId(id)
        while (true) {
            val response = readResponse()
            when (response.string("type")) {
                "ui_request" -> {
                    val requestId = response.string("requestId")
                        ?: error("Extension host UI request is missing requestId")
                    val responded = AtomicBoolean(false)
                    onUiRequest(response) { value ->
                        if (responded.compareAndSet(false, true)) {
                            writePayload(
                                buildJsonObject {
                                    put("type", "ui_response")
                                    put("requestId", requestId)
                                    value.forEach { (name, element) ->
                                        if (name != "type" && name != "id" && name != "requestId") {
                                            put(name, element)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }

                "ui_cancel" ->
                    response.string("requestId")?.let(onUiCancelled)

                "ui_control" -> onUiControl(response)

                else -> {
                    if (requireExtensionHostFinalResponse(response, id)) {
                        return response
                    }
                    onIntermediate(response)
                }
            }
        }
    }

    private fun handleProviderIntermediate(
        message: JsonObject,
        interaction: AuthInteraction?,
        authContext: AuthContext?,
        store: ProviderModelsStore?,
    ): Boolean =
        when (message.string("type")) {
            "provider_auth_event" -> {
                val event = message["event"]?.jsonObject
                    ?: error("Extension provider auth event is missing event")
                requireNotNull(interaction) {
                    "Extension provider emitted an auth event outside login"
                }.notify(
                    when (event.string("type")) {
                        "auth_url" ->
                            AuthEvent.AuthUrl(
                                url = requireNotNull(event.string("url")),
                                instructions = event.string("instructions"),
                            )

                        "device_code" ->
                            AuthEvent.DeviceCode(
                                userCode = requireNotNull(event.string("userCode")),
                                verificationUri = requireNotNull(event.string("verificationUri")),
                                intervalSeconds =
                                    event["intervalSeconds"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                        ?.toDoubleOrNull(),
                                expiresInSeconds =
                                    event["expiresInSeconds"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                        ?.toIntOrNull(),
                            )

                        "progress" ->
                            AuthEvent.Progress(
                                message = requireNotNull(event.string("message")),
                            )

                        "info" ->
                            AuthEvent.Info(
                                message = requireNotNull(event.string("message")),
                            )

                        else -> error("Unknown extension provider auth event: $event")
                    },
                )
                true
            }

            "provider_auth_request" -> {
                val requestId = message.string("requestId")
                    ?: error("Extension provider auth request is missing requestId")
                val authInteraction =
                    requireNotNull(interaction) {
                        "Extension provider requested auth input outside login"
                    }
                val prompt =
                    when (message.string("method")) {
                        "text",
                        "secret",
                        ->
                            AuthPrompt.Text(
                                message = requireNotNull(message.string("message")),
                                placeholder = message.string("placeholder"),
                                secret = message.string("method") == "secret",
                            )

                        "manual_code" ->
                            AuthPrompt.ManualCode(
                                message = message.string("message") ?: "Paste the authorization code",
                                placeholder = message.string("placeholder"),
                            )

                        "select" ->
                            AuthPrompt.Select(
                                message = requireNotNull(message.string("message")),
                                options =
                                    message["options"]
                                        ?.jsonArray
                                        .orEmpty()
                                        .map { option ->
                                            val value = option.jsonObject
                                            AuthOption(
                                                id = requireNotNull(value.string("id")),
                                                label = requireNotNull(value.string("label")),
                                                description = value.string("description"),
                                            )
                                        },
                            )

                        else -> error("Unknown extension provider auth request: $message")
                    }
                val answer = runCatching { runBlocking { authInteraction.prompt(prompt) } }
                writePayload(
                    buildJsonObject {
                        put("type", "provider_auth_response")
                        put("requestId", requestId)
                        answer
                            .onSuccess { put("value", it) }
                            .onFailure {
                                put("cancelled", true)
                                put("error", it.message ?: "Login cancelled")
                            }
                    },
                )
                true
            }

            "provider_context_request" -> {
                val requestId = message.string("requestId")
                    ?: error("Extension provider context request is missing requestId")
                val context =
                    requireNotNull(authContext) {
                        "Extension provider requested auth context outside API-key auth"
                    }
                val result =
                    runCatching {
                        runBlocking {
                            when (message.string("method")) {
                                "env" ->
                                    context.env(
                                        requireNotNull(message.string("name")) {
                                            "Extension provider env request is missing name"
                                        },
                                    )

                                "file_exists" ->
                                    context.fileExists(
                                        requireNotNull(message.string("path")) {
                                            "Extension provider fileExists request is missing path"
                                        },
                                    )

                                else -> error("Unknown extension provider context request: $message")
                            }
                        }
                    }
                writeProviderBridgeResponse(
                    type = "provider_context_response",
                    requestId = requestId,
                    result = result,
                )
                true
            }

            "provider_store_request" -> {
                val requestId = message.string("requestId")
                    ?: error("Extension provider store request is missing requestId")
                val providerStore =
                    requireNotNull(store) {
                        "Extension provider requested model storage outside refreshModels"
                    }
                val result =
                    runCatching {
                        runBlocking {
                            when (message.string("method")) {
                                "read" ->
                                    providerStore.read()?.let { entry ->
                                        protocolJson.encodeToJsonElement(
                                            ModelsStoreEntry.serializer(),
                                            entry,
                                        )
                                    }

                                "write" -> {
                                    val entry =
                                        message["entry"]?.let { value ->
                                            protocolJson.decodeFromJsonElement(
                                                ModelsStoreEntry.serializer(),
                                                value,
                                            )
                                        } ?: error("Extension provider store write is missing entry")
                                    providerStore.write(entry)
                                    null
                                }

                                "delete" -> {
                                    providerStore.delete()
                                    null
                                }

                                else -> error("Unknown extension provider store request: $message")
                            }
                        }
                    }
                writeProviderBridgeResponse(
                    type = "provider_store_response",
                    requestId = requestId,
                    result = result,
                )
                true
            }

            else -> false
        }

    private fun writeProviderBridgeResponse(
        type: String,
        requestId: String,
        result: Result<Any?>,
    ) {
        writePayload(
            buildJsonObject {
                put("type", type)
                put("requestId", requestId)
                result
                    .onSuccess { value ->
                        when (value) {
                            null -> put("value", JsonNull)
                            is Boolean -> put("value", value)
                            is Number -> put("value", value.toString())
                            is JsonElement -> put("value", value)
                            else -> put("value", value.toString())
                        }
                    }.onFailure { error ->
                        put("error", error.message ?: "Extension provider bridge request failed")
                    }
            },
        )
    }

    private fun writePayload(payload: JsonObject) {
        synchronized(outputLock) {
            output.println(protocolJson.encodeToString(JsonObject.serializer(), payload))
            output.flush()
        }
    }

    private fun readHostOutput() {
        try {
            while (true) {
                val line = input.readLine() ?: break
                val message =
                    try {
                        protocolJson.parseToJsonElement(line).jsonObject
                    } catch (error: Exception) {
                        responseQueue.put(
                            ExtensionHostRead.Failure(
                                IllegalStateException("Extension host returned invalid JSON: $line", error),
                            ),
                        )
                        continue
                    }
                when (message.string("type")) {
                    "background_actions" -> handleBackgroundActions(message)
                    "terminal_input_response" ->
                        message.string("requestId")
                            ?.let(pendingTerminalInputRequests::remove)
                            ?.complete(message)

                    "editor_component_response" ->
                        message.string("requestId")
                            ?.let(pendingEditorComponentRequests::remove)
                            ?.complete(message)

                    "autocomplete_response" ->
                        message.string("requestId")
                            ?.let(pendingAutocompleteRequests::remove)
                            ?.complete(message)

                    "autocomplete_base_request" -> handleAutocompleteBaseRequest(message)

                    else -> responseQueue.put(ExtensionHostRead.Message(message))
                }
            }
            responseQueue.put(
                ExtensionHostRead.Failure(
                    IllegalStateException(
                        buildString {
                            append("Extension host exited unexpectedly")
                            val stderr = synchronized(stderrLines) { stderrLines.joinToString("\n") }
                            if (stderr.isNotBlank()) {
                                append(": ")
                                append(stderr)
                            }
                        },
                    ),
                ),
            )
        } catch (error: Throwable) {
            responseQueue.offer(ExtensionHostRead.Failure(error))
        }
    }

    private fun readResponse(): JsonObject =
        when (val read = responseQueue.take()) {
            is ExtensionHostRead.Message -> read.value
            is ExtensionHostRead.Failure -> throw read.error
        }

    private fun handleAutocompleteBaseRequest(message: JsonObject) {
        val requestId = message.string("requestId") ?: return
        val parentRequestId = message.string("parentRequestId")
        val handler = parentRequestId?.let(autocompleteBaseHandlers::get)
        val result =
            if (handler == null) {
                Result.failure(IllegalStateException("Unknown autocomplete parent request"))
            } else {
                runCatching { handler(message) }
            }
        writePayload(
            buildJsonObject {
                put("type", "autocomplete_base_response")
                put("requestId", requestId)
                result
                    .onSuccess { value -> put("result", value) }
                    .onFailure { error ->
                        put("error", error.message ?: "Autocomplete base provider failed")
                    }
            },
        )
    }

    private fun parseRegistrations(value: JsonObject): ExtensionRegistrations {
        val extensions =
            value["extensions"]
                ?.jsonArray
                .orEmpty()
                .map { element ->
                    val item = element.jsonObject
                    val path = Path.of(requireNotNull(item.string("path"))).toAbsolutePath().normalize()
                    ExtensionRegistration(
                        path = path,
                        events = item.stringList("events").toSet(),
                        shortcuts =
                            item["shortcuts"]
                                ?.jsonArray
                                .orEmpty()
                                .mapNotNull { shortcutElement ->
                                    val shortcut = shortcutElement.jsonObject
                                    val id = shortcut.string("id") ?: return@mapNotNull null
                                    val key = shortcut.string("shortcut") ?: return@mapNotNull null
                                    ExtensionShortcutRegistration(
                                        id = id,
                                        shortcut = key,
                                        description = shortcut.string("description"),
                                        extensionPath = path,
                                    )
                                },
                        messageRenderers = parseRendererRegistrations(item, "messageRenderers", path),
                        entryRenderers = parseRendererRegistrations(item, "entryRenderers", path),
                        hasMarkdownTransformer =
                            item["hasMarkdownTransformer"]
                                ?.jsonPrimitive
                                ?.booleanOrNull
                                ?: false,
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
                        hasRenderCall = item["hasRenderCall"]?.jsonPrimitive?.booleanOrNull ?: false,
                        hasRenderResult = item["hasRenderResult"]?.jsonPrimitive?.booleanOrNull ?: false,
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
            autocompleteProviderCount =
                value["autocompleteProviderCount"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toIntOrNull()
                    ?: 0,
            markdownTransformerCount =
                value["markdownTransformerCount"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toIntOrNull()
                    ?: 0,
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

    private fun parseRendererRegistrations(
        item: JsonObject,
        name: String,
        path: Path,
    ): List<ExtensionRendererRegistration> =
        item[name]
            ?.jsonArray
            .orEmpty()
            .mapNotNull { element ->
                val renderer = element as? JsonObject ?: return@mapNotNull null
                ExtensionRendererRegistration(
                    id = renderer.string("id") ?: return@mapNotNull null,
                    customType = renderer.string("customType") ?: return@mapNotNull null,
                    extensionPath = path,
                )
            }

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
            onUiRequest: (JsonObject, (JsonObject) -> Unit) -> Unit = { _, respond ->
                respond(buildJsonObject { put("cancelled", true) })
            },
            onUiCancelled: (String) -> Unit = {},
            onUiControl: (JsonObject) -> Unit = {},
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
                    onUiRequest = onUiRequest,
                    onUiCancelled = onUiCancelled,
                    onUiControl = onUiControl,
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
                host.updateRegistrations(
                    host.parseRegistrations(
                        response["registrations"]?.jsonObject ?: JsonObject(emptyMap()),
                    ),
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
            val resourceRoot = "/works/earendil/pi/codingagent"
            val resources =
                listOf(
                    "extension-host.mjs",
                    "jiti/LICENSE",
                    "jiti/package.json",
                    "jiti/lib/jiti-static.mjs",
                    "jiti/dist/jiti.cjs",
                    "jiti/dist/babel.cjs",
                ).associateWith { relativePath ->
                    checkNotNull(
                        ExtensionHost::class.java.getResourceAsStream("$resourceRoot/$relativePath"),
                    ) {
                        "Bundled extension host resource is missing: $relativePath"
                    }.use { it.readAllBytes() }
                }
            val digest = MessageDigest.getInstance("SHA-256")
            resources.toSortedMap().forEach { (relativePath, bytes) ->
                digest.update(relativePath.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
                digest.update(bytes)
            }
            val hash =
                digest
                    .digest()
                    .joinToString("") { "%02x".format(it) }
                    .take(16)
            val directory =
                agentDir
                    .resolve("tmp")
                    .resolve("extension-host")
                    .resolve(hash)
            resources.forEach { (relativePath, bytes) ->
                val target = directory.resolve(relativePath)
                Files.createDirectories(target.parent)
                if (Files.exists(target) && Files.readAllBytes(target).contentEquals(bytes)) {
                    return@forEach
                }
                val temporary = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
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
            return directory.resolve("extension-host.mjs")
        }
    }
}

internal fun requireExtensionHostFinalResponse(
    response: JsonObject,
    expectedId: String,
): Boolean {
    val ok = response["ok"]?.jsonPrimitive?.booleanOrNull ?: return false
    check(response.string("id") == expectedId) {
        "Extension host response id mismatch: expected $expectedId, received ${response.string("id")}"
    }
    if (!ok) {
        error(response.string("error") ?: "Extension host request failed")
    }
    return true
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
    scopedModels: List<ScopedModel> = emptyList(),
    uiWidth: Int? = null,
    autocompleteMaxVisible: Int = 5,
    toolsExpanded: Boolean = false,
    themeRegistry: ThemeRegistry? = null,
): JsonObject =
    buildJsonObject {
        put("cwd", cwd.toString())
        put("mode", mode.wireName)
        put("hasUI", mode == ExtensionMode.TUI)
        put("projectTrusted", projectTrusted)
        model?.let { put("model", protocolJson.encodeToJsonElement(Model.serializer(), it)) }
        put(
            "scopedModels",
            JsonArray(
                scopedModels.map { scoped ->
                    buildJsonObject {
                        put("model", protocolJson.encodeToJsonElement(Model.serializer(), scoped.model))
                        scoped.thinkingLevel?.let { put("thinkingLevel", it.name.lowercase().replace('_', '-')) }
                    }
                },
            ),
        )
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
        uiWidth?.takeIf { it > 0 }?.let { put("uiWidth", it) }
        put("autocompleteMaxVisible", autocompleteMaxVisible.coerceIn(3, 20))
        put("toolsExpanded", toolsExpanded)
        themeRegistry?.extensionJson()?.forEach { (name, value) -> put(name, value) }
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
                            source = "cli",
                            scope = "temporary",
                            origin = "top-level",
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
private const val TERMINAL_INPUT_TIMEOUT_MS = 1_000L
private const val EDITOR_COMPONENT_TIMEOUT_MS = 2_000L
private const val AUTOCOMPLETE_TIMEOUT_MS = 15_000L
