import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { registerHooks } from "node:module";
import readline from "node:readline";
import { createJiti } from "./jiti/lib/jiti-static.mjs";

const protocolWrite = process.stdout.write.bind(process.stdout);
const log = (...values) => process.stderr.write(`${values.map(formatLogValue).join(" ")}\n`);
console.log = log;
console.info = log;
console.warn = log;
console.error = log;
console.debug = log;

function formatLogValue(value) {
	if (typeof value === "string") return value;
	try {
		return JSON.stringify(value);
	} catch {
		return String(value);
	}
}

const TYPEBOX_SOURCE = String.raw`
const optionalMarker = Symbol.for("pi-kotlin.typebox.optional");
const clone = value => value && typeof value === "object" ? { ...value } : value;
const withOptions = (schema, options) => ({ ...schema, ...(options ?? {}) });
const primitive = (type, options) => withOptions({ type }, options);
const Type = {
	Any: options => withOptions({}, options),
	Unknown: options => withOptions({}, options),
	String: options => primitive("string", options),
	Number: options => primitive("number", options),
	Integer: options => primitive("integer", options),
	Boolean: options => primitive("boolean", options),
	Null: options => withOptions({ type: "null" }, options),
	Literal: (value, options) => withOptions({ const: value, type: value === null ? "null" : typeof value }, options),
	Array: (items, options) => withOptions({ type: "array", items }, options),
	Tuple: (items, options) => withOptions({ type: "array", items, minItems: items.length, maxItems: items.length }, options),
	Union: (items, options) => withOptions({ anyOf: items }, options),
	Intersect: (items, options) => withOptions({ allOf: items }, options),
	Record: (key, value, options) => withOptions({ type: "object", additionalProperties: value }, options),
	Object: (properties, options) => {
		const required = Object.entries(properties)
			.filter(([, schema]) => !schema?.[optionalMarker])
			.map(([name]) => name);
		return withOptions({
			type: "object",
			properties,
			...(required.length > 0 ? { required } : {}),
		}, options);
	},
	Optional: schema => {
		const result = clone(schema);
		Object.defineProperty(result, optionalMarker, { value: true, enumerable: false });
		return result;
	},
	Partial: schema => {
		const properties = Object.fromEntries(
			Object.entries(schema.properties ?? {}).map(([name, value]) => [name, Type.Optional(value)]),
		);
		return Type.Object(properties, { ...schema, required: undefined });
	},
	Enum: (values, options) => {
		const items = Object.values(values).filter(value => typeof value === "string" || typeof value === "number");
		return withOptions({ enum: [...new Set(items)] }, options);
	},
	Unsafe: schema => schema,
};
export { Type };
export default Type;
`;

const PI_AI_SOURCE = String.raw`
export { Type } from "virtual:pi-typebox";
export const StringEnum = (values, options = {}) => ({ type: "string", enum: [...values], ...options });
export const uuidv7 = () => globalThis.crypto.randomUUID();
export class EventStream {
	constructor(isComplete, extractResult) {
		this.queue = [];
		this.waiting = [];
		this.done = false;
		this.isComplete = isComplete;
		this.extractResult = extractResult;
		this.finalResultPromise = new Promise(resolve => {
			this.resolveFinalResult = resolve;
		});
	}
	push(event) {
		if (this.done) return;
		if (this.isComplete(event)) {
			this.done = true;
			this.resolveFinalResult(this.extractResult(event));
		}
		const waiter = this.waiting.shift();
		if (waiter) waiter({ value: event, done: false });
		else this.queue.push(event);
	}
	end(result) {
		this.done = true;
		if (result !== undefined) this.resolveFinalResult(result);
		for (const waiter of this.waiting.splice(0)) waiter({ value: undefined, done: true });
	}
	async *[Symbol.asyncIterator]() {
		while (true) {
			if (this.queue.length > 0) yield this.queue.shift();
			else if (this.done) return;
			else {
				const result = await new Promise(resolve => this.waiting.push(resolve));
				if (result.done) return;
				yield result.value;
			}
		}
	}
	result() {
		return this.finalResultPromise;
	}
}
export class AssistantMessageEventStream extends EventStream {
	constructor() {
		super(
			event => event?.type === "done" || event?.type === "error",
			event => event.type === "done" ? event.message : event.error,
		);
	}
}
export const createAssistantMessageEventStream = () => new AssistantMessageEventStream();
const unsupported = name => (..._args) => { throw new Error(name + " is not available in the pi-kotlin extension host"); };
export const complete = unsupported("complete");
export const getModel = unsupported("getModel");
export const registerApiProvider = unsupported("registerApiProvider");
export const streamSimple = unsupported("streamSimple");
`;

const CODING_AGENT_SOURCE = String.raw`
export const CONFIG_DIR_NAME = ".pi";
export const VERSION = "0.1.0-SNAPSHOT";
export const defineTool = tool => tool;
export const getAgentDir = () => process.env.PI_CODING_AGENT_DIR || (process.env.HOME + "/.pi/agent");
export const parseFrontmatter = content => {
	if (!content.startsWith("---")) return { data: {}, content };
	const end = content.indexOf("\n---", 3);
	if (end < 0) return { data: {}, content };
	const data = {};
	for (const line of content.slice(4, end).split(/\r?\n/)) {
		const split = line.indexOf(":");
		if (split > 0) data[line.slice(0, split).trim()] = line.slice(split + 1).trim();
	}
	return { data, content: content.slice(end + 4).replace(/^\r?\n/, "") };
};
export const withFileMutationQueue = async callback => callback();
export const convertToLlm = messages => messages;
export const serializeConversation = messages => JSON.stringify(messages);
export const getMarkdownTheme = () => ({});
export const getSettingsListTheme = () => ({});
const unsupported = name => (..._args) => { throw new Error(name + " is not available in the pi-kotlin extension host"); };
export const createBashTool = unsupported("createBashTool");
export const createEditTool = unsupported("createEditTool");
export const createReadTool = unsupported("createReadTool");
export const createWriteTool = unsupported("createWriteTool");
export class DynamicBorder {}
export class BorderedLoader {}
export class CustomEditor {}
`;

const PI_TUI_SOURCE = String.raw`
export const Key = {
	ctrl: key => "ctrl+" + key,
	shift: key => "shift+" + key,
	alt: key => "alt+" + key,
};
export const matchesKey = (input, key) => input === key;
export const isKeyRelease = () => false;
export const parseKey = key => key;
export const visibleWidth = value => [...String(value)].length;
export const truncateToWidth = (value, width) => [...String(value)].slice(0, width).join("");
export class Component {}
export class Container extends Component {}
export class Text extends Component {}
export class Box extends Component {}
export class Markdown extends Component {}
export class Spacer extends Component {}
export class Input extends Component {}
export class SettingsList extends Component {}
export class SelectList extends Component {}
export const CURSOR_MARKER = "";
`;

