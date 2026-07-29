import { readdirSync } from "node:fs";
import { basename, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const targetRoot = resolve(import.meta.dirname, "../..");
const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const fixtureRoot = resolve(targetRoot, "migration/fixtures/extension-shortcuts");
const fixturePaths = readdirSync(fixtureRoot)
	.filter(name => name.endsWith(".ts"))
	.sort()
	.map(name => resolve(fixtureRoot, name));

const loader = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/extensions/loader.ts")).href
);
const runnerModule = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/extensions/runner.ts")).href
);
const keybindingsModule = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/keybindings.ts")).href
);

const runtime = loader.createExtensionRuntime();
const loaded = await loader.loadExtensions(fixturePaths, fixtureRoot, undefined, runtime);
if (loaded.errors.length > 0) {
	throw new Error(JSON.stringify(loaded.errors));
}
const runner = new runnerModule.ExtensionRunner(
	loaded.extensions,
	loaded.runtime,
	fixtureRoot,
	{} as never,
	{} as never,
);

function normalizeMessage(value: string): string {
	let normalized = value;
	for (const path of fixturePaths) {
		normalized = normalized.replaceAll(path, basename(path));
	}
	return normalized;
}

function shortcutContext(actions: string[]) {
	return {
		ui: {
			notify(message: string) {
				actions.push(message);
			},
		},
	} as never;
}

async function scenario(config: Record<string, string>) {
	const keybindings = new keybindingsModule.KeybindingsManager(config).getEffectiveConfig();
	const warnings: string[] = [];
	const originalWarn = console.warn;
	console.warn = value => warnings.push(String(value));
	let shortcuts;
	try {
		shortcuts = runner.getShortcuts(keybindings);
	} finally {
		console.warn = originalWarn;
	}
	const actions: Array<{ key: string; message: string }> = [];
	for (const [key, shortcut] of shortcuts) {
		const messages: string[] = [];
		await shortcut.handler(shortcutContext(messages));
		for (const message of messages) {
			actions.push({ key, message });
		}
	}
	return {
		shortcuts: [...shortcuts.entries()]
			.map(([key, shortcut]) => ({
				key,
				description: shortcut.description ?? null,
				path: basename(shortcut.extensionPath),
			}))
			.sort((a, b) => a.key.localeCompare(b.key)),
		diagnostics: warnings.map(normalizeMessage).sort(),
		actions: actions.sort((a, b) => a.key.localeCompare(b.key)),
	};
}

console.log(
	JSON.stringify({
		default: await scenario({}),
		custom: await scenario({
			"app.interrupt": "ctrl+q",
			"app.model.cycleForward": "ctrl+n",
		}),
	}),
);
