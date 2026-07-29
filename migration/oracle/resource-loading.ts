import {
	mkdirSync,
	mkdtempSync,
	readFileSync,
	realpathSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, relative } from "node:path";
import { pathToFileURL } from "node:url";

const sourceRoot = process.env.PI_TYPESCRIPT_ROOT ?? "/Users/junyizhang/Git/pi";
const { DefaultResourceLoader } = await import(
	pathToFileURL(`${sourceRoot}/packages/coding-agent/src/core/resource-loader.ts`).href
);
const {
	expandPromptTemplate,
	parseCommandArgs,
	substituteArgs,
} = await import(pathToFileURL(`${sourceRoot}/packages/coding-agent/src/core/prompt-templates.ts`).href);
const { formatSkillsForPrompt } = await import(
	pathToFileURL(`${sourceRoot}/packages/coding-agent/src/core/skills.ts`).href
);
const { SettingsManager } = await import(
	pathToFileURL(`${sourceRoot}/packages/coding-agent/src/core/settings-manager.ts`).href
);
const { ProjectTrustStore, hasTrustRequiringProjectResources } = await import(
	pathToFileURL(`${sourceRoot}/packages/coding-agent/src/core/trust-manager.ts`).href
);

const root = realpathSync(mkdtempSync(join(tmpdir(), "pi-resource-oracle-")));
const home = join(root, "home");
const agentDir = join(root, "agent");
const cwd = join(root, "workspace", "project");
const child = join(cwd, "child");
const originalHome = process.env.HOME;

function write(path: string, content: string): void {
	mkdirSync(join(path, ".."), { recursive: true });
	writeFileSync(path, content);
}

function normalizePath(path: string | undefined): string | undefined {
	if (!path) return undefined;
	return relative(root, realpathSync(path)).split("\\").join("/");
}

function normalizeText(value: string): string {
	return value.split(root).join("<ROOT>");
}

function projectResource(resource: {
	name: string;
	description: string;
	filePath: string;
	baseDir?: string;
	disableModelInvocation?: boolean;
	sourceInfo: {
		path: string;
		source: string;
		scope: string;
		origin: string;
		baseDir?: string;
	};
}) {
	return {
		name: resource.name,
		description: resource.description,
		disableModelInvocation: resource.disableModelInvocation ?? false,
		filePath: normalizePath(resource.filePath),
		baseDir: normalizePath(resource.baseDir),
		sourceInfo: {
			path: normalizePath(resource.sourceInfo.path),
			source: resource.sourceInfo.source,
			scope: resource.sourceInfo.scope,
			origin: resource.sourceInfo.origin,
			baseDir: normalizePath(resource.sourceInfo.baseDir),
		},
	};
}

function projectPrompt(prompt: {
	name: string;
	description: string;
	argumentHint?: string;
	content: string;
	filePath: string;
	sourceInfo: {
		path: string;
		source: string;
		scope: string;
		origin: string;
		baseDir?: string;
	};
}) {
	return {
		name: prompt.name,
		description: prompt.description,
		argumentHint: prompt.argumentHint ?? null,
		content: prompt.content,
		filePath: normalizePath(prompt.filePath),
		sourceInfo: {
			path: normalizePath(prompt.sourceInfo.path),
			source: prompt.sourceInfo.source,
			scope: prompt.sourceInfo.scope,
			origin: prompt.sourceInfo.origin,
			baseDir: normalizePath(prompt.sourceInfo.baseDir),
		},
	};
}

function projectDiagnostic(diagnostic: {
	type: string;
	message: string;
	path?: string;
	collision?: {
		resourceType: string;
		name: string;
		winnerPath: string;
		loserPath: string;
	};
}) {
	return {
		type: diagnostic.type,
		message: diagnostic.message,
		path: normalizePath(diagnostic.path),
		collision: diagnostic.collision
			? {
					resourceType: diagnostic.collision.resourceType,
					name: diagnostic.collision.name,
					winnerPath: normalizePath(diagnostic.collision.winnerPath),
					loserPath: normalizePath(diagnostic.collision.loserPath),
				}
			: null,
	};
}

