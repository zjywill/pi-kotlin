package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import works.earendil.pi.agent.AgentTool
import works.earendil.pi.codingagent.tools.createCodingTools

internal data class ProjectContextFile(
    val path: Path,
    val content: String,
)

internal data class PromptResources(
    val customPrompt: String?,
    val appendPrompts: List<String>,
    val contextFiles: List<ProjectContextFile>,
    val skills: List<Skill>,
    val promptTemplates: List<PromptTemplate>,
    val diagnostics: List<ResourceDiagnostic>,
    val packageResources: ResolvedPackageResources,
)

internal fun defaultAgentDirectory(): Path {
    val configured = System.getenv("PI_CODING_AGENT_DIR")
    if (!configured.isNullOrBlank()) {
        return resolvePromptPath(Path.of("").toAbsolutePath().normalize(), configured)
    }
    return defaultHomeDirectory().resolve(".pi").resolve("agent").toAbsolutePath().normalize()
}

internal fun defaultHomeDirectory(): Path =
    System.getenv("HOME")
        ?.takeIf(String::isNotBlank)
        ?.let(Path::of)
        ?.toAbsolutePath()
        ?.normalize()
        ?: Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()

internal fun loadPromptResources(
    cwd: Path,
    agentDir: Path = defaultAgentDirectory(),
    systemPromptSource: String? = null,
    appendPromptSources: List<String> = emptyList(),
    noContextFiles: Boolean = false,
    skillPaths: List<String> = emptyList(),
    noSkills: Boolean = false,
    promptTemplatePaths: List<String> = emptyList(),
    noPromptTemplates: Boolean = false,
    projectTrusted: Boolean = false,
    homeDir: Path = defaultHomeDirectory(),
    resolvedPackageResources: ResolvedPackageResources? = null,
    onWarning: (String) -> Unit = {},
): PromptResources {
    val normalizedCwd = cwd.toAbsolutePath().normalize()
    val normalizedAgentDir = agentDir.toAbsolutePath().normalize()
    val packageResources =
        resolvedPackageResources
            ?: resolvePackageResources(
                cwd = normalizedCwd,
                agentDir = normalizedAgentDir,
                projectTrusted = projectTrusted,
                homeDir = homeDir,
                onWarning = onWarning,
            )
    val discoveredSystemPrompt =
        systemPromptSource
            ?: discoverPromptFile(
                normalizedCwd,
                normalizedAgentDir,
                "SYSTEM.md",
                projectTrusted,
            )
    val discoveredAppendPrompts =
        if (appendPromptSources.isNotEmpty()) {
            appendPromptSources
        } else {
            listOfNotNull(
                discoverPromptFile(
                    normalizedCwd,
                    normalizedAgentDir,
                    "APPEND_SYSTEM.md",
                    projectTrusted,
                ),
            )
        }
    val loadedSkills =
        loadSkills(
            cwd = normalizedCwd,
            agentDir = normalizedAgentDir,
            skillPaths = skillPaths,
            includeDefaults = !noSkills,
            projectTrusted = projectTrusted,
            homeDir = homeDir,
            defaultResources = packageResources.skills,
        )
    val loadedPrompts =
        loadPromptTemplates(
            cwd = normalizedCwd,
            agentDir = normalizedAgentDir,
            promptPaths = promptTemplatePaths,
            includeDefaults = !noPromptTemplates,
            projectTrusted = projectTrusted,
            defaultResources = packageResources.prompts,
        )
    val diagnostics = loadedSkills.diagnostics + loadedPrompts.diagnostics
    diagnostics.forEach { diagnostic ->
        val path = diagnostic.path?.let { ": $it" }.orEmpty()
        onWarning("${diagnostic.message}$path")
    }

    return PromptResources(
        customPrompt =
            discoveredSystemPrompt?.let {
                resolvePromptInput(it, normalizedCwd, "system prompt", onWarning)
            },
        appendPrompts =
            discoveredAppendPrompts.map {
                resolvePromptInput(it, normalizedCwd, "append system prompt", onWarning)
            },
        contextFiles =
            if (noContextFiles) {
                emptyList()
            } else {
                loadProjectContextFiles(normalizedCwd, normalizedAgentDir, onWarning)
            },
        skills = loadedSkills.skills,
        promptTemplates = loadedPrompts.prompts,
        diagnostics = diagnostics,
        packageResources = packageResources,
    )
}

