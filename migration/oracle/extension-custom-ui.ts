import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const targetRoot = resolve(import.meta.dirname, "../..");
const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const fixture = resolve(targetRoot, "migration/fixtures/extension-custom-ui.ts");

const loader = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/extensions/loader.ts")).href
);
const runnerModule = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/extensions/runner.ts")).href
);

const runtime = loader.createExtensionRuntime();
const loaded = await loader.loadExtensions([fixture], targetRoot, undefined, runtime);
if (loaded.errors.length > 0) {
	throw new Error(JSON.stringify(loaded.errors));
}
const runner = new runnerModule.ExtensionRunner(
	loaded.extensions,
	loaded.runtime,
	targetRoot,
	{} as never,
	{} as never,
);

type SurfaceRecord = {
	component: { render(width: number): string[]; dispose?(): void };
	requestRender: () => void;
};

let width = 32;
const actions: Array<Record<string, unknown>> = [];
const statuses = new Map<string, string>();
const widgets = new Map<string, SurfaceRecord>();
let header: SurfaceRecord | undefined;
let footer: SurfaceRecord | undefined;
let customInputs: string[] = [];

const theme = {
	fg: (_color: string, text: string) => String(text),
	bg: (_color: string, text: string) => String(text),
	bold: (text: string) => String(text),
	italic: (text: string) => String(text),
	underline: (text: string) => String(text),
	strikethrough: (text: string) => String(text),
	inverse: (text: string) => String(text),
};

function render(method: string, component: SurfaceRecord["component"], extra: Record<string, unknown> = {}) {
	actions.push({ method, ...extra, lines: component.render(width) });
}

function componentRecord(
	method: string,
	factory: (...args: unknown[]) => SurfaceRecord["component"],
	extra: Record<string, unknown> = {},
	footerData?: unknown,
): SurfaceRecord {
	let record: SurfaceRecord;
	const tui = {
		requestRender() {
			record.requestRender();
		},
	};
	const component = factory(tui, theme, footerData);
	record = {
		component,
		requestRender: () => render(method, component, extra),
	};
	return record;
}

const ui = {
	setStatus(key: string, text: string | undefined) {
		if (text === undefined) statuses.delete(key);
		else statuses.set(key, text);
		actions.push({ method: "setStatus", key, text });
	},
	setWidget(key: string, content: string[] | ((...args: unknown[]) => SurfaceRecord["component"]) | undefined, options?: { placement?: string }) {
		widgets.get(key)?.component.dispose?.();
		widgets.delete(key);
		if (content === undefined) {
			actions.push({ method: "setWidget", key });
			return;
		}
		if (Array.isArray(content)) {
			actions.push({
				method: "setWidget",
				key,
				placement: options?.placement ?? "aboveEditor",
				lines: content,
			});
			return;
		}
		const record = componentRecord(
			"setWidget",
			content,
			{ key, placement: options?.placement ?? "aboveEditor" },
		);
		widgets.set(key, record);
		record.requestRender();
	},
	setHeader(factory: ((...args: unknown[]) => SurfaceRecord["component"]) | undefined) {
		header?.component.dispose?.();
		header = undefined;
		if (factory === undefined) {
			actions.push({ method: "setHeader" });
			return;
		}
		header = componentRecord("setHeader", factory);
		header.requestRender();
	},
	setFooter(factory: ((...args: unknown[]) => SurfaceRecord["component"]) | undefined) {
		footer?.component.dispose?.();
		footer = undefined;
		if (factory === undefined) {
			actions.push({ method: "setFooter" });
			return;
		}
		const footerData = {
			getGitBranch: () => "main",
			getExtensionStatuses: () => new Map(statuses),
			getAvailableProviderCount: () => 0,
			onBranchChange: () => () => {},
		};
		footer = componentRecord("setFooter", factory, {}, footerData);
		footer.requestRender();
	},
	async custom(factory: (...args: unknown[]) => Promise<SurfaceRecord["component"]> | SurfaceRecord["component"]) {
		let completed = false;
		let result: unknown;
		const tui = { requestRender() {} };
		const component = await factory(tui, theme, {}, (value: unknown) => {
			completed = true;
			result = value;
		});
		try {
			while (!completed) {
				actions.push({ method: "custom", lines: component.render(width) });
				const input = customInputs.shift();
				if (input === undefined) return undefined;
				component.handleInput?.(input);
			}
			return result;
		} finally {
			component.dispose?.();
		}
	},
	notify(message: string, notifyType = "info") {
		actions.push({ method: "notify", message, notifyType });
	},
} as never;

runner.setUIContext(ui, "tui");

await runner.emit({ type: "session_start", reason: "startup" });
const startup = actions.splice(0);

width = 40;
await runner.getCommand("refresh-clear")?.handler("", runner.createCommandContext());
const refresh = actions.splice(0);

customInputs = ["\x1b[B", "\r"];
await runner.getCommand("choose")?.handler("", runner.createCommandContext());
const custom = actions.splice(0);

customInputs = ["Ada", "\r"];
await runner.getCommand("edit")?.handler("", runner.createCommandContext());
const editor = actions.splice(0);

console.log(JSON.stringify({ startup, refresh, custom, editor }));
