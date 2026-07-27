package works.earendil.pi.codingagent

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

private const val MAX_SKILL_NAME_LENGTH = 64
private const val MAX_SKILL_DESCRIPTION_LENGTH = 1_024
private val IGNORE_FILE_NAMES = listOf(".gitignore", ".ignore", ".fdignore")

internal fun loadSkills(
    cwd: Path,
    agentDir: Path,
    skillPaths: List<String> = emptyList(),
    includeDefaults: Boolean = true,
    projectTrusted: Boolean = true,
    homeDir: Path = defaultHomeDirectory(),
    defaultResources: List<ResolvedResource>? = null,
): LoadedSkills {
    val normalizedCwd = canonicalPath(cwd)
    val normalizedAgentDir = canonicalPath(agentDir)
    val normalizedHome = canonicalPath(homeDir)
    val skills = linkedMapOf<String, Skill>()
    val canonicalFiles = linkedSetOf<Path>()
    val diagnostics = mutableListOf<ResourceDiagnostic>()

    fun add(result: LoadedSkills) {
        diagnostics += result.diagnostics
        result.skills.forEach { skill ->
            val canonicalFile = canonicalPath(skill.filePath)
            if (!canonicalFiles.add(canonicalFile)) {
                return@forEach
            }
            val existing = skills[skill.name]
            if (existing == null) {
                skills[skill.name] = skill
            } else {
                diagnostics +=
                    ResourceDiagnostic(
                        type = ResourceDiagnosticType.COLLISION,
                        message = "name \"${skill.name}\" collision",
                        path = skill.filePath,
                        collision =
                            ResourceCollision(
                                resourceType = "skill",
                                name = skill.name,
                                winnerPath = existing.filePath,
                                loserPath = skill.filePath,
                            ),
                    )
            }
        }
    }

    if (includeDefaults && defaultResources != null) {
        defaultResources
            .filter(ResolvedResource::enabled)
            .forEach { resource ->
                val sourceInfoFactory = { filePath: Path, fallbackBaseDir: Path ->
                    resource.sourceInfo.copy(
                        path = filePath.toAbsolutePath().normalize(),
                        baseDir = resource.sourceInfo.baseDir ?: fallbackBaseDir,
                    )
                }
                add(
                    if (Files.isDirectory(resource.path)) {
                        loadSkillsFromDirectory(
                            directory = resource.path,
                            sourceInfoFactory = sourceInfoFactory,
                            includeRootMarkdown = true,
                        )
                    } else {
                        loadSkillFile(resource.path, sourceInfoFactory)
                    },
                )
            }
    } else {
        if (includeDefaults && projectTrusted) {
            val projectBaseDir = normalizedCwd.resolve(".pi")
            add(
                loadSkillsFromDirectory(
                    projectBaseDir.resolve("skills"),
                    sourceInfoFactory = sourceInfoFactory("auto", "project", projectBaseDir),
                    includeRootMarkdown = true,
                ),
            )
            collectAncestorAgentsSkillDirectories(normalizedCwd).forEach { directory ->
                if (canonicalPath(directory) != canonicalPath(normalizedHome.resolve(".agents").resolve("skills"))) {
                    add(
                        loadSkillsFromDirectory(
                            directory,
                            sourceInfoFactory =
                                sourceInfoFactory(
                                    source = "auto",
                                    scope = "project",
                                    baseDir = directory.parent,
                                ),
                            includeRootMarkdown = false,
                        ),
                    )
                }
            }
        }
        if (includeDefaults) {
            add(
                loadSkillsFromDirectory(
                    normalizedAgentDir.resolve("skills"),
                    sourceInfoFactory = sourceInfoFactory("auto", "user", normalizedAgentDir),
                    includeRootMarkdown = true,
                ),
            )
            val userAgentsSkills = normalizedHome.resolve(".agents").resolve("skills")
            add(
                loadSkillsFromDirectory(
                    userAgentsSkills,
                    sourceInfoFactory =
                        sourceInfoFactory(
                            source = "auto",
                            scope = "user",
                            baseDir = userAgentsSkills.parent,
                        ),
                    includeRootMarkdown = false,
                ),
            )
        }
    }
    skillPaths.forEach { rawPath ->
        val path = resolveResourcePath(normalizedCwd, rawPath)
        if (!Files.exists(path)) {
            diagnostics +=
                ResourceDiagnostic(
                    ResourceDiagnosticType.WARNING,
                    "skill path does not exist",
                    path,
                )
            return@forEach
        }
        when {
            Files.isDirectory(path) ->
                add(
                    loadSkillsFromDirectory(
                        path,
                        sourceInfoFactory = sourceInfoFactory("local", "temporary"),
                        includeRootMarkdown = true,
                    ),
                )

            Files.isRegularFile(path) && path.fileName.toString().endsWith(".md") ->
                add(loadSkillFile(path, sourceInfoFactory("local", "temporary")))

            else ->
                diagnostics +=
                    ResourceDiagnostic(
                        ResourceDiagnosticType.WARNING,
                        "skill path is not a markdown file",
                        path,
                    )
        }
    }
    return LoadedSkills(skills.values.toList(), diagnostics)
}

