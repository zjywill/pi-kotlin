package works.earendil.pi.codingagent.tools

import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.agent.AgentTool
import works.earendil.pi.agent.AgentToolResult
import works.earendil.pi.agent.AgentToolUpdateCallback
import works.earendil.pi.ai.TextContent
import works.earendil.pi.codingagent.withPiAgentEnvironment

class BashTool(
    private val cwd: Path,
) : AgentTool {
    override val name = "bash"
    override val label = "bash"
    override val description =
        "Execute a shell command in the working directory. Output keeps the last 2000 lines or 50KB."
    override val parameters =
        objectSchema(
            properties =
                buildJsonObject {
                    put("command", stringSchema("Shell command to execute"))
                    put("timeout", numberSchema("Optional timeout in seconds"))
                },
            required = listOf("command"),
        )

    override suspend fun execute(
        toolCallId: String,
        params: JsonObject,
        onUpdate: AgentToolUpdateCallback?,
    ): AgentToolResult =
        withContext(Dispatchers.IO) {
            require(Files.isDirectory(cwd)) {
                "Working directory does not exist: $cwd\nCannot execute bash commands."
            }
            val command = params.requireString("command")
            val timeout = params.optionalInt("timeout")
            require(timeout == null || timeout > 0) {
                "Invalid timeout: must be a finite number of seconds"
            }
            val shell =
                if (System.getProperty("os.name").lowercase().contains("win")) {
                    listOf("cmd.exe", "/c", command)
                } else {
                    listOf(System.getenv("SHELL") ?: "/bin/zsh", "-lc", command)
                }
            val process =
                ProcessBuilder(shell)
                    .directory(cwd.toFile())
                    .withPiAgentEnvironment()
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .start()
            coroutineScope {
                val stdout = async(Dispatchers.IO) { process.inputStream.readBytes() }
                val stderr = async(Dispatchers.IO) { process.errorStream.readBytes() }
                val completed =
                    if (timeout == null) {
                        process.waitFor()
                        true
                    } else {
                        process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
                    }
                if (!completed) {
                    process.destroyForcibly()
                    val output =
                        (stdout.await() + stderr.await()).toString(StandardCharsets.UTF_8)
                    error("${output.takeIf(String::isNotBlank)?.plus("\n\n").orEmpty()}Command timed out after $timeout seconds")
                }
                val output =
                    (stdout.await() + stderr.await()).toString(StandardCharsets.UTF_8)
                val truncated = truncateTail(output)
                val text = truncated.content.ifEmpty { "(no output)" }
                if (process.exitValue() != 0) {
                    error("$text\n\nCommand exited with code ${process.exitValue()}")
                }
                AgentToolResult(
                    content = listOf(TextContent(text)),
                    details =
                        if (truncated.truncated) {
                            buildJsonObject { put("truncation", truncated.toJson()) }
                        } else {
                            JsonObject(emptyMap())
                        },
                )
            }
        }
}

class LsTool(
    private val cwd: Path,
) : AgentTool {
    override val name = "ls"
    override val label = "ls"
    override val description = "List directory contents, sorted alphabetically, with '/' on directories."
    override val parameters =
        objectSchema(
            buildJsonObject {
                put("path", stringSchema("Directory to list"))
                put("limit", numberSchema("Maximum entries, default 500"))
            },
        )

    override suspend fun execute(
        toolCallId: String,
        params: JsonObject,
        onUpdate: AgentToolUpdateCallback?,
    ): AgentToolResult {
        val path = resolvePath(cwd, params.optionalString("path") ?: ".")
        require(Files.exists(path)) { "Path not found: $path" }
        require(Files.isDirectory(path)) { "Not a directory: $path" }
        val limit = params.optionalInt("limit") ?: 500
        val entries =
            Files
                .list(path)
                .use { stream ->
                    stream
                        .sorted(compareBy(String.CASE_INSENSITIVE_ORDER) { it.fileName.toString() })
                        .limit(limit.toLong())
                        .map { child -> child.fileName.toString() + if (Files.isDirectory(child)) "/" else "" }
                        .toList()
                }
        return AgentToolResult(
            listOf(TextContent(entries.joinToString("\n").ifEmpty { "(empty directory)" })),
        )
    }
}

