import { realpathSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const targetRoot = resolve(import.meta.dirname, "../..");
const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const fixture = realpathSync(resolve(targetRoot, "migration/fixtures/extension-runtime/basic.ts"));
const loader = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/extensions/loader.ts")).href
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

const output = {
	errors: loaded.errors,
	registrations: {
		tools: [...extension.tools.values()].map(({ definition }) => ({
			name: definition.name,
			label: definition.label,
			description: definition.description,
			parameters: definition.parameters,
			executionMode: definition.executionMode ?? null,
		})),
		commands: [...extension.commands.values()].map(value => ({
			name: value.name,
			description: value.description ?? null,
		})),
		flags: [...extension.flags.values()].map(value => ({
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
	tool: {
		result: toolResult,
		updates,
	},
	commandActions,
	sessionActions,
	beforeAgentStart,
	toolCall,
	toolResult: toolResultPatch,
	resourcesDiscover,
};

process.stdout.write(`${JSON.stringify(output)}\n`);
