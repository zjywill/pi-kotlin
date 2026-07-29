import { realpathSync } from "node:fs";
import { basename, dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const targetRoot = resolve(import.meta.dirname, "../..");
const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const fixture = realpathSync(resolve(targetRoot, "migration/fixtures/extension-runtime/basic.ts"));
const loader = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/extensions/loader.ts")).href
);
const skillsModule = await import(pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/skills.ts")).href);
const promptsModule = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/prompt-templates.ts")).href
);
const providerComposer = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/provider-composer.ts")).href
);

const runtime = loader.createExtensionRuntime();
runtime.flagValues.set("loud", true);
const runtimeActions: unknown[] = [];
runtime.appendEntry = (customType: string, data?: unknown) => {
	runtimeActions.push({ type: "append_entry", customType, data });
};
runtime.sendMessage = () => {};
runtime.sendUserMessage = () => {};
runtime.setSessionName = () => {};
runtime.getSessionName = () => undefined;
runtime.setLabel = () => {};
runtime.getActiveTools = () => ["extension_echo"];
runtime.getAllTools = () => [];
runtime.setActiveTools = () => {};
runtime.getCommands = () => [];
runtime.setModel = async () => true;
runtime.getThinkingLevel = () => "off";
runtime.setThinkingLevel = () => {};

const loaded = await loader.loadExtensions([fixture], dirname(fixture), undefined, runtime);
if (loaded.extensions.length !== 1) {
	throw new Error(`Expected one extension, received ${loaded.extensions.length}`);
}
const extension = loaded.extensions[0];
const tool = extension.tools.get("extension_echo")?.definition;
if (!tool) throw new Error("extension_echo was not registered");
const command = [...extension.commands.values()].find((value) => value.name === "record");
if (!command) throw new Error("record was not registered");
const dialogsCommand = [...extension.commands.values()].find((value) => value.name === "dialogs");
if (!dialogsCommand) throw new Error("dialogs was not registered");
const scheduleBackgroundCommand = [...extension.commands.values()].find(
	value => value.name === "schedule-background",
);
if (!scheduleBackgroundCommand) throw new Error("schedule-background was not registered");
const initialTools = [...extension.tools.values()];
const initialCommands = [...extension.commands.values()];
const initialFlags = [...extension.flags.values()];

function context(actions: unknown[]) {
	return {
		cwd: dirname(fixture),
		mode: "print",
		hasUI: false,
		ui: {
			async select(title: string, options: string[]) {
				actions.push({ type: "ui_dialog", method: "select", title, options });
				return "beta";
			},
			async confirm(title: string, message: string) {
				actions.push({ type: "ui_dialog", method: "confirm", title, message });
				return true;
			},
			async input(title: string, placeholder?: string) {
				actions.push({ type: "ui_dialog", method: "input", title, placeholder });
				return "Ada";
			},
			async editor(title: string, prefill?: string) {
				actions.push({ type: "ui_dialog", method: "editor", title, prefill });
				return "edited";
			},
			notify(message: string, notifyType = "info") {
				actions.push({ type: "ui", method: "notify", message, notifyType });
			},
			setStatus(key: string, text: string) {
				actions.push({ type: "ui", method: "setStatus", key, text });
			},
		},
		model: undefined,
		signal: undefined,
		sessionManager: {},
		isIdle: () => true,
		isProjectTrusted: () => true,
		hasPendingMessages: () => false,
		getContextUsage: () => undefined,
		getSystemPrompt: () => "base",
		getSystemPromptOptions: () => ({ cwd: dirname(fixture) }),
		abort: () => {},
		shutdown: () => {},
		compact: () => {},
		waitForIdle: async () => {},
		newSession: async () => ({ cancelled: false }),
		fork: async () => ({ cancelled: false }),
		navigateTree: async () => ({ cancelled: false }),
		switchSession: async () => ({ cancelled: false }),
		reload: async () => {},
	};
}

const updates: unknown[] = [];
const toolResult = await tool.execute(
	"call-1",
	{ text: "hello", suffix: "!" },
	undefined,
	update => updates.push(update),
	context([]) as never,
);

