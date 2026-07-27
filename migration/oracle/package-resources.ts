import { mkdirSync, mkdtempSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, relative, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const { DefaultPackageManager } = await import(
	pathToFileURL(`${sourceRoot}/packages/coding-agent/src/core/package-manager.ts`).href
);
const { SettingsManager } = await import(
	pathToFileURL(`${sourceRoot}/packages/coding-agent/src/core/settings-manager.ts`).href
);

const root = realpathSync(mkdtempSync(join(tmpdir(), "pi-package-oracle-")));
const home = join(root, "home");
const agentDir = join(root, "agent");
const cwd = join(root, "workspace", "project");
const userPackage = join(root, "packages", "user");
const projectPackage = join(cwd, ".pi", "packages", "project");
const originalHome = process.env.HOME;

function write(path: string, content: string): void {
	mkdirSync(join(path, ".."), { recursive: true });
	writeFileSync(path, content);
}

function createPackage(path: string, prefix: string): void {
	write(join(path, "extensions", "main.ts"), "export default function() {}");
	write(join(path, "extensions", "skip.ts"), "export default function() {}");
	write(join(path, "~extensions", "tilde.ts"), "export default function() {}");
	write(join(path, "~", "extensions", "home-like.ts"), "export default function() {}");
	write(
		join(path, "skills", `${prefix}-skill`, "SKILL.md"),
		`---\nname: ${prefix}-skill\ndescription: ${prefix} skill\n---\n${prefix} body`,
	);
	write(join(path, "prompts", `${prefix}.md`), `${prefix} prompt`);
	write(join(path, "prompts", "skip.md"), "skip prompt");
	write(join(path, "themes", `${prefix}.json`), JSON.stringify({ name: prefix }));
	write(
		join(path, "package.json"),
		JSON.stringify(
			{
				name: `${prefix}-package`,
				version: "1.0.0",
				pi: {
					extensions: ["extensions/*.ts", "~extensions/tilde.ts", "~/extensions/home-like.ts"],
					skills: ["skills/**"],
					prompts: ["prompts/*.md"],
					themes: ["themes/*.json"],
				},
			},
			null,
			2,
		),
	);
}

function normalizePath(path: string | undefined): string | null {
	if (!path) return null;
	let normalized: string;
	try {
		normalized = realpathSync(path);
	} catch {
		normalized = resolve(path);
	}
	return relative(root, normalized).split("\\").join("/");
}

function project(resources: Array<{
	path: string;
	enabled: boolean;
	metadata: { source: string; scope: string; origin: string; baseDir?: string };
}>) {
	return resources.map((resource) => ({
		path: normalizePath(resource.path),
		enabled: resource.enabled,
		metadata: {
			source: resource.metadata.source.replace(root, "<ROOT>"),
			scope: resource.metadata.scope,
			origin: resource.metadata.origin,
			baseDir: normalizePath(resource.metadata.baseDir),
		},
	}));
}

function projectParsed(parsed:
	| { type: "npm"; spec: string; name: string; version?: string; pinned: boolean }
	| { type: "git"; repo: string; host: string; path: string; ref?: string; pinned: boolean }
	| { type: "local"; path: string }) {
	if (parsed.type === "npm") {
		return {
			type: parsed.type,
			spec: parsed.spec,
			name: parsed.name,
			version: parsed.version ?? null,
			pinned: parsed.pinned,
		};
	}
	if (parsed.type === "git") {
		return {
			type: parsed.type,
			repo: parsed.repo,
			host: parsed.host,
			path: parsed.path,
			ref: parsed.ref ?? null,
			pinned: parsed.pinned,
		};
	}
	return { type: parsed.type, path: parsed.path };
}

try {
	process.env.HOME = home;
	mkdirSync(home, { recursive: true });
	mkdirSync(agentDir, { recursive: true });
	mkdirSync(cwd, { recursive: true });
	mkdirSync(join(cwd, ".git"));
	createPackage(userPackage, "user");
	createPackage(projectPackage, "project");

	write(join(agentDir, "prompts", "auto.md"), "auto user prompt");
	write(join(agentDir, "prompts", "disabled.md"), "disabled user prompt");
	write(join(agentDir, "prompts", "ignored.md"), "ignored user prompt");
	write(join(agentDir, "prompts", ".gitignore"), "ignored.md\n");
	write(
		join(cwd, ".pi", "skills", "local", "SKILL.md"),
		"---\nname: local\ndescription: project local\n---\nlocal",
	);

	const settings = SettingsManager.inMemory(
		{
			packages: [
				{
					source: userPackage,
					extensions: [],
					prompts: ["!prompts/skip.md"],
				},
			],
			prompts: ["!prompts/disabled.md"],
		},
		{ projectTrusted: true },
	);
	settings.setProjectPackages([projectPackage]);
	settings.setProjectSkillPaths(["+skills/local"]);

	const manager = new DefaultPackageManager({ cwd, agentDir, settingsManager: settings });
	const internals = manager as unknown as {
		parseSource(source: string):
			| { type: "npm"; spec: string; name: string; version?: string; pinned: boolean }
			| { type: "git"; repo: string; host: string; path: string; ref?: string; pinned: boolean }
			| { type: "local"; path: string };
		getNpmInstallPath(
			source: { type: "npm"; spec: string; name: string; version?: string; pinned: boolean },
			scope: "user" | "project" | "temporary",
		): string;
		getGitInstallPath(
			source: { type: "git"; repo: string; host: string; path: string; ref?: string; pinned: boolean },
			scope: "user" | "project" | "temporary",
		): string;
	};
	const parseInputs = [
		"npm:@scope/pkg@1.2.3",
		"npm:plain@^2.0.0",
		"git:github.com/user/repo@v2",
		"git:git@github.com:user/repo@main",
		"https://gitlab.com/group/repo.git",
		"./github.com/user/repo",
	];
	const parsedSources = parseInputs.map((source) => ({ source, parsed: projectParsed(internals.parseSource(source)) }));
	const npmSource = internals.parseSource("npm:@scope/pkg@1.2.3");
	const gitSource = internals.parseSource("git:github.com/user/repo@v2");
	const resolved = await manager.resolve();
	const before = manager.listConfiguredPackages().map((pkg) => ({
		source: pkg.source.replace(root, "<ROOT>"),
		scope: pkg.scope,
		filtered: pkg.filtered,
		installedPath: normalizePath(pkg.installedPath),
	}));
	const added = manager.addSourceToSettings(join(root, "packages", "extra"));
	const stored = settings.getGlobalSettings().packages?.at(-1);
	const removed = manager.removeSourceFromSettings(join(root, "packages", "extra"));

	console.log(
		JSON.stringify(
			{
				resolved: {
					extensions: project(resolved.extensions),
					skills: project(resolved.skills),
					prompts: project(resolved.prompts),
					themes: project(resolved.themes),
				},
				configured: before,
				settingsMutation: {
					added,
					stored,
					removed,
				},
				parsedSources,
				installPaths: {
					npm:
						npmSource.type === "npm"
							? {
									user: normalizePath(internals.getNpmInstallPath(npmSource, "user")),
									project: normalizePath(internals.getNpmInstallPath(npmSource, "project")),
									temporary: normalizePath(internals.getNpmInstallPath(npmSource, "temporary")),
								}
							: null,
					git:
						gitSource.type === "git"
							? {
									user: normalizePath(internals.getGitInstallPath(gitSource, "user")),
									project: normalizePath(internals.getGitInstallPath(gitSource, "project")),
									temporary: normalizePath(internals.getGitInstallPath(gitSource, "temporary")),
								}
							: null,
				},
			},
			null,
			2,
		),
	);
} finally {
	if (originalHome === undefined) {
		delete process.env.HOME;
	} else {
		process.env.HOME = originalHome;
	}
	rmSync(root, { recursive: true, force: true });
}
