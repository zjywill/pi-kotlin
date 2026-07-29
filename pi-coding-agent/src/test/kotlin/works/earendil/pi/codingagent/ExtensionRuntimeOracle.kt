package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.AuthType
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.InMemoryModelsStore
import works.earendil.pi.ai.InMemoryCredentialStore
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.ModelsRefreshOptions
import works.earendil.pi.ai.ModelsStoreEntry
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextDelta
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.ai.contentText

fun main(args: Array<String>) =
    runBlocking {
    val fixture =
        Path
            .of(args.firstOrNull() ?: "migration/fixtures/extension-runtime/basic.ts")
            .toAbsolutePath()
            .normalize()
    val root = Files.createTempDirectory("pi-extension-runtime-oracle")
    val agentDir = Files.createDirectories(root.resolve("agent"))
    val diagnostics = mutableListOf<ExtensionDiagnostic>()
    val dialogRequests = mutableListOf<JsonObject>()
    val context = oracleExtensionContext(fixture.parent)
    val host =
        checkNotNull(
            ExtensionHost.start(
                sources =
                    listOf(
                        ExtensionSource(
                            fixture,
                            ResourceSourceInfo(
                                path = fixture,
                                source = "local",
                                scope = "temporary",
                                origin = "top-level",
                                baseDir = fixture.parent,
                            ),
                        ),
                    ),
                agentDir = agentDir,
                cwd = fixture.parent,
                mode = ExtensionMode.PRINT,
                projectTrusted = true,
                flagValues = mapOf("loud" to true),
                context = context,
                onDiagnostic = diagnostics::add,
                onUiRequest = { request, respond ->
                    dialogRequests +=
                        buildJsonObject {
                            put("type", "ui_dialog")
                            request["method"]?.let { put("method", it) }
                            listOf("title", "options", "message", "placeholder", "prefill").forEach { name ->
                                request[name]?.let { put(name, it) }
                            }
                        }
                    respond(
                        when (request["method"]?.jsonPrimitive?.content) {
                            "select" -> buildJsonObject { put("value", "beta") }
                            "confirm" -> buildJsonObject { put("confirmed", true) }
                            "input" -> buildJsonObject { put("value", "Ada") }
                            "editor" -> buildJsonObject { put("value", "edited") }
                            else -> buildJsonObject { put("cancelled", true) }
                        },
                    )
                },
            ),
        )
    val credentials = InMemoryCredentialStore()
    val modelsStore = InMemoryModelsStore()
    val models =
        Models(
            providers = emptyList(),
            modelsStore = modelsStore,
            credentials = credentials,
            environment = { name ->
                if (name == "NATIVE_ACCOUNT") "oracle" else null
            },
            currentTimeMillis = { 1_000 },
        )
    val providerRegistry = ExtensionProviderRegistry(models, extensionHost = { host })
    try {
        val registration = host.registrations
        val toolRegistration = registration.tools.single { it.name == "extension_echo" }
        val toolInvocation =
            host.invokeTool(
                toolId = toolRegistration.id,
                toolCallId = "call-1",
                params =
                    buildJsonObject {
                        put("text", "hello")
                        put("suffix", "!")
                    },
                context = context,
            )
        val command =
            host.invokeCommand(
                name = "record",
                args = "checkpoint",
                context = context,
            )
        val dialogs =
            host.invokeCommand(
                name = "dialogs",
                args = "",
                context = context,
            )
        val dynamicRegistration = host.registrations
        val session =
            host.emit(
                event =
                    buildJsonObject {
                        put("type", "session_start")
                        put("reason", "startup")
                    },
                context = context,
            )
        val projectTrust =
            host.emit(
                event =
                    buildJsonObject {
                        put("type", "project_trust")
                        put("cwd", fixture.parent.toString())
                    },
                context = context,
            )
        val before =
            host.emit(
                event =
                    buildJsonObject {
                        put("type", "before_agent_start")
                        put("prompt", "hello")
                        put("systemPrompt", "base")
                        put("systemPromptOptions", buildJsonObject { put("cwd", fixture.parent.toString()) })
                    },
                context = JsonObject(context + ("systemPrompt" to JsonPrimitive("base"))),
            )
        val toolCall =
            host.emit(
                event =
                    buildJsonObject {
                        put("type", "tool_call")
                        put("toolName", "bash")
                        put("toolCallId", "call-2")
                        put("input", buildJsonObject { put("block", true) })
                    },
                context = context,
            )
        val toolResult =
            host.emit(
                event =
                    buildJsonObject {
                        put("type", "tool_result")
                        put("toolName", "extension_echo")
                        put("toolCallId", "call-1")
                        put("input", buildJsonObject { put("text", "hello") })
                        put(
                            "content",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", "hello")
                                    },
                                ),
                            ),
                        )
                        put("details", JsonObject(emptyMap()))
                        put("isError", false)
                    },
                context = context,
            )
        val resources =
            host.emit(
                event =
                    buildJsonObject {
                        put("type", "resources_discover")
                        put("cwd", fixture.parent.toString())
                        put("reason", "startup")
                    },
                context = context,
            )
        val bashUpdates = mutableListOf<String>()
        val userBash =
            host.emitUserBash(
                event =
                    buildJsonObject {
                        put("type", "user_bash")
                        put("command", "hostname")
                        put("excludeFromContext", false)
                        put("cwd", fixture.parent.toString())
                    },
                context = context,
                onOperationStart = {},
                onUpdate = bashUpdates::add,
            )
        val discoveredResources =
            discoverExtensionResources(
                host = host,
                cwd = fixture.parent,
                reason = "startup",
                context = context,
                onActions = {},
            )
        val composedResources =
            loadPromptResources(
                cwd = fixture.parent,
                agentDir = agentDir,
                projectTrusted = true,
                resolvedPackageResources = discoveredResources,
            )
        providerRegistry.apply(registration.providers) { error(it) }
        val nativeInitial = checkNotNull(models.getModel("native-provider", "native-initial"))
        val cachedNative = nativeInitial.copy(id = "cached", name = "Cached")
        val cachedDynamic =
            nativeInitial.copy(
                id = "cached",
                name = "Cached",
                api = "openai-completions",
                provider = "dynamic-provider",
                baseUrl = "https://dynamic.invalid/v1",
            )
        modelsStore.write(
            "native-provider",
            ModelsStoreEntry(
                models = listOf(cachedNative),
                checkedAt = 123,
            ),
        )
        val dynamicStored =
            ModelsStoreEntry(
                models = listOf(cachedDynamic),
                checkedAt = 123,
            )
        modelsStore.write("dynamic-provider", dynamicStored)
        val registeredModel = checkNotNull(models.getModel("fixture-provider", "fixture-model"))
        val providerConfig =
            registration.providers
                .single { it.getValue("name").jsonPrimitive.content == "fixture-provider" }
                .getValue("config")
                .jsonObject
        val originalModel = providerConfig.getValue("models").jsonArray.single().jsonObject
        val invalidConfig =
            buildJsonObject {
                put(
                    "models",
                    JsonArray(
                        listOf(
                            JsonObject(originalModel - "api" - "baseUrl"),
                        ),
                    ),
                )
            }
        val invalidProviderRejected =
            runCatching { providerRegistry.register("fixture-provider", invalidConfig) }.isFailure
        val callbackModel = checkNotNull(models.getModel("callback-provider", "callback-model"))
        val callbackStream =
            models.streamSimple(
                callbackModel,
                Context(messages = mutableListOf(UserMessage("hello", timestamp = 1))),
                SimpleStreamOptions(
                    stream =
                        StreamOptions(
                            apiKey = "callback-key",
                            sessionId = "oracle-session",
                        ),
                    reasoning = ThinkingLevel.HIGH,
                ),
            )
        val callbackEvents = callbackStream.events.toList()
        val callbackResult = callbackStream.result()
        val oauthActions = mutableListOf<JsonObject>()
        val loggedIn =
            models.login(
                "callback-provider",
                AuthType.OAUTH,
                object : AuthInteraction {
                    override suspend fun prompt(prompt: AuthPrompt): String {
                        oauthActions +=
                            buildJsonObject {
                                put("type", "prompt")
                                when (prompt) {
                                    is AuthPrompt.Text -> {
                                        put("method", "text")
                                        put("message", prompt.message)
                                        prompt.placeholder?.let { put("placeholder", it) }
                                    }

                                    is AuthPrompt.ManualCode -> {
                                        put("method", "manual_code")
                                        put("message", prompt.message)
                                        prompt.placeholder?.let { put("placeholder", it) }
                                    }

                                    is AuthPrompt.Select -> {
                                        put("method", "select")
                                        put("message", prompt.message)
                                        put(
                                            "options",
                                            JsonArray(
                                                prompt.options.map { option ->
                                                    buildJsonObject {
                                                        put("id", option.id)
                                                        put("label", option.label)
                                                    }
                                                },
                                            ),
                                        )
                                    }
                                }
                            }
                        return when (prompt) {
                            is AuthPrompt.Text -> "alice"
                            is AuthPrompt.ManualCode -> "manual"
                            is AuthPrompt.Select -> "team"
                        }
                    }

                    override fun notify(event: AuthEvent) {
                        oauthActions +=
                            buildJsonObject {
                                when (event) {
                                    is AuthEvent.AuthUrl -> {
                                        put("type", "auth_url")
                                        put("url", event.url)
                                        event.instructions?.let { put("instructions", it) }
                                    }

                                    is AuthEvent.DeviceCode -> {
                                        put("type", "device_code")
                                        put("userCode", event.userCode)
                                        put("verificationUri", event.verificationUri)
                                        event.intervalSeconds?.let { interval ->
                                            if (interval % 1.0 == 0.0) {
                                                put("intervalSeconds", interval.toInt())
                                            } else {
                                                put("intervalSeconds", interval)
                                            }
                                        }
                                        event.expiresInSeconds?.let { put("expiresInSeconds", it) }
                                    }

                                    is AuthEvent.Progress -> {
                                        put("type", "progress")
                                        put("message", event.message)
                                    }

                                    is AuthEvent.Info -> {
                                        put("type", "info")
                                        put("message", event.message)
                                    }
                                }
                            }
                    }
                },
            ) as OAuthCredential
        val callbackAuth = checkNotNull(models.getAuth("callback-provider"))
        val refreshed = checkNotNull(credentials.read("callback-provider")) as OAuthCredential
        val callbackRefresh = models.refresh(ModelsRefreshOptions(allowNetwork = false))
        check(callbackRefresh.errors.isEmpty())
        val callbackModelIds =
            models
                .getModels("callback-provider")
                .map(Model::id)
        check(models.checkAuth("native-provider") == null)
        models.login(
            "native-provider",
            AuthType.API_KEY,
            object : AuthInteraction {
                override suspend fun prompt(prompt: AuthPrompt): String = "native-key"

                override fun notify(event: AuthEvent) = Unit
            },
        )
        val nativeCheck = checkNotNull(models.checkAuth("native-provider"))
        val nativeAuth = checkNotNull(models.getAuth("native-provider"))
        val nativeRefresh =
            models.refresh(
                ModelsRefreshOptions(
                    allowNetwork = false,
                    force = true,
                ),
            )
        check(nativeRefresh.errors.isEmpty())
        val nativeModels = models.getModels("native-provider")
        val nativeFiltered = models.getAvailable("native-provider")
        val nativeModel = nativeModels.single()
        val nativeStream = models.stream(nativeModel, Context(), StreamOptions())
        val nativeStreamEvents = nativeStream.events.toList()
        val nativeStreamResult = nativeStream.result()
        val nativeSimpleStream = models.streamSimple(nativeModel, Context(), SimpleStreamOptions())
        nativeSimpleStream.events.toList()
        val nativeSimpleResult = nativeSimpleStream.result()
        val nativeWritten = checkNotNull(modelsStore.read("native-provider"))
        val dynamicModels = models.getModels("dynamic-provider")

        val output =
            buildJsonObject {
                put(
                    "errors",
                    JsonArray(
                        diagnostics.map { diagnostic ->
                            buildJsonObject {
                                put("path", diagnostic.extensionPath)
                                put("error", diagnostic.error)
                            }
                        },
                    ),
                )
                put(
                    "registrations",
                    buildJsonObject {
                        put(
                            "tools",
                            JsonArray(
                                registration.tools.map { tool ->
                                    buildJsonObject {
                                        put("name", tool.name)
                                        put("label", tool.label)
                                        put("description", tool.description)
                                        put("parameters", tool.parameters)
                                        if (tool.executionMode == null) {
                                            put("executionMode", JsonNull)
                                        } else {
                                            put("executionMode", tool.executionMode.name.lowercase())
                                        }
                                    }
                                },
                            ),
                        )
                        put(
                            "commands",
                            JsonArray(
                                registration.commands.map { commandRegistration ->
                                    buildJsonObject {
                                        put("name", commandRegistration.name)
                                        if (commandRegistration.description == null) {
                                            put("description", JsonNull)
                                        } else {
                                            put("description", commandRegistration.description)
                                        }
                                    }
                                },
                            ),
                        )
                        put(
                            "flags",
                            JsonArray(
                                registration.flags.map { flag ->
                                    buildJsonObject {
                                        put("name", flag.name)
                                        if (flag.description == null) {
                                            put("description", JsonNull)
                                        } else {
                                            put("description", flag.description)
                                        }
                                        put("type", flag.type)
                                        put("default", flag.defaultValue ?: JsonNull)
                                        put("value", true)
                                    }
                                },
                            ),
                        )
                        put(
                            "providers",
                            JsonArray(
                                registration.providers
                                    .sortedBy { provider ->
                                        provider.getValue("name").jsonPrimitive.content
                                    }.map { provider ->
                                    buildJsonObject {
                                        put("name", provider.getValue("name"))
                                        put(
                                            "config",
                                            JsonObject(
                                                provider
                                                    .getValue("config")
                                                    .jsonObject
                                                    .filterKeys { !it.startsWith("__pi") },
                                            ),
                                        )
                                    }
                                },
                            ),
                        )
                        put(
                            "events",
                            JsonArray(
                                registration.extensions
                                    .flatMap { it.events }
                                    .distinct()
                                    .sorted()
                                    .map(::JsonPrimitive),
                            ),
                        )
                    },
                )
                put(
                    "dynamicRegistrations",
                    buildJsonObject {
                        put("tools", JsonArray(dynamicRegistration.tools.map { JsonPrimitive(it.name) }))
                        put("commands", JsonArray(dynamicRegistration.commands.map { JsonPrimitive(it.name) }))
                        put("flags", JsonArray(dynamicRegistration.flags.map { JsonPrimitive(it.name) }))
                    },
                )
                put(
                    "tool",
                    buildJsonObject {
                        put("result", requireNotNull(toolInvocation.result))
                        put(
                            "updates",
                            JsonArray(
                                toolInvocation.actions
                                    .filter { it.type == "tool_update" }
                                    .mapNotNull { it.data["result"] },
                            ),
                        )
                    },
                )
                put("commandActions", normalizedActions(command.actions))
                put(
                    "dialogActions",
                    JsonArray(
                        normalizedActions(dialogs.actions).toList() +
                            dialogRequests,
                    ),
                )
                put("sessionActions", normalizedActions(session.actions))
                put("projectTrust", requireNotNull(projectTrust.result))
                put("beforeAgentStart", requireNotNull(before.result))
                put("toolCall", requireNotNull(toolCall.result))
                put("toolResult", requireNotNull(toolResult.result))
                put("resourcesDiscover", requireNotNull(resources.result))
                put(
                    "userBash",
                    buildJsonObject {
                        put("output", bashUpdates.joinToString(""))
                        val operationsResult =
                            requireNotNull(userBash.result)
                                .jsonObject
                                .getValue("operationsResult")
                                .jsonObject
                        put("exitCode", operationsResult.getValue("exitCode"))
                        put(
                            "cancelled",
                            operationsResult["cancelled"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                        )
                        put("truncated", false)
                    },
                )
                put(
                    "composedResources",
                    buildJsonObject {
                        put("skills", JsonArray(composedResources.skills.map { JsonPrimitive(it.name) }))
                        put("prompts", JsonArray(composedResources.promptTemplates.map { JsonPrimitive(it.name) }))
                        put(
                            "themes",
                            JsonArray(
                                composedResources.packageResources.themes.map {
                                    JsonPrimitive(it.path.fileName.toString())
                                },
                            ),
                        )
                    },
                )
                put(
                    "providerRuntime",
                    buildJsonObject {
                        put("model", providerModelJson(registeredModel))
                        put("invalidProviderRejected", invalidProviderRejected)
                        put("preservedModelId", registeredModel.id)
                    },
                )
                put(
                    "providerCallbacks",
                    buildJsonObject {
                        put(
                            "stream",
                            buildJsonObject {
                                put(
                                    "eventTypes",
                                    JsonArray(
                                        callbackEvents.map { event ->
                                            protocolJson
                                                .encodeToJsonElement(
                                                    works.earendil.pi.ai.AssistantMessageEvent.serializer(),
                                                    event,
                                                ).jsonObject
                                                .getValue("type")
                                        },
                                    ),
                                )
                                put(
                                    "deltas",
                                    JsonArray(
                                        callbackEvents
                                            .filterIsInstance<TextDelta>()
                                            .map { JsonPrimitive(it.delta) },
                                    ),
                                )
                                put("text", contentText(callbackResult.content))
                                put(
                                    "stopReason",
                                    protocolJson.encodeToJsonElement(
                                        works.earendil.pi.ai.StopReason.serializer(),
                                        callbackResult.stopReason,
                                    ),
                                )
                            },
                        )
                        put(
                            "oauth",
                            buildJsonObject {
                                put("actions", JsonArray(oauthActions))
                                put("loggedIn", oracleCredential(loggedIn))
                                put("refreshed", oracleCredential(refreshed))
                                put("apiKey", requireNotNull(callbackAuth.auth.apiKey))
                                put(
                                    "modelIds",
                                    JsonArray(callbackModelIds.map(::JsonPrimitive)),
                                )
                            },
                        )
                        put(
                            "native",
                            buildJsonObject {
                                put(
                                    "check",
                                    buildJsonObject {
                                        put("type", nativeCheck.type.name.lowercase())
                                    },
                                )
                                put(
                                    "auth",
                                    buildJsonObject {
                                        put(
                                            "auth",
                                            buildJsonObject {
                                                nativeAuth.auth.apiKey?.let { put("apiKey", it) }
                                                nativeAuth.auth.baseUrl?.let { put("baseUrl", it) }
                                            },
                                        )
                                        put(
                                            "env",
                                            JsonObject(
                                                nativeAuth.env.mapValues { (_, value) ->
                                                    JsonPrimitive(value)
                                                },
                                            ),
                                        )
                                        put("source", nativeAuth.source)
                                    },
                                )
                                put("modelIds", JsonArray(nativeModels.map { JsonPrimitive(it.id) }))
                                put(
                                    "filteredModelIds",
                                    JsonArray(nativeFiltered.map { JsonPrimitive(it.id) }),
                                )
                                put(
                                    "written",
                                    oracleStoreEntry(nativeWritten),
                                )
                                put(
                                    "streamEventTypes",
                                    JsonArray(
                                        nativeStreamEvents.map { event ->
                                            protocolJson
                                                .encodeToJsonElement(
                                                    works.earendil.pi.ai.AssistantMessageEvent.serializer(),
                                                    event,
                                                ).jsonObject
                                                .getValue("type")
                                        },
                                    ),
                                )
                                put("streamText", contentText(nativeStreamResult.content))
                                put("simpleText", contentText(nativeSimpleResult.content))
                            },
                        )
                        put(
                            "refreshModels",
                            buildJsonObject {
                                put(
                                    "modelIds",
                                    JsonArray(dynamicModels.map { JsonPrimitive(it.id) }),
                                )
                                put(
                                    "storeUnchanged",
                                    oracleStoreEntry(dynamicStored),
                                )
                            },
                        )
                    },
                )
            }
        println(protocolJson.encodeToString(JsonObject.serializer(), output))
    } finally {
        providerRegistry.reset()
        host.close()
        root.toFile().deleteRecursively()
    }
}