const TYPEBOX_COMPILE_SOURCE = String.raw`
const check = (schema, value) => {
	if (!schema || Object.keys(schema).length === 0) return true;
	if (schema.const !== undefined) return value === schema.const;
	if (schema.anyOf) return schema.anyOf.some(item => check(item, value));
	if (schema.allOf) return schema.allOf.every(item => check(item, value));
	if (Array.isArray(schema.type)) return schema.type.some(type => check({ ...schema, type }, value));
	if (schema.type === "object") {
		if (!value || typeof value !== "object" || Array.isArray(value)) return false;
		return (schema.required ?? []).every(name => Object.hasOwn(value, name));
	}
	if (schema.type === "array") {
		return Array.isArray(value) && (!schema.items || value.every(item => check(schema.items, item)));
	}
	if (schema.type === "integer") return Number.isInteger(value);
	if (schema.type === "null") return value === null;
	return schema.type === undefined || typeof value === schema.type;
};
export const TypeCompiler = { Compile: schema => ({ Check: value => check(schema, value) }) };
export const Compile = schema => ({
	Check: value => check(schema, value),
	Code: () => {
		const serialized = JSON.stringify(schema);
		return "const check = " + check.toString() + "; return value => check(" + serialized + ", value);";
	},
});
`;

const TYPEBOX_VALUE_SOURCE = String.raw`
export const Value = {
	Check: (schema, value) => {
		if (Array.isArray(schema?.type)) return schema.type.some(type => Value.Check({ ...schema, type }, value));
		if (schema?.type === "object") return !!value && typeof value === "object" && !Array.isArray(value);
		if (schema?.type === "array") {
			return Array.isArray(value) && (!schema.items || value.every(item => Value.Check(schema.items, item)));
		}
		if (schema?.type === "integer") return Number.isInteger(value);
		if (schema?.type === "null") return value === null;
		return schema?.type === undefined || typeof value === schema.type;
	},
};
`;

const EMPTY_SOURCE = "export {};";
const virtualModules = new Map([
	["virtual:pi-typebox", TYPEBOX_SOURCE],
	["virtual:pi-ai", PI_AI_SOURCE],
	["virtual:pi-coding-agent", CODING_AGENT_SOURCE],
	["virtual:pi-tui", PI_TUI_SOURCE],
	["virtual:typebox-compile", TYPEBOX_COMPILE_SOURCE],
	["virtual:typebox-value", TYPEBOX_VALUE_SOURCE],
	["virtual:empty", EMPTY_SOURCE],
]);
const aliases = new Map([
	["typebox", "virtual:pi-typebox"],
	["@sinclair/typebox", "virtual:pi-typebox"],
	["typebox/compile", "virtual:typebox-compile"],
	["@sinclair/typebox/compile", "virtual:typebox-compile"],
	["typebox/value", "virtual:typebox-value"],
	["@sinclair/typebox/value", "virtual:typebox-value"],
	["@earendil-works/pi-ai", "virtual:pi-ai"],
	["@earendil-works/pi-ai/compat", "virtual:pi-ai"],
	["@earendil-works/pi-ai/oauth", "virtual:empty"],
	["@earendil-works/pi-ai/providers/all", "virtual:empty"],
	["@mariozechner/pi-ai", "virtual:pi-ai"],
	["@mariozechner/pi-ai/compat", "virtual:pi-ai"],
	["@mariozechner/pi-ai/oauth", "virtual:empty"],
	["@mariozechner/pi-ai/providers/all", "virtual:empty"],
	["@earendil-works/pi-coding-agent", "virtual:pi-coding-agent"],
	["@mariozechner/pi-coding-agent", "virtual:pi-coding-agent"],
	["@earendil-works/pi-tui", "virtual:pi-tui"],
	["@mariozechner/pi-tui", "virtual:pi-tui"],
	["@earendil-works/pi-agent-core", "virtual:empty"],
	["@mariozechner/pi-agent-core", "virtual:empty"],
]);

registerHooks({
	resolve(specifier, context, nextResolve) {
		const alias = aliases.get(specifier);
		if (alias) return { url: alias, shortCircuit: true };
		return nextResolve(specifier, context);
	},
	load(url, context, nextLoad) {
		const source = virtualModules.get(url);
		if (source !== undefined) {
			return { format: "module", source, shortCircuit: true };
		}
		return nextLoad(url, context);
	},
});

const virtualModuleNamespaces = new Map(
	await Promise.all(
		[...new Set(aliases.values())].map(async specifier => [specifier, await import(specifier)]),
	),
);
const jitiVirtualModules = Object.fromEntries(
	[...aliases].map(([specifier, target]) => [specifier, virtualModuleNamespaces.get(target)]),
);

const state = {
	cwd: process.cwd(),
	mode: "print",
	hasUI: false,
	projectTrusted: false,
	sessionName: undefined,
	model: undefined,
	scopedModels: [],
	thinkingLevel: "off",
	systemPrompt: "",
	activeTools: [],
	allTools: [],
	flags: new Map(),
};
let extensions = [];
let tools = new Map();
let commands = new Map();
let flags = new Map();
let providers = new Map();
let attemptedPaths = new Set();
let registrationVersion = 0;
let currentActions = null;
let pendingBackgroundActions = [];
let backgroundPushScheduled = false;
let currentProtocolRequestId = null;
const pendingUiRequests = new Map();
const activeBashOperations = new Map();
let providerCallbacks = new Map();
const activeProviderOperations = new Map();
const pendingProviderAuthRequests = new Map();
const pendingProviderBridgeRequests = new Map();

function jsonValue(value) {
	if (value === undefined) return null;
	return JSON.parse(
		JSON.stringify(value, (_key, item) => {
			if (typeof item === "bigint") return item.toString();
			if (typeof item === "function" || typeof item === "symbol") return undefined;
			return item;
		}),
	);
}

function providerCallbackCapabilities(config, native = false) {
	const apiKey = native ? config?.auth?.apiKey : undefined;
	const oauth = native ? config?.auth?.oauth : config?.oauth;
	return {
		native,
		getModels: native && typeof config?.getModels === "function",
		stream: native && typeof config?.stream === "function",
		streamSimple: typeof config?.streamSimple === "function",
		refreshModels: typeof config?.refreshModels === "function",
		filterModels: native && typeof config?.filterModels === "function",
		apiKey: apiKey && typeof apiKey === "object"
			? {
					login: typeof apiKey.login === "function",
					check: typeof apiKey.check === "function",
					resolve: typeof apiKey.resolve === "function",
				}
			: undefined,
		oauth: oauth && typeof oauth === "object"
			? native
				? {
						login: typeof oauth.login === "function",
						refresh: typeof oauth.refresh === "function",
						toAuth: typeof oauth.toAuth === "function",
					}
				: {
						login: typeof oauth.login === "function",
						refreshToken: typeof oauth.refreshToken === "function",
						getApiKey: typeof oauth.getApiKey === "function",
						modifyModels: typeof oauth.modifyModels === "function",
					}
			: undefined,
	};
}

