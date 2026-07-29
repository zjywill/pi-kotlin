import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const rendererModule = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/export-html/tool-renderer.ts")).href
);
const findModule = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/tools/find.ts")).href
);
const grepModule = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/tools/grep.ts")).href
);
const themeModule = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/modes/interactive/theme/theme.ts")).href
);
const tuiModule = await import(pathToFileURL(resolve(sourceRoot, "packages/tui/src/index.ts")).href);
const keybindingsModule = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/keybindings.ts")).href
);

tuiModule.setCapabilities({ images: null, trueColor: true, hyperlinks: false });
tuiModule.setKeybindings(new keybindingsModule.KeybindingsManager());
themeModule.initTheme("dark");
const tools = new Map([
	["find", findModule.createFindToolDefinition("/workspace/oracle")],
	["grep", grepModule.createGrepToolDefinition("/workspace/oracle")],
]);
const renderer = rendererModule.createToolHtmlRenderer({
	getToolDefinition: name => tools.get(name),
	theme: themeModule.theme,
	cwd: "/workspace/oracle",
	width: 100,
});
const findLines = Array.from({ length: 22 }, (_, index) => `src/File${index + 1}.kt`).join("\n");
const grepLines = Array.from({ length: 17 }, (_, index) => `src/File.kt:${index + 1}:needle`).join("\n");

const output = {
	find: {
		call: renderer.renderCall("find-call", "find", { pattern: "**/*.kt", path: "src", limit: 25 }),
		result: renderer.renderResult(
			"find-call",
			"find",
			[{ type: "text", text: findLines }],
			{ resultLimitReached: 25 },
			false,
		),
	},
	grep: {
		call: renderer.renderCall("grep-call", "grep", {
			pattern: "needle",
			path: ".",
			glob: "*.kt",
			limit: 3,
		}),
		result: renderer.renderResult(
			"grep-call",
			"grep",
			[{ type: "text", text: grepLines }],
			{ matchLimitReached: 3, linesTruncated: true },
			false,
		),
	},
};
console.log(JSON.stringify(output));
