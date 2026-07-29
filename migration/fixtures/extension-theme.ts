import { Theme } from "@earendil-works/pi-coding-agent";

const foregroundTokens = [
	"accent",
	"border",
	"borderAccent",
	"borderMuted",
	"success",
	"error",
	"warning",
	"muted",
	"dim",
	"text",
	"thinkingText",
	"userMessageText",
	"customMessageText",
	"customMessageLabel",
	"toolTitle",
	"toolOutput",
	"mdHeading",
	"mdLink",
	"mdLinkUrl",
	"mdCode",
	"mdCodeBlock",
	"mdCodeBlockBorder",
	"mdQuote",
	"mdQuoteBorder",
	"mdHr",
	"mdListBullet",
	"toolDiffAdded",
	"toolDiffRemoved",
	"toolDiffContext",
	"syntaxComment",
	"syntaxKeyword",
	"syntaxFunction",
	"syntaxVariable",
	"syntaxString",
	"syntaxNumber",
	"syntaxType",
	"syntaxOperator",
	"syntaxPunctuation",
	"thinkingOff",
	"thinkingMinimal",
	"thinkingLow",
	"thinkingMedium",
	"thinkingHigh",
	"thinkingXhigh",
	"thinkingMax",
	"bashMode",
];
const backgroundTokens = [
	"selectedBg",
	"userMessageBg",
	"customMessageBg",
	"toolPendingBg",
	"toolSuccessBg",
	"toolErrorBg",
];

export default function (pi: any) {
	pi.registerCommand("theme-probe", {
		description: "Exercise extension theme APIs",
		handler: async (_args: string, ctx: any) => {
			const available = ctx.ui.getAllThemes().map((theme: any) => theme.name);
			const before = ctx.ui.theme.name;
			const light = ctx.ui.getTheme("light");
			const missingTheme = ctx.ui.getTheme("__missing_theme__");

			ctx.ui.setWidget("theme-probe", (_tui: any, theme: any) => ({
				render: () => [
					`name=${ctx.ui.theme.name}`,
					theme.fg("accent", "accent"),
					theme.bg("customMessageBg", "background"),
					theme.bold("bold"),
					theme.getThinkingBorderColor("max")("max"),
				],
			}));

			const switched = ctx.ui.setTheme("light");
			const afterSwitch = ctx.ui.theme.name;
			const missing = ctx.ui.setTheme("__missing_theme__");
			const afterMissing = ctx.ui.theme.name;
			const memoryTheme = new Theme(
				Object.fromEntries(foregroundTokens.map(name => [name, name === "accent" ? "#010203" : ""])),
				Object.fromEntries(backgroundTokens.map(name => [name, ""])),
				"truecolor",
				{ name: "memory" },
			);
			const memory = ctx.ui.setTheme(memoryTheme);
			const afterMemory = ctx.ui.theme.name;
			ctx.ui.notify(
				JSON.stringify({
					available,
					before,
					lightAccent: light?.getFgAnsi("accent"),
					missingTheme: missingTheme === undefined,
					switched,
					afterSwitch,
					missing,
					afterMissing,
					memory,
					afterMemory,
				}),
				"info",
			);
		},
	});
}