function hasProviderCallbacks(capabilities) {
	return capabilities.getModels ||
		capabilities.stream ||
		capabilities.streamSimple ||
		capabilities.refreshModels ||
		capabilities.filterModels ||
		Object.values(capabilities.apiKey ?? {}).some(Boolean) ||
		Object.values(capabilities.oauth ?? {}).some(Boolean);
}

function nativeProviderMetadata(provider) {
	let models = [];
	try {
		models = typeof provider?.getModels === "function" ? provider.getModels() : [];
	} catch {
		models = [];
	}
	return jsonValue({
		__piNative: true,
		name: provider?.name,
		baseUrl: provider?.baseUrl,
		headers: provider?.headers,
		auth: {
			apiKey: provider?.auth?.apiKey
				? { name: provider.auth.apiKey.name }
				: undefined,
			oauth: provider?.auth?.oauth
				? {
						name: provider.auth.oauth.name,
						loginLabel: provider.auth.oauth.loginLabel,
					}
				: undefined,
		},
		models,
	});
}

function providerRegistrationMetadata(registration) {
	const config = registration.native
		? nativeProviderMetadata(registration.config)
		: (jsonValue(registration.config) ?? {});
	const capabilities = providerCallbackCapabilities(registration.config, registration.native);
	if (hasProviderCallbacks(capabilities)) {
		config.__piCallbackToken = registration.callbackToken;
		config.__piCallbacks = capabilities;
	}
	return {
		name: registration.name,
		config,
		extensionPath: registration.extensionPath,
	};
}

function registerProviderConfig(name, config, extensionPath) {
	const previous = providers.get(name);
	if (previous?.callbackToken) providerCallbacks.delete(previous.callbackToken);
	const effective =
		previous && !previous.native
			? { ...previous.config, ...config }
			: config;
	const callbackToken = randomUUID();
	const registration = {
		name,
		config: effective,
		extensionPath,
		callbackToken,
		native: false,
	};
	providers.set(name, registration);
	providerCallbacks.set(callbackToken, registration);
	return registration;
}

function registerNativeProvider(provider, extensionPath) {
	const previous = providers.get(provider.id);
	if (previous?.callbackToken) providerCallbacks.delete(previous.callbackToken);
	const callbackToken = randomUUID();
	const registration = {
		name: provider.id,
		config: provider,
		extensionPath,
		callbackToken,
		native: true,
	};
	providers.set(provider.id, registration);
	providerCallbacks.set(callbackToken, registration);
	return registration;
}

function action(type, payload = {}) {
	const value = { type, ...jsonValue(payload) };
	if (currentActions) {
		currentActions.push(value);
	} else {
		pendingBackgroundActions.push(value);
		scheduleBackgroundActions();
	}
}

function scheduleBackgroundActions() {
	if (currentActions || backgroundPushScheduled) return;
	backgroundPushScheduled = true;
	queueMicrotask(() => {
		backgroundPushScheduled = false;
		if (currentActions) return;
		protocolWrite(
			`${JSON.stringify({
				type: "background_actions",
				actions: pendingBackgroundActions.splice(0),
				registrations: registrationMetadata(),
				registrationVersion,
			})}\n`,
		);
	});
}

function registrationChanged() {
	registrationVersion++;
	scheduleBackgroundActions();
}

function updateState(context = {}) {
	for (const name of [
		"cwd",
		"mode",
		"hasUI",
		"projectTrusted",
		"sessionName",
		"model",
		"scopedModels",
		"thinkingLevel",
		"systemPrompt",
		"activeTools",
		"allTools",
	]) {
		if (Object.hasOwn(context, name)) state[name] = context[name];
	}
	if (context.flags && typeof context.flags === "object") {
		for (const [name, value] of Object.entries(context.flags)) state.flags.set(name, value);
	}
}

function createEventBus() {
	const handlers = new Map();
	return {
		on(name, handler) {
			const list = handlers.get(name) ?? [];
			list.push(handler);
			handlers.set(name, list);
			return () => handlers.set(name, list.filter(item => item !== handler));
		},
		emit(name, data) {
			for (const handler of handlers.get(name) ?? []) handler(data);
		},
	};
}

const sharedEventBus = createEventBus();

function requestUI(method, payload, uiOptions, defaultValue, parseResponse) {
	const parentId = currentProtocolRequestId;
	if (!parentId) return Promise.resolve(defaultValue);
	if (uiOptions?.signal?.aborted) return Promise.resolve(defaultValue);

	const requestId = randomUUID();
	return new Promise(resolve => {
		let timeoutId;
		const signal = uiOptions?.signal;
		const cleanup = () => {
			if (timeoutId) clearTimeout(timeoutId);
			signal?.removeEventListener("abort", onAbort);
			pendingUiRequests.delete(requestId);
		};
		const settle = (value, notifyCancel = false) => {
			if (!pendingUiRequests.has(requestId)) return;
			cleanup();
			if (notifyCancel) {
				protocolWrite(`${JSON.stringify({ type: "ui_cancel", id: parentId, requestId })}\n`);
			}
			resolve(value);
		};
		const onAbort = () => settle(defaultValue, true);
		signal?.addEventListener("abort", onAbort, { once: true });
		if (Number.isFinite(uiOptions?.timeout) && uiOptions.timeout > 0) {
			timeoutId = setTimeout(() => settle(defaultValue, true), uiOptions.timeout);
		}
		pendingUiRequests.set(requestId, {
			resolve: response => settle(parseResponse(response)),
			cancel: () => settle(defaultValue),
		});
		protocolWrite(
			`${JSON.stringify({
				type: "ui_request",
				id: parentId,
				requestId,
				method,
				...payload,
				...(Number.isFinite(uiOptions?.timeout) ? { timeout: uiOptions.timeout } : {}),
			})}\n`,
		);
	});
}

function cancelAllUiRequests() {
	for (const pending of [...pendingUiRequests.values()]) pending.cancel();
}

