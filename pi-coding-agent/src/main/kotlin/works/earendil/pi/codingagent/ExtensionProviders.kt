package works.earendil.pi.codingagent

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.AssistantError
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.AssistantMessageEvent
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.AssistantStart
import works.earendil.pi.ai.AuthResult
import works.earendil.pi.ai.CacheRetention
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.Credential
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelAuth
import works.earendil.pi.ai.ModelCost
import works.earendil.pi.ai.ModelCostTier
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.ModelThinkingLevel
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.OAuthAuth
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.RefreshModelsContext
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextDelta
import works.earendil.pi.ai.TextEnd
import works.earendil.pi.ai.TextStart
import works.earendil.pi.ai.ThinkingDelta
import works.earendil.pi.ai.ThinkingEnd
import works.earendil.pi.ai.ThinkingStart
import works.earendil.pi.ai.ToolCallEnd
import works.earendil.pi.ai.ToolCallStart
import works.earendil.pi.ai.ToolCallDelta
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.ThinkingBudgets
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.ai.Transport
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.createAssistantMessageEventStream
import works.earendil.pi.ai.providers.protocolProvider

internal class ExtensionProviderRegistry(
    private val models: Models,
    private val environment: Map<String, String> = System.getenv(),
    private val extensionHost: () -> ExtensionHost? = { null },
) {
    private val baseProviders = mutableMapOf<String, Provider?>()
    private val configurations = mutableMapOf<String, JsonObject>()
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeOperations = ConcurrentHashMap<String, ExtensionHost>()

    fun apply(
        registrations: List<JsonObject>,
        onError: (String) -> Unit,
    ) {
        registrations.forEach { registration ->
            val name = registration.stringValue("name")
            val config = registration["config"] as? JsonObject
            if (name == null || config == null) {
                onError("Extension provider registration is missing a name or serializable config")
                return@forEach
            }
            runCatching { register(name, config) }
                .onFailure { error -> onError(error.message ?: "Failed to register provider $name") }
        }
    }

    fun register(
        name: String,
        incoming: JsonObject,
    ) {
        require(name.isNotBlank()) { "Provider id must not be empty" }
        if (name !in baseProviders) {
            baseProviders[name] = models.getProvider(name)
        }
        validateIncomingProvider(name, baseProviders[name], incoming)
        val effective = JsonObject(configurations[name].orEmpty() + incoming)
        val provider =
            composeProvider(
                providerId = name,
                base = baseProviders[name],
                config = effective,
                configuredEnvironment = environment,
                extensionHost = extensionHost,
                callbackScope = callbackScope,
                activeOperations = activeOperations,
            )
        configurations[name] = effective
        models.setProvider(provider)
    }

    fun unregister(name: String) {
        if (name !in baseProviders) {
            return
        }
        configurations.remove(name)
        val base = baseProviders.remove(name)
        if (base == null) {
            models.deleteProvider(name)
        } else {
            models.setProvider(base)
        }
    }

    fun reset() {
        activeOperations.forEach { (id, host) -> host.abortProviderOperation(id) }
        activeOperations.clear()
        callbackScope.coroutineContext.cancelChildren()
        configurations.keys.toList().forEach(::unregister)
        baseProviders.clear()
    }
}

private fun validateIncomingProvider(
    providerId: String,
    base: Provider?,
    config: JsonObject,
) {
    val callbacks = config[PROVIDER_CALLBACKS_KEY] as? JsonObject
    if (callbacks?.booleanValue("streamSimple") == true && config.stringValue("api") == null) {
        error("Provider $providerId: \"api\" is required when registering streamSimple")
    }
    val oauthCallbacks = callbacks?.get("oauth") as? JsonObject
    if (oauthCallbacks != null) {
        require(
            oauthCallbacks.booleanValue("login") &&
                oauthCallbacks.booleanValue("refreshToken") &&
                oauthCallbacks.booleanValue("getApiKey"),
        ) {
            "Provider $providerId: OAuth requires login, refreshToken, and getApiKey callbacks"
        }
    }
    val configuredModels = config["models"] ?: return
    if (configuredModels is JsonNull) {
        return
    }
    configuredModels.jsonArray.forEach { element ->
        parseExtensionModel(
            providerId = providerId,
            value = element.jsonObject,
            configApi = config.stringValue("api"),
            configBaseUrl = config.stringValue("baseUrl"),
            defaults = base?.getModels().orEmpty(),
        )
    }
}

