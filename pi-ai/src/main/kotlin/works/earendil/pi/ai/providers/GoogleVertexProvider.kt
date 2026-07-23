package works.earendil.pi.ai.providers

import com.google.auth.oauth2.GoogleCredentials
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.AssistantError
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.AssistantMessageEventStream
import works.earendil.pi.ai.AssistantStart
import works.earendil.pi.ai.BashExecutionMessage
import works.earendil.pi.ai.BranchSummaryMessage
import works.earendil.pi.ai.CompactionSummaryMessage
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.CustomMessage
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelInput
import works.earendil.pi.ai.ModelThinkingLevel
import works.earendil.pi.ai.Provider
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.TextDelta
import works.earendil.pi.ai.TextEnd
import works.earendil.pi.ai.TextStart
import works.earendil.pi.ai.ThinkingContent
import works.earendil.pi.ai.ThinkingDelta
import works.earendil.pi.ai.ThinkingEnd
import works.earendil.pi.ai.ThinkingStart
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolCallDelta
import works.earendil.pi.ai.ToolCallEnd
import works.earendil.pi.ai.ToolCallStart
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.Usage
import works.earendil.pi.ai.contentText
import works.earendil.pi.ai.createAssistantMessageEventStream
import works.earendil.pi.ai.http.postSse

internal data class GoogleVertexRequest(
    val url: String,
    val headers: Map<String, String>,
)