internal fun resolvePackageResources(
    cwd: Path,
    agentDir: Path = defaultAgentDirectory(),
    projectTrusted: Boolean,
    homeDir: Path = defaultHomeDirectory(),
    onWarning: (String) -> Unit = {},
): ResolvedPackageResources {
    val normalizedCwd = cwd.toAbsolutePath().normalize()
    val normalizedAgentDir = agentDir.toAbsolutePath().normalize()
    val settingsStore =
        SettingsStore(
            cwd = normalizedCwd,
            agentDir = normalizedAgentDir,
            projectTrusted = projectTrusted,
            onWarning = onWarning,
        )
    return PackageManager(
        cwd = normalizedCwd,
        agentDir = normalizedAgentDir,
        settings = settingsStore,
        projectTrusted = projectTrusted,
        homeDir = homeDir,
    ).resolve()
}

internal fun ResolvedPackageResources.merge(other: ResolvedPackageResources): ResolvedPackageResources =
    ResolvedPackageResources(
        extensions = mergeResources(extensions, other.extensions),
        skills = mergeResources(skills, other.skills),
        prompts = mergeResources(prompts, other.prompts),
        themes = mergeResources(themes, other.themes),
    )

private fun mergeResources(
    first: List<ResolvedResource>,
    second: List<ResolvedResource>,
): List<ResolvedResource> {
    val merged = linkedMapOf<Path, ResolvedResource>()
    (first + second).forEach { resource ->
        merged.putIfAbsent(canonicalPath(resource.path), resource)
    }
    return merged.values.toList()
}

internal fun loadProjectContextFiles(
    cwd: Path,
    agentDir: Path,
    onWarning: (String) -> Unit = {},
): List<ProjectContextFile> {
    val contextFiles = mutableListOf<ProjectContextFile>()
    val seenPaths = linkedSetOf<Path>()
    loadContextFileFromDirectory(agentDir, onWarning)?.let { global ->
        contextFiles += global
        seenPaths.add(global.path)
    }

    val shadowedContextFile = findShadowedContextFile(cwd)
    val ancestors = ArrayDeque<ProjectContextFile>()
    var current: Path? = cwd
    while (current != null) {
        loadContextFileFromDirectory(current, onWarning)?.let { contextFile ->
            if (
                canonicalPath(contextFile.path) != shadowedContextFile &&
                contextFile.path !in seenPaths
            ) {
                ancestors.addFirst(contextFile)
                seenPaths.add(contextFile.path)
            }
        }
        current = current.parent
    }
    contextFiles += ancestors
    return contextFiles
}

private data class GitContextPaths(
    val repoDir: Path,
    val commonGitDir: Path,
)

private fun findShadowedContextFile(cwd: Path): Path? {
    val gitPaths = findGitContextPaths(cwd) ?: return null
    val worktreeRoot = canonicalPath(gitPaths.repoDir)
    val commonGitDir = canonicalPath(gitPaths.commonGitDir)
    val mainRepoRoot = commonGitDir.parent ?: return null
    if (worktreeRoot == mainRepoRoot || !worktreeRoot.startsWith(mainRepoRoot)) {
        return null
    }
    if (canonicalPath(mainRepoRoot.resolve(".git")) != commonGitDir) {
        return null
    }
    val worktreeContext =
        loadContextFileFromDirectory(worktreeRoot) {}
            ?: return null
    return canonicalPath(mainRepoRoot.resolve(worktreeContext.path.fileName))
}

private fun findGitContextPaths(cwd: Path): GitContextPaths? {
    var current: Path? = cwd.toAbsolutePath().normalize()
    while (current != null) {
        val gitPath = current.resolve(".git")
        when {
            Files.isDirectory(gitPath) ->
                return GitContextPaths(current, gitPath)

            Files.isRegularFile(gitPath) -> {
                val gitDirLine =
                    runCatching { Files.readString(gitPath).lineSequence().firstOrNull() }
                        .getOrNull()
                        ?.trim()
                        ?: return null
                if (!gitDirLine.startsWith("gitdir:")) {
                    return null
                }
                val rawGitDir = Path.of(gitDirLine.removePrefix("gitdir:").trim())
                val gitDir =
                    canonicalPath(
                        if (rawGitDir.isAbsolute) rawGitDir else gitPath.parent.resolve(rawGitDir),
                    )
                val commonDirFile = gitDir.resolve("commondir")
                val commonGitDir =
                    if (Files.isRegularFile(commonDirFile)) {
                        val rawCommon =
                            runCatching { Path.of(Files.readString(commonDirFile).trim()) }
                                .getOrNull()
                                ?: return null
                        canonicalPath(
                            if (rawCommon.isAbsolute) rawCommon else gitDir.resolve(rawCommon),
                        )
                    } else {
                        gitDir
                    }
                return GitContextPaths(current, commonGitDir)
            }
        }
        current = current.parent
    }
    return null
}

