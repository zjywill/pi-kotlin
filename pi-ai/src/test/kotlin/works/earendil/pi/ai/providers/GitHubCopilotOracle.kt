package works.earendil.pi.ai.providers

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.TextContent

fun main() =
    runBlocking {
        val requests = mutableListOf<OAuthHttpRequest>()
        val token = "tid=test;proxy-ep=proxy.enterprise.githubcopilot.com;exp=900"
        val oauth =
            GitHubCopilotOAuth(
                transport =
                    OAuthHttpTransport { request ->
                        requests += request
                        when {
                            request.url.endsWith("/login/device/code") ->
                                OAuthHttpResponse(
                                    200,
                                    """
                                    {
                                      "device_code":"device-code",
                                      "user_code":"ABCD-EFGH",
                                      "verification_uri":"https://company.ghe.com/login/device",
                                      "interval":1,
                                      "expires_in":60
                                    }
                                    """.trimIndent(),
                                )

                            request.url.endsWith("/login/oauth/access_token") ->
                                OAuthHttpResponse(200, """{"access_token":"ghu-enterprise"}""")

                            request.url.endsWith("/copilot_internal/v2/token") ->
                                OAuthHttpResponse(
                                    200,
                                    """{"token":"$token","expires_at":900}""",
                                )

                            request.url.endsWith("/models") ->
                                OAuthHttpResponse(
                                    200,
                                    """
                                    {
                                      "data":[
                                        {
                                          "id":"gpt-4.1",
                                          "model_picker_enabled":true,
                                          "capabilities":{"supports":{"tool_calls":true}}
                                        },
                                        {
                                          "id":"claude-opus-4.7",
                                          "model_picker_enabled":true,
                                          "policy":{"state":"disabled"},
                                          "capabilities":{"supports":{"tool_calls":true}}
                                        },
                                        {
                                          "id":"gpt-5.4-nano",
                                          "model_picker_enabled":false,
                                          "capabilities":{"supports":{"tool_calls":true}}
                                        }
                                      ]
                                    }
                                    """.trimIndent(),
                                )

                            request.url.endsWith("/policy") -> OAuthHttpResponse(200, "")
                            else -> error("Unexpected GitHub Copilot request: ${request.url}")
                        }
                    },
                now = { 0 },
                sleep = {},
            )
        var promptProjection: JsonObject? = null
        var deviceProjection: JsonObject? = null
        val progress = mutableListOf<String>()
        val credential =
            oauth.login(
                object : AuthInteraction {
                    override suspend fun prompt(prompt: AuthPrompt): String {
                        require(prompt is AuthPrompt.Text)
                        promptProjection =
                            buildJsonObject {
                                put("message", prompt.message)
                                prompt.placeholder?.let { put("placeholder", it) }
                            }
                        return "https://company.ghe.com/some/path"
                    }

                    override fun notify(event: AuthEvent) {
                        when (event) {
                            is AuthEvent.DeviceCode ->
                                deviceProjection =
                                    buildJsonObject {
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

                            is AuthEvent.Progress -> progress += event.message
                            else -> Unit
                        }
                    }
                },
            )
        val auth = oauth.toAuth(credential)
        val provider = githubCopilotProvider()
        val allModels = provider.getModels()
        val filtered = provider.filterModels(allModels, credential)
        val policyModels =
            requests
                .filter { it.url.endsWith("/policy") }
                .map { request ->
                    request.url
                        .substringAfter("/models/")
                        .substringBeforeLast("/policy")
                        .let(::decode)
                }.sorted()

        val output =
            buildJsonObject {
                put(
                    "login",
                    buildJsonObject {
                        put("prompt", requireNotNull(promptProjection))
                        put("device", requireNotNull(deviceProjection))
                        put("progress", stringArray(progress))
                        put("credential", credentialProjection(credential))
                    },
                )
                put(
                    "requests",
                    buildJsonObject {
                        put(
                            "device",
                            requestProjection(
                                request = requests.first { it.url.endsWith("/login/device/code") },
                                includeForm = true,
                            ),
                        )
                        put(
                            "access",
                            requestProjection(
                                request = requests.first { it.url.endsWith("/login/oauth/access_token") },
                                includeForm = true,
                            ),
                        )
                        val tokenRequest =
                            requests.first { it.url.endsWith("/copilot_internal/v2/token") }
                        put(
                            "token",
                            buildJsonObject {
                                put("url", tokenRequest.url)
                                put("method", tokenRequest.method)
                                put("authorization", tokenRequest.header("authorization"))
                            },
                        )
                        put(
                            "policy",
                            buildJsonObject {
                                put("count", policyModels.size)
                                put("models", stringArray(policyModels))
                            },
                        )
                        val modelsRequest = requests.first { it.url.endsWith("/models") }
                        put(
                            "models",
                            buildJsonObject {
                                put("url", modelsRequest.url)
                                put("method", modelsRequest.method)
                                put("authorization", modelsRequest.header("authorization"))
                            },
                        )
                    },
                )
                put(
                    "auth",
                    buildJsonObject {
                        auth.apiKey?.let { put("apiKey", it) }
                        auth.baseUrl?.let { put("baseUrl", it) }
                    },
                )
                put(
                    "catalog",
                    buildJsonObject {
                        put("total", allModels.size)
                        put(
                            "countByApi",
                            buildJsonObject {
                                listOf(
                                    "anthropic-messages",
                                    "openai-completions",
                                    "openai-responses",
                                ).forEach { api ->
                                    put(api, allModels.count { it.api == api })
                                }
                            },
                        )
                        put("filtered", stringArray(filtered.map { it.id }))
                    },
                )
                put(
                    "dynamicHeaders",
                    buildJsonObject {
                        put("empty", headersProjection(githubCopilotDynamicHeaders(Context())))
                        put(
                            "agent",
                            headersProjection(
                                githubCopilotDynamicHeaders(
                                    Context(
                                        messages =
                                            mutableListOf(
                                                AssistantMessage(
                                                    content = listOf(TextContent("done")),
                                                    api = "openai-responses",
                                                    provider = "github-copilot",
                                                    model = "gpt-5.4",
                                                    timestamp = 0,
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                        )
                        put(
                            "vision",
                            headersProjection(
                                githubCopilotDynamicHeaders(
                                    Context(
                                        messages =
                                            mutableListOf(
                                                works.earendil.pi.ai.UserMessage(
                                                    listOf(
                                                        TextContent("inspect"),
                                                        ImageContent("aGVsbG8=", "image/png"),
                                                    ),
                                                    0,
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                        )
                    },
                )
            }
        println(oracleJson.encodeToString(JsonObject.serializer(), output))
    }

private val oracleJson =
    Json {
        explicitNulls = false
    }

private fun credentialProjection(credential: OAuthCredential): JsonObject =
    buildJsonObject {
        put("type", "oauth")
        put("access", credential.access)
        put("refresh", credential.refresh)
        put("expires", credential.expires)
        credential.enterpriseUrl?.let { put("enterpriseUrl", it) }
        credential.availableModelIds?.let { put("availableModelIds", stringArray(it)) }
    }

private fun requestProjection(
    request: OAuthHttpRequest,
    includeForm: Boolean,
): JsonObject =
    buildJsonObject {
        put("url", request.url)
        put("method", request.method)
        if (includeForm) {
            put(
                "form",
                JsonObject(
                    parseOracleForm(request.body)
                        .mapValues { JsonPrimitive(it.value) },
                ),
            )
        }
    }

private fun headersProjection(headers: Map<String, String>): JsonObject =
    JsonObject(headers.mapValues { JsonPrimitive(it.value) })

private fun stringArray(values: List<String>): JsonArray =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

private fun OAuthHttpRequest.header(name: String): String =
    requireNotNull(headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value)

private fun parseOracleForm(value: String): Map<String, String> =
    value
        .split("&")
        .filter(String::isNotEmpty)
        .associate { field ->
            val parts = field.split("=", limit = 2)
            decode(parts[0]) to decode(parts.getOrElse(1) { "" })
        }

private fun decode(value: String): String =
    URLDecoder.decode(value, StandardCharsets.UTF_8)
