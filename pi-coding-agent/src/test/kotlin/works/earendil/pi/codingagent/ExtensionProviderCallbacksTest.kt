package works.earendil.pi.codingagent

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import works.earendil.pi.ai.AssistantDone
import works.earendil.pi.ai.AssistantError
import works.earendil.pi.ai.AssistantMessageEvent
import works.earendil.pi.ai.AuthEvent
import works.earendil.pi.ai.AuthInteraction
import works.earendil.pi.ai.AuthPrompt
import works.earendil.pi.ai.AuthType
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.InMemoryCredentialStore
import works.earendil.pi.ai.InMemoryModelsStore
import works.earendil.pi.ai.Model
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.ModelsRefreshOptions
import works.earendil.pi.ai.ModelsStoreEntry
import works.earendil.pi.ai.OAuthCredential
import works.earendil.pi.ai.SimpleStreamOptions
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.TextDelta
import works.earendil.pi.ai.ThinkingLevel
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.ai.contentText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtensionProviderCallbacksTest {
    @Test
    fun `custom provider streams live events and receives cancellation`() =
        runBlocking {
            assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
            val root = Files.createTempDirectory("pi-kotlin-extension-provider-stream")
            val abortPath = root.resolve("abort-state")
            val ignoreAbortPath = root.resolve("ignore-abort-state")
            val extension =
                root.resolve("provider-stream.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        import {
                          createAssistantMessageEventStream,
                        } from "@earendil-works/pi-ai";
                        import { writeFileSync } from "node:fs";

                        const usage = {
                          input: 1,
                          output: 2,
                          cacheRead: 0,
                          cacheWrite: 0,
                          totalTokens: 3,
                          cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
                        };

                        export default function(pi) {
                          pi.registerProvider("callback-provider", {
                            name: "Callback Provider",
                            baseUrl: "https://callback.invalid/v1",
                            apiKey: "inline-key",
                            api: "callback-api",
                            models: [
                              {
                                id: "callback-model",
                                name: "Callback Model",
                                reasoning: true,
                                input: ["text"],
                                cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
                                contextWindow: 8192,
                                maxTokens: 1024,
                              },
                              {
                                id: "cancel-model",
                                name: "Cancel Model",
                                reasoning: false,
                                input: ["text"],
                                cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
                                contextWindow: 8192,
                                maxTokens: 1024,
                              },
                              {
                                id: "ignore-cancel-model",
                                name: "Ignore Cancel Model",
                                reasoning: false,
                                input: ["text"],
                                cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
                                contextWindow: 8192,
                                maxTokens: 1024,
                              },
                            ],
                            streamSimple(model, context, options) {
                              const stream = createAssistantMessageEventStream();
                              if (model.id === "cancel-model") {
                                writeFileSync(process.env.CALLBACK_ABORT_PATH, "started");
                                options.signal.addEventListener("abort", () => {
                                  writeFileSync(process.env.CALLBACK_ABORT_PATH, "aborted");
                                }, { once: true });
                                return stream;
                              }
                              if (model.id === "ignore-cancel-model") {
                                writeFileSync(process.env.CALLBACK_IGNORE_ABORT_PATH, "started");
                                return stream;
                              }
                              const prompt = context.messages[0]?.content ?? "";
                              const output = `${'$'}{model.id}|${'$'}{prompt}|${'$'}{options.apiKey}|${'$'}{options.reasoning}`;
                              const message = (text, stopReason) => ({
                                role: "assistant",
                                content: text ? [{ type: "text", text }] : [],
                                api: model.api,
                                provider: model.provider,
                                model: model.id,
                                usage,
                                stopReason,
                                timestamp: 123,
                              });
                              const initial = message("", "pending");
                              stream.push({ type: "start", partial: initial });
                              setTimeout(() => {
                                const partial = message(output, "pending");
                                stream.push({ type: "text_start", contentIndex: 0, partial: initial });
                                stream.push({ type: "text_delta", contentIndex: 0, delta: output, partial });
                                setTimeout(() => {
                                  stream.push({ type: "text_end", contentIndex: 0, content: output, partial });
                                  const final = message(output, "stop");
                                  stream.push({ type: "done", reason: "stop", message: final });
                                }, 250);
                              }, 20);
                              return stream;
                            },
                          });
                        }
                        """.trimIndent(),
                    )
                }
            val host =
                startHost(
                    root = root,
                    extension = extension,
                    environment =
                        System.getenv() +
                            mapOf(
                                "CALLBACK_ABORT_PATH" to abortPath.toString(),
                                "CALLBACK_IGNORE_ABORT_PATH" to ignoreAbortPath.toString(),
                            ),
                )
            val models = Models()
            val registry = ExtensionProviderRegistry(models, extensionHost = { host })
            registry.apply(host.registrations.providers) { error(it) }

            try {
                val model = assertNotNull(models.getModel("callback-provider", "callback-model"))
                val stream =
                    models.streamSimple(
                        model,
                        Context(messages = mutableListOf(UserMessage("hello"))),
                        SimpleStreamOptions(
                            stream = StreamOptions(sessionId = "callback-session"),
                            reasoning = ThinkingLevel.HIGH,
                        ),
                    )
                val events = CopyOnWriteArrayList<AssistantMessageEvent>()
                val collector = async(Dispatchers.Default) { stream.events.toList(events) }

                withTimeout(2_000) {
                    while (events.none { it is TextDelta }) {
                        delay(5)
                    }
                }
                assertFalse(collector.isCompleted)
                val result = withTimeout(2_000) { stream.result() }
                withTimeout(2_000) { collector.await() }

                assertEquals("callback-model|hello|inline-key|high", contentText(result.content))
                assertEquals(StopReason.STOP, result.stopReason)
                assertIs<AssistantDone>(events.last())

                val cancelModel = assertNotNull(models.getModel("callback-provider", "cancel-model"))
                val cancelled =
                    async(Dispatchers.Default) {
                        models
                            .streamSimple(cancelModel, Context())
                            .events
                            .toList()
                    }
                withTimeout(2_000) {
                    while (!Files.exists(abortPath)) {
                        delay(5)
                    }
                }
                cancelled.cancelAndJoin()
                withTimeout(2_000) {
                    while (Files.readString(abortPath) != "aborted") {
                        delay(5)
                    }
                }

                val ignoreCancelModel =
                    assertNotNull(models.getModel("callback-provider", "ignore-cancel-model"))
                val ignoredEvents =
                    async(Dispatchers.Default) {
                        models
                            .streamSimple(ignoreCancelModel, Context())
                            .events
                            .toList()
                    }
                withTimeout(2_000) {
                    while (!Files.exists(ignoreAbortPath)) {
                        delay(5)
                    }
                }
                withTimeout(2_000) {
                    async(Dispatchers.IO) { host.close() }.await()
                }
                val ignored = withTimeout(2_000) { ignoredEvents.await() }
                val terminal = assertIs<AssistantError>(ignored.last())
                assertEquals(StopReason.ABORTED, terminal.reason)
            } finally {
                registry.reset()
                host.close()
            }
        }

    @Test
    fun `extension OAuth bridges interaction refresh auth and model projection`() =
        runBlocking {
            assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
            val root = Files.createTempDirectory("pi-kotlin-extension-provider-oauth")
            val blockingLoginPath = root.resolve("blocking-login")
            val extension =
                root.resolve("provider-oauth.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        import { writeFileSync } from "node:fs";

                        export default function(pi) {
                          const baseModel = {
                            id: "base-model",
                            name: "Base Model",
                            reasoning: false,
                            input: ["text"],
                            cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
                            contextWindow: 8192,
                            maxTokens: 1024,
                          };
                          pi.registerProvider("oauth-provider", {
                            name: "OAuth Provider",
                            baseUrl: "https://oauth.invalid/v1",
                            api: "openai-completions",
                            authHeader: true,
                            headers: { "X-Tenant": "${'$'}TENANT_HEADER" },
                            models: [baseModel],
                            oauth: {
                              name: "Extension Subscription",
                              async login(callbacks) {
                                callbacks.onAuth({
                                  url: "https://auth.invalid/start",
                                  instructions: "Open the browser",
                                });
                                callbacks.onDeviceCode({
                                  userCode: "ABCD",
                                  verificationUri: "https://auth.invalid/device",
                                  intervalSeconds: 2,
                                  expiresInSeconds: 60,
                                });
                                callbacks.onProgress("Waiting");
                                const account = await callbacks.onPrompt({
                                  message: "Account",
                                  placeholder: "name",
                                });
                                const code = await callbacks.onManualCodeInput();
                                const tenant = await callbacks.onSelect({
                                  message: "Workspace",
                                  options: [
                                    { id: "team", label: "Team" },
                                    { id: "personal", label: "Personal" },
                                  ],
                                });
                                return {
                                  access: `${'$'}{account}-${'$'}{code}`,
                                  refresh: "refresh-1",
                                  expires: 0,
                                  tenant,
                                  env: { TENANT_HEADER: "credential-header" },
                                };
                              },
                              async refreshToken(credentials) {
                                return {
                                  ...credentials,
                                  access: `${'$'}{credentials.access}-refreshed`,
                                  refresh: "refresh-2",
                                  expires: 1000000,
                                };
                              },
                              getApiKey(credentials) {
                                return `${'$'}{credentials.access}:${'$'}{credentials.tenant}`;
                              },
                              modifyModels(models, credentials) {
                                return [
                                  ...models,
                                  {
                                    ...baseModel,
                                    id: `tenant-${'$'}{credentials.tenant}`,
                                    name: `Tenant ${'$'}{credentials.tenant}`,
                                  },
                                ];
                              },
                            },
                          });
                          pi.registerProvider("oauth-blocking", {
                            name: "Blocking OAuth",
                            baseUrl: "https://blocking.invalid/v1",
                            api: "openai-completions",
                            models: [baseModel],
                            oauth: {
                              name: "Blocking Subscription",
                              async login() {
                                writeFileSync(process.env.CALLBACK_BLOCKING_LOGIN_PATH, "started");
                                await new Promise(() => {});
                              },
                              async refreshToken(credentials) {
                                return credentials;
                              },
                              getApiKey(credentials) {
                                return credentials.access;
                              },
                            },
                          });
                        }
                        """.trimIndent(),
                    )
                }
            val host =
                startHost(
                    root,
                    extension,
                    environment =
                        System.getenv() +
                            mapOf("CALLBACK_BLOCKING_LOGIN_PATH" to blockingLoginPath.toString()),
                )
            val credentials = InMemoryCredentialStore()
            val models =
                Models(
                    providers = emptyList(),
                    credentials = credentials,
                    currentTimeMillis = { 1_000 },
                )
            val registry =
                ExtensionProviderRegistry(
                    models,
                    environment = mapOf("TENANT_HEADER" to "ambient-header"),
                    extensionHost = { host },
                )
            registry.apply(host.registrations.providers) { error(it) }
            val interaction = ScriptedAuthInteraction()

            try {
                val loggedIn =
                    assertIs<OAuthCredential>(
                        models.login("oauth-provider", AuthType.OAUTH, interaction),
                    )
                assertEquals("team", loggedIn.extra["tenant"]?.toString()?.trim('"'))
                assertEquals(
                    listOf("Account", "Paste the authorization code", "Workspace"),
                    interaction.prompts.map(AuthPrompt::message),
                )
                assertTrue(interaction.events.any { it is AuthEvent.AuthUrl })
                assertTrue(interaction.events.any { it is AuthEvent.DeviceCode })
                assertTrue(interaction.events.any { it is AuthEvent.Progress })

                val auth = assertNotNull(models.getAuth("oauth-provider"))
                assertEquals("alice-manual-refreshed:team", auth.auth.apiKey)
                assertEquals("credential-header", auth.auth.headers["X-Tenant"])
                assertEquals("Bearer alice-manual-refreshed:team", auth.auth.headers["Authorization"])
                val stored = assertIs<OAuthCredential>(credentials.read("oauth-provider"))
                assertEquals("refresh-2", stored.refresh)
                assertEquals("team", stored.extra["tenant"]?.toString()?.trim('"'))

                val refresh = models.refresh(ModelsRefreshOptions(allowNetwork = false))
                assertTrue(refresh.errors.isEmpty())
                val projected: Model? = models.getModel("oauth-provider", "tenant-team")
                assertNotNull(projected)

                val blockingLogin =
                    async(Dispatchers.Default) {
                        models.login(
                            "oauth-blocking",
                            AuthType.OAUTH,
                            ScriptedAuthInteraction(),
                        )
                    }
                withTimeout(2_000) {
                    while (!Files.exists(blockingLoginPath)) {
                        delay(5)
                    }
                }
                blockingLogin.cancelAndJoin()
                val stillResponsive =
                    withTimeout(2_000) {
                        models.getAuth("oauth-provider")
                    }
                assertEquals("alice-manual-refreshed:team", stillResponsive?.auth?.apiKey)

                credentials.modify("oauth-provider") { current ->
                    val oauth = current as OAuthCredential
                    oauth.copy(extra = kotlinx.serialization.json.JsonObject(oauth.extra - "env"))
                }
                assertTrue(
                    runCatching { models.getAuth("oauth-provider") }
                        .exceptionOrNull()
                        ?.message
                        .orEmpty()
                        .contains("Environment variable TENANT_HEADER is not set"),
                )
            } finally {
                registry.reset()
                host.close()
            }
        }

    @Test
    fun `native provider bridges API key auth refresh store filtering and both stream methods`() =
        runBlocking {
            assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
            val root = Files.createTempDirectory("pi-kotlin-extension-native-provider")
            val contextFile = root.resolve("native-context").also { Files.writeString(it, "ready") }
            val extension =
                root.resolve("native-provider.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        import { createAssistantMessageEventStream } from "@earendil-works/pi-ai";

                        const usage = {
                          input: 1,
                          output: 1,
                          cacheRead: 0,
                          cacheWrite: 0,
                          totalTokens: 2,
                          cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
                        };
                        const initialModel = {
                          id: "native-initial",
                          name: "Native Initial",
                          api: "native-api",
                          provider: "native-provider",
                          baseUrl: "https://fallback.invalid/v1",
                          reasoning: false,
                          input: ["text"],
                          cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
                          contextWindow: 8192,
                          maxTokens: 1024,
                        };
                        let providerModels = [initialModel];

                        function response(kind, model, options) {
                          const stream = createAssistantMessageEventStream();
                          const text = [
                            kind,
                            model.id,
                            options?.apiKey,
                            options?.env?.NATIVE_ACCOUNT,
                            model.baseUrl,
                          ].join("|");
                          const partial = {
                            role: "assistant",
                            content: [{ type: "text", text }],
                            api: model.api,
                            provider: model.provider,
                            model: model.id,
                            usage,
                            stopReason: "pending",
                            timestamp: 123,
                          };
                          stream.push({ type: "start", partial });
                          stream.push({ type: "text_start", contentIndex: 0, partial });
                          stream.push({ type: "text_delta", contentIndex: 0, delta: text, partial });
                          stream.push({ type: "text_end", contentIndex: 0, content: text, partial });
                          stream.push({
                            type: "done",
                            reason: "stop",
                            message: { ...partial, stopReason: "stop" },
                          });
                          return stream;
                        }

                        export default function(pi) {
                          pi.registerProvider({
                            id: "native-provider",
                            name: "Native Provider",
                            baseUrl: "https://native.invalid/v1",
                            headers: { "X-Native": "metadata" },
                            auth: {
                              apiKey: {
                                name: "Native setup",
                                async login(interaction) {
                                  const key = await interaction.prompt({
                                    type: "secret",
                                    message: "Native API key",
                                    placeholder: "secret",
                                  });
                                  return { type: "api_key", key, env: { LOGIN: "yes" } };
                                },
                                async check({ credential }) {
                                  return credential?.key
                                    ? { type: "api_key", source: `native-${'$'}{credential.env?.LOGIN}` }
                                    : undefined;
                                },
                                async resolve({ ctx, credential }) {
                                  const account = credential?.env?.NATIVE_ACCOUNT
                                    ?? await ctx.env("NATIVE_ACCOUNT");
                                  const exists = await ctx.fileExists(process.env.NATIVE_CONTEXT_FILE);
                                  if (!credential?.key || !account || !exists) return undefined;
                                  return {
                                    auth: {
                                      apiKey: credential.key,
                                      headers: { "X-Account": account },
                                      baseUrl: `https://resolved.invalid/${'$'}{account}`,
                                    },
                                    env: { NATIVE_ACCOUNT: account },
                                    source: "native resolve",
                                  };
                                },
                              },
                            },
                            getModels() {
                              return providerModels;
                            },
                            async refreshModels(context) {
                              const cached = await context.store.read();
                              const suffix = cached?.models?.[0]?.id ?? "empty";
                              providerModels = [{
                                ...initialModel,
                                id: `native-${'$'}{suffix}`,
                                name: [
                                  context.credential?.key,
                                  context.allowNetwork,
                                  context.force,
                                ].join("|"),
                              }];
                              await context.store.delete();
                              await context.store.write({
                                models: providerModels,
                                checkedAt: 321,
                              });
                            },
                            filterModels(models, credential) {
                              return credential?.key ? models : [];
                            },
                            stream(model, context, options) {
                              return response("stream", model, options);
                            },
                            streamSimple(model, context, options) {
                              return response("simple", model, options);
                            },
                          });
                        }
                        """.trimIndent(),
                    )
                }
            val host =
                startHost(
                    root = root,
                    extension = extension,
                    environment =
                        System.getenv() +
                            mapOf("NATIVE_CONTEXT_FILE" to contextFile.toString()),
                )
            val credentials = InMemoryCredentialStore()
            val modelsStore = InMemoryModelsStore()
            modelsStore.write(
                "native-provider",
                ModelsStoreEntry(
                    models =
                        listOf(
                            Model(
                                id = "cached",
                                name = "Cached",
                                api = "native-api",
                                provider = "native-provider",
                                baseUrl = "https://cached.invalid/v1",
                                reasoning = false,
                                input = listOf(works.earendil.pi.ai.ModelInput.TEXT),
                                cost = works.earendil.pi.ai.ModelCost(0.0, 0.0, 0.0, 0.0),
                                contextWindow = 8_192,
                                maxTokens = 1_024,
                            ),
                        ),
                    checkedAt = 100,
                ),
            )
            val models =
                Models(
                    providers = emptyList(),
                    modelsStore = modelsStore,
                    credentials = credentials,
                    environment = { name ->
                        if (name == "NATIVE_ACCOUNT") "ambient-account" else null
                    },
                )
            val registry = ExtensionProviderRegistry(models, extensionHost = { host })
            registry.apply(host.registrations.providers) { error(it) }
            val prompts = mutableListOf<AuthPrompt>()

            try {
                val initial = assertNotNull(models.getModel("native-provider", "native-initial"))
                assertEquals("https://fallback.invalid/v1", initial.baseUrl)
                assertEquals("metadata", models.getProvider("native-provider")?.headers?.get("X-Native"))
                assertEquals(null, models.checkAuth("native-provider"))
                assertEquals(emptyList(), models.getAvailable("native-provider"))

                models.login(
                    "native-provider",
                    AuthType.API_KEY,
                    object : AuthInteraction {
                        override suspend fun prompt(prompt: AuthPrompt): String {
                            prompts += prompt
                            return "native-secret"
                        }

                        override fun notify(event: AuthEvent) = Unit
                    },
                )
                assertTrue(assertIs<AuthPrompt.Text>(prompts.single()).secret)
                assertEquals(
                    "native-yes",
                    models.checkAuth("native-provider")?.source,
                )
                val auth = assertNotNull(models.getAuth("native-provider"))
                assertEquals("native-secret", auth.auth.apiKey)
                assertEquals("ambient-account", auth.auth.headers["X-Account"])
                assertEquals("https://resolved.invalid/ambient-account", auth.auth.baseUrl)

                val refresh =
                    models.refresh(
                        ModelsRefreshOptions(
                            allowNetwork = true,
                            force = true,
                        ),
                    )
                assertTrue(refresh.errors.isEmpty())
                val refreshed = assertNotNull(models.getModel("native-provider", "native-native-cached"))
                assertEquals("native-secret|true|true", refreshed.name)
                assertEquals(
                    listOf("native-native-cached"),
                    models.getAvailable("native-provider").map(Model::id),
                )
                assertEquals(321, modelsStore.read("native-provider")?.checkedAt)
                assertEquals(
                    listOf("native-native-cached"),
                    modelsStore.read("native-provider")?.models?.map(Model::id),
                )

                val streamed =
                    models.complete(
                        refreshed,
                        Context(),
                        StreamOptions(),
                    )
                assertEquals(
                    "stream|native-native-cached|native-secret|ambient-account|" +
                        "https://resolved.invalid/ambient-account",
                    contentText(streamed.content),
                )
                val simple =
                    models.completeSimple(
                        refreshed,
                        Context(),
                        SimpleStreamOptions(),
                    )
                assertEquals(
                    "simple|native-native-cached|native-secret|ambient-account|" +
                        "https://resolved.invalid/ambient-account",
                    contentText(simple.content),
                )

                registry.unregister("native-provider")
                assertEquals(null, models.getProvider("native-provider"))
            } finally {
                registry.reset()
                host.close()
            }
        }

    @Test
    fun `named provider refreshModels receives scoped store and publishes without implicit persistence`() =
        runBlocking {
            assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
            val root = Files.createTempDirectory("pi-kotlin-extension-refresh-models")
            val extension =
                root.resolve("refresh-models.ts").also { path ->
                    Files.writeString(
                        path,
                        """
                        export default function(pi) {
                          pi.registerProvider("dynamic-provider", {
                            name: "Dynamic Provider",
                            baseUrl: "https://dynamic.invalid/v1",
                            apiKey: "local-key",
                            api: "openai-completions",
                            async refreshModels(context) {
                              const cached = await context.store.read();
                              return [{
                                id: `live-${'$'}{cached?.models?.[0]?.id ?? "empty"}`,
                                name: [
                                  context.credential?.key,
                                  context.allowNetwork,
                                  context.force,
                                ].join("|"),
                                reasoning: false,
                                input: ["text"],
                                cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
                                contextWindow: 8192,
                                maxTokens: 1024,
                              }];
                            },
                          });
                        }
                        """.trimIndent(),
                    )
                }
            val host = startHost(root, extension)
            val modelsStore = InMemoryModelsStore()
            val cached =
                ModelsStoreEntry(
                    models =
                        listOf(
                            Model(
                                id = "cached",
                                name = "Cached",
                                api = "openai-completions",
                                provider = "dynamic-provider",
                                baseUrl = "https://cached.invalid/v1",
                                reasoning = false,
                                input = listOf(works.earendil.pi.ai.ModelInput.TEXT),
                                cost = works.earendil.pi.ai.ModelCost(0.0, 0.0, 0.0, 0.0),
                                contextWindow = 8_192,
                                maxTokens = 1_024,
                            ),
                        ),
                    checkedAt = 123,
                )
            modelsStore.write("dynamic-provider", cached)
            val models =
                Models(
                    providers = emptyList(),
                    modelsStore = modelsStore,
                )
            val registry = ExtensionProviderRegistry(models, extensionHost = { host })
            registry.apply(host.registrations.providers) { error(it) }

            try {
                assertEquals(emptyList(), models.getModels("dynamic-provider"))
                val refresh =
                    models.refresh(
                        ModelsRefreshOptions(
                            allowNetwork = false,
                            force = true,
                        ),
                    )
                assertTrue(refresh.errors.isEmpty())
                val live = assertNotNull(models.getModel("dynamic-provider", "live-cached"))
                assertEquals("|false|false", live.name)
                assertEquals(cached, modelsStore.read("dynamic-provider"))
            } finally {
                registry.reset()
                host.close()
            }
        }

    private class ScriptedAuthInteraction : AuthInteraction {
        val prompts = mutableListOf<AuthPrompt>()
        val events = mutableListOf<AuthEvent>()

        override suspend fun prompt(prompt: AuthPrompt): String {
            prompts += prompt
            return when (prompt) {
                is AuthPrompt.Text -> "alice"
                is AuthPrompt.ManualCode -> "manual"
                is AuthPrompt.Select -> "team"
            }
        }

        override fun notify(event: AuthEvent) {
            events += event
        }
    }

    private fun startHost(
        root: java.nio.file.Path,
        extension: java.nio.file.Path,
        environment: Map<String, String> = System.getenv(),
    ): ExtensionHost =
        assertNotNull(
            ExtensionHost.start(
                sources =
                    listOf(
                        ExtensionSource(
                            extension,
                            ResourceSourceInfo(extension, "local", baseDir = root),
                        ),
                    ),
                agentDir = Files.createDirectories(root.resolve("agent")),
                cwd = root,
                mode = ExtensionMode.RPC,
                projectTrusted = true,
                flagValues = emptyMap(),
                context =
                    buildJsonObject {
                        put("cwd", root.toString())
                        put("mode", "rpc")
                        put("hasUI", false)
                        put("projectTrusted", true)
                        put("thinkingLevel", "off")
                        put("systemPrompt", "")
                        put("activeTools", JsonArray(emptyList()))
                        put("allTools", JsonArray(emptyList()))
                        put("isIdle", true)
                        put("hasPendingMessages", false)
                        put("flags", buildJsonObject {})
                    },
                environment = environment,
            ),
        )

    private fun nodeAvailable(): Boolean =
        runCatching {
            val process = ProcessBuilder("node", "--version").start()
            process.waitFor()
            process.exitValue() == 0 &&
                process.inputStream.bufferedReader().readText().trim().removePrefix("v").substringBefore('.').toInt() >= 22
        }.getOrDefault(false)
}