function createUI() {
	return {
		async select(title, options, uiOptions) {
			return requestUI(
				"select",
				{ title, options },
				uiOptions,
				undefined,
				response => response?.cancelled ? undefined : response?.value,
			);
		},
		async confirm(title, message, uiOptions) {
			return requestUI(
				"confirm",
				{ title, message },
				uiOptions,
				false,
				response => response?.cancelled ? false : response?.confirmed === true,
			);
		},
		async input(title, placeholder, uiOptions) {
			return requestUI(
				"input",
				{ title, placeholder },
				uiOptions,
				undefined,
				response => response?.cancelled ? undefined : response?.value,
			);
		},
		async editor(title, prefill) {
			return requestUI(
				"editor",
				{ title, prefill },
				undefined,
				undefined,
				response => response?.cancelled ? undefined : response?.value,
			);
		},
		notify(message, notifyType = "info") {
			action("ui", { id: randomUUID(), method: "notify", message, notifyType });
		},
		setStatus(key, text) {
			action("ui", { id: randomUUID(), method: "setStatus", key, text });
		},
		setWidget(key, content, options) {
			action("ui", { id: randomUUID(), method: "setWidget", key, content, options });
		},
		setFooter(factory) {
			action("unsupported", { method: "setFooter", enabled: factory != null });
		},
		setHeader(factory) {
			action("unsupported", { method: "setHeader", enabled: factory != null });
		},
		setTitle(title) {
			action("ui", { id: randomUUID(), method: "setTitle", title });
		},
		setEditorText(text) {
			action("ui", { id: randomUUID(), method: "set_editor_text", text });
		},
		getEditorText() {
			return "";
		},
		pasteToEditor(text) {
			action("ui", { id: randomUUID(), method: "set_editor_text", text });
		},
		setWorkingMessage(message) {
			action("ui", { id: randomUUID(), method: "setWorkingMessage", message });
		},
		setWorkingVisible(visible) {
			action("ui", { id: randomUUID(), method: "setWorkingVisible", visible });
		},
		setWorkingIndicator(indicator) {
			action("unsupported", { method: "setWorkingIndicator", enabled: indicator != null });
		},
		setHiddenThinkingLabel(label) {
			action("ui", { id: randomUUID(), method: "setHiddenThinkingLabel", label });
		},
		onTerminalInput() {
			return () => {};
		},
		addAutocompleteProvider() {},
		setEditorComponent() {
			action("unsupported", { method: "setEditorComponent" });
		},
		getEditorComponent() {
			return undefined;
		},
		async custom() {
			return undefined;
		},
		getAllThemes() {
			return [];
		},
		getTheme() {
			return undefined;
		},
		setTheme() {
			return { success: false, error: "Themes are not available in the pi-kotlin extension host" };
		},
		getToolsExpanded() {
			return false;
		},
		setToolsExpanded() {},
		get theme() {
			return {};
		},
	};
}

function contextFor(overrides = {}) {
	updateState(overrides);
	return {
		cwd: state.cwd,
		mode: state.mode,
		hasUI: state.hasUI,
		ui: createUI(),
		model: state.model,
		scopedModels: state.scopedModels,
		signal: undefined,
		sessionManager: {
			getSessionFile: () => overrides.sessionFile,
			getSessionId: () => overrides.sessionId,
			getSessionName: () => state.sessionName,
			getEntries: () => overrides.entries ?? [],
			getBranch: () => overrides.entries ?? [],
		},
		isIdle: () => overrides.isIdle ?? true,
		isProjectTrusted: () => state.projectTrusted,
		hasPendingMessages: () => overrides.hasPendingMessages ?? false,
		getContextUsage: () => overrides.contextUsage,
		getSystemPrompt: () => state.systemPrompt,
		getSystemPromptOptions: () => overrides.systemPromptOptions ?? { cwd: state.cwd },
		abort: () => action("abort"),
		shutdown: () => action("shutdown"),
		compact: options => action("compact", { options }),
		waitForIdle: async () => {},
		newSession: async options => {
			action("new_session", { options });
			return { cancelled: false };
		},
		fork: async (entryId, options) => {
			action("fork", { entryId, options });
			return { cancelled: false };
		},
		navigateTree: async (targetId, options) => {
			action("navigate_tree", { targetId, options });
			return { cancelled: false };
		},
		switchSession: async (sessionPath, options) => {
			action("switch_session", { sessionPath, options });
			return { cancelled: false };
		},
		reload: async () => action("reload"),
	};
}

function runCommand(command, args, options = {}) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, {
			cwd: options.cwd ?? state.cwd,
			env: { ...process.env, ...(options.env ?? {}) },
			shell: options.shell ?? false,
			stdio: ["ignore", "pipe", "pipe"],
		});
		const stdout = [];
		const stderr = [];
		child.stdout.on("data", chunk => stdout.push(chunk));
		child.stderr.on("data", chunk => stderr.push(chunk));
		child.on("error", reject);
		child.on("close", code => {
			resolve({
				stdout: Buffer.concat(stdout).toString("utf8"),
				stderr: Buffer.concat(stderr).toString("utf8"),
				code: code ?? 1,
				killed: child.killed,
			});
		});
	});
}

function createAPI(extension) {
	return {
		on(event, handler) {
			const list = extension.handlers.get(event) ?? [];
			list.push(handler);
			extension.handlers.set(event, list);
			registrationChanged();
		},
		registerTool(tool) {
			const id = `${extension.index}:tool:${tool.name}`;
			extension.tools.set(tool.name, { id, definition: tool });
			if (!tools.has(tool.name)) tools.set(tool.name, { id, extension, definition: tool });
			registrationChanged();
		},
		registerCommand(name, options) {
			const id = `${extension.index}:command:${name}:${extension.commands.size}`;
			extension.commands.set(id, { id, name, options });
			registrationChanged();
		},
		registerShortcut(shortcut, options) {
			const id = `${extension.index}:shortcut:${shortcut}`;
			extension.shortcuts.set(shortcut, { id, shortcut, ...options });
			registrationChanged();
		},
		registerFlag(name, options) {
			const registration = { name, extensionPath: extension.path, ...options };
			extension.flags.set(name, registration);
			if (!flags.has(name)) {
				flags.set(name, registration);
				if (!state.flags.has(name) && options.default !== undefined) state.flags.set(name, options.default);
			}
			registrationChanged();
		},
		registerMessageRenderer(customType, renderer) {
			extension.messageRenderers.set(customType, renderer);
			registrationChanged();
		},
		registerEntryRenderer(customType, renderer) {
			extension.entryRenderers.set(customType, renderer);
			registrationChanged();
		},
		getFlag(name) {
			return extension.flags.has(name) ? state.flags.get(name) : undefined;
		},
		sendMessage(message, options) {
			action("send_message", { message, options });
		},
		sendUserMessage(content, options) {
			action("send_user_message", { content, options });
		},
		appendEntry(customType, data) {
			action("append_entry", { customType, data });
		},
		setSessionName(name) {
			state.sessionName = name;
			action("set_session_name", { name });
		},
		getSessionName() {
			return state.sessionName;
		},
		setLabel(entryId, label) {
			action("set_label", { entryId, label });
		},
		exec(command, args, options) {
			return runCommand(command, args, options);
		},
		getActiveTools() {
			return [...state.activeTools];
		},
		getAllTools() {
			return state.allTools;
		},
		setActiveTools(toolNames) {
			state.activeTools = [...toolNames];
			action("set_active_tools", { toolNames });
		},
		getCommands() {
			return commandMetadata();
		},
		async setModel(model) {
			action("set_model", { model });
			return true;
		},
		getThinkingLevel() {
			return state.thinkingLevel;
		},
		setThinkingLevel(level) {
			state.thinkingLevel = level;
			action("set_thinking_level", { level });
		},
		registerProvider(providerOrName, config) {
			const registration =
				typeof providerOrName === "string"
					? registerProviderConfig(providerOrName, config, extension.path)
					: registerNativeProvider(providerOrName, extension.path);
			action("register_provider", providerRegistrationMetadata(registration));
			registrationChanged();
		},
		unregisterProvider(name) {
			const registration = providers.get(name);
			if (registration?.callbackToken) providerCallbacks.delete(registration.callbackToken);
			providers.delete(name);
			action("unregister_provider", { name });
			registrationChanged();
		},
		events: sharedEventBus,
	};
}

