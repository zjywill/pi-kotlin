package works.earendil.pi.codingagent

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelfUpdateTest {
    @Test
    fun `source distribution self update fetches fast forwards and rebuilds`() {
        val root = createSourceRoot()
        val before = "1111111111111111111111111111111111111111"
        val available = "2222222222222222222222222222222222222222"
        val runner = SelfUpdateRecordingRunner(before, available)
        val output = ByteArrayOutputStream()
        val errors = ByteArrayOutputStream()
        val updater =
            SourceDistributionSelfUpdater(
                environment = mapOf("PI_KOTLIN_SOURCE_DIR" to root.toString()),
                commandRunner = runner,
                currentDirectory = root.resolve("nested"),
                codeSource = null,
            )

        val exit = updater.update(false, PrintStream(output), PrintStream(errors))

        assertEquals(0, exit, errors.toString())
        assertTrue(runner.commands.contains(listOf("git", "fetch", "origin")))
        assertTrue(runner.commands.contains(listOf("git", "merge", "--ff-only", "origin/main^{commit}")))
        assertTrue(
            runner.commands.contains(
                listOf(root.resolve("gradlew").toString(), ":pi-coding-agent:installDist"),
            ),
        )
        assertTrue(output.toString().contains("11111111 -> 22222222"))
        assertEquals("", errors.toString())
    }

    @Test
    fun `source distribution skips rebuild when current unless forced`() {
        val root = createSourceRoot()
        val head = "3333333333333333333333333333333333333333"
        val runner = SelfUpdateRecordingRunner(head, head)
        val output = ByteArrayOutputStream()
        val updater =
            SourceDistributionSelfUpdater(
                environment = mapOf("PI_KOTLIN_SOURCE_DIR" to root.toString()),
                commandRunner = runner,
                currentDirectory = root,
                codeSource = null,
            )

        val exit = updater.update(false, PrintStream(output), PrintStream(ByteArrayOutputStream()))

        assertEquals(0, exit)
        assertFalse(runner.commands.any { command -> command.any { it.endsWith("gradlew") } })
        assertTrue(output.toString().contains("already up to date"))
    }

    @Test
    fun `source distribution refuses tracked changes`() {
        val root = createSourceRoot()
        val runner =
            SelfUpdateRecordingRunner(
                head = "4444444444444444444444444444444444444444",
                available = "5555555555555555555555555555555555555555",
                status = " M build.gradle.kts",
            )
        val errors = ByteArrayOutputStream()
        val updater =
            SourceDistributionSelfUpdater(
                environment = mapOf("PI_KOTLIN_SOURCE_DIR" to root.toString()),
                commandRunner = runner,
                currentDirectory = root,
                codeSource = null,
            )

        val exit = updater.update(false, PrintStream(ByteArrayOutputStream()), PrintStream(errors))

        assertEquals(1, exit)
        assertTrue(errors.toString().contains("tracked changes"))
        assertFalse(runner.commands.contains(listOf("git", "fetch", "origin")))
    }

    private fun createSourceRoot(): Path =
        Files.createTempDirectory("pi-kotlin-self-update").also { root ->
            Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"pi-kotlin\"")
            Files.writeString(root.resolve("gradlew"), "#!/usr/bin/env sh")
            Files.createDirectories(root.resolve("pi-coding-agent"))
        }
}

private class SelfUpdateRecordingRunner(
    head: String,
    private val available: String,
    private val status: String = "",
) : PackageCommandRunner {
    private var currentHead = head
    val commands = mutableListOf<List<String>>()

    override fun run(
        command: List<String>,
        cwd: Path?,
        environment: Map<String, String>,
        timeoutSeconds: Long,
    ): String {
        commands += command
        return when {
            command == listOf("git", "status", "--porcelain", "--untracked-files=no") -> status
            command == listOf("git", "rev-parse", "HEAD") -> currentHead
            command == listOf("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}") -> "origin/main"
            command == listOf("git", "fetch", "origin") -> ""
            command == listOf("git", "rev-parse", "origin/main^{commit}") -> available
            command == listOf("git", "merge", "--ff-only", "origin/main^{commit}") -> {
                currentHead = available
                ""
            }

            command.firstOrNull()?.endsWith("gradlew") == true -> ""
            else -> error("Unexpected command: ${command.joinToString(" ")}")
        }
    }
}
