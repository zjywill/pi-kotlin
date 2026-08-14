import { pathToFileURL } from "node:url";

const tsRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const componentModule = await import(
	pathToFileURL(`${tsRoot}/packages/coding-agent/src/modes/interactive/components/tool-execution.ts`).href
);
const { stripAnsi } = await import(
	pathToFileURL(`${tsRoot}/packages/coding-agent/src/utils/ansi.ts`).href
);
const { initTheme } = await import(
	pathToFileURL(`${tsRoot}/packages/coding-agent/src/modes/interactive/theme/theme.ts`).href
);

initTheme("dark");
const component = new componentModule.ToolExecutionComponent(
	"custom_tool",
	"tool-1",
	{},
	{},
	{
		name: "custom_tool",
		label: "Custom tool",
		description: "Fallback renderer fixture",
		parameters: {},
		async execute() {
			return { content: [] };
		},
	},
	{ requestRender() {} },
	process.cwd(),
);
const output = Array.from({ length: 15 }, (_, index) => `line-${index + 1}`).join("\n");
component.updateResult({ content: [{ type: "text", text: output }], details: {}, isError: false }, false);
const collapsed = stripAnsi(component.render(120).join("\n"));
component.setExpanded(true);
const expanded = stripAnsi(component.render(120).join("\n"));

console.log(
	JSON.stringify({
		collapsedHasLine10: collapsed.includes("line-10"),
		collapsedHasLine11: collapsed.includes("line-11"),
		collapsedHasRemaining: collapsed.includes("5 more lines"),
		expandedHasLine15: expanded.includes("line-15"),
		expandedHasRemaining: expanded.includes("more lines"),
	}),
);