function createExtension(path, index) {
	return {
		path,
		index,
		handlers: new Map(),
		tools: new Map(),
		commands: new Map(),
		flags: new Map(),
		shortcuts: new Map(),
		messageRenderers: new Map(),
		entryRenderers: new Map(),
	};
}

function commandMetadata() {
	const grouped = new Map();
	for (const extension of extensions) {
		for (const command of extension.commands.values()) {
			const list = grouped.get(command.name) ?? [];
			list.push({ extension, command });
			grouped.set(command.name, list);
		}
	}
	const result = [];
	for (const [name, registrations] of grouped) {
		registrations.forEach(({ extension, command }, index) => {
			const invocationName = registrations.length === 1 ? name : `${name}:${index + 1}`;
			const metadata = {
				id: command.id,
				name,
				invocationName,
				description: command.options.description,
				extensionPath: extension.path,
			};
			result.push(metadata);
			commands.set(invocationName, { extension, command });
		});
	}
	return result;
}

function registrationMetadata() {
	commands = new Map();
	const commandList = commandMetadata();
	return {
		version: registrationVersion,
		extensions: extensions.map(extension => ({
			path: extension.path,
			events: [...extension.handlers.keys()],
			shortcuts: [...extension.shortcuts.values()].map(value => ({
				id: value.id,
				shortcut: value.shortcut,
				description: value.description,
			})),
			messageRenderers: [...extension.messageRenderers.keys()],
			entryRenderers: [...extension.entryRenderers.keys()],
		})),
		tools: [...tools.values()].map(({ id, extension, definition }) => ({
			id,
			name: definition.name,
			label: definition.label ?? definition.name,
			description: definition.description ?? "",
			parameters: jsonValue(definition.parameters ?? { type: "object", properties: {} }),
			executionMode: definition.executionMode,
			promptSnippet: definition.promptSnippet,
			promptGuidelines: definition.promptGuidelines,
			extensionPath: extension.path,
		})),
		commands: commandList,
		flags: [...flags.values()].map(flag => jsonValue(flag)),
		providers: [...providers.values()].map(providerRegistrationMetadata),
	};
}

async function loadExtensions(request) {
	extensions = [];
	tools = new Map();
	commands = new Map();
	flags = new Map();
	providers = new Map();
	providerCallbacks = new Map();
	attemptedPaths = new Set();
	registrationVersion = 0;
	pendingBackgroundActions = [];
	backgroundPushScheduled = false;
	state.flags = new Map(Object.entries(request.flags ?? {}));
	updateState(request.context);
	const errors = await loadExtensionPaths(request.paths ?? []);
	return { registrations: registrationMetadata(), errors };
}

async function loadExtensionPaths(paths) {
	const errors = [];
	for (const extensionPath of paths) {
		if (attemptedPaths.has(extensionPath)) continue;
		attemptedPaths.add(extensionPath);
		const index = extensions.length;
		try {
			const jiti = createJiti(import.meta.url, {
				moduleCache: false,
				virtualModules: jitiVirtualModules,
				tryNative: false,
			});
			const factory = await jiti.import(extensionPath, { default: true });
			if (typeof factory !== "function") {
				throw new Error(`Extension does not export a default factory function: ${extensionPath}`);
			}
			const extension = createExtension(extensionPath, index);
			extensions.push(extension);
			await factory(createAPI(extension));
		} catch (error) {
			errors.push(errorInfo(extensionPath, "load", error));
		}
	}
	return errors;
}

async function loadMoreExtensions(request) {
	updateState(request.context);
	const errors = await loadExtensionPaths(request.paths ?? []);
	const extensionsByPath = new Map(extensions.map(extension => [extension.path, extension]));
	extensions = (request.paths ?? []).map(path => extensionsByPath.get(path)).filter(Boolean);
	return { registrations: registrationMetadata(), errors };
}

function errorInfo(extensionPath, event, error) {
	return {
		extensionPath,
		event,
		error: error instanceof Error ? error.message : String(error),
		stack: error instanceof Error ? error.stack : undefined,
	};
}

function handlersFor(eventType) {
	const result = [];
	for (const extension of extensions) {
		for (const handler of extension.handlers.get(eventType) ?? []) {
			result.push({ extension, handler });
		}
	}
	return result;
}