internal fun loadSkillsFromDirectory(
    directory: Path,
    source: String,
): LoadedSkills =
    loadSkillsFromDirectory(
        directory = directory,
        sourceInfoFactory = sourceInfoFactory(source, "temporary"),
        includeRootMarkdown = true,
    )

private fun loadSkillsFromDirectory(
    directory: Path,
    sourceInfoFactory: (Path, Path) -> ResourceSourceInfo,
    includeRootMarkdown: Boolean,
    rootDirectory: Path = directory,
    inheritedIgnoreRules: List<IgnoreRule> = emptyList(),
): LoadedSkills {
    if (!Files.exists(directory) || !Files.isDirectory(directory)) {
        return LoadedSkills(emptyList(), emptyList())
    }
    val rules = inheritedIgnoreRules + readIgnoreRules(directory, rootDirectory)
    val entries =
        runCatching {
            Files.list(directory).use { stream ->
                stream.sorted(compareBy<Path> { it.fileName.toString() }).toList()
            }
        }.getOrElse { return LoadedSkills(emptyList(), emptyList()) }
    val rootSkill =
        entries.firstOrNull { path ->
            path.fileName.toString() == "SKILL.md" &&
                Files.isRegularFile(path) &&
                !isIgnored(rootDirectory, path, isDirectory = false, rules)
        }
    if (rootSkill != null) {
        return loadSkillFile(rootSkill, sourceInfoFactory)
    }

    val skills = mutableListOf<Skill>()
    val diagnostics = mutableListOf<ResourceDiagnostic>()
    entries.forEach { path ->
        val name = path.fileName.toString()
        if (name.startsWith(".") || name == "node_modules") {
            return@forEach
        }
        val isDirectory = Files.isDirectory(path)
        if (isIgnored(rootDirectory, path, isDirectory, rules)) {
            return@forEach
        }
        when {
            isDirectory -> {
                val nested =
                    loadSkillsFromDirectory(
                        directory = path,
                        sourceInfoFactory = sourceInfoFactory,
                        includeRootMarkdown = false,
                        rootDirectory = rootDirectory,
                        inheritedIgnoreRules = rules,
                    )
                skills += nested.skills
                diagnostics += nested.diagnostics
            }

            includeRootMarkdown && Files.isRegularFile(path) && name.endsWith(".md") -> {
                val loaded = loadSkillFile(path, sourceInfoFactory)
                skills += loaded.skills
                diagnostics += loaded.diagnostics
            }
        }
    }
    return LoadedSkills(skills, diagnostics)
}