try {
	process.env.HOME = home;
	mkdirSync(home, { recursive: true });
	mkdirSync(agentDir, { recursive: true });
	mkdirSync(child, { recursive: true });
	mkdirSync(join(cwd, ".git"));

	write(
		join(agentDir, "skills", "shared", "SKILL.md"),
		"---\nname: shared\ndescription: User skill\n---\nUser skill body.",
	);
	write(
		join(agentDir, "skills", "manual", "SKILL.md"),
		"---\nname: manual\ndescription: Manual only\ndisable-model-invocation: true\n---\nManual body.",
	);
	write(
		join(cwd, ".pi", "skills", "shared", "SKILL.md"),
		"---\nname: shared\ndescription: Project skill\n---\nProject skill body.",
	);
	write(
		join(cwd, ".agents", "skills", "ancestor", "SKILL.md"),
		"---\nname: ancestor\ndescription: Ancestor skill\n---\nAncestor body.",
	);
	write(join(agentDir, "prompts", "review.md"), "User review $ARGUMENTS");
	write(
		join(cwd, ".pi", "prompts", "review.md"),
		'---\ndescription: Project review\nargument-hint: "<target>"\n---\nReview $1 with ${@:2}',
	);
	write(join(agentDir, "SYSTEM.md"), "Global system.");
	write(join(cwd, ".pi", "SYSTEM.md"), "Project system.");
	write(join(cwd, ".pi", "APPEND_SYSTEM.md"), "Project append.");
	write(join(agentDir, "AGENTS.md"), "Global context.");
	write(join(cwd, "AGENTS.md"), "Project context.");

	const trustedSettings = SettingsManager.create(cwd, agentDir, { projectTrusted: true });
	const trusted = new DefaultResourceLoader({
		cwd,
		agentDir,
		settingsManager: trustedSettings,
		noExtensions: true,
		noThemes: true,
	});
	await trusted.reload();

	const untrustedSettings = SettingsManager.create(cwd, agentDir, { projectTrusted: false });
	const untrusted = new DefaultResourceLoader({
		cwd,
		agentDir,
		settingsManager: untrustedSettings,
		noExtensions: true,
		noThemes: true,
	});
	await untrusted.reload();

	const trustStore = new ProjectTrustStore(agentDir);
	const trustBefore = trustStore.get(child);
	trustStore.set(cwd, true);
	const inheritedTrust = trustStore.get(child);
	trustStore.set(child, false);
	const childTrust = trustStore.get(child);
	trustStore.set(child, null);
	const restoredTrust = trustStore.get(child);

	const trustedSkills = trusted.getSkills();
	const trustedPrompts = trusted.getPrompts();
	const untrustedSkills = untrusted.getSkills();
	const untrustedPrompts = untrusted.getPrompts();

	console.log(
		JSON.stringify(
			{
				commandArgs: parseCommandArgs('"auth flow" strict mode'),
				substitution: substituteArgs("$1|${@:2}|${4:-fallback}|$ARGUMENTS", [
					"auth flow",
					"strict",
					"mode",
				]),
				templateExpansion: expandPromptTemplate("/review \"auth flow\" strict mode", trustedPrompts.prompts),
				trusted: {
					systemPrompt: trusted.getSystemPrompt(),
					systemPromptSource: trusted.getSystemPromptSource()
						? normalizePath(trusted.getSystemPromptSource()!.path)
						: null,
					appendSystemPrompt: trusted.getAppendSystemPrompt(),
					appendSystemPromptSources: trusted
						.getAppendSystemPromptSources()
						.map((source) => normalizePath(source.path)),
					contextFiles: trusted
						.getAgentsFiles()
						.agentsFiles.map((file) => ({ path: normalizePath(file.path), content: file.content })),
					skills: trustedSkills.skills.map(projectResource),
					skillDiagnostics: trustedSkills.diagnostics.map(projectDiagnostic),
					prompts: trustedPrompts.prompts.map(projectPrompt),
					promptDiagnostics: trustedPrompts.diagnostics.map(projectDiagnostic),
					formattedSkills: normalizeText(formatSkillsForPrompt(trustedSkills.skills)),
				},
				untrusted: {
					systemPrompt: untrusted.getSystemPrompt(),
					systemPromptSource: untrusted.getSystemPromptSource()
						? normalizePath(untrusted.getSystemPromptSource()!.path)
						: null,
					appendSystemPrompt: untrusted.getAppendSystemPrompt(),
					appendSystemPromptSources: untrusted
						.getAppendSystemPromptSources()
						.map((source) => normalizePath(source.path)),
					contextFiles: untrusted
						.getAgentsFiles()
						.agentsFiles.map((file) => ({ path: normalizePath(file.path), content: file.content })),
					skills: untrustedSkills.skills.map(projectResource),
					skillDiagnostics: untrustedSkills.diagnostics.map(projectDiagnostic),
					prompts: untrustedPrompts.prompts.map(projectPrompt),
					promptDiagnostics: untrustedPrompts.diagnostics.map(projectDiagnostic),
					formattedSkills: normalizeText(formatSkillsForPrompt(untrustedSkills.skills)),
				},
				trust: {
					requiresTrust: hasTrustRequiringProjectResources(cwd),
					before: trustBefore,
					inherited: inheritedTrust,
					child: childTrust,
					restored: restoredTrust,
					file: Object.fromEntries(
						Object.entries(
							JSON.parse(readFileSync(join(agentDir, "trust.json"), "utf-8")) as Record<string, boolean>,
						).map(([path, decision]) => [normalizePath(path), decision]),
					),
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