class GoogleVertexProvider(
    override val id: String,
    override val name: String,
    private val models: List<Model>,
    private val environment: (String) -> String? = System::getenv,
    private val accessTokenProvider: (StreamOptions) -> String = ::resolveGoogleAccessToken,
    private val client: HttpClient = HttpClient.newHttpClient(),
) : Provider {
    override val baseUrl: String? = models.firstOrNull()?.baseUrl

    override fun getModels(): List<Model> = models

    override suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        providerScope.launch {
            runCatching {
                execute(model, context, options, stream)
            }.onFailure { error ->
                stream.push(
                    AssistantError(
                        StopReason.ERROR,
                        AssistantMessage(
                            content = emptyList(),
                            api = model.api,
                            provider = model.provider,
                            model = model.id,
                            stopReason = StopReason.ERROR,
                            errorMessage = error.message ?: error::class.simpleName.orEmpty(),
                        ),
                    ),
                )
            }
        }
        return stream
    }

    private suspend fun execute(
        model: Model,
        context: Context,
        options: StreamOptions,
        stream: AssistantMessageEventStream,
    ) {
        val request = resolveGoogleVertexRequest(model, options, environment, accessTokenProvider)
        val body = buildGoogleVertexRequestBody(model, context, options)
        val blocks = mutableListOf<works.earendil.pi.ai.ContentBlock>()
        var currentIndex: Int? = null
        var currentThinking = false
        var responseId: String? = null
        var stopReason = StopReason.STOP
        var usage = Usage()

        fun snapshot(): AssistantMessage =
            AssistantMessage(
                content = copyBlocks(blocks),
                api = model.api,
                provider = model.provider,
                model = model.id,
                responseId = responseId,
                usage = usage,
                stopReason = stopReason,
            )

        fun finishCurrent() {
            val index = currentIndex ?: return
            when (val block = blocks[index]) {
                is TextContent -> stream.push(TextEnd(index, block.text, snapshot()))
                is ThinkingContent -> stream.push(ThinkingEnd(index, block.thinking, snapshot()))
                else -> Unit
            }
            currentIndex = null
        }

        stream.push(AssistantStart(snapshot()))
        postSse(
            client = client,
            url = request.url,
            body = providerJson.encodeToString(JsonObject.serializer(), body),
            headers = mergedHeaders(request.headers, model.headers, options.headers),
            timeoutMs = options.timeoutMs,
            maxRetries = options.maxRetries,
            maxRetryDelayMs = options.maxRetryDelayMs,
        ) { sse ->
            if (sse.data.isBlank()) {
                return@postSse
            }
            val chunk = providerJson.parseToJsonElement(sse.data).jsonObject
            responseId = chunk.string("responseId") ?: responseId
            val candidate = chunk.array("candidates")?.firstOrNull()?.jsonObject
            candidate?.obj("content")?.array("parts")?.forEach { rawPart ->
                val part = rawPart.jsonObject
                part.string("text")?.let { text ->
                    val thinking = part["thought"]?.toString() == "true"
                    if (currentIndex == null || thinking != currentThinking) {
                        finishCurrent()
                        currentThinking = thinking
                        currentIndex = blocks.size
                        if (thinking) {
                            blocks += ThinkingContent("", part.string("thoughtSignature"))
                            stream.push(ThinkingStart(requireNotNull(currentIndex), snapshot()))
                        } else {
                            blocks += TextContent("", part.string("thoughtSignature"))
                            stream.push(TextStart(requireNotNull(currentIndex), snapshot()))
                        }
                    }
                    val index = requireNotNull(currentIndex)
                    if (thinking) {
                        val current = blocks[index] as ThinkingContent
                        blocks[index] =
                            current.copy(
                                thinking = current.thinking + text,
                                thinkingSignature = retainSignature(current.thinkingSignature, part.string("thoughtSignature")),
                            )
                        stream.push(ThinkingDelta(index, text, snapshot()))
                    } else {
                        val current = blocks[index] as TextContent
                        blocks[index] =
                            current.copy(
                                text = current.text + text,
                                textSignature = retainSignature(current.textSignature, part.string("thoughtSignature")),
                            )
                        stream.push(TextDelta(index, text, snapshot()))
                    }
                }
                part.obj("functionCall")?.let { function ->
                    finishCurrent()
                    val providedId = function.string("id")
                    val duplicate = providedId != null && blocks.filterIsInstance<ToolCall>().any { it.id == providedId }
                    val call =
                        ToolCall(
                            id =
                                providedId
                                    ?.takeUnless { duplicate }
                                    ?: "${function.string("name").orEmpty()}_${System.currentTimeMillis()}_" +
                                        vertexToolCounter.incrementAndGet(),
                            name = function.string("name").orEmpty(),
                            arguments = function.obj("args") ?: JsonObject(emptyMap()),
                            thoughtSignature = part.string("thoughtSignature"),
                        )
                    val index = blocks.size
                    blocks += call
                    stream.push(ToolCallStart(index, snapshot()))
                    stream.push(
                        ToolCallDelta(
                            index,
                            providerJson.encodeToString(JsonObject.serializer(), call.arguments),
                            snapshot(),
                        ),
                    )
                    stream.push(ToolCallEnd(index, call, snapshot()))
                }
            }
            candidate?.string("finishReason")?.let { reason ->
                stopReason = googleVertexStopReason(reason)
                if (blocks.any { it is ToolCall }) {
                    stopReason = StopReason.TOOL_USE
                }
            }
            chunk.obj("usageMetadata")?.let { rawUsage ->
                val cached = rawUsage.int("cachedContentTokenCount") ?: 0
                val thoughts = rawUsage.int("thoughtsTokenCount") ?: 0
                usage =
                    calculateUsageCost(
                        model = model,
                        input = ((rawUsage.int("promptTokenCount") ?: 0) - cached).coerceAtLeast(0),
                        output = (rawUsage.int("candidatesTokenCount") ?: 0) + thoughts,
                        cacheRead = cached,
                        reasoning = thoughts,
                    )
            }
        }
        finishCurrent()
        val final = snapshot()
        if (stopReason == StopReason.ERROR) {
            stream.push(AssistantError(StopReason.ERROR, final.copy(errorMessage = "Google blocked the response")))
        } else {
            stream.push(AssistantDone(stopReason, final))
        }
    }
}

