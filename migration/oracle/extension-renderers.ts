import { readdirSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { stripVTControlCharacters } from "node:util";

const targetRoot = resolve(import.meta.dirname, "../..");
const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const fixtureRoot = resolve(targetRoot, "migration/fixtures/extension-renderers");
const fixturePaths = readdirSync(fixtureRoot)
	.filter((name) => name.endsWith(".ts"))
	.sort()
	.map((name) => resolve(fixtureRoot, name));

const loader = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/extensions/loader.ts")).href
);
const runnerModule = await import(
	pathToFileURL(resolve(sourceRoot, "packages/coding-agent/src/core/extensions/runner.ts")).href
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
const theme = {
	fg: (_color: string, text: string) => String(text),
	bg: (_color: string, text: string) => String(text),
	bold: (text: string) => String(text),
	italic: (text: string) => String(text),
	underline: (text: string) => String(text),
	strikethrough: (text: string) => String(text),
	inverse: (text: string) => String(text),
} as never;

const message = {
	role: "custom",
	customType: "oracle-message",
	content: "hello",
	display: true,
	details: { source: "oracle" },
	timestamp: 123,
};
const entry = {
	type: "custom",
	id: "entry-1",
	parentId: "parent-1",
	timestamp: "2026-07-29T00:00:00Z",
	customType: "oracle-entry",
	data: { value: "saved" },
};

function render(kind: "message" | "entry", customType: string, value: unknown) {
	const renderer =
		kind === "message" ? runner.getMessageRenderer(customType) : runner.getEntryRenderer(customType);
	if (!renderer) {
		return { missing: true };
	}
	try {
		const component =
			kind === "message"
				? renderer(value as never, { expanded: true, outputPad: 2 }, theme)
				: renderer(value as never, { expanded: true }, theme);
		if (!component) {
			return { rendered: false, lines: [] };
		}
		return {
			rendered: true,
			lines: component.render(44).map((line: string) => stripVTControlCharacters(line)),
		};
	} catch {
		return { threw: true };
	}
}

console.log(
	JSON.stringify({
		message: render("message", "oracle-message", message),
		entry: render("entry", "oracle-entry", entry),
		messageUndefined: render("message", "undefined-message", {
			...message,
			customType: "undefined-message",
		}),
		entryUndefined: render("entry", "undefined-entry", {
			...entry,
			customType: "undefined-entry",
		}),
		messageThrow: render("message", "throw-message", {
			...message,
			customType: "throw-message",
		}),
		entryThrow: render("entry", "throw-entry", {
			...entry,
			customType: "throw-entry",
		}),
	}),
);
