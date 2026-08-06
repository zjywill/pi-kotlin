package works.earendil.pi.codingagent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptResourcesTest {
    @Test
    fun `context discovery loads global then ancestors from root to cwd`() {
        val root = Files.createTempDirectory("pi-kotlin-context")
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val project = Files.createDirectories(root.resolve("workspace").resolve("project"))
        Files.writeString(agentDir.resolve("AGENTS.md"), "global")
        Files.writeString(root.resolve("workspace").resolve("CLAUDE.md"), "workspace")
        Files.writeString(project.resolve("AGENTS.md"), "project")
        Files.writeString(project.resolve("CLAUDE.md"), "lower priority")

        val files = loadProjectContextFiles(project, agentDir)

        assertEquals(listOf("global", "workspace", "project"), files.map { it.content })
        assertEquals(
            listOf(
                agentDir.resolve("AGENTS.md"),
                root.resolve("workspace").resolve("CLAUDE.md"),
                project.resolve("AGENTS.md"),
            ).map { it.toAbsolutePath().normalize() },
            files.map { it.path },
        )
    }

    @Test
    fun `AGENTS override takes precedence within a context directory`() {
        val root = Files.createTempDirectory("pi-kotlin-context-override")
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val project = Files.createDirectories(root.resolve("project"))
        Files.writeString(project.resolve("AGENTS.md"), "base instructions")
        Files.writeString(project.resolve("AGENTS.override.md"), "override instructions")

        val files = loadProjectContextFiles(project, agentDir)

        assertEquals(listOf("override instructions"), files.map(ProjectContextFile::content))
        assertEquals(listOf(project.resolve("AGENTS.override.md")), files.map(ProjectContextFile::path))
    }

    @Test
    fun `prompt resources honor disabling and file based overrides`() {
        val root = Files.createTempDirectory("pi-kotlin-prompt-resources")
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val project = Files.createDirectories(root.resolve("project"))
        Files.writeString(agentDir.resolve("AGENTS.md"), "global instructions")
        Files.writeString(project.resolve("AGENTS.md"), "project instructions")
        Files.writeString(project.resolve("custom.md"), "custom prompt")
        Files.writeString(project.resolve("append.md"), "append prompt")

        val resources =
            loadPromptResources(
                cwd = project,
                agentDir = agentDir,
                systemPromptSource = "custom.md",
                appendPromptSources = listOf("append.md"),
                noContextFiles = true,
            )
        val prompt = buildCodingSystemPrompt(project, emptyList(), resources)

        assertEquals("custom prompt", resources.customPrompt)
        assertEquals(project.resolve("custom.md"), resources.systemPromptSourcePath)
        assertEquals(listOf("append prompt"), resources.appendPrompts)
        assertEquals(listOf(project.resolve("append.md")), resources.appendPromptSourcePaths)
        assertTrue(resources.contextFiles.isEmpty())
        assertTrue(prompt.startsWith("custom prompt\n\nappend prompt"))
        assertFalse(prompt.contains("<project_context>"))
        assertTrue(prompt.endsWith("Current working directory: ${project.toString().replace('\\', '/')}"))
    }

    @Test
    fun `project system prompt requires explicit trust while global prompt is fallback`() {
        val root = Files.createTempDirectory("pi-kotlin-system-discovery")
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val project = Files.createDirectories(root.resolve("project"))
        Files.createDirectories(project.resolve(".pi"))
        Files.writeString(agentDir.resolve("SYSTEM.md"), "global system")
        Files.writeString(project.resolve(".pi").resolve("SYSTEM.md"), "project system")

        val untrusted = loadPromptResources(project, agentDir, projectTrusted = false)
        val trusted = loadPromptResources(project, agentDir, projectTrusted = true)

        assertEquals("global system", untrusted.customPrompt)
        assertEquals(agentDir.resolve("SYSTEM.md"), untrusted.systemPromptSourcePath)
        assertEquals("project system", trusted.customPrompt)
        assertEquals(project.resolve(".pi").resolve("SYSTEM.md"), trusted.systemPromptSourcePath)
    }

    @Test
    fun `literal prompt overrides do not report file sources`() {
        val root = Files.createTempDirectory("pi-kotlin-literal-prompt-sources")
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val project = Files.createDirectories(root.resolve("project"))

        val resources =
            loadPromptResources(
                cwd = project,
                agentDir = agentDir,
                systemPromptSource = "literal system",
                appendPromptSources = listOf("literal append"),
            )

        assertEquals("literal system", resources.customPrompt)
        assertEquals(null, resources.systemPromptSourcePath)
        assertEquals(listOf("literal append"), resources.appendPrompts)
        assertTrue(resources.appendPromptSourcePaths.isEmpty())
    }

    @Test
    fun `context discovery skips directory candidates and uses the next file`() {
        val root = Files.createTempDirectory("pi-kotlin-context-directory")
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val project = Files.createDirectories(root.resolve("project"))
        Files.createDirectories(project.resolve("AGENTS.md"))
        Files.writeString(project.resolve("CLAUDE.md"), "fallback instructions")
        val warnings = mutableListOf<String>()

        val files = loadProjectContextFiles(project, agentDir, warnings::add)

        assertEquals(listOf("fallback instructions"), files.map(ProjectContextFile::content))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `nested linked worktree does not reload the main worktree context file`() {
        val root = Files.createTempDirectory("pi-kotlin-nested-worktree")
        val main = Files.createDirectories(root.resolve("main"))
        val mainGit = Files.createDirectories(main.resolve(".git"))
        val linkedGit = Files.createDirectories(mainGit.resolve("worktrees").resolve("nested"))
        val linked = Files.createDirectories(main.resolve("nested"))
        val cwd = Files.createDirectories(linked.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        Files.writeString(main.resolve("AGENTS.md"), "main instructions")
        Files.writeString(linked.resolve("AGENTS.md"), "linked instructions")
        Files.writeString(linked.resolve(".git"), "gitdir: $linkedGit\n")
        Files.writeString(linkedGit.resolve("commondir"), "../..\n")

        val files = loadProjectContextFiles(cwd, agentDir)

        assertEquals(listOf("linked instructions"), files.map(ProjectContextFile::content))
        assertEquals(listOf(linked.resolve("AGENTS.md")), files.map(ProjectContextFile::path))
    }
}