private fun oracleCredential(credential: OAuthCredential): JsonObject =
    buildJsonObject {
        put("access", credential.access)
        put("refresh", credential.refresh)
        put("expires", credential.expires)
        credential.extra["tenant"]?.let { put("tenant", it) }
    }

private fun providerModelJson(model: Model): JsonObject =
    buildJsonObject {
        put("id", model.id)
        put("name", model.name)
        put("api", model.api)
        put("provider", model.provider)
        put("baseUrl", model.baseUrl)
        put("reasoning", model.reasoning)
        put(
            "input",
            protocolJson.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(works.earendil.pi.ai.ModelInput.serializer()),
                model.input,
            ),
        )
        put(
            "cost",
            buildJsonObject {
                put("input", model.cost.input.toInt())
                put("output", model.cost.output.toInt())
                put("cacheRead", model.cost.cacheRead.toInt())
                put("cacheWrite", model.cost.cacheWrite.toInt())
            },
        )
        put("contextWindow", model.contextWindow)
        put("maxTokens", model.maxTokens)
    }

private fun oracleStoreEntry(entry: ModelsStoreEntry): JsonObject =
    buildJsonObject {
        put("models", JsonArray(entry.models.map(::providerModelJson)))
        entry.lastModified?.let { put("lastModified", it) }
        entry.checkedAt?.let { put("checkedAt", it) }
        entry.etag?.let { put("etag", it) }
    }

private fun oracleExtensionContext(cwd: Path): JsonObject =
    buildJsonObject {
        put("cwd", cwd.toString())
        put("mode", "print")
        put("hasUI", false)
        put("projectTrusted", true)
        put("thinkingLevel", "off")
        put("systemPrompt", "base")
        put("activeTools", JsonArray(listOf(JsonPrimitive("extension_echo"))))
        put("allTools", JsonArray(emptyList()))
        put("isIdle", true)
        put("hasPendingMessages", false)
        put("flags", buildJsonObject { put("loud", true) })
    }

private fun normalizedActions(actions: List<ExtensionAction>): JsonArray =
    JsonArray(
        actions.filterNot { it.type == "registrations_changed" }.map { action ->
            buildJsonObject {
                put("type", action.type)
                action.data
                    .filterKeys { it != "id" }
                    .forEach { (name, value) -> put(name, value) }
            }
        },
    )