internal fun createSelectedCodingTools(
    cwd: Path,
    noTools: Boolean,
    noBuiltinTools: Boolean,
    allowedTools: List<String>?,
    excludedTools: List<String>?,
    extensionTools: List<AgentTool> = emptyList(),
): List<AgentTool> {
    if (noTools) {
        return emptyList()
    }
    val extensionNames = extensionTools.mapTo(mutableSetOf(), AgentTool::name)
    val builtInTools =
        if (noBuiltinTools) {
            emptyList()
        } else {
            createCodingTools(cwd).filterNot { it.name in extensionNames }
        }
    return (builtInTools + extensionTools).filter { tool ->
        (allowedTools == null || tool.name in allowedTools) &&
            (excludedTools == null || tool.name !in excludedTools)
    }
}

internal fun buildCodingSystemPrompt(
    cwd: Path,
    tools: List<AgentTool>,
    resources: PromptResources,
): String {
    val normalizedCwd = cwd.toAbsolutePath().normalize().toString().replace('\\', '/')
    val basePrompt =
        resources.customPrompt?.takeIf(String::isNotEmpty)
            ?: buildString {
                append(
                    "You are an expert coding assistant operating inside pi, a coding agent harness. " +
                        "You help users by reading files, executing commands, editing code, and writing new files.",
                )
                append("\n\nAvailable tools:\n")
                if (tools.isEmpty()) {
                    append("(none)")
                } else {
                    append(tools.joinToString("\n") { "- ${it.name}: ${it.description.singleLine()}" })
                }
                append("\n\nGuidelines:\n")
                if (tools.any { it.name == "bash" } && tools.none { it.name in FILE_EXPLORATION_TOOLS }) {
                    append("- Use bash for file operations like ls, rg, find\n")
                }
                append("- Be concise in your responses\n")
                append("- Show file paths clearly when working with files")
            }

    return buildString {
        append(basePrompt)
        resources.appendPrompts.filter(String::isNotEmpty).forEach { prompt ->
            append("\n\n")
            append(prompt)
        }
        if (resources.contextFiles.isNotEmpty()) {
            append("\n\n<project_context>\n\n")
            append("Project-specific instructions and guidelines:\n\n")
            resources.contextFiles.forEach { contextFile ->
                val displayPath = contextFile.path.toString().replace('\\', '/')
                append("<project_instructions path=\"")
                append(displayPath)
                append("\">\n")
                append(contextFile.content)
                append("\n</project_instructions>\n\n")
            }
            append("</project_context>\n")
        }
        if (tools.any { it.name == "read" } && resources.skills.isNotEmpty()) {
            append(formatSkillsForPrompt(resources.skills))
        }
        append("\nCurrent working directory: ")
        append(normalizedCwd)
    }
}

private val CONTEXT_FILE_NAMES = listOf("AGENTS.md", "AGENTS.MD", "CLAUDE.md", "CLAUDE.MD")
private val FILE_EXPLORATION_TOOLS = setOf("grep", "find", "ls")

private fun discoverPromptFile(
    cwd: Path,
    agentDir: Path,
    name: String,
    projectTrusted: Boolean,
): String? {
    val projectFile = cwd.resolve(".pi").resolve(name)
    if (projectTrusted && Files.exists(projectFile)) {
        return projectFile.toString()
    }
    return agentDir.resolve(name).takeIf(Files::exists)?.toString()
}

private fun loadContextFileFromDirectory(
    directory: Path,
    onWarning: (String) -> Unit,
): ProjectContextFile? {
    for (name in CONTEXT_FILE_NAMES) {
        val path = directory.resolve(name).toAbsolutePath().normalize()
        if (!Files.exists(path)) {
            continue
        }
        try {
            if (!Files.isRegularFile(path)) {
                continue
            }
            return ProjectContextFile(path, Files.readString(path))
        } catch (error: Exception) {
            onWarning("Could not read $path: ${error.message}")
        }
    }
    return null
}

private fun resolvePromptInput(
    input: String,
    cwd: Path,
    description: String,
    onWarning: (String) -> Unit,
): String {
    val path = runCatching { resolvePromptPath(cwd, input) }.getOrNull() ?: return input
    if (!Files.exists(path)) {
        return input
    }
    return try {
        Files.readString(path)
    } catch (error: Exception) {
        onWarning("Could not read $description file $path: ${error.message}")
        input
    }
}

private fun resolvePromptPath(
    cwd: Path,
    value: String,
): Path {
    val expanded =
        if (value == "~" || value.startsWith("~/")) {
            defaultHomeDirectory().resolve(value.removePrefix("~/"))
        } else {
            Path.of(value)
        }
    return (if (expanded.isAbsolute) expanded else cwd.resolve(expanded)).toAbsolutePath().normalize()
}

private fun String.singleLine(): String =
    replace(Regex("[\\r\\n]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