internal fun resolveGoogleVertexRequest(
    model: Model,
    options: StreamOptions,
    environment: (String) -> String? = System::getenv,
    accessTokenProvider: (StreamOptions) -> String = ::resolveGoogleAccessToken,
): GoogleVertexRequest {
    val apiKey = resolveGoogleVertexApiKey(options, environment)
    val project =
        if (apiKey == null) {
            resolveGoogleVertexProject(options, environment)
        } else {
            null
        }
    val location =
        if (apiKey == null) {
            resolveGoogleVertexLocation(options, environment)
        } else {
            null
        }
    val customBaseUrl =
        model.baseUrl
            .trim()
            .takeIf(String::isNotEmpty)
            ?.takeUnless { "{location}" in it }
    val modelPath = googleVertexModelPath(model.id)
    val base =
        if (customBaseUrl != null) {
            appendVertexApiVersion(customBaseUrl)
        } else if (apiKey != null) {
            "$VERTEX_GLOBAL_BASE_URL/$VERTEX_API_VERSION"
        } else {
            val requiredProject = requireNotNull(project)
            val requiredLocation = requireNotNull(location)
            val host =
                when (requiredLocation) {
                    "global" -> VERTEX_GLOBAL_BASE_URL
                    "us", "eu" -> "https://aiplatform.$requiredLocation.rep.googleapis.com"
                    else -> "https://$requiredLocation-aiplatform.googleapis.com"
                }
            "$host/$VERTEX_API_VERSION/projects/${encodePathSegment(requiredProject)}/locations/" +
                encodePathSegment(requiredLocation)
        }
    val authHeaders =
        if (apiKey != null) {
            mapOf("x-goog-api-key" to apiKey)
        } else {
            mapOf("authorization" to "Bearer ${accessTokenProvider(options)}")
        }
    return GoogleVertexRequest(
        url = "${base.trimEnd('/')}/$modelPath:streamGenerateContent?alt=sse",
        headers = authHeaders,
    )
}

internal fun buildGoogleVertexParams(
    model: Model,
    context: Context,
    options: StreamOptions,
): JsonObject =
    buildJsonObject {
        put("model", model.id)
        put("contents", googleVertexContents(model, context))
        put(
            "config",
            buildJsonObject {
                options.temperature?.let { put("temperature", it) }
                options.maxTokens?.let { put("maxOutputTokens", it) }
                context.systemPrompt?.let { put("systemInstruction", sanitizeSurrogates(it)) }
                if (context.tools.isNotEmpty()) {
                    put("tools", googleVertexTools(context))
                }
                resolveGoogleFunctionCallingMode(model.id, context.tools, options.toolChoice)?.let { mode ->
                    put(
                        "toolConfig",
                        buildJsonObject {
                            put(
                                "functionCallingConfig",
                                buildJsonObject { put("mode", mode) },
                            )
                        },
                    )
                }
                googleVertexThinkingConfig(model, options)?.let { put("thinkingConfig", it) }
            },
        )
    }

internal fun buildGoogleVertexRequestBody(
    model: Model,
    context: Context,
    options: StreamOptions,
): JsonObject {
    val params = buildGoogleVertexParams(model, context, options)
    val config = params.getValue("config").jsonObject
    return buildJsonObject {
        put("contents", params.getValue("contents"))
        config.string("systemInstruction")?.let { system ->
            put(
                "systemInstruction",
                buildJsonObject {
                    put(
                        "parts",
                        buildJsonArray { add(buildJsonObject { put("text", system) }) },
                    )
                    put("role", "user")
                },
            )
        }
        config["tools"]?.let { put("tools", it) }
        config["toolConfig"]?.let { put("toolConfig", it) }
        put(
            "generationConfig",
            buildJsonObject {
                config["temperature"]?.let { put("temperature", it) }
                config["maxOutputTokens"]?.let { put("maxOutputTokens", it) }
                config["thinkingConfig"]?.let { put("thinkingConfig", it) }
            },
        )
    }
}

