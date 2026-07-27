package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path

internal fun loadPromptTemplates(
    cwd: Path,
    agentDir: Path,
    promptPaths: List<String> = emptyList(),
    includeDefaults: Boolean = true,
    projectTrusted: Boolean = true,
    defaultResources: List<ResolvedResource>? = null,
): LoadedPromptTemplates {
    val normalizedCwd = canonicalPath(cwd)
    val normalizedAgentDir = canonicalPath(agentDir)
    val prompts = linkedMapOf<String, PromptTemplate>()
    val diagnostics = mutableListOf<ResourceDiagnostic>()

    fun add(loaded: List<PromptTemplate>) {
        loaded.forEach { prompt ->
            val existing = prompts[prompt.name]
            if (existing == null) {
                prompts[prompt.name] = prompt
            } else {
                diagnostics +=
                    ResourceDiagnostic(
                        type = ResourceDiagnosticType.COLLISION,
                        message = "name \"/${prompt.name}\" collision",
                        path = prompt.filePath,
                        collision =
                            ResourceCollision(
                                resourceType = "prompt",
                                name = prompt.name,
                                winnerPath = existing.filePath,
                                loserPath = prompt.filePath,
                            ),
                    )
            }
        }
    }

    if (includeDefaults && defaultResources != null) {
        defaultResources
            .filter(ResolvedResource::enabled)
            .mapNotNull { resource ->
                loadPromptFile(
                    resource.path,
                    sourceInfoFactory = { filePath ->
                        resource.sourceInfo.copy(path = filePath.toAbsolutePath().normalize())
                    },
                )
            }.let(::add)
    } else {
        if (includeDefaults && projectTrusted) {
            val projectBaseDir = normalizedCwd.resolve(".pi")
            val projectRoot = projectBaseDir.resolve("prompts")
            add(
                loadPromptDirectory(
                    projectRoot,
                    sourceInfoFactory(
                        root = projectBaseDir,
                        source = "auto",
                        scope = "project",
                    ),
                ),
            )
        }
        if (includeDefaults) {
            val userRoot = normalizedAgentDir.resolve("prompts")
            add(
                loadPromptDirectory(
                    userRoot,
                    sourceInfoFactory(
                        root = normalizedAgentDir,
                        source = "auto",
                        scope = "user",
                    ),
                ),
            )
        }
    }
    promptPaths.forEach { rawPath ->
        val path = resolvePromptTemplatePath(normalizedCwd, rawPath)
        if (!Files.exists(path)) {
            diagnostics +=
                ResourceDiagnostic(
                    ResourceDiagnosticType.ERROR,
                    "Prompt template path does not exist",
                    path,
                )
            return@forEach
        }
        when {
            Files.isDirectory(path) ->
                add(
                    loadPromptDirectory(
                        path,
                        sourceInfoFactory(path, "local", "temporary"),
                    ),
                )

            Files.isRegularFile(path) && path.fileName.toString().endsWith(".md") ->
                loadPromptFile(
                    path,
                    sourceInfoFactory(path.parent, "local", "temporary"),
                )?.let { add(listOf(it)) }

            else ->
                diagnostics +=
                    ResourceDiagnostic(
                        ResourceDiagnosticType.WARNING,
                        "prompt template path is not a markdown file",
                        path,
                    )
        }
    }
    return LoadedPromptTemplates(prompts.values.toList(), diagnostics)
}

