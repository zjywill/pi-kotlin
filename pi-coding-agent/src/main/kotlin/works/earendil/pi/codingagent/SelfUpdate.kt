package works.earendil.pi.codingagent

import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

internal fun interface SelfUpdater {
    fun update(
        force: Boolean,
        output: PrintStream,
        errorOutput: PrintStream,
    ): Int
}

internal class SourceDistributionSelfUpdater(
    private val environment: Map<String, String> = System.getenv(),
    private val commandRunner: PackageCommandRunner = SelfUpdateProcessRunner,
    private val currentDirectory: Path = Path.of("").toAbsolutePath().normalize(),
    private val codeSource: Path? = mainCodeSourcePath(),
) : SelfUpdater {
    override fun update(
        force: Boolean,
        output: PrintStream,
        errorOutput: PrintStream,
    ): Int {
        val sourceRoot =
            locateSourceRoot(
                environment["PI_KOTLIN_SOURCE_DIR"]?.let(Path::of),
                System.getProperty("pi.project.root")?.let(Path::of),
                codeSource,
                currentDirectory,
            )
        if (sourceRoot == null) {
            errorOutput.println("Error: pi cannot locate a Kotlin source checkout for self-update.")
            errorOutput.println(
                "Set PI_KOTLIN_SOURCE_DIR to the pi-kotlin checkout, or update the installed application package.",
            )
            return 1
        }

        return runCatching {
            val status =
                commandRunner.run(
                    listOf("git", "status", "--porcelain", "--untracked-files=no"),
                    cwd = sourceRoot,
                    environment = environment,
                    timeoutSeconds = SELF_UPDATE_NETWORK_TIMEOUT_SECONDS,
                )
            check(status.isBlank()) {
                "Refusing to self-update a source checkout with tracked changes: $sourceRoot"
            }

            val before =
                commandRunner
                    .run(
                        listOf("git", "rev-parse", "HEAD"),
                        cwd = sourceRoot,
                        environment = environment,
                        timeoutSeconds = SELF_UPDATE_NETWORK_TIMEOUT_SECONDS,
                    ).trim()
            val upstream =
                runCatching {
                    commandRunner
                        .run(
                            listOf("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"),
                            cwd = sourceRoot,
                            environment = environment,
                            timeoutSeconds = SELF_UPDATE_NETWORK_TIMEOUT_SECONDS,
                        ).trim()
                        .takeIf(String::isNotBlank)
                }.getOrNull() ?: "origin/main"

            commandRunner.run(
                listOf("git", "fetch", upstream.substringBefore('/')),
                cwd = sourceRoot,
                environment = environment + ("GIT_TERMINAL_PROMPT" to "0"),
                timeoutSeconds = SELF_UPDATE_NETWORK_TIMEOUT_SECONDS,
            )
            val available =
                commandRunner
                    .run(
                        listOf("git", "rev-parse", "$upstream^{commit}"),
                        cwd = sourceRoot,
                        environment = environment,
                        timeoutSeconds = SELF_UPDATE_NETWORK_TIMEOUT_SECONDS,
                    ).trim()
            if (before == available && !force) {
                output.println("pi Kotlin is already up to date (${before.shortCommit()}).")
                return 0
            }

            if (before != available) {
                output.println("Updating pi Kotlin ${before.shortCommit()} -> ${available.shortCommit()}...")
                commandRunner.run(
                    listOf("git", "merge", "--ff-only", "$upstream^{commit}"),
                    cwd = sourceRoot,
                    environment = environment,
                    timeoutSeconds = SELF_UPDATE_NETWORK_TIMEOUT_SECONDS,
                )
            } else {
                output.println("Reinstalling pi Kotlin ${before.shortCommit()}...")
            }

            val gradleWrapper =
                sourceRoot.resolve(
                    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                        "gradlew.bat"
                    } else {
                        "gradlew"
                    },
                )
            check(Files.isRegularFile(gradleWrapper)) {
                "Gradle wrapper is missing from $sourceRoot"
            }
            commandRunner.run(
                listOf(gradleWrapper.toString(), ":pi-coding-agent:installDist"),
                cwd = sourceRoot,
                environment = environment,
                timeoutSeconds = SELF_UPDATE_BUILD_TIMEOUT_SECONDS,
            )
            val after =
                commandRunner
                    .run(
                        listOf("git", "rev-parse", "HEAD"),
                        cwd = sourceRoot,
                        environment = environment,
                        timeoutSeconds = SELF_UPDATE_NETWORK_TIMEOUT_SECONDS,
                    ).trim()
            output.println(
                if (before == after) {
                    "Reinstalled pi Kotlin ${after.shortCommit()}."
                } else {
                    "Updated pi Kotlin ${before.shortCommit()} -> ${after.shortCommit()}."
                },
            )
            0
        }.getOrElse { failure ->
            errorOutput.println("Error: ${failure.message ?: "Self-update failed"}")
            1
        }
    }
}

internal fun locateSourceRoot(vararg candidates: Path?): Path? =
    candidates
        .asSequence()
        .filterNotNull()
        .mapNotNull(::findSourceRoot)
        .firstOrNull()

private fun findSourceRoot(candidate: Path): Path? {
    var current =
        candidate
            .toAbsolutePath()
            .normalize()
            .let { path -> if (Files.isDirectory(path)) path else path.parent }
    while (current != null) {
        if (
            Files.isRegularFile(current.resolve("settings.gradle.kts")) &&
            (
                Files.isRegularFile(current.resolve("gradlew")) ||
                    Files.isRegularFile(current.resolve("gradlew.bat"))
            ) &&
            Files.isDirectory(current.resolve("pi-coding-agent"))
        ) {
            return current
        }
        current = current.parent
    }
    return null
}

private fun mainCodeSourcePath(): Path? =
    runCatching {
        Path.of(
            URI(
                Class
                    .forName("works.earendil.pi.codingagent.MainKt")
                    .protectionDomain
                    .codeSource
                    .location
                    .toString(),
            ),
        )
    }.getOrNull()

private fun String.shortCommit(): String = take(8)

private object SelfUpdateProcessRunner : PackageCommandRunner {
    override fun run(
        command: List<String>,
        cwd: Path?,
        environment: Map<String, String>,
        timeoutSeconds: Long,
    ): String = runPackageProcess(command, cwd, environment, timeoutSeconds)
}

private const val SELF_UPDATE_NETWORK_TIMEOUT_SECONDS = 30L
private const val SELF_UPDATE_BUILD_TIMEOUT_SECONDS = 600L