private fun composeProvider(
    providerId: String,
    base: Provider?,
    config: JsonObject,
    configuredEnvironment: Map<String, String>,
    extensionHost: () -> ExtensionHost?,
    callbackScope: CoroutineScope,
    activeOperations: ConcurrentHashMap<String, ExtensionHost>,
): Provider {
    val configuredName = config.stringValue("name")
    val configuredBaseUrl = config.stringValue("baseUrl")
    val configuredApi = config.stringValue("api")
    val apiKey = config.stringValue("apiKey")
    val configuredHeaderValues = config.stringMap("headers")
    val authHeader = config["authHeader"]?.jsonPrimitive?.booleanOrNull ?: false
    val callbackToken = config.stringValue(PROVIDER_CALLBACK_TOKEN_KEY)
    val callbackCapabilities = config[PROVIDER_CALLBACKS_KEY] as? JsonObject
    val hasStreamCallback = callbackCapabilities?.booleanValue("streamSimple") == true
    val oauthCallbacks = callbackCapabilities?.get("oauth") as? JsonObject
    val oauthConfig = config["oauth"] as? JsonObject
    if ((hasStreamCallback || oauthCallbacks != null) && callbackToken == null) {
        error("Provider $providerId: extension callback metadata is missing")
    }
    val extensionOAuth =
        if (oauthCallbacks != null) {
            ExtensionProviderOAuth(
                providerId = providerId,
                name = oauthConfig?.stringValue("name") ?: configuredName ?: providerId,
                callbackToken = requireNotNull(callbackToken),
                configuredHeaders = configuredHeaderValues,
                authHeader = authHeader,
                extensionHost = extensionHost,
                callbackScope = callbackScope,
                activeOperations = activeOperations,
            )
        } else {
            null
        }
    if (
        config["oauth"] != null &&
        config["oauth"] !is JsonNull &&
        extensionOAuth == null &&
        base?.oauth == null
    ) {
        error("Provider $providerId: function-based OAuth registrations are not serializable")
    }
    if (authHeader && apiKey == null && base == null && extensionOAuth == null) {
        error("Provider $providerId: authHeader requires apiKey or an existing provider")
    }

    val baseModels = base?.getModels().orEmpty()
    val configuredModels = config["models"]
    val providerModels =
        if (configuredModels == null || configuredModels is JsonNull) {
            require(baseModels.isNotEmpty()) {
                "Provider $providerId: models are required for a new provider"
            }
            baseModels.map { model ->
                if (configuredBaseUrl == null) model else model.copy(baseUrl = configuredBaseUrl)
            }
        } else {
            configuredModels.jsonArray.map { element ->
                parseExtensionModel(
                    providerId = providerId,
                    value = element.jsonObject,
                    configApi = configuredApi,
                    configBaseUrl = configuredBaseUrl,
                    defaults = baseModels,
                )
            }
        }
    require(providerModels.isNotEmpty()) { "Provider $providerId: models must not be empty" }

    val name = configuredName ?: base?.name ?: providerId
    val baseApis = baseModels.mapTo(mutableSetOf(), Model::api)
    val fallbackModels =
        providerModels.filterNot { model ->
            model.api in baseApis || (hasStreamCallback && model.api == configuredApi)
        }
    val fallback =
        fallbackModels
            .takeIf(List<Model>::isNotEmpty)
            ?.let {
                protocolProvider(
                    id = providerId,
                    name = name,
                    models = it,
                    apiKeyEnvNames = emptyList(),
                )
            }
    val projectedModels = AtomicReference<List<Model>?>(null)
    val supportsModelProjection = oauthCallbacks?.booleanValue("modifyModels") == true
    return object : Provider {
        override val id: String = providerId
        override val name: String = name
        override val baseUrl: String? = configuredBaseUrl ?: base?.baseUrl ?: providerModels.firstOrNull()?.baseUrl
        override val headers: Map<String, String?> = base?.headers.orEmpty()
        override val oauth: OAuthAuth? = extensionOAuth ?: base?.oauth

        override fun resolveAmbientAuth(environment: (String) -> String?): AuthResult? {
            if (extensionOAuth != null && apiKey == null && base == null) {
                return null
            }
            val inherited = base?.resolveAmbientAuth(environment)
            val resolvedKey =
                apiKey?.let { value ->
                    resolveConfigValue(value) { key -> environment(key) ?: configuredEnvironment[key] }
                }
                    ?: inherited?.auth?.apiKey
            val resolvedHeaders =
                inherited?.auth?.headers.orEmpty() +
                    configuredHeaderValues.mapValues { (_, value) ->
                        resolveConfigValue(value) { key -> environment(key) ?: configuredEnvironment[key] }
                    }
            if (apiKey == null && configuredHeaderValues.isEmpty() && !authHeader) {
                return inherited
            }
            val authHeaders =
                if (authHeader) {
                    require(!resolvedKey.isNullOrBlank()) {
                        "Provider $providerId: authHeader requires a resolved API key"
                    }
                    resolvedHeaders + ("Authorization" to "Bearer $resolvedKey")
                } else {
                    resolvedHeaders
                }
            return AuthResult(
                auth =
                    ModelAuth(
                        apiKey = resolvedKey,
                        headers = authHeaders,
                        baseUrl = inherited?.auth?.baseUrl,
                    ),
                source = "Extension provider",
                env = inherited?.env.orEmpty(),
            )
        }

        override fun getModels(): List<Model> = projectedModels.get() ?: providerModels

        override fun filterModels(
            models: List<Model>,
            credential: Credential?,
        ): List<Model> = base?.filterModels(models, credential) ?: models

        override val supportsModelRefresh: Boolean
            get() = supportsModelProjection || base?.supportsModelRefresh == true

        override suspend fun refreshModels(context: RefreshModelsContext) {
            if (base?.supportsModelRefresh == true) {
                base.refreshModels(context)
            }
            if (!supportsModelProjection) {
                return
            }
            val credential = context.credential as? OAuthCredential
            if (credential == null) {
                projectedModels.set(null)
                return
            }
            val result =
                invokeExtensionProviderCallback(
                    callbackToken = requireNotNull(callbackToken),
                    method = "oauth_modify_models",
                    arguments =
                        buildJsonObject {
                            put(
                                "models",
                                JsonArray(
                                    providerModels.map {
                                        extensionProviderJson.encodeToJsonElement(Model.serializer(), it)
                                    },
                                ),
                            )
                            put("credential", credential.toExtensionJson())
                        },
                    extensionHost = extensionHost,
                    callbackScope = callbackScope,
                    activeOperations = activeOperations,
                )
            val updated =
                result
                    ?.jsonArray
                    ?.map { element ->
                        parseExtensionModel(
                            providerId = providerId,
                            value = element.jsonObject,
                            configApi = configuredApi,
                            configBaseUrl = configuredBaseUrl,
                            defaults = providerModels,
                        )
                    }
                    ?: error("Provider $providerId oauth.modifyModels must return an array")
            projectedModels.set(updated)
        }

        override suspend fun stream(
            model: Model,
            context: Context,
            options: StreamOptions,
        ): AssistantMessageEventStream =
            if (hasStreamCallback && model.api == configuredApi) {
                extensionProviderStream(
                    providerId = providerId,
                    callbackToken = requireNotNull(callbackToken),
                    model = model,
                    context = context,
                    options = extensionStreamOptions(options),
                    extensionHost = extensionHost,
                    callbackScope = callbackScope,
                    activeOperations = activeOperations,
                )
            } else {
                delegate(model).stream(model, context, options)
            }

        override suspend fun streamSimple(
            model: Model,
            context: Context,
            options: SimpleStreamOptions,
        ): AssistantMessageEventStream =
            if (hasStreamCallback && model.api == configuredApi) {
                extensionProviderStream(
                    providerId = providerId,
                    callbackToken = requireNotNull(callbackToken),
                    model = model,
                    context = context,
                    options = extensionStreamOptions(options),
                    extensionHost = extensionHost,
                    callbackScope = callbackScope,
                    activeOperations = activeOperations,
                )
            } else {
                delegate(model).streamSimple(model, context, options)
            }

        private fun delegate(model: Model): Provider =
            when {
                base != null && model.api in baseApis -> base
                fallback != null -> fallback
                else -> error("Provider $providerId does not support API ${model.api}")
            }
    }
}