const commandActions: unknown[] = [];
await command.handler("checkpoint", context(commandActions) as never);
commandActions.unshift(...runtimeActions.splice(0));
const dynamicTools = [...extension.tools.values()].map(({ definition }) => definition.name);
const dynamicCommands = [...extension.commands.values()].map(value => value.name);
const dynamicFlags = [...extension.flags.values()].map(value => value.name);

const dialogActions: unknown[] = [];
await dialogsCommand.handler("", context(dialogActions) as never);
dialogActions.unshift(...runtimeActions.splice(0));

await scheduleBackgroundCommand.handler("", context([]) as never);
for (let attempt = 0; attempt < 200; attempt++) {
	const ready =
		extension.tools.has("background_echo") &&
		[...extension.commands.values()].some(value => value.name === "background-command") &&
		extension.flags.has("background-flag") &&
		runtime.pendingProviderRegistrations.some(value => value.name === "background-provider");
	if (ready) break;
	await new Promise(resolve => setTimeout(resolve, 5));
}
const backgroundTool = extension.tools.get("background_echo")?.definition;
if (!backgroundTool) throw new Error("background tool was not registered");
const backgroundCommand = [...extension.commands.values()].find(value => value.name === "background-command");
if (!backgroundCommand) throw new Error("background command was not registered");
const backgroundProvider = runtime.pendingProviderRegistrations.find(value => value.name === "background-provider");
if (!backgroundProvider) throw new Error("background provider was not registered");
const backgroundToolResult = await backgroundTool.execute(
	"background-call",
	{ text: "ready" },
	undefined,
	undefined,
	context([]) as never,
);
const backgroundCommandActions: unknown[] = [];
await backgroundCommand.handler("", context(backgroundCommandActions) as never);

const sessionActions: unknown[] = [];
for (const handler of extension.handlers.get("session_start") ?? []) {
	await handler({ type: "session_start", reason: "startup" }, context(sessionActions));
}

let projectTrust: unknown;
for (const handler of extension.handlers.get("project_trust") ?? []) {
	const value = await handler({ type: "project_trust", cwd: dirname(fixture) }, context([]));
	if (value?.trusted && value.trusted !== "undecided") {
		projectTrust = value;
		break;
	}
}

let beforeAgentStart: unknown;
for (const handler of extension.handlers.get("before_agent_start") ?? []) {
	beforeAgentStart = await handler(
		{ type: "before_agent_start", prompt: "hello", systemPrompt: "base", systemPromptOptions: { cwd: dirname(fixture) } },
		context([]),
	);
}

let toolCall: unknown;
for (const handler of extension.handlers.get("tool_call") ?? []) {
	toolCall = await handler(
		{ type: "tool_call", toolName: "bash", toolCallId: "call-2", input: { block: true } },
		context([]),
	);
}

let toolResultPatch: unknown;
for (const handler of extension.handlers.get("tool_result") ?? []) {
	const event = {
		type: "tool_result",
		toolName: "extension_echo",
		toolCallId: "call-1",
		input: { text: "hello" },
		content: [{ type: "text", text: "hello" }],
		details: {},
		isError: false,
	};
	const patch = await handler(event, context([]));
	const current = { ...event, ...(patch ?? {}) };
	toolResultPatch = {
		content: current.content,
		details: current.details,
		isError: current.isError,
	};
}

let resourcesDiscover: unknown;
for (const handler of extension.handlers.get("resources_discover") ?? []) {
	resourcesDiscover = await handler(
		{ type: "resources_discover", cwd: dirname(fixture), reason: "startup" },
		context([]),
	);
}

let userBashResult: unknown;
for (const handler of extension.handlers.get("user_bash") ?? []) {
	const handled = await handler(
		{
			type: "user_bash",
			command: "hostname",
			excludeFromContext: false,
			cwd: dirname(fixture),
		},
		context([]),
	);
	if (handled?.result) {
		userBashResult = handled.result;
		break;
	}
	if (handled?.operations) {
		const chunks: string[] = [];
		const result = await handled.operations.exec("hostname", dirname(fixture), {
			onData: data => chunks.push(data.toString("utf8")),
		});
		userBashResult = {
			output: chunks.join(""),
			exitCode: result.exitCode,
			cancelled: false,
			truncated: false,
		};
		break;
	}
}

