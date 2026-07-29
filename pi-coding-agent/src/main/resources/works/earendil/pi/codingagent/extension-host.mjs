import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { registerHooks, stripTypeScriptTypes } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";
import readline from "node:readline";

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

function transformTypeScript(source) {
	try {
		return stripTypeScriptTypes(source, { mode: "transform", sourceMap: false });
	} catch (error) {
		if (error?.code !== "ERR_INVALID_ARG_VALUE") throw error;
		return stripTypeScriptTypes(source, { mode: "strip", sourceMap: false });
	}
}

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
		if (url.startsWith("file:") && fileURLToPath(url).endsWith(".ts")) {
			const typescript = readFileSync(fileURLToPath(url), "utf8");
			return {
				format: "module",
				source: transformTypeScript(typescript),
				shortCircuit: true,
			};
		}
		return nextLoad(url, context);
	},
});

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
let currentProtocolRequestId = null;
const pendingUiRequests = new Map();
const activeBashOperations = new Map();

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

function action(type, payload = {}) {
	if (currentActions) currentActions.push({ type, ...jsonValue(payload) });
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
			registrationVersion++;
		},
		registerTool(tool) {
			const id = `${extension.index}:tool:${tool.name}`;
			extension.tools.set(tool.name, { id, definition: tool });
			if (!tools.has(tool.name)) tools.set(tool.name, { id, extension, definition: tool });
			registrationVersion++;
		},
		registerCommand(name, options) {
			const id = `${extension.index}:command:${name}:${extension.commands.size}`;
			extension.commands.set(id, { id, name, options });
			registrationVersion++;
		},
		registerShortcut(shortcut, options) {
			extension.shortcuts.set(shortcut, { shortcut, ...options });
			registrationVersion++;
		},
		registerFlag(name, options) {
			const registration = { name, extensionPath: extension.path, ...options };
			extension.flags.set(name, registration);
			if (!flags.has(name)) {
				flags.set(name, registration);
				if (!state.flags.has(name) && options.default !== undefined) state.flags.set(name, options.default);
			}
			registrationVersion++;
		},
		registerMessageRenderer(customType, renderer) {
			extension.messageRenderers.set(customType, renderer);
			registrationVersion++;
		},
		registerEntryRenderer(customType, renderer) {
			extension.entryRenderers.set(customType, renderer);
			registrationVersion++;
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
			const name = typeof providerOrName === "string" ? providerOrName : providerOrName.id;
			const value = typeof providerOrName === "string" ? config : providerOrName;
			providers.set(name, { name, config: jsonValue(value), extensionPath: extension.path });
			action("register_provider", { name, config: jsonValue(value) });
			registrationVersion++;
		},
		unregisterProvider(name) {
			providers.delete(name);
			action("unregister_provider", { name });
			registrationVersion++;
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
		providers: [...providers.values()].map(provider => jsonValue(provider)),
	};
}

async function loadExtensions(request) {
	extensions = [];
	tools = new Map();
	commands = new Map();
	flags = new Map();
	providers = new Map();
	attemptedPaths = new Set();
	registrationVersion = 0;
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
			const url = pathToFileURL(extensionPath);
			url.searchParams.set("pi-kotlin-load", `${Date.now()}-${index}`);
			const module = await import(url.href);
			const factory = module.default;
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

async function handle(request) {
	currentActions = [];
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
			case "registrations":
				response = { result: registrationMetadata() };
				break;
			case "close":
				response = { result: null };
				break;
			default:
				throw new Error(`Unknown request type: ${request.type}`);
		}
		return {
			id: request.id,
			ok: true,
			...response,
			actions: currentActions,
			registrationVersion,
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
	requestQueue = requestQueue.then(async () => {
		currentProtocolRequestId = request.id;
		const response = await handle(request);
		protocolWrite(`${JSON.stringify(response)}\n`);
		if (request.type === "close") input.close();
	});
});
await inputClosed;
await requestQueue;