private class ExtensionProviderOAuth(
    private val providerId: String,
    override val name: String,
    private val callbackToken: String,
    private val configuredHeaders: Map<String, String>,
    private val authHeader: Boolean,
    private val extensionHost: () -> ExtensionHost?,
    private val callbackScope: CoroutineScope,
    private val activeOperations: ConcurrentHashMap<String, ExtensionHost>,
) : OAuthAuth {
    override suspend fun login(interaction: works.earendil.pi.ai.AuthInteraction): OAuthCredential =
        invokeExtensionProviderCallback(
            callbackToken = callbackToken,
            method = "oauth_login",
            interaction = interaction,
            extensionHost = extensionHost,
            callbackScope = callbackScope,
            activeOperations = activeOperations,
        )?.jsonObject?.toExtensionOAuthCredential()
            ?: error("Provider $providerId oauth.login returned no credentials")

    override suspend fun refresh(credential: OAuthCredential): OAuthCredential =
        invokeExtensionProviderCallback(
            callbackToken = callbackToken,
            method = "oauth_refresh",
            arguments =
                buildJsonObject {
                    put("credential", credential.toExtensionJson())
                },
            extensionHost = extensionHost,
            callbackScope = callbackScope,
            activeOperations = activeOperations,
        )?.jsonObject?.toExtensionOAuthCredential()
            ?: error("Provider $providerId oauth.refreshToken returned no credentials")

    override suspend fun toAuth(credential: OAuthCredential): ModelAuth {
        val apiKey =
            invokeExtensionProviderCallback(
                callbackToken = callbackToken,
                method = "oauth_get_api_key",
                arguments =
                    buildJsonObject {
                        put("credential", credential.toExtensionJson())
                    },
                extensionHost = extensionHost,
                callbackScope = callbackScope,
                activeOperations = activeOperations,
            )?.jsonPrimitive?.contentOrNull
                ?: error("Provider $providerId oauth.getApiKey returned no API key")
        val credentialEnvironment =
            (credential.extra["env"] as? JsonObject)
                ?.mapValues { (_, value) -> value.jsonPrimitive.content }
                .orEmpty()
        var headers =
            configuredHeaders.mapValues { (_, value) ->
                resolveConfigValue(value, credentialEnvironment)
            }
        if (authHeader) {
            headers = headers + ("Authorization" to "Bearer $apiKey")
        }
        return ModelAuth(
            apiKey = apiKey,
            headers = headers,
        )
    }
}