private fun googleVertexContents(
    model: Model,
    context: Context,
): JsonArray {
    val contents = mutableListOf<JsonObject>()
    context.messages.forEach { message ->
        when (message) {
            is works.earendil.pi.ai.UserMessage -> {
                val parts = googleVertexUserParts(message.content)
                if (parts.isNotEmpty()) {
                    contents +=
                        buildJsonObject {
                            put("role", "user")
                            put("parts", JsonArray(parts))
                        }
                }
            }

            is AssistantMessage -> {
                val sameProviderAndModel = message.provider == model.provider && message.model == model.id
                val parts =
                    message.content.mapNotNull { block ->
                        when (block) {
                            is TextContent ->
                                block.text
                                    .takeIf { it.isNotBlank() }
                                    ?.let { text ->
                                        buildJsonObject {
                                            put("text", sanitizeSurrogates(text))
                                            validGoogleSignature(sameProviderAndModel, block.textSignature)
                                                ?.let { put("thoughtSignature", it) }
                                        }
                                    }

                            is ThinkingContent ->
                                block.thinking
                                    .takeIf { it.isNotBlank() }
                                    ?.let { thinking ->
                                        buildJsonObject {
                                            put("text", sanitizeSurrogates(thinking))
                                            if (sameProviderAndModel) {
                                                put("thought", true)
                                                validGoogleSignature(true, block.thinkingSignature)
                                                    ?.let { put("thoughtSignature", it) }
                                            }
                                        }
                                    }

                            is ToolCall ->
                                buildJsonObject {
                                    put(
                                        "functionCall",
                                        buildJsonObject {
                                            put("name", block.name)
                                            put("args", block.arguments)
                                            if (googleVertexRequiresToolCallId(model.id)) {
                                                put("id", normalizeGoogleToolCallId(block.id))
                                            }
                                        },
                                    )
                                    validGoogleSignature(sameProviderAndModel, block.thoughtSignature)
                                        ?.let { put("thoughtSignature", it) }
                                }

                            else -> null
                        }
                    }
                if (parts.isNotEmpty()) {
                    contents +=
                        buildJsonObject {
                            put("role", "model")
                            put("parts", JsonArray(parts))
                        }
                }
            }

            is ToolResultMessage -> appendGoogleVertexToolResult(contents, model, message)

            else -> {
                val text =
                    when (message) {
                        is CustomMessage -> contentText(message.content)
                        is CompactionSummaryMessage -> message.summary
                        is BranchSummaryMessage -> message.summary
                        is BashExecutionMessage -> "${message.command}\n${message.output}"
                    }
                contents +=
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "parts",
                            buildJsonArray {
                                add(buildJsonObject { put("text", sanitizeSurrogates(text)) })
                            },
                        )
                    }
            }
        }
    }
    return JsonArray(contents)
}

private fun appendGoogleVertexToolResult(
    contents: MutableList<JsonObject>,
    model: Model,
    message: ToolResultMessage,
) {
    val text = message.content.filterIsInstance<TextContent>().joinToString("\n", transform = TextContent::text)
    val images =
        if (ModelInput.IMAGE in model.input) {
            message.content.filterIsInstance<ImageContent>()
        } else {
            emptyList()
        }
    val supportsNestedImages = googleVertexSupportsMultimodalFunctionResponse(model.id)
    val responseText =
        when {
            text.isNotEmpty() -> sanitizeSurrogates(text)
            images.isNotEmpty() -> "(see attached image)"
            else -> ""
        }
    val imageParts =
        images.map { image ->
            buildJsonObject {
                put(
                    "inlineData",
                    buildJsonObject {
                        put("mimeType", image.mimeType)
                        put("data", image.data)
                    },
                )
            }
        }
    val functionResponse =
        buildJsonObject {
            put("name", message.toolName)
            put(
                "response",
                buildJsonObject {
                    put(if (message.isError) "error" else "output", responseText)
                },
            )
            if (images.isNotEmpty() && supportsNestedImages) {
                put("parts", JsonArray(imageParts))
            }
            if (googleVertexRequiresToolCallId(model.id)) {
                put("id", normalizeGoogleToolCallId(message.toolCallId))
            }
        }
    val part = buildJsonObject { put("functionResponse", functionResponse) }
    val last = contents.lastOrNull()
    val lastParts = last?.array("parts")
    if (
        last?.string("role") == "user" &&
        lastParts?.any { element -> element.jsonObject["functionResponse"] != null } == true
    ) {
        contents[contents.lastIndex] =
            buildJsonObject {
                put("role", "user")
                put("parts", JsonArray(lastParts + part))
            }
    } else {
        contents +=
            buildJsonObject {
                put("role", "user")
                put("parts", JsonArray(listOf(part)))
            }
    }
    if (images.isNotEmpty() && !supportsNestedImages) {
        contents +=
            buildJsonObject {
                put("role", "user")
                put(
                    "parts",
                    buildJsonArray {
                        add(buildJsonObject { put("text", "Tool result image:") })
                        imageParts.forEach(::add)
                    },
                )
            }
    }
}