async function emitEvent(event, context) {
	updateState(context);
	const ctx = contextFor(context);
	if (event.type === "before_agent_start" && event.systemPrompt !== undefined) {
		state.systemPrompt = event.systemPrompt;
	}
	const errors = [];
	const collectedMessages = [];
	let result;
	let resources;
	let bashOperations;
	let currentEvent = jsonValue(event);

	handlerLoop: for (const { extension, handler } of handlersFor(event.type)) {
		try {
			if (event.type === "before_agent_start") {
				currentEvent.systemPrompt = state.systemPrompt;
			}
			const handlerResult = await handler(currentEvent, ctx);
			if (handlerResult === undefined) continue;
			if (event.type === "user_bash") {
				if (handlerResult?.result !== undefined) {
					result = jsonValue(handlerResult);
					break handlerLoop;
				}
				if (typeof handlerResult?.operations?.exec === "function") {
					bashOperations = handlerResult.operations;
					break handlerLoop;
				}
			}
			const value = jsonValue(handlerResult);
			if (event.type === "tool_call") {
				result = value;
				if (value?.block) break;
			} else if (event.type === "tool_result") {
				currentEvent = { ...currentEvent, ...value };
				result = {
					content: currentEvent.content,
					details: currentEvent.details,
					isError: currentEvent.isError,
					usage: currentEvent.usage,
					terminate: currentEvent.terminate,
				};
			} else if (event.type === "before_agent_start") {
				if (value?.message) collectedMessages.push(value.message);
				if (value?.systemPrompt !== undefined) {
					state.systemPrompt = value.systemPrompt;
					currentEvent.systemPrompt = value.systemPrompt;
				}
				result = {
					...(collectedMessages.length > 0 ? { messages: collectedMessages } : {}),
					...(state.systemPrompt !== event.systemPrompt ? { systemPrompt: state.systemPrompt } : {}),
				};
			} else if (event.type === "resources_discover") {
				result ??= { skillPaths: [], promptPaths: [], themePaths: [] };
				resources ??= { skillPaths: [], promptPaths: [], themePaths: [] };
				for (const name of ["skillPaths", "promptPaths", "themePaths"]) {
					if (Array.isArray(value?.[name])) {
						result[name].push(...value[name]);
						resources[name].push(
							...value[name].map(path => ({ path, extensionPath: extension.path })),
						);
					}
				}
			} else if (event.type === "project_trust") {
				if (value?.trusted && value.trusted !== "undecided") {
					result = value;
					break;
				}
			} else if (event.type === "user_bash") {
				result = value;
				break handlerLoop;
			} else if (event.type === "message_end" && value?.message) {
				currentEvent.message = value.message;
				result = { message: value.message };
			} else {
				result = value;
				if (value?.cancel) break;
			}
		} catch (error) {
			errors.push(errorInfo(extension.path, event.type, error));
		}
	}
	if (bashOperations) {
		result = {
			operationsResult: await executeBashOperations(
				bashOperations,
				event.command,
				event.cwd,
				currentProtocolRequestId,
			),
		};
	}
	return { result: result ?? null, errors, ...(resources ? { resources } : {}) };
}

async function executeBashOperations(operations, command, cwd, requestId) {
	if (!requestId) throw new Error("BashOperations require an active host request");
	const controller = new AbortController();
	const decoder = new TextDecoder();
	activeBashOperations.set(requestId, controller);
	protocolWrite(`${JSON.stringify({ type: "bash_start", id: requestId })}\n`);
	const emitDecoded = value => {
		if (!value) return;
		protocolWrite(
			`${JSON.stringify({
				type: "bash_update",
				id: requestId,
				data: value,
			})}\n`,
		);
	};
	try {
		const result = await operations.exec(command, cwd, {
			onData(data) {
				const bytes = Buffer.isBuffer(data) ? data : Buffer.from(data);
				emitDecoded(decoder.decode(bytes, { stream: true }));
			},
			signal: controller.signal,
		});
		return {
			exitCode: result?.exitCode ?? null,
			cancelled: controller.signal.aborted,
		};
	} catch (error) {
		if (controller.signal.aborted) {
			return { exitCode: null, cancelled: true };
		}
		throw error;
	} finally {
		emitDecoded(decoder.decode());
		activeBashOperations.delete(requestId);
	}
}

function providerRegistration(callbackToken) {
	const registration = providerCallbacks.get(callbackToken);
	if (!registration) throw new Error(`Unknown extension provider callback: ${callbackToken}`);
	return registration;
}

function providerAbortError(message = "Extension provider operation aborted") {
	const error = new Error(message);
	error.name = "AbortError";
	return error;
}

function awaitProviderOperation(operation, signal) {
	if (signal.aborted) return Promise.reject(providerAbortError());
	return new Promise((resolve, reject) => {
		const onAbort = () => {
			signal.removeEventListener("abort", onAbort);
			reject(providerAbortError());
		};
		signal.addEventListener("abort", onAbort, { once: true });
		Promise.resolve(operation).then(
			value => {
				signal.removeEventListener("abort", onAbort);
				resolve(value);
			},
			error => {
				signal.removeEventListener("abort", onAbort);
				reject(error);
			},
		);
	});
}

function providerAuthEvent(parentId, event) {
	protocolWrite(
		`${JSON.stringify({
			type: "provider_auth_event",
			id: parentId,
			event: jsonValue(event),
		})}\n`,
	);
}

function requestProviderAuthInput(parentId, method, payload, signal, optional = false) {
	if (signal?.aborted) return Promise.reject(providerAbortError());
	const requestId = randomUUID();
	return new Promise((resolve, reject) => {
		const cleanup = () => {
			signal?.removeEventListener("abort", onAbort);
			pendingProviderAuthRequests.delete(requestId);
		};
		const onAbort = () => {
			cleanup();
			reject(providerAbortError());
		};
		signal?.addEventListener("abort", onAbort, { once: true });
		pendingProviderAuthRequests.set(requestId, {
			resolve: response => {
				cleanup();
				if (response.cancelled) {
					if (optional) resolve(undefined);
					else reject(new Error(response.error || "Login cancelled"));
					return;
				}
				resolve(response.value);
			},
			cancel: onAbort,
		});
		protocolWrite(
			`${JSON.stringify({
				...jsonValue(payload),
				type: "provider_auth_request",
				id: parentId,
				requestId,
				method,
			})}\n`,
		);
	});
}

function requestProviderBridge(parentId, type, method, payload, signal) {
	if (signal?.aborted) return Promise.reject(providerAbortError());
	const requestId = randomUUID();
	return new Promise((resolve, reject) => {
		const cleanup = () => {
			signal?.removeEventListener("abort", onAbort);
			pendingProviderBridgeRequests.delete(requestId);
		};
		const onAbort = () => {
			cleanup();
			reject(providerAbortError());
		};
		signal?.addEventListener("abort", onAbort, { once: true });
		pendingProviderBridgeRequests.set(requestId, {
			resolve: response => {
				cleanup();
				if (response.error) {
					reject(new Error(response.error));
					return;
				}
				resolve(response.value);
			},
			cancel: onAbort,
		});
		protocolWrite(
			`${JSON.stringify({
				type,
				id: parentId,
				requestId,
				method,
				...jsonValue(payload),
			})}\n`,
		);
	});
}

function extensionAuthInteraction(parentId, signal) {
	return {
		signal,
		prompt(prompt) {
			const method = prompt?.type ?? "text";
			return requestProviderAuthInput(parentId, method, prompt ?? {}, signal);
		},
		notify(event) {
			providerAuthEvent(parentId, event);
		},
	};
}

function extensionOAuthCallbacks(parentId, signal) {
	return {
		onAuth(info) {
			providerAuthEvent(parentId, { type: "auth_url", ...info });
		},
		onDeviceCode(info) {
			providerAuthEvent(parentId, { type: "device_code", ...info });
		},
		onPrompt(prompt) {
			return requestProviderAuthInput(parentId, "text", prompt, signal);
		},
		onProgress(message) {
			providerAuthEvent(parentId, { type: "progress", message });
		},
		onManualCodeInput() {
			return requestProviderAuthInput(
				parentId,
				"manual_code",
				{ message: "Paste the authorization code" },
				signal,
			);
		},
		onSelect(prompt) {
			return requestProviderAuthInput(parentId, "select", prompt, signal, true);
		},
		signal,
	};
}