private suspend fun invokeExtensionProviderCallback(
    callbackToken: String,
    method: String,
    arguments: JsonObject = JsonObject(emptyMap()),
    interaction: works.earendil.pi.ai.AuthInteraction? = null,
    extensionHost: () -> ExtensionHost?,
    callbackScope: CoroutineScope,
    activeOperations: ConcurrentHashMap<String, ExtensionHost>,
): JsonElement? {
    val host = extensionHost() ?: error("Extension provider host is unavailable")
    return suspendCancellableCoroutine { continuation ->
        val operationId = AtomicReference<String?>()
        continuation.invokeOnCancellation {
            operationId.get()?.let(host::abortProviderOperation)
        }
        callbackScope.launch {
            try {
                val result =
                    host.invokeProviderCallback(
                        callbackToken = callbackToken,
                        method = method,
                        arguments = arguments,
                        interaction = interaction,
                        onOperationStart = { id ->
                            operationId.set(id)
                            activeOperations[id] = host
                            if (!continuation.isActive) {
                                host.abortProviderOperation(id)
                            }
                        },
                    )
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            } finally {
                operationId.get()?.let(activeOperations::remove)
            }
        }
    }
}

private suspend fun extensionProviderStream(
    providerId: String,
    callbackToken: String,
    model: Model,
    context: Context,
    options: JsonObject,
    extensionHost: () -> ExtensionHost?,
    callbackScope: CoroutineScope,
    activeOperations: ConcurrentHashMap<String, ExtensionHost>,
): AssistantMessageEventStream {
    val host = extensionHost() ?: error("Extension provider host is unavailable")
    val stream = createAssistantMessageEventStream()
    val initial =
        AssistantMessage(
            content = emptyList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            usage = Usage(),
            stopReason = StopReason.PENDING,
        )
    val lastMessage = AtomicReference(initial)
    val terminal = AtomicBoolean(false)
    val operationId = AtomicReference<String?>()
    val callerJob = currentCoroutineContext()[Job]
    val cancellationHandle =
        callerJob?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                operationId.get()?.let(host::abortProviderOperation)
            }
        }

    fun fail(
        reason: StopReason,
        message: String,
    ) {
        if (terminal.compareAndSet(false, true)) {
            stream.push(
                AssistantError(
                    reason,
                    lastMessage.get().copy(
                        stopReason = reason,
                        errorMessage = message,
                    ),
                ),
            )
        }
    }

    callbackScope.launch {
        try {
            val response =
                host.streamProvider(
                    callbackToken = callbackToken,
                    model = extensionProviderJson.encodeToJsonElement(Model.serializer(), model).jsonObject,
                    context = context.toExtensionJson(),
                    options = options,
                    onOperationStart = { id ->
                        operationId.set(id)
                        activeOperations[id] = host
                        if (callerJob?.isCancelled == true) {
                            host.abortProviderOperation(id)
                        }
                    },
                    onEvent = { value ->
                        val event =
                            extensionProviderJson.decodeFromJsonElement(
                                AssistantMessageEvent.serializer(),
                                value,
                            )
                        event.partialMessage()?.let(lastMessage::set)
                        if (event is AssistantDone || event is AssistantError) {
                            terminal.set(true)
                        }
                        stream.push(event)
                    },
                )
            if (!terminal.get()) {
                val cancelled =
                    response["result"]
                        ?.jsonObject
                        ?.get("cancelled")
                        ?.jsonPrimitive
                        ?.booleanOrNull == true
                fail(
                    reason = if (cancelled) StopReason.ABORTED else StopReason.ERROR,
                    message =
                        if (cancelled) {
                            "Extension provider stream aborted"
                        } else {
                            "Extension provider stream ended without a terminal event"
                        },
                )
            }
        } catch (error: CancellationException) {
            operationId.get()?.let(host::abortProviderOperation)
            fail(StopReason.ABORTED, error.message ?: "Extension provider stream aborted")
        } catch (error: Throwable) {
            fail(
                StopReason.ERROR,
                error.message ?: "Extension provider stream failed",
            )
        } finally {
            operationId.get()?.let(activeOperations::remove)
            cancellationHandle?.dispose()
        }
    }
    return stream
}