const resourceResult = resourcesDiscover as {
	skillPaths?: string[];
	promptPaths?: string[];
	themePaths?: string[];
};
const loadedSkills = skillsModule.loadSkills({
	cwd: dirname(fixture),
	agentDir: dirname(fixture),
	skillPaths: resourceResult.skillPaths ?? [],
	includeDefaults: false,
});
const loadedPrompts = promptsModule.loadPromptTemplates({
	cwd: dirname(fixture),
	agentDir: dirname(fixture),
	promptPaths: resourceResult.promptPaths ?? [],
	includeDefaults: false,
});

const providerRegistration = runtime.pendingProviderRegistrations.find(value => value.name === "fixture-provider");
if (!providerRegistration) throw new Error("fixture provider was not registered");
providerComposer.validateExtensionProvider(
	providerRegistration.name,
	undefined,
	undefined,
	providerRegistration.config,
);
const providerModel = providerRegistration.config.models?.[0];
if (!providerModel) throw new Error("fixture provider model was not registered");
const registeredModel = {
	...providerModel,
	api: providerModel.api ?? providerRegistration.config.api,
	provider: providerRegistration.name,
	baseUrl: providerModel.baseUrl ?? providerRegistration.config.baseUrl,
};
let invalidProviderRejected = false;
try {
	providerComposer.validateExtensionProvider("fixture-provider", undefined, undefined, {
		models: [{
			id: registeredModel.id,
			name: registeredModel.name,
			reasoning: registeredModel.reasoning,
			input: registeredModel.input,
			cost: registeredModel.cost,
			contextWindow: registeredModel.contextWindow,
			maxTokens: registeredModel.maxTokens,
		}],
	});
} catch {
	invalidProviderRejected = true;
}

const callbackRegistration = runtime.pendingProviderRegistrations.find(value => value.name === "callback-provider");
if (!callbackRegistration) throw new Error("callback provider was not registered");
const callbackModelConfig = callbackRegistration.config.models?.[0];
if (!callbackModelConfig) throw new Error("callback provider model was not registered");
const callbackModel = {
	...callbackModelConfig,
	api: callbackModelConfig.api ?? callbackRegistration.config.api,
	provider: callbackRegistration.name,
	baseUrl: callbackModelConfig.baseUrl ?? callbackRegistration.config.baseUrl,
};
const callbackStream = callbackRegistration.config.streamSimple?.(
	callbackModel as never,
	{
		messages: [{ role: "user", content: "hello", timestamp: 1 }],
		tools: [],
	},
	{
		apiKey: "callback-key",
		reasoning: "high",
		sessionId: "oracle-session",
	},
);
if (!callbackStream) throw new Error("callback provider streamSimple was not registered");
const callbackEvents: unknown[] = [];
for await (const event of callbackStream) callbackEvents.push(event);
const callbackResult = await callbackStream.result();

const oauth = callbackRegistration.config.oauth;
if (!oauth) throw new Error("callback provider OAuth was not registered");
const oauthActions: unknown[] = [];
const loggedIn = await oauth.login({
	onAuth(info) {
		oauthActions.push({ type: "auth_url", ...info });
	},
	onDeviceCode(info) {
		oauthActions.push({ type: "device_code", ...info });
	},
	async onPrompt(prompt) {
		oauthActions.push({ type: "prompt", method: "text", ...prompt });
		return "alice";
	},
	onProgress(message) {
		oauthActions.push({ type: "progress", message });
	},
	async onManualCodeInput() {
		oauthActions.push({
			type: "prompt",
			method: "manual_code",
			message: "Paste the authorization code",
		});
		return "manual";
	},
	async onSelect(prompt) {
		oauthActions.push({ type: "prompt", method: "select", ...prompt });
		return "team";
	},
});
const refreshed = await oauth.refreshToken(loggedIn);
const callbackApiKey = oauth.getApiKey(refreshed);
const modifiedModels = oauth.modifyModels?.([callbackModel as never], refreshed) ?? [callbackModel];

