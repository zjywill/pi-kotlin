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
        assertEquals(listOf("append prompt"), resources.appendPrompts)
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

        assertEquals(
            "global system",
            loadPromptResources(project, agentDir, projectTrusted = false).customPrompt,
        )
        assertEquals(
            "project system",
            loadPromptResources(project, agentDir, projectTrusted = true).customPrompt,
        )
    }
}