private fun AssistantMessageEvent.partialMessage(): AssistantMessage? =
    when (this) {
        is AssistantStart -> partial
        is TextStart -> partial
        is TextDelta -> partial
        is TextEnd -> partial
        is ThinkingStart -> partial
        is ThinkingDelta -> partial
        is ThinkingEnd -> partial
        is ToolCallStart -> partial
        is ToolCallDelta -> partial
        is ToolCallEnd -> partial
        is AssistantDone -> message
        is AssistantError -> error
    }

private fun Context.toExtensionJson(): JsonObject =
    buildJsonObject {
        systemPrompt?.let { put("systemPrompt", it) }
        put(
            "messages",
            JsonArray(
                messages.map { message ->
                    extensionProviderJson.encodeToJsonElement(Message.serializer(), message)
                },
            ),
        )
        if (tools.isNotEmpty()) {
            put(
                "tools",
                JsonArray(
                    tools.map { tool ->
                        extensionProviderJson.encodeToJsonElement(ToolDefinition.serializer(), tool)
                    },
                ),
            )
        }
    }

private fun extensionStreamOptions(options: SimpleStreamOptions): JsonObject =
    JsonObject(
        extensionStreamOptions(options.stream) +
            buildMap {
                options.reasoning?.let {
                    put(
                        "reasoning",
                        extensionProviderJson.encodeToJsonElement(ThinkingLevel.serializer(), it),
                    )
                }
                options.thinkingBudgets?.let {
                    put(
                        "thinkingBudgets",
                        extensionProviderJson.encodeToJsonElement(ThinkingBudgets.serializer(), it),
                    )
                }
            },
    )