function extensionAuthContext(parentId, signal) {
	return {
		env(name) {
			return requestProviderBridge(
				parentId,
				"provider_context_request",
				"env",
				{ name },
				signal,
			);
		},
		fileExists(path) {
			return requestProviderBridge(
				parentId,
				"provider_context_request",
				"file_exists",
				{ path },
				signal,
			);
		},
	};
}

function extensionProviderStore(parentId, signal) {
	return {
		read() {
			return requestProviderBridge(parentId, "provider_store_request", "read", {}, signal);
		},
		write(entry) {
			return requestProviderBridge(parentId, "provider_store_request", "write", { entry }, signal);
		},
		delete() {
			return requestProviderBridge(parentId, "provider_store_request", "delete", {}, signal);
		},
	};
}

async function invokeProviderCallback(request) {
	const registration = providerRegistration(request.callbackToken);
	const config = registration.config;
	const controller = new AbortController();
	activeProviderOperations.set(request.id, controller);
	try {
		switch (request.method) {
			case "get_models":
				if (typeof config.getModels !== "function") {
					throw new Error(`Provider ${registration.name} does not define getModels`);
				}
				return { result: jsonValue(config.getModels()) };
			case "filter_models":
				if (typeof config.filterModels !== "function") {
					throw new Error(`Provider ${registration.name} does not define filterModels`);
				}
				return {
					result: jsonValue(
						await awaitProviderOperation(
							config.filterModels(
								request.arguments?.models ?? [],
								request.arguments?.credential,
							),
							controller.signal,
						),
					),
				};
			case "refresh_models": {
				if (typeof config.refreshModels !== "function") {
					throw new Error(`Provider ${registration.name} does not define refreshModels`);
				}
				const context = request.arguments?.context ?? {};
				const returned = await awaitProviderOperation(
					config.refreshModels({
						...context,
						store: extensionProviderStore(request.id, controller.signal),
						signal: controller.signal,
					}),
					controller.signal,
				);
				return {
					result: {
						returned: jsonValue(returned),
						models: registration.native && typeof config.getModels === "function"
							? jsonValue(config.getModels())
							: undefined,
					},
				};
			}
			case "api_key_login":
				if (typeof config.auth?.apiKey?.login !== "function") {
					throw new Error(`Provider ${registration.name} does not define auth.apiKey.login`);
				}
				return {
					result: jsonValue(
						await awaitProviderOperation(
							config.auth.apiKey.login(extensionAuthInteraction(request.id, controller.signal)),
							controller.signal,
						),
					),
				};
			case "api_key_check":
				if (typeof config.auth?.apiKey?.check !== "function") {
					throw new Error(`Provider ${registration.name} does not define auth.apiKey.check`);
				}
				return {
					result: jsonValue(
						await awaitProviderOperation(
							config.auth.apiKey.check({
								ctx: extensionAuthContext(request.id, controller.signal),
								credential: request.arguments?.credential,
							}),
							controller.signal,
						),
					),
				};
			case "api_key_resolve":
				if (typeof config.auth?.apiKey?.resolve !== "function") {
					throw new Error(`Provider ${registration.name} does not define auth.apiKey.resolve`);
				}
				return {
					result: jsonValue(
						await awaitProviderOperation(
							config.auth.apiKey.resolve({
								ctx: extensionAuthContext(request.id, controller.signal),
								credential: request.arguments?.credential,
							}),
							controller.signal,
						),
					),
				};
			case "native_oauth_login":
				if (typeof config.auth?.oauth?.login !== "function") {
					throw new Error(`Provider ${registration.name} does not define auth.oauth.login`);
				}
				return {
					result: jsonValue(
						await awaitProviderOperation(
							config.auth.oauth.login(extensionAuthInteraction(request.id, controller.signal)),
							controller.signal,
						),
					),
				};
			case "native_oauth_refresh":
				if (typeof config.auth?.oauth?.refresh !== "function") {
					throw new Error(`Provider ${registration.name} does not define auth.oauth.refresh`);
				}
				return {
					result: jsonValue(
						await awaitProviderOperation(
							config.auth.oauth.refresh(request.arguments?.credential, controller.signal),
							controller.signal,
						),
					),
				};
			case "native_oauth_to_auth":
				if (typeof config.auth?.oauth?.toAuth !== "function") {
					throw new Error(`Provider ${registration.name} does not define auth.oauth.toAuth`);
				}
				return {
					result: jsonValue(
						await awaitProviderOperation(
							config.auth.oauth.toAuth(request.arguments?.credential),
							controller.signal,
						),
					),
				};
			case "oauth_login":
				if (typeof config.oauth?.login !== "function") {
					throw new Error(`Provider ${registration.name} does not define oauth.login`);
				}
				return {
					result: jsonValue(
						await awaitProviderOperation(
							config.oauth.login(extensionOAuthCallbacks(request.id, controller.signal)),
							controller.signal,
						),
					),
				};
			case "oauth_refresh":
				if (typeof config.oauth?.refreshToken !== "function") {
					throw new Error(`Provider ${registration.name} does not define oauth.refreshToken`);
				}
				return {
					result: jsonValue(
						await awaitProviderOperation(
							config.oauth.refreshToken(request.arguments?.credential),
							controller.signal,
						),
					),
				};
			case "oauth_get_api_key":
				if (typeof config.oauth?.getApiKey !== "function") {
					throw new Error(`Provider ${registration.name} does not define oauth.getApiKey`);
				}
				return {
					result: jsonValue(
						await awaitProviderOperation(
							config.oauth.getApiKey(request.arguments?.credential),
							controller.signal,
						),
					),
				};
			case "oauth_modify_models":
				if (typeof config.oauth?.modifyModels !== "function") {
					throw new Error(`Provider ${registration.name} does not define oauth.modifyModels`);
				}
				return {
					result: jsonValue(
						await awaitProviderOperation(
							config.oauth.modifyModels(
								request.arguments?.models ?? [],
								request.arguments?.credential,
							),
							controller.signal,
						),
					),
				};
			default:
				throw new Error(`Unknown extension provider callback method: ${request.method}`);
		}
	} finally {
		activeProviderOperations.delete(request.id);
	}
}

const PROVIDER_STREAM_ABORTED = Symbol("provider-stream-aborted");