internal fun parseCommandArgs(arguments: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    arguments.forEach { character ->
        when {
            quote != null && character == quote -> quote = null
            quote != null -> current.append(character)
            character == '"' || character == '\'' -> quote = character
            character.isWhitespace() -> {
                if (current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
            }

            else -> current.append(character)
        }
    }
    if (current.isNotEmpty()) {
        result += current.toString()
    }
    return result
}

internal fun substituteArgs(
    content: String,
    arguments: List<String>,
): String {
    val allArguments = arguments.joinToString(" ")
    return TEMPLATE_ARGUMENT_PATTERN.replace(content) { match ->
        val defaultTarget = match.groups[1]?.value
        val defaultValue = match.groups[2]?.value
        val sliceStart = match.groups[3]?.value
        val sliceLength = match.groups[4]?.value
        val simple = match.groups[5]?.value
        when {
            defaultTarget != null -> {
                val value =
                    if (defaultTarget == "@" || defaultTarget == "ARGUMENTS") {
                        allArguments
                    } else {
                        arguments.getOrNull(defaultTarget.toInt() - 1).orEmpty()
                    }
                value.ifEmpty { defaultValue.orEmpty() }
            }

            sliceStart != null -> {
                val start = (sliceStart.toInt() - 1).coerceAtLeast(0)
                val values =
                    sliceLength
                        ?.toInt()
                        ?.let { length -> arguments.drop(start).take(length) }
                        ?: arguments.drop(start)
                values.joinToString(" ")
            }

            simple == "@" || simple == "ARGUMENTS" -> allArguments
            simple != null -> arguments.getOrNull(simple.toInt() - 1).orEmpty()
            else -> match.value
        }
    }
}

internal fun expandPromptTemplate(
    text: String,
    templates: List<PromptTemplate>,
): String {
    if (!text.startsWith("/")) {
        return text
    }
    val match = PROMPT_COMMAND_PATTERN.matchEntire(text) ?: return text
    val name = match.groupValues[1]
    val template = templates.firstOrNull { it.name == name } ?: return text
    return substituteArgs(template.content, parseCommandArgs(match.groups[2]?.value.orEmpty()))
}

internal fun expandResourceCommand(
    text: String,
    skills: List<Skill>,
    templates: List<PromptTemplate>,
    onWarning: (String) -> Unit = {},
): String =
    expandPromptTemplate(
        expandSkillCommand(text, skills, onWarning),
        templates,
    )

private fun loadPromptDirectory(
    directory: Path,
    sourceInfoFactory: (Path) -> ResourceSourceInfo,
): List<PromptTemplate> {
    if (!Files.isDirectory(directory)) {
        return emptyList()
    }
    return runCatching {
        Files.list(directory).use { stream ->
            stream
                .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".md") }
                .sorted(compareBy<Path> { it.fileName.toString() })
                .map { path -> loadPromptFile(path, sourceInfoFactory) }
                .filter { it != null }
                .map { requireNotNull(it) }
                .toList()
        }
    }.getOrDefault(emptyList())
}

private fun loadPromptFile(
    filePath: Path,
    sourceInfoFactory: (Path) -> ResourceSourceInfo,
): PromptTemplate? =
    runCatching {
        val parsed = parseFrontmatter(Files.readString(filePath))
        val firstLine = parsed.body.lineSequence().firstOrNull { it.isNotBlank() }
        val description =
            (parsed.values["description"] as? String)
                ?.takeIf(String::isNotEmpty)
                ?: firstLine
                    ?.let { line ->
                        if (line.length > 60) {
                            line.take(60) + "..."
                        } else {
                            line
                        }
                    }.orEmpty()
        PromptTemplate(
            name = filePath.fileName.toString().removeSuffix(".md"),
            description = description,
            argumentHint = (parsed.values["argument-hint"] as? String)?.takeIf(String::isNotEmpty),
            content = parsed.body,
            sourceInfo = sourceInfoFactory(filePath),
            filePath = filePath.toAbsolutePath().normalize(),
        )
    }.getOrNull()

private fun sourceInfoFactory(
    root: Path,
    source: String,
    scope: String,
): (Path) -> ResourceSourceInfo =
    { filePath ->
        ResourceSourceInfo(
            path = filePath.toAbsolutePath().normalize(),
            source = source,
            scope = scope,
            baseDir = root.toAbsolutePath().normalize(),
        )
    }

private fun resolvePromptTemplatePath(
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

private val TEMPLATE_ARGUMENT_PATTERN =
    Regex("""\$\{(\d+|ARGUMENTS|@):-([^}]*)}|\$\{@:(\d+)(?::(\d+))?}|\$(ARGUMENTS|@|\d+)""")

private val PROMPT_COMMAND_PATTERN = Regex("""^/([^\s]+)(?:\s+([\s\S]*))?$""")