private fun extensionStreamOptions(options: StreamOptions): JsonObject =
    buildJsonObject {
        options.temperature?.let { put("temperature", it) }
        options.maxTokens?.let { put("maxTokens", it) }
        options.apiKey?.let { put("apiKey", it) }
        if (options.transport != Transport.AUTO) {
            put(
                "transport",
                extensionProviderJson.encodeToJsonElement(Transport.serializer(), options.transport),
            )
        }
        options.cacheRetention?.let {
            put(
                "cacheRetention",
                extensionProviderJson.encodeToJsonElement(CacheRetention.serializer(), it),
            )
        }
        options.sessionId?.let { put("sessionId", it) }
        if (options.headers.isNotEmpty()) {
            put(
                "headers",
                JsonObject(
                    options.headers.mapValues { (_, value) ->
                        value?.let(::JsonPrimitive) ?: JsonNull
                    },
                ),
            )
        }
        options.timeoutMs?.let { put("timeoutMs", it) }
        options.websocketConnectTimeoutMs?.let { put("websocketConnectTimeoutMs", it) }
        options.maxRetries?.let { put("maxRetries", it) }
        options.maxRetryDelayMs?.let { put("maxRetryDelayMs", it) }
        options.metadata?.let { put("metadata", it) }
        if (options.env.isNotEmpty()) {
            put(
                "env",
                JsonObject(options.env.mapValues { (_, value) -> JsonPrimitive(value) }),
            )
        }
        options.reasoning?.let {
            put(
                "reasoning",
                extensionProviderJson.encodeToJsonElement(ThinkingLevel.serializer(), it),
            )
        }
        options.reasoningEffort?.let { put("reasoningEffort", it) }
        options.reasoningSummary?.let { put("reasoningSummary", it) }
        options.serviceTier?.let { put("serviceTier", it) }
        options.textVerbosity?.let { put("textVerbosity", it) }
        options.promptMode?.let { put("promptMode", it) }
        options.toolChoice?.let { put("toolChoice", it) }
        options.thinkingBudgets?.let {
            put(
                "thinkingBudgets",
                extensionProviderJson.encodeToJsonElement(ThinkingBudgets.serializer(), it),
            )
        }
        options.azureApiVersion?.let { put("azureApiVersion", it) }
        options.azureResourceName?.let { put("azureResourceName", it) }
        options.azureBaseUrl?.let { put("azureBaseUrl", it) }
        options.azureDeploymentName?.let { put("azureDeploymentName", it) }
        options.project?.let { put("project", it) }
        options.location?.let { put("location", it) }
        options.region?.let { put("region", it) }
        options.profile?.let { put("profile", it) }
        options.interleavedThinking?.let { put("interleavedThinking", it) }
        options.requestMetadata?.let {
            put(
                "requestMetadata",
                JsonObject(it.mapValues { (_, value) -> JsonPrimitive(value) }),
            )
        }
        options.bearerToken?.let { put("bearerToken", it) }
        if (options.debug) {
            put("debug", true)
        }
    }

