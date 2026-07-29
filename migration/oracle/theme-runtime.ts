import {
	mkdirSync,
	mkdtempSync,
	readFileSync,
	realpathSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { basename, join, relative, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const themeModule = await import(
	pathToFileURL(`${sourceRoot}/packages/coding-agent/src/modes/interactive/theme/theme.ts`).href
);
const { DefaultResourceLoader } = await import(
	pathToFileURL(`${sourceRoot}/packages/coding-agent/src/core/resource-loader.ts`).href
);
const { SettingsManager } = await import(
	pathToFileURL(`${sourceRoot}/packages/coding-agent/src/core/settings-manager.ts`).href
);

const {
	getAvailableThemesWithPaths,
	loadThemeFromPath,
	parseAutoThemeSetting,
	resolveThemeSetting,
	setRegisteredThemes,
} = themeModule;

type ThemeJson = {
	$schema?: string;
	name: string;
	vars?: Record<string, string | number>;
	colors: Record<string, string | number>;
	export?: Record<string, string | number>;
};

const root = realpathSync(mkdtempSync(join(tmpdir(), "pi-theme-oracle-")));
const agentDir = join(root, "agent");
const cwd = join(root, "workspace", "project");
const packageRoot = join(root, "packages", "theme-package");
const extensionRoot = join(root, "extension-themes");
const builtinDarkPath = join(
	sourceRoot,
	"packages",
	"coding-agent",
	"src",
	"modes",
	"interactive",
	"theme",
	"dark.json",
);
const builtinDark = JSON.parse(readFileSync(builtinDarkPath, "utf8")) as ThemeJson;

function write(path: string, content: string): void {
	mkdirSync(join(path, ".."), { recursive: true });
	writeFileSync(path, content);
}

function themeFile(
	path: string,
	name: string,
	accent: string | number,
	mutate?: (theme: ThemeJson) => void,
): string {
	const value = structuredClone(builtinDark);
	value.name = name;
	value.colors.accent = accent;
	mutate?.(value);
	write(path, JSON.stringify(value, null, 2));
	return path;
}

function normalizePath(path: string | undefined): string | null {
	if (!path) return null;
	let normalized: string;
	try {
		normalized = realpathSync(path);
	} catch {
		normalized = resolve(path);
	}
	if (normalized.startsWith(root)) {
		return relative(root, normalized).split("\\").join("/");
	}
	if (normalized === resolve(builtinDarkPath) || basename(normalized) === "light.json") {
		return "<builtin>";
	}
	return normalized.replace(sourceRoot, "<SOURCE>").split("\\").join("/");
}

function classifyThemeError(error: unknown): string {
	const message = error instanceof Error ? error.message : String(error);
	if (message.includes("Missing required color tokens")) return "missing-required-colors";
	if (message.includes("Circular variable reference")) return "circular-variable";
	if (message.includes("Variable reference not found")) return "missing-variable";
	if (message.includes("cannot contain")) return "invalid-name";
	if (message.includes("Invalid hex color")) return "invalid-color";
	if (message.includes("must be <= 255") || message.includes("maximum")) return "invalid-index";
	return "other";
}

function invalidCase(name: string, mutate: (theme: ThemeJson) => void): { name: string; error: string } {
	const path = join(root, "invalid", `${name}.json`);
	themeFile(path, name, "#123456", mutate);
	try {
		loadThemeFromPath(path, "truecolor");
		return { name, error: "accepted" };
	} catch (error) {
		return { name, error: classifyThemeError(error) };
	}
}

try {
	mkdirSync(cwd, { recursive: true });
	mkdirSync(join(cwd, ".git"));

	const contractPath = themeFile(join(root, "contract.json"), "contract", "alias", theme => {
		theme.vars = {
			...(theme.vars ?? {}),
			primary: "#123456",
			alias: "primary",
			terminalDefault: "",
			palette: 244,
		};
		theme.colors.text = "terminalDefault";
		theme.colors.selectedBg = "#abcdef";
		theme.colors.customMessageBg = 17;
		theme.colors.thinkingXhigh = "#654321";
		delete theme.colors.thinkingMax;
		theme.export = {
			pageBg: "alias",
			cardBg: "palette",
			infoBg: "terminalDefault",
		};
	});
	const truecolor = loadThemeFromPath(contractPath, "truecolor");
	const palette = loadThemeFromPath(contractPath, "256color");

	const projectShared = themeFile(
		join(cwd, ".pi", "themes", "project-shared.json"),
		"shared",
		"#220000",
	);
	themeFile(join(cwd, ".pi", "themes", "project-only.json"), "project-only", "#221100");
	const userShared = themeFile(join(agentDir, "themes", "user-shared.json"), "shared", "#110000");
	themeFile(join(agentDir, "themes", "renamed-file.json"), "user-only", "#112200");
	themeFile(join(packageRoot, "themes", "package.json"), "package-only", "#003300");
	write(
		join(packageRoot, "package.json"),
		JSON.stringify({
			name: "theme-package",
			version: "1.0.0",
			pi: { themes: ["themes/*.json"] },
		}),
	);
	write(
		join(agentDir, "settings.json"),
		JSON.stringify({ theme: "user-only", packages: [packageRoot] }, null, 2),
	);
	write(
		join(cwd, ".pi", "settings.json"),
		JSON.stringify({ theme: "shared" }, null, 2),
	);
	const extensionShared = themeFile(join(extensionRoot, "extension-shared.json"), "shared", "#440000");
	themeFile(join(extensionRoot, "extension-only.json"), "extension-only", "#004400");

	const settings = SettingsManager.create(cwd, agentDir, { projectTrusted: true });
	const loader = new DefaultResourceLoader({ cwd, agentDir, settingsManager: settings });
	await loader.reload();
	loader.extendResources({
		themePaths: [
			{
				path: extensionRoot,
				metadata: {
					source: "extension:theme-oracle",
					scope: "temporary",
					origin: "top-level",
					baseDir: extensionRoot,
				},
			},
		],
	});
	const loaded = loader.getThemes();
	setRegisteredThemes(loaded.themes);

	const themeProjection = loaded.themes.map(theme => ({
		name: theme.name ?? null,
		path: normalizePath(theme.sourcePath),
		accentAnsi: theme.sourcePath
			? loadThemeFromPath(theme.sourcePath, "truecolor").getFgAnsi("accent")
			: null,
		source: theme.sourceInfo
			? {
					source: theme.sourceInfo.source.replace(root, "<ROOT>"),
					scope: theme.sourceInfo.scope,
					origin: theme.sourceInfo.origin,
					baseDir: normalizePath(theme.sourceInfo.baseDir),
				}
			: null,
	}));
	const collisionProjection = loaded.diagnostics
		.filter(diagnostic => diagnostic.collision?.resourceType === "theme")
		.map(diagnostic => ({
			name: diagnostic.collision?.name,
			winnerPath: normalizePath(diagnostic.collision?.winnerPath),
			loserPath: normalizePath(diagnostic.collision?.loserPath),
		}));
	const availableProjection = getAvailableThemesWithPaths().map(theme => ({
		name: theme.name,
		path: normalizePath(theme.path),
	}));

	console.log(
		JSON.stringify(
			{
				contract: {
					name: truecolor.name,
					sourcePath: normalizePath(truecolor.sourcePath),
					truecolor: {
						mode: truecolor.getColorMode(),
						accentAnsi: truecolor.getFgAnsi("accent"),
						defaultTextAnsi: truecolor.getFgAnsi("text"),
						selectedBgAnsi: truecolor.getBgAnsi("selectedBg"),
						customBgAnsi: truecolor.getBgAnsi("customMessageBg"),
						accentText: truecolor.fg("accent", "accent"),
						selectedText: truecolor.bg("selectedBg", "selected"),
					},
					palette: {
						mode: palette.getColorMode(),
						accentAnsi: palette.getFgAnsi("accent"),
						selectedBgAnsi: palette.getBgAnsi("selectedBg"),
					},
					thinkingFallback:
						truecolor.getThinkingBorderColor("max")("border") ===
						truecolor.getThinkingBorderColor("xhigh")("border"),
				},
				autoTheme: {
					valid: parseAutoThemeSetting(" light-custom / dark-custom "),
					invalid: [
						parseAutoThemeSetting("dark"),
						parseAutoThemeSetting("light/dark/extra"),
						parseAutoThemeSetting("/dark"),
					],
					resolved: {
						light: resolveThemeSetting("light-custom/dark-custom", "light"),
						dark: resolveThemeSetting("light-custom/dark-custom", "dark"),
						fixed: resolveThemeSetting("shared", "light"),
						invalid: resolveThemeSetting("light/dark/extra", "dark") ?? null,
					},
				},
				resources: {
					selectedSetting: settings.getThemeSetting(),
					themes: themeProjection,
					collisions: collisionProjection,
					available: availableProjection,
					expectedWinnerPaths: {
						project: normalizePath(projectShared),
						userLoser: normalizePath(userShared),
						extensionLoser: normalizePath(extensionShared),
					},
				},
				invalid: [
					invalidCase("missing-colors", theme => {
						delete theme.colors.accent;
					}),
					invalidCase("circular-vars", theme => {
						theme.vars = { a: "b", b: "a" };
						theme.colors.accent = "a";
					}),
					invalidCase("missing-var", theme => {
						theme.colors.accent = "not-defined";
					}),
					invalidCase("invalid-name", theme => {
						theme.name = "light/dark";
					}),
					invalidCase("invalid-hex", theme => {
						theme.colors.accent = "#xyz";
					}),
					invalidCase("invalid-index", theme => {
						theme.colors.accent = 256;
					}),
				],
			},
			null,
			2,
		),
	);
} finally {
	setRegisteredThemes([]);
	rmSync(root, { recursive: true, force: true });
}