private fun googleVertexUserParts(content: MessageContent): List<JsonObject> =
    when (content) {
        is MessageContent.Text ->
            listOf(buildJsonObject { put("text", sanitizeSurrogates(content.text)) })

        is MessageContent.Blocks ->
            content.blocks.mapNotNull { block ->
                when (block) {
                    is TextContent -> buildJsonObject { put("text", sanitizeSurrogates(block.text)) }
                    is ImageContent ->
                        buildJsonObject {
                            put(
                                "inlineData",
                                buildJsonObject {
                                    put("mimeType", block.mimeType)
                                    put("data", block.data)
                                },
                            )
                        }

                    else -> null
                }
            }
    }

private fun googleVertexTools(context: Context): JsonArray =
    buildJsonArray {
        add(
            buildJsonObject {
                put(
                    "functionDeclarations",
                    buildJsonArray {
                        context.tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parametersJsonSchema", tool.parameters)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

private fun googleVertexThinkingConfig(
    model: Model,
    options: StreamOptions,
): JsonObject? {
    if (!model.reasoning) {
        return null
    }
    val reasoning = options.reasoning
    if (reasoning == null) {
        return disabledGoogleVertexThinkingConfig(model)
    }
    val level = model.clampThinkingLevel(reasoning)
    return if (googleVertexUsesThinkingLevels(model)) {
        buildJsonObject {
            put("includeThoughts", true)
            put("thinkingLevel", googleVertexThinkingLevel(model, level))
        }
    } else {
        buildJsonObject {
            put("includeThoughts", true)
            put("thinkingBudget", googleVertexThinkingBudget(model, level, options))
        }
    }
}

private fun disabledGoogleVertexThinkingConfig(model: Model): JsonObject =
    when {
        isGoogleVertexGemini3Pro(model) ->
            buildJsonObject { put("thinkingLevel", "LOW") }

        isGoogleVertexGemini3Flash(model) ->
            buildJsonObject { put("thinkingLevel", "MINIMAL") }

        else ->
            buildJsonObject { put("thinkingBudget", 0) }
    }

private fun googleVertexUsesThinkingLevels(model: Model): Boolean =
    isGoogleVertexGemini3Pro(model) || isGoogleVertexGemini3Flash(model)

private fun googleVertexThinkingLevel(
    model: Model,
    level: ModelThinkingLevel,
): String =
    if (isGoogleVertexGemini3Pro(model)) {
        when (level) {
            ModelThinkingLevel.MINIMAL, ModelThinkingLevel.LOW -> "LOW"
            else -> "HIGH"
        }
    } else {
        when (level) {
            ModelThinkingLevel.MINIMAL -> "MINIMAL"
            ModelThinkingLevel.LOW -> "LOW"
            ModelThinkingLevel.MEDIUM -> "MEDIUM"
            else -> "HIGH"
        }
    }

private fun googleVertexThinkingBudget(
    model: Model,
    level: ModelThinkingLevel,
    options: StreamOptions,
): Int {
    val custom =
        when (level) {
            ModelThinkingLevel.MINIMAL -> options.thinkingBudgets?.minimal
            ModelThinkingLevel.LOW -> options.thinkingBudgets?.low
            ModelThinkingLevel.MEDIUM -> options.thinkingBudgets?.medium
            else -> options.thinkingBudgets?.high
        }
    if (custom != null) {
        return custom
    }
    return when {
        "2.5-pro" in model.id ->
            when (level) {
                ModelThinkingLevel.MINIMAL -> 128
                ModelThinkingLevel.LOW -> 2_048
                ModelThinkingLevel.MEDIUM -> 8_192
                else -> 32_768
            }

        "2.5-flash" in model.id ->
            when (level) {
                ModelThinkingLevel.MINIMAL -> 128
                ModelThinkingLevel.LOW -> 2_048
                ModelThinkingLevel.MEDIUM -> 8_192
                else -> 24_576
            }

        else -> -1
    }
}

private fun resolveGoogleVertexApiKey(
    options: StreamOptions,
    environment: (String) -> String?,
): String? {
    val raw =
        options.apiKey
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: providerEnvValue(GOOGLE_CLOUD_API_KEY, options.env, environment)
    return raw?.takeUnless { it == GCP_VERTEX_CREDENTIALS_MARKER || PLACEHOLDER_API_KEY.matches(it) }
}

private fun resolveGoogleVertexProject(
    options: StreamOptions,
    environment: (String) -> String?,
): String =
    options.project?.takeIf(String::isNotBlank)
        ?: providerEnvValue(GOOGLE_CLOUD_PROJECT, options.env, environment)
        ?: providerEnvValue(GCLOUD_PROJECT, options.env, environment)
        ?: error(
            "Vertex AI requires a project ID. Set GOOGLE_CLOUD_PROJECT/GCLOUD_PROJECT or pass project in options.",
        )

private fun resolveGoogleVertexLocation(
    options: StreamOptions,
    environment: (String) -> String?,
): String =
    options.location?.takeIf(String::isNotBlank)
        ?: providerEnvValue(GOOGLE_CLOUD_LOCATION, options.env, environment)
        ?: error("Vertex AI requires a location. Set GOOGLE_CLOUD_LOCATION or pass location in options.")

private fun providerEnvValue(
    name: String,
    env: Map<String, String>,
    environment: (String) -> String?,
): String? =
    env[name]?.takeIf(String::isNotBlank)
        ?: environment(name)?.takeIf(String::isNotBlank)

private fun resolveGoogleAccessToken(options: StreamOptions): String {
    val credentialsPath =
        providerEnvValue(GOOGLE_APPLICATION_CREDENTIALS, options.env, System::getenv)
            ?.let(::expandHome)
    val credentials =
        if (credentialsPath != null) {
            Files.newInputStream(credentialsPath).use(GoogleCredentials::fromStream)
        } else {
            GoogleCredentials.getApplicationDefault()
        }
    val scoped =
        if (credentials.createScopedRequired()) {
            credentials.createScoped(listOf(GOOGLE_CLOUD_PLATFORM_SCOPE))
        } else {
            credentials
        }
    scoped.refreshIfExpired()
    return scoped.accessToken?.tokenValue
        ?: requireNotNull(scoped.refreshAccessToken()) {
            "Google Application Default Credentials did not provide an access token"
        }.tokenValue
}

private fun expandHome(path: String): Path =
    if (path == "~") {
        Path.of(System.getProperty("user.home"))
    } else if (path.startsWith("~/")) {
        Path.of(System.getProperty("user.home")).resolve(path.removePrefix("~/"))
    } else {
        Path.of(path)
    }.toAbsolutePath().normalize()

private fun appendVertexApiVersion(baseUrl: String): String {
    val trimmed = baseUrl.trimEnd('/')
    val path =
        runCatching { URI.create(trimmed).path }
            .getOrDefault(trimmed)
    return if (VERTEX_API_VERSION_PATH.containsMatchIn(path)) {
        trimmed
    } else {
        "$trimmed/$VERTEX_API_VERSION"
    }
}

private fun googleVertexModelPath(modelId: String): String {
    require(".." !in modelId && "?" !in modelId && "&" !in modelId) {
        "Invalid Vertex model id: $modelId"
    }
    val parts =
        when {
            modelId.startsWith("publishers/") ||
                modelId.startsWith("projects/") ||
                modelId.startsWith("models/") -> modelId.split('/')

            '/' in modelId -> {
                val split = modelId.split('/', limit = 2)
                listOf("publishers", split[0], "models", split[1])
            }

            else -> listOf("publishers", "google", "models", modelId)
        }
    return parts.joinToString("/") { encodePathSegment(it) }
}

private fun encodePathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

private fun validGoogleSignature(
    sameProviderAndModel: Boolean,
    signature: String?,
): String? {
    if (!sameProviderAndModel || signature.isNullOrEmpty() || signature.length % 4 != 0) {
        return null
    }
    return runCatching {
        Base64.getDecoder().decode(signature)
        signature
    }.getOrNull()
}

private fun normalizeGoogleToolCallId(id: String): String =
    id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

private fun googleVertexRequiresToolCallId(modelId: String): Boolean =
    modelId.startsWith("claude-") || modelId.startsWith("gpt-oss-")

private fun googleVertexSupportsMultimodalFunctionResponse(modelId: String): Boolean {
    val version = Regex("^gemini(?:-live)?-(\\d+)").find(modelId.lowercase())?.groupValues?.get(1)?.toIntOrNull()
    return version?.let { it >= 3 } ?: true
}

private fun isGoogleVertexGemini3Pro(model: Model): Boolean =
    Regex("gemini-3(?:\\.\\d+)?-pro").containsMatchIn(model.id.lowercase())

private fun isGoogleVertexGemini3Flash(model: Model): Boolean {
    val id = model.id.lowercase()
    return Regex("gemini-3(?:\\.\\d+)?-flash").containsMatchIn(id) ||
        id == "gemini-flash-latest" ||
        id == "gemini-flash-lite-latest"
}

private fun sanitizeSurrogates(value: String): String {
    val output = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val current = value[index]
        when {
            Character.isHighSurrogate(current) -> {
                val next = value.getOrNull(index + 1)
                if (next != null && Character.isLowSurrogate(next)) {
                    output.append(current).append(next)
                    index += 2
                } else {
                    index += 1
                }
            }

            Character.isLowSurrogate(current) -> index += 1
            else -> {
                output.append(current)
                index += 1
            }
        }
    }
    return output.toString()
}

private fun retainSignature(
    existing: String?,
    incoming: String?,
): String? = incoming?.takeIf(String::isNotEmpty) ?: existing

private fun googleVertexStopReason(reason: String): StopReason =
    when (reason) {
        "STOP" -> StopReason.STOP
        "MAX_TOKENS" -> StopReason.LENGTH
        else -> StopReason.ERROR
    }

private val vertexToolCounter = AtomicLong()
private val PLACEHOLDER_API_KEY = Regex("^<[^>]+>$")
private val VERTEX_API_VERSION_PATH = Regex("(?:^|/)v\\d+(?:beta\\d*)?(?:/|$)")
private const val VERTEX_API_VERSION = "v1"
private const val VERTEX_GLOBAL_BASE_URL = "https://aiplatform.googleapis.com"
private const val GCP_VERTEX_CREDENTIALS_MARKER = "gcp-vertex-credentials"
private const val GOOGLE_CLOUD_API_KEY = "GOOGLE_CLOUD_API_KEY"
private const val GOOGLE_CLOUD_PROJECT = "GOOGLE_CLOUD_PROJECT"
private const val GCLOUD_PROJECT = "GCLOUD_PROJECT"
private const val GOOGLE_CLOUD_LOCATION = "GOOGLE_CLOUD_LOCATION"
private const val GOOGLE_APPLICATION_CREDENTIALS = "GOOGLE_APPLICATION_CREDENTIALS"
private const val GOOGLE_CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