private fun OAuthCredential.toExtensionJson(): JsonObject =
    buildJsonObject {
        extra.forEach { (name, value) -> put(name, value) }
        put("type", "oauth")
        put("access", access)
        put("refresh", refresh)
        put("expires", expires)
        scope?.let { put("scope", it) }
        accountId?.let { put("accountId", it) }
        enterpriseUrl?.let { put("enterpriseUrl", it) }
        availableModelIds?.let { values ->
            put("availableModelIds", JsonArray(values.map(::JsonPrimitive)))
        }
        gatewayConfig?.let { put("gatewayConfig", it) }
    }

private fun JsonObject.toExtensionOAuthCredential(): OAuthCredential {
    val reserved =
        setOf(
            "type",
            "access",
            "refresh",
            "expires",
            "scope",
            "accountId",
            "enterpriseUrl",
            "availableModelIds",
            "gatewayConfig",
        )
    return OAuthCredential(
        access = stringValue("access") ?: error("Extension OAuth credentials are missing access"),
        refresh = stringValue("refresh") ?: error("Extension OAuth credentials are missing refresh"),
        expires =
            this["expires"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: error("Extension OAuth credentials are missing expires"),
        scope = stringValue("scope"),
        accountId = stringValue("accountId"),
        enterpriseUrl = stringValue("enterpriseUrl"),
        availableModelIds =
            (this["availableModelIds"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull },
        gatewayConfig = this["gatewayConfig"] as? JsonObject,
        extra = JsonObject(filterKeys { it !in reserved }),
    )
}

private fun parseExtensionModel(
    providerId: String,
    value: JsonObject,
    configApi: String?,
    configBaseUrl: String?,
    defaults: List<Model>,
): Model {
    val id = value.stringValue("id")?.takeIf(String::isNotBlank)
        ?: error("Provider $providerId: model id is required")
    val fallback = defaults.firstOrNull { it.id == id } ?: defaults.firstOrNull()
    val api = value.stringValue("api") ?: configApi ?: fallback?.api
        ?: error("Provider $providerId, model $id: no api specified")
    val baseUrl = value.stringValue("baseUrl") ?: configBaseUrl ?: fallback?.baseUrl
        ?: error("Provider $providerId, model $id: no baseUrl specified")
    val contextWindow = value.intValue("contextWindow") ?: fallback?.contextWindow ?: 128_000
    val maxTokens = value.intValue("maxTokens") ?: fallback?.maxTokens ?: 16_384
    return Model(
        id = id,
        name = value.stringValue("name") ?: fallback?.name ?: id,
        api = api,
        provider = providerId,
        baseUrl = baseUrl,
        reasoning = value["reasoning"]?.jsonPrimitive?.booleanOrNull ?: fallback?.reasoning ?: false,
        thinkingLevelMap = value.thinkingLevelMap() ?: fallback?.thinkingLevelMap.orEmpty(),
        input = value.modelInputs() ?: fallback?.input ?: listOf(ModelInput.TEXT),
        cost = value.modelCost() ?: fallback?.cost ?: ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = contextWindow,
        maxTokens = maxTokens,
        headers = fallback?.headers.orEmpty() + value.stringMap("headers"),
        compat = value["compat"] as? JsonObject ?: fallback?.compat,
    )
}

private fun JsonObject.intValue(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.booleanValue(name: String): Boolean =
    this[name]?.jsonPrimitive?.booleanOrNull == true

private fun JsonObject.modelInputs(): List<ModelInput>? =
    (this["input"] as? JsonArray)?.map { input ->
        when (input.jsonPrimitive.content) {
            "text" -> ModelInput.TEXT
            "image" -> ModelInput.IMAGE
            else -> error("Unsupported model input: ${input.jsonPrimitive.content}")
        }
    }

private fun JsonObject.thinkingLevelMap(): Map<ModelThinkingLevel, String?>? =
    (this["thinkingLevelMap"] as? JsonObject)?.mapKeys { (name, _) ->
        when (name) {
            "off" -> ModelThinkingLevel.OFF
            "minimal" -> ModelThinkingLevel.MINIMAL
            "low" -> ModelThinkingLevel.LOW
            "medium" -> ModelThinkingLevel.MEDIUM
            "high" -> ModelThinkingLevel.HIGH
            "xhigh" -> ModelThinkingLevel.XHIGH
            "max" -> ModelThinkingLevel.MAX
            else -> error("Unsupported thinking level: $name")
        }
    }?.mapValues { (_, value) ->
        if (value is JsonNull) null else value.jsonPrimitive.content
    }

private fun JsonObject.modelCost(): ModelCost? {
    val cost = this["cost"] as? JsonObject ?: return null
    return ModelCost(
        input = cost.doubleValue("input"),
        output = cost.doubleValue("output"),
        cacheRead = cost.doubleValue("cacheRead"),
        cacheWrite = cost.doubleValue("cacheWrite"),
        tiers =
            (cost["tiers"] as? JsonArray).orEmpty().map { tier ->
                val item = tier.jsonObject
                ModelCostTier(
                    input = item.doubleValue("input"),
                    output = item.doubleValue("output"),
                    cacheRead = item.doubleValue("cacheRead"),
                    cacheWrite = item.doubleValue("cacheWrite"),
                    inputTokensAbove =
                        item["inputTokensAbove"]?.jsonPrimitive?.intOrNull
                            ?: error("Model cost tier inputTokensAbove is required"),
                )
            },
    )
}

private fun JsonObject.doubleValue(name: String): Double =
    this[name]?.jsonPrimitive?.doubleOrNull ?: 0.0

private fun JsonObject.stringMap(name: String): Map<String, String> =
    (this[name] as? JsonObject)
        ?.mapValues { (_, value) -> value.jsonPrimitive.content }
        .orEmpty()

private fun resolveConfigValue(
    value: String,
    environment: Map<String, String>,
): String = resolveConfigValue(value, environment::get)

private fun resolveConfigValue(
    value: String,
    environment: (String) -> String?,
): String {
    val trimmed = value.trim()
    val envName =
        when {
            trimmed.startsWith("\${") && trimmed.endsWith("}") -> trimmed.substring(2, trimmed.length - 1)
            trimmed.startsWith("\$") -> trimmed.drop(1)
            else -> null
        }
    if (envName != null) {
        return environment(envName)
            ?: error("Environment variable $envName is not set")
    }
    if (trimmed.startsWith("!")) {
        val command = trimmed.drop(1).trim()
        require(command.isNotEmpty()) { "Configured command must not be empty" }
        val shell =
            if (System.getProperty("os.name").lowercase().contains("win")) {
                listOf("cmd.exe", "/c", command)
            } else {
                listOf(System.getenv("SHELL") ?: "/bin/zsh", "-lc", command)
            }
        val process = ProcessBuilder(shell).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8).trim()
        check(process.waitFor() == 0) { "Configured command failed: $output" }
        return output
    }
    return value
}

private val extensionProviderJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

private const val PROVIDER_CALLBACK_TOKEN_KEY = "__piCallbackToken"
private const val PROVIDER_CALLBACKS_KEY = "__piCallbacks"