class FindTool(
    private val cwd: Path,
) : AgentTool {
    override val name = "find"
    override val label = "find"
    override val description = "Search for files by glob pattern. Skips .git and node_modules."
    override val parameters =
        objectSchema(
            properties =
                buildJsonObject {
                    put("pattern", stringSchema("Glob pattern"))
                    put("path", stringSchema("Directory to search"))
                    put("limit", numberSchema("Maximum results, default 1000"))
                },
            required = listOf("pattern"),
        )

    override suspend fun execute(
        toolCallId: String,
        params: JsonObject,
        onUpdate: AgentToolUpdateCallback?,
    ): AgentToolResult {
        val root = resolvePath(cwd, params.optionalString("path") ?: ".")
        require(Files.exists(root)) { "Path not found: $root" }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:${params.requireString("pattern")}")
        val limit = params.optionalInt("limit") ?: 1000
        val results = mutableListOf<String>()
        Files.walk(root).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext() && results.size < limit) {
                val path = iterator.next()
                if (path == root || shouldSkip(path, root) || Files.isDirectory(path)) {
                    continue
                }
                val relative = relativizeFindResultPath(path, root)
                if (matcher.matches(relative) || matcher.matches(relative.fileName)) {
                    results += relative.toString().replace('\\', '/')
                }
            }
        }
        val output = results.joinToString("\n").ifEmpty { "No files found matching pattern" }
        return AgentToolResult(listOf(TextContent(truncateHead(output, maxLines = Int.MAX_VALUE).content)))
    }
}

internal fun relativizeFindResultPath(
    resultPath: Path,
    searchRoot: Path,
): Path =
    if (resultPath.isAbsolute) {
        searchRoot.relativize(resultPath)
    } else {
        resultPath
    }

class GrepTool(
    private val cwd: Path,
) : AgentTool {
    override val name = "grep"
    override val label = "grep"
    override val description = "Search file contents with a regex or literal pattern."
    override val parameters =
        objectSchema(
            properties =
                buildJsonObject {
                    put("pattern", stringSchema("Regex or literal pattern"))
                    put("path", stringSchema("File or directory to search"))
                    put("glob", stringSchema("Optional glob filter"))
                    put("ignoreCase", booleanSchema("Case-insensitive search"))
                    put("literal", booleanSchema("Treat pattern as literal"))
                    put("context", numberSchema("Context lines"))
                    put("limit", numberSchema("Maximum matches, default 100"))
                },
            required = listOf("pattern"),
        )

    override suspend fun execute(
        toolCallId: String,
        params: JsonObject,
        onUpdate: AgentToolUpdateCallback?,
    ): AgentToolResult {
        val root = resolvePath(cwd, params.optionalString("path") ?: ".")
        require(Files.exists(root)) { "Path not found: $root" }
        val patternText =
            if (params.optionalBoolean("literal") == true) {
                Regex.escape(params.requireString("pattern"))
            } else {
                params.requireString("pattern")
            }
        val regex =
            Regex(
                patternText,
                if (params.optionalBoolean("ignoreCase") == true) {
                    setOf(RegexOption.IGNORE_CASE)
                } else {
                    emptySet()
                },
            )
        val glob =
            params.optionalString("glob")?.let {
                FileSystems.getDefault().getPathMatcher("glob:$it")
            }
        val limit = params.optionalInt("limit") ?: 100
        val files =
            if (Files.isDirectory(root)) {
                Files.walk(root).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) && !shouldSkip(it, root) }
                        .toList()
                }
            } else {
                listOf(root)
            }
        val matches = mutableListOf<String>()
        for (file in files) {
            val relative = if (Files.isDirectory(root)) root.relativize(file) else file.fileName
            if (glob != null && !glob.matches(relative) && !glob.matches(relative.fileName)) {
                continue
            }
            val lines = runCatching { Files.readAllLines(file, StandardCharsets.UTF_8) }.getOrNull() ?: continue
            for ((index, line) in lines.withIndex()) {
                if (regex.containsMatchIn(line)) {
                    val display = line.take(500)
                    matches += "${relative.toString().replace('\\', '/')}:${index + 1}:$display"
                    if (matches.size >= limit) {
                        break
                    }
                }
            }
            if (matches.size >= limit) {
                break
            }
        }
        val output = matches.joinToString("\n").ifEmpty { "No matches found" }
        return AgentToolResult(listOf(TextContent(truncateHead(output).content)))
    }
}

fun createCodingTools(cwd: Path): List<AgentTool> =
    listOf(
        ReadTool(cwd),
        BashTool(cwd),
        EditTool(cwd),
        WriteTool(cwd),
        GrepTool(cwd),
        FindTool(cwd),
        LsTool(cwd),
    )

private fun shouldSkip(
    path: Path,
    root: Path,
): Boolean {
    val relative = root.relativize(path)
    return relative.any { part -> part.name == ".git" || part.name == "node_modules" }
}