const nativeRegistration = runtime.pendingNativeProviderRegistrations.find(
	value => value.provider.id === "native-provider",
);
if (!nativeRegistration) throw new Error("native provider was not registered");
const nativeProvider = nativeRegistration.provider;
const nativeRegistrationModels = nativeProvider.getModels();
await nativeProvider.auth.apiKey?.check?.({
	ctx: {
		env: async () => undefined,
		fileExists: async () => true,
	},
	credential: undefined,
});
const nativeCredential = await nativeProvider.auth.apiKey?.login?.({
	prompt: async () => "native-key",
	notify: () => {},
});
if (!nativeCredential) throw new Error("native API-key login was not registered");
const nativeAuthContext = {
	env: async (name: string) => name === "NATIVE_ACCOUNT" ? "oracle" : undefined,
	fileExists: async () => true,
};
const nativeCheck = await nativeProvider.auth.apiKey?.check?.({
	ctx: nativeAuthContext,
	credential: nativeCredential,
});
const nativeAuth = await nativeProvider.auth.apiKey?.resolve({
	ctx: nativeAuthContext,
	credential: nativeCredential,
});
const nativeStored = {
	models: [{
		...nativeProvider.getModels()[0],
		id: "cached",
		name: "Cached",
	}],
	checkedAt: 123,
};
let nativeWritten: unknown;
await nativeProvider.refreshModels?.({
	credential: { ...nativeCredential, env: { NATIVE_ACCOUNT: "oracle" } },
	store: {
		read: async () => nativeStored,
		write: async entry => {
			nativeWritten = entry;
		},
		delete: async () => {},
	},
	allowNetwork: false,
	force: true,
});
const nativeModels = nativeProvider.getModels();
const nativeFiltered = nativeProvider.filterModels?.(nativeModels, nativeCredential) ?? nativeModels;
const nativeStream = nativeProvider.stream(
	nativeModels[0] as never,
	{ messages: [], tools: [] },
	{ apiKey: "native-key" } as never,
);
const nativeStreamEvents: unknown[] = [];
for await (const event of nativeStream) nativeStreamEvents.push(event);
const nativeStreamResult = await nativeStream.result();
const nativeSimpleStream = nativeProvider.streamSimple(
	nativeModels[0] as never,
	{ messages: [], tools: [] },
	{ apiKey: "native-key" },
);
for await (const _event of nativeSimpleStream) {
	// Drain the provider stream before reading its result.
}
const nativeSimpleResult = await nativeSimpleStream.result();

const dynamicRegistration = runtime.pendingProviderRegistrations.find(value => value.name === "dynamic-provider");
if (!dynamicRegistration?.config.refreshModels) throw new Error("dynamic refreshModels was not registered");
const dynamicStored = {
	models: [{
		id: "cached",
		name: "Cached",
		api: "openai-completions",
		provider: "dynamic-provider",
		baseUrl: "https://dynamic.invalid/v1",
		reasoning: false,
		input: ["text"] as const,
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 8192,
		maxTokens: 1024,
	}],
	checkedAt: 123,
};
const dynamicModels = await dynamicRegistration.config.refreshModels({
	credential: { type: "api_key", key: "dynamic-key" },
	store: {
		read: async () => dynamicStored,
		write: async () => {},
		delete: async () => {},
	},
	allowNetwork: false,
	force: true,
});