function nextProviderStreamEvent(iterator, signal) {
	if (signal.aborted) return Promise.resolve(PROVIDER_STREAM_ABORTED);
	return new Promise((resolve, reject) => {
		const onAbort = () => {
			signal.removeEventListener("abort", onAbort);
			resolve(PROVIDER_STREAM_ABORTED);
		};
		signal.addEventListener("abort", onAbort, { once: true });
		Promise.resolve(iterator.next()).then(
			value => {
				signal.removeEventListener("abort", onAbort);
				resolve(value);
			},
			error => {
				signal.removeEventListener("abort", onAbort);
				reject(error);
			},
		);
	});
}

function cancelAllProviderOperations() {
	for (const operation of activeProviderOperations.values()) operation.abort();
	for (const pending of [...pendingProviderAuthRequests.values()]) pending.cancel();
	for (const pending of [...pendingProviderBridgeRequests.values()]) pending.cancel();
}

async function invokeProviderStream(request) {
	const registration = providerRegistration(request.callbackToken);
	const method = request.method === "stream" ? "stream" : "streamSimple";
	const streamMethod = registration.config?.[method];
	if (typeof streamMethod !== "function") {
		throw new Error(`Provider ${registration.name} does not define ${method}`);
	}
	const controller = new AbortController();
	activeProviderOperations.set(request.id, controller);
	let iterator;
	let terminal = false;
	try {
		const providerStream = await streamMethod(
			request.model,
			request.context,
			{ ...(request.options ?? {}), signal: controller.signal },
		);
		if (!providerStream || typeof providerStream[Symbol.asyncIterator] !== "function") {
			throw new Error(`Provider ${registration.name} ${method} did not return an async event stream`);
		}
		iterator = providerStream[Symbol.asyncIterator]();
		while (true) {
			const next = await nextProviderStreamEvent(iterator, controller.signal);
			if (next === PROVIDER_STREAM_ABORTED || next.done) break;
			const event = jsonValue(next.value);
			if (!event || typeof event.type !== "string") {
				throw new Error(`Provider ${registration.name} emitted an invalid stream event`);
			}
			protocolWrite(
				`${JSON.stringify({
					type: "provider_stream_event",
					id: request.id,
					event,
				})}\n`,
			);
			if (event.type === "done" || event.type === "error") {
				terminal = true;
				break;
			}
		}
		return {
			result: {
				cancelled: controller.signal.aborted,
				terminal,
			},
		};
	} finally {
		if (controller.signal.aborted) {
			Promise.resolve(iterator?.return?.()).catch(() => {});
		}
		activeProviderOperations.delete(request.id);
	}
}

async function invokeTool(request) {
	const registration = [...tools.values()].find(value => value.id === request.toolId);
	if (!registration) throw new Error(`Unknown extension tool: ${request.toolId}`);
	updateState(request.context);
	const updates = [];
	const ctx = contextFor(request.context);
	const onUpdate = partial => {
		const value = jsonValue(partial);
		updates.push(value);
		action("tool_update", { toolCallId: request.toolCallId, result: value });
	};
	const result = await registration.definition.execute(
		request.toolCallId,
		request.params ?? {},
		undefined,
		onUpdate,
		ctx,
	);
	return { result: jsonValue(result), updates };
}

async function invokeCommand(request) {
	commandMetadata();
	const registration = commands.get(request.name);
	if (!registration) throw new Error(`Unknown extension command: ${request.name}`);
	updateState(request.context);
	await registration.command.options.handler(request.args ?? "", contextFor(request.context));
	return { result: null };
}

async function invokeShortcut(request) {
	const registration = extensions
		.flatMap(extension => [...extension.shortcuts.values()])
		.find(value => value.id === request.shortcutId);
	if (!registration) throw new Error(`Unknown extension shortcut: ${request.shortcutId}`);
	updateState(request.context);
	await registration.handler(contextFor(request.context));
	return { result: null };
}

async function handle(request) {
	currentActions = pendingBackgroundActions.splice(0);
	try {
		let response;
		switch (request.type) {
			case "load":
				response = await loadExtensions(request);
				break;
			case "load_more":
				response = await loadMoreExtensions(request);
				break;
			case "emit":
				response = await emitEvent(request.event, request.context);
				break;
			case "invoke_tool":
				response = await invokeTool(request);
				break;
			case "invoke_command":
				response = await invokeCommand(request);
				break;
			case "invoke_shortcut":
				response = await invokeShortcut(request);
				break;
			case "provider_stream":
				response = await invokeProviderStream(request);
				break;
			case "provider_callback":
				response = await invokeProviderCallback(request);
				break;
			case "registrations":
				response = { result: registrationMetadata() };
				break;
			case "close":
				response = { result: null };
				break;
			default:
				throw new Error(`Unknown request type: ${request.type}`);
		}
		const registrationsChanged =
			Number.isInteger(request.knownRegistrationVersion) &&
			request.knownRegistrationVersion !== registrationVersion;
		return {
			id: request.id,
			ok: true,
			...response,
			actions: currentActions,
			registrationVersion,
			...(registrationsChanged ? { registrations: registrationMetadata() } : {}),
		};
	} catch (error) {
		return {
			id: request.id,
			ok: false,
			error: error instanceof Error ? error.message : String(error),
			stack: error instanceof Error ? error.stack : undefined,
			actions: currentActions,
			registrationVersion,
		};
	} finally {
		currentActions = null;
		currentProtocolRequestId = null;
	}
}

const input = readline.createInterface({ input: process.stdin, crlfDelay: Infinity });
let requestQueue = Promise.resolve();
const inputClosed = new Promise(resolve => {
	input.once("close", () => {
		cancelAllUiRequests();
		cancelAllProviderOperations();
		resolve();
	});
});
input.on("line", line => {
	if (!line.trim()) return;
	let request;
	try {
		request = JSON.parse(line);
	} catch (error) {
		protocolWrite(`${JSON.stringify({ ok: false, error: `Invalid JSON: ${error.message}` })}\n`);
		return;
	}
	if (request.type === "ui_response") {
		pendingUiRequests.get(request.requestId)?.resolve(request);
		return;
	}
	if (request.type === "bash_abort") {
		activeBashOperations.get(request.id)?.abort();
		return;
	}
	if (request.type === "provider_auth_response") {
		pendingProviderAuthRequests.get(request.requestId)?.resolve(request);
		return;
	}
	if (request.type === "provider_context_response" || request.type === "provider_store_response") {
		pendingProviderBridgeRequests.get(request.requestId)?.resolve(request);
		return;
	}
	if (request.type === "provider_abort") {
		activeProviderOperations.get(request.id)?.abort();
		return;
	}
	requestQueue = requestQueue.then(async () => {
		currentProtocolRequestId = request.id;
		const response = await handle(request);
		protocolWrite(`${JSON.stringify(response)}\n`);
		if (request.type === "close") input.close();
	});
});
await inputClosed;
await requestQueue;