private fun loadSkillFile(
    filePath: Path,
    sourceInfoFactory: (Path, Path) -> ResourceSourceInfo,
): LoadedSkills {
    val diagnostics = mutableListOf<ResourceDiagnostic>()
    return try {
        val parsed = parseFrontmatter(Files.readString(filePath))
        val description = parsed.values["description"] as? String
        if (description.isNullOrBlank()) {
            diagnostics +=
                ResourceDiagnostic(
                    ResourceDiagnosticType.WARNING,
                    "description is required",
                    filePath,
                )
        } else if (description.length > MAX_SKILL_DESCRIPTION_LENGTH) {
            diagnostics +=
                ResourceDiagnostic(
                    ResourceDiagnosticType.WARNING,
                    "description exceeds $MAX_SKILL_DESCRIPTION_LENGTH characters (${description.length})",
                    filePath,
                )
        }
        val name = (parsed.values["name"] as? String).orEmpty().ifBlank { filePath.parent.name }
        validateSkillName(name).forEach { message ->
            diagnostics += ResourceDiagnostic(ResourceDiagnosticType.WARNING, message, filePath)
        }
        if (description.isNullOrBlank()) {
            LoadedSkills(emptyList(), diagnostics)
        } else {
            val baseDir = filePath.parent.toAbsolutePath().normalize()
            LoadedSkills(
                skills =
                    listOf(
                        Skill(
                            name = name,
                            description = description,
                            filePath = filePath.toAbsolutePath().normalize(),
                            baseDir = baseDir,
                            sourceInfo = sourceInfoFactory(filePath, baseDir),
                            disableModelInvocation = parsed.values["disable-model-invocation"] == true,
                        ),
                    ),
                diagnostics = diagnostics,
            )
        }
    } catch (error: Exception) {
        LoadedSkills(
            emptyList(),
            listOf(
                ResourceDiagnostic(
                    ResourceDiagnosticType.WARNING,
                    error.message ?: "failed to parse skill file",
                    filePath,
                ),
            ),
        )
    }
}

internal fun formatSkillsForPrompt(skills: List<Skill>): String {
    val visible = skills.filterNot(Skill::disableModelInvocation)
    if (visible.isEmpty()) {
        return ""
    }
    return buildString {
        append("\n\nThe following skills provide specialized instructions for specific tasks.\n")
        append("Use the read tool to load a skill's file when the task matches its description.\n")
        append(
            "When a skill file references a relative path, resolve it against the skill directory " +
                "(parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.\n\n",
        )
        append("<available_skills>\n")
        visible.forEach { skill ->
            append("  <skill>\n")
            append("    <name>${escapeXml(skill.name)}</name>\n")
            append("    <description>${escapeXml(skill.description)}</description>\n")
            append("    <location>${escapeXml(skill.filePath.toString())}</location>\n")
            append("  </skill>\n")
        }
        append("</available_skills>")
    }
}

internal fun expandSkillCommand(
    text: String,
    skills: List<Skill>,
    onWarning: (String) -> Unit = {},
): String {
    if (!text.startsWith("/skill:")) {
        return text
    }
    val space = text.indexOf(' ')
    val name = if (space < 0) text.drop(7) else text.substring(7, space)
    val arguments = if (space < 0) "" else text.substring(space + 1).trim()
    val skill = skills.firstOrNull { it.name == name } ?: return text
    return try {
        val body = stripFrontmatter(Files.readString(skill.filePath)).trim()
        val block =
            "<skill name=\"${skill.name}\" location=\"${skill.filePath}\">\n" +
                "References are relative to ${skill.baseDir}.\n\n" +
                "$body\n</skill>"
        if (arguments.isEmpty()) block else "$block\n\n$arguments"
    } catch (error: Exception) {
        onWarning("Could not read skill ${skill.filePath}: ${error.message}")
        text
    }
}

private fun validateSkillName(name: String): List<String> =
    buildList {
        if (name.length > MAX_SKILL_NAME_LENGTH) {
            add("name exceeds $MAX_SKILL_NAME_LENGTH characters (${name.length})")
        }
        if (!name.matches(Regex("^[a-z0-9-]+$"))) {
            add("name contains invalid characters (must be lowercase a-z, 0-9, hyphens only)")
        }
        if (name.startsWith("-") || name.endsWith("-")) {
            add("name must not start or end with a hyphen")
        }
        if ("--" in name) {
            add("name must not contain consecutive hyphens")
        }
    }

