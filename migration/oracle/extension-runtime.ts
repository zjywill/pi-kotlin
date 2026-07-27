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
const initialTools = [...extension.tools.values()];
const initialCommands = [...extension.commands.values()];
const initialFlags = [...extension.flags.values()];

function context(actions: unknown[]) {
	return {
		cwd: dirname(fixture),
		mode: "print",
		hasUI: false,
		ui: {
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
		providers: runtime.pendingProviderRegistrations.map(value => ({
			name: value.name,
			config: value.config,
		})),
		events: [...extension.handlers.keys()].sort(),
	},
	dynamicRegistrations: {
		tools: [...extension.tools.values()].map(({ definition }) => definition.name),
		commands: [...extension.commands.values()].map(value => value.name),
		flags: [...extension.flags.values()].map(value => value.name),
	},
	tool: {
		result: toolResult,
		updates,
	},
	commandActions,
	sessionActions,
	projectTrust,
	beforeAgentStart,
	toolCall,
	toolResult: toolResultPatch,
	resourcesDiscover,
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
};

process.stdout.write(`${JSON.stringify(output)}\n`);