const output = {
	errors: loaded.errors,
	registrations: {
		tools: initialTools.map(({ definition }) => ({
			name: definition.name,
			label: definition.label,
			description: definition.description,
			parameters: definition.parameters,
			executionMode: definition.executionMode ?? null,
		})),
		commands: initialCommands.map(value => ({
			name: value.name,
			description: value.description ?? null,
		})),
		flags: initialFlags.map(value => ({
			name: value.name,
			description: value.description ?? null,
			type: value.type,
			default: value.default ?? null,
			value: runtime.flagValues.get(value.name) ?? null,
		})),
		providers: [
			...runtime.pendingProviderRegistrations
				.map(value => ({
					name: value.name,
					config: value.config,
				}))
				.filter(value => value.name !== "background-provider"),
			...runtime.pendingNativeProviderRegistrations.map(value => ({
				name: value.provider.id,
				config: {
					name: value.provider.name,
					baseUrl: value.provider.baseUrl,
					headers: value.provider.headers,
					auth: {
						apiKey: value.provider.auth.apiKey
							? { name: value.provider.auth.apiKey.name }
							: undefined,
						oauth: value.provider.auth.oauth
							? {
									name: value.provider.auth.oauth.name,
									loginLabel: value.provider.auth.oauth.loginLabel,
								}
							: undefined,
					},
					models: value.provider.id === "native-provider"
						? nativeRegistrationModels
						: value.provider.getModels(),
				},
			})),
		].sort((a, b) => a.name.localeCompare(b.name)),
		events: [...extension.handlers.keys()].sort(),
	},
	dynamicRegistrations: {
		tools: dynamicTools,
		commands: dynamicCommands,
		flags: dynamicFlags,
	},
	backgroundRegistrations: {
		tools: [...extension.tools.values()].map(({ definition }) => definition.name),
		commands: [...extension.commands.values()].map(value => value.name),
		flags: [...extension.flags.values()].map(value => value.name),
		providerModelIds: backgroundProvider.config.models?.map(model => model.id) ?? [],
		toolResult: backgroundToolResult,
		commandActions: backgroundCommandActions,
	},
	tool: {
		result: toolResult,
		updates,
	},
	commandActions,
	dialogActions,
	sessionActions,
	projectTrust,
	beforeAgentStart,
	toolCall,
	toolResult: toolResultPatch,
	resourcesDiscover,
	userBash: userBashResult,
	composedResources: {
		skills: loadedSkills.skills.map(skill => skill.name),
		prompts: loadedPrompts.map(prompt => prompt.name),
		themes: (resourceResult.themePaths ?? []).map(path => basename(path)),
	},
	providerRuntime: {
		model: {
			id: registeredModel.id,
			name: registeredModel.name,
			api: registeredModel.api,
			provider: registeredModel.provider,
			baseUrl: registeredModel.baseUrl,
			reasoning: registeredModel.reasoning,
			input: registeredModel.input,
			cost: registeredModel.cost,
			contextWindow: registeredModel.contextWindow,
			maxTokens: registeredModel.maxTokens,
		},
		invalidProviderRejected,
		preservedModelId: registeredModel.id,
	},
	providerCallbacks: {
		stream: {
			eventTypes: callbackEvents.map(event => (event as { type: string }).type),
			deltas: callbackEvents
				.filter(event => (event as { type: string }).type === "text_delta")
				.map(event => (event as { delta: string }).delta),
			text: callbackResult.content
				.filter(content => content.type === "text")
				.map(content => content.text)
				.join(""),
			stopReason: callbackResult.stopReason,
		},
		oauth: {
			actions: oauthActions,
			loggedIn: {
				access: loggedIn.access,
				refresh: loggedIn.refresh,
				expires: loggedIn.expires,
				tenant: loggedIn.tenant,
			},
			refreshed: {
				access: refreshed.access,
				refresh: refreshed.refresh,
				expires: refreshed.expires,
				tenant: refreshed.tenant,
			},
			apiKey: callbackApiKey,
			modelIds: modifiedModels.map(model => model.id),
		},
		native: {
			check: { type: nativeCheck?.type },
			auth: nativeAuth,
			modelIds: nativeModels.map(model => model.id),
			filteredModelIds: nativeFiltered.map(model => model.id),
			written: nativeWritten,
			streamEventTypes: nativeStreamEvents.map(event => (event as { type: string }).type),
			streamText: nativeStreamResult.content
				.filter(content => content.type === "text")
				.map(content => content.text)
				.join(""),
			simpleText: nativeSimpleResult.content
				.filter(content => content.type === "text")
				.map(content => content.text)
				.join(""),
		},
		refreshModels: {
			modelIds: dynamicModels.map(model => model.id),
			storeUnchanged: dynamicStored,
		},
	},
};

process.stdout.write(`${JSON.stringify(output)}\n`);