private fun sourceInfoFactory(
    source: String,
    scope: String,
    baseDir: Path? = null,
): (Path, Path) -> ResourceSourceInfo =
    { filePath, fallbackBaseDir ->
        ResourceSourceInfo(
            path = filePath.toAbsolutePath().normalize(),
            source = source,
            scope = scope,
            baseDir = baseDir?.toAbsolutePath()?.normalize() ?: fallbackBaseDir,
        )
    }

private fun collectAncestorAgentsSkillDirectories(cwd: Path): List<Path> {
    val result = mutableListOf<Path>()
    val gitRoot = findGitRoot(cwd)
    var current: Path? = cwd
    while (current != null) {
        result.add(current.resolve(".agents").resolve("skills"))
        if (current == gitRoot) {
            break
        }
        current = current.parent
    }
    return result
}

private fun findGitRoot(start: Path): Path? {
    var current: Path? = start
    while (current != null) {
        if (Files.exists(current.resolve(".git"))) {
            return current
        }
        current = current.parent
    }
    return null
}

private fun resolveResourcePath(
    cwd: Path,
    rawPath: String,
): Path {
    val trimmed = rawPath.trim()
    val expanded =
        if (trimmed == "~" || trimmed.startsWith("~/")) {
            defaultHomeDirectory().resolve(trimmed.removePrefix("~/"))
        } else {
            Path.of(trimmed)
        }
    return (if (expanded.isAbsolute) expanded else cwd.resolve(expanded)).toAbsolutePath().normalize()
}

private data class IgnoreRule(
    val pattern: String,
    val negated: Boolean,
)

private fun readIgnoreRules(
    directory: Path,
    root: Path,
): List<IgnoreRule> {
    val prefix =
        root.relativize(directory)
            .toString()
            .replace('\\', '/')
            .takeIf(String::isNotEmpty)
            ?.plus("/")
            .orEmpty()
    return IGNORE_FILE_NAMES.flatMap { name ->
        val path = directory.resolve(name)
        if (!Files.isRegularFile(path)) {
            return@flatMap emptyList()
        }
        runCatching {
            Files.readAllLines(path).mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || (trimmed.startsWith("#") && !trimmed.startsWith("\\#"))) {
                    return@mapNotNull null
                }
                var pattern = line
                val negated = pattern.startsWith("!")
                if (negated) {
                    pattern = pattern.drop(1)
                } else if (pattern.startsWith("\\!")) {
                    pattern = pattern.drop(1)
                }
                if (pattern.startsWith("/")) {
                    pattern = pattern.drop(1)
                }
                IgnoreRule(prefix + pattern, negated)
            }
        }.getOrDefault(emptyList())
    }
}

private fun isIgnored(
    root: Path,
    path: Path,
    isDirectory: Boolean,
    rules: List<IgnoreRule>,
): Boolean {
    val relative = root.relativize(path).toString().replace('\\', '/')
    var ignored = false
    rules.forEach { rule ->
        if (matchesIgnoreRule(relative, isDirectory, rule.pattern)) {
            ignored = !rule.negated
        }
    }
    return ignored
}

private fun matchesIgnoreRule(
    relative: String,
    isDirectory: Boolean,
    rawPattern: String,
): Boolean {
    val directoryOnly = rawPattern.endsWith("/")
    val pattern = rawPattern.removeSuffix("/")
    if (directoryOnly && !isDirectory) {
        return false
    }
    if ('/' !in pattern) {
        return relative.split('/').any { segment -> globMatches(pattern, segment) }
    }
    return globMatches(pattern, relative) ||
        (directoryOnly && relative.startsWith("$pattern/"))
}

private fun globMatches(
    pattern: String,
    value: String,
): Boolean =
    runCatching {
        FileSystems.getDefault().getPathMatcher("glob:$pattern").matches(Path.of(value))
    }.getOrDefault(false)

private fun escapeXml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
