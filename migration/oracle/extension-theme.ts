import {
	mkdirSync,
	mkdtempSync,
	readFileSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const targetRoot = resolve(import.meta.dirname, "../..");
const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const fixture = resolve(targetRoot, "migration/fixtures/extension-theme.ts");
const root = mkdtempSync(join(tmpdir(), "pi-extension-theme-oracle-"));
const agentDir = join(root, "agent");
const cwd = join(root, "project");

const loader = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/extensions/loader.ts")).href
);
const runnerModule = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/extensions/runner.ts")).href
);
const themeModule = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/modes/interactive/theme/theme.ts")).href
);

function write(path: string, content: string): void {
	mkdirSync(resolve(path, ".."), { recursive: true });
	writeFileSync(path, content);
}

try {
	mkdirSync(cwd, { recursive: true });
	const dark = JSON.parse(
		readFileSync(
			resolve(sourceRoot, "packages/coding-agent/src/modes/interactive/theme/dark.json"),
			"utf8",
		),
	);
	dark.name = "oracle";
	dark.colors.accent = "#123456";
	const customPath = join(root, "oracle.json");
	write(customPath, JSON.stringify(dark, null, 2));

	const customTheme = themeModule.loadThemeFromPath(customPath);
	themeModule.setRegisteredThemes([customTheme]);
	themeModule.initTheme("oracle");

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

	const actions: Array<Record<string, unknown>> = [];
	const widgets = new Map<string, { render(width: number): string[] }>();
	const renderWidgets = () => {
		for (const [key, component] of widgets) {
			actions.push({ method: "setWidget", key, lines: component.render(80) });
		}
	};
	const ui = {
		get theme() {
			return themeModule.theme;
		},
		getAllThemes: themeModule.getAvailableThemesWithPaths,
		getTheme: themeModule.getThemeByName,
		setTheme(themeOrName: string | object) {
			const result =
				typeof themeOrName === "string"
					? themeModule.setTheme(themeOrName)
					: (themeModule.setThemeInstance(themeOrName), { success: true });
			renderWidgets();
			return result;
		},
		setWidget(key: string, factory: any) {
			const component = factory({ requestRender: renderWidgets }, themeModule.theme);
			widgets.set(key, component);
			actions.push({ method: "setWidget", key, lines: component.render(80) });
		},
		notify(message: string, notifyType: string) {
			actions.push({ method: "notify", message, notifyType });
		},
	} as never;

	runner.setUIContext(ui, "tui");
	await runner.getCommand("theme-probe")?.handler("", runner.createCommandContext());
	console.log(JSON.stringify(actions));
} finally {
	themeModule.setRegisteredThemes([]);
	themeModule.initTheme("dark");
	rmSync(root, { recursive: true, force: true });
}
