import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const targetRoot = resolve(import.meta.dirname, "../..");
const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const fixture = resolve(targetRoot, "migration/fixtures/html-export/extension-tool.ts");
const loader = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/extensions/loader.ts")).href
);
const rendererModule = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/export-html/tool-renderer.ts")).href
);
const themeModule = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/modes/interactive/theme/theme.ts")).href
);

const runtime = loader.createExtensionRuntime();
const loaded = await loader.loadExtensions([fixture], targetRoot, undefined, runtime);
if (loaded.errors.length > 0) {
	throw new Error(JSON.stringify(loaded.errors));
}
const definition = loaded.extensions[0]?.tools.get("html_probe")?.definition;
if (!definition) {
	throw new Error("html_probe tool was not registered");
}
const theme = themeModule.loadThemeFromPath(
	resolve(sourceRoot, "packages/coding-agent/src/modes/interactive/theme/dark.json"),
	"truecolor",
);
const renderer = rendererModule.createToolHtmlRenderer({
	getToolDefinition: name => (name === "html_probe" ? definition : undefined),
	theme,
	cwd: targetRoot,
	width: 100,
});
const callHtml = renderer.renderCall("html-call", "html_probe", { text: "hello" });
const result = renderer.renderResult(
	"html-call",
	"html_probe",
	[{ type: "text", text: "result:hello" }],
	{ source: "html-probe" },
	false,
);
console.log(JSON.stringify({ callHtml, result }));
