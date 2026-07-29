package works.earendil.pi.codingagent

import java.nio.file.Files
import works.earendil.pi.ai.FauxModelDefinition
import works.earendil.pi.ai.FauxProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelScopeTest {
    @Test
    fun `model scopes resolve globs thinking levels and stable deduplication`() {
        val provider =
            FauxProvider(
                definitions =
                    listOf(
                        FauxModelDefinition("faux-1", reasoning = true),
                        FauxModelDefinition("faux-2", reasoning = true),
                    ),
            )

        val resolution =
            resolveModelScope(
                listOf("faux/*:high", "faux-1:low", "missing"),
                provider.getModels(),
            )

        assertEquals(listOf("faux-1", "faux-2"), resolution.scopedModels.map { it.model.id })
        assertTrue(resolution.scopedModels.all { it.thinkingLevel == AgentThinkingLevel.HIGH })
        assertEquals(listOf("missing"), resolution.diagnostics.map { it.pattern })

        val malformed = resolveModelScope(listOf("["), provider.getModels())
        assertTrue(malformed.scopedModels.isEmpty())
        assertEquals(listOf("["), malformed.diagnostics.map { it.pattern })
    }

    @Test
    fun `explicit and project enabled models override global settings`() {
        val root = Files.createTempDirectory("pi-kotlin-model-scope-settings")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        Files.createDirectories(cwd.resolve(".pi"))
        Files.writeString(agentDir.resolve("settings.json"), """{"enabledModels":["faux-1"]}""")
        Files.writeString(cwd.resolve(".pi").resolve("settings.json"), """{"enabledModels":["faux-2:low"]}""")
        val models =
            FauxProvider(
                definitions =
                    listOf(
                        FauxModelDefinition("faux-1"),
                        FauxModelDefinition("faux-2", reasoning = true),
                    ),
            ).getModels()

        val project =
            resolveConfiguredModelScope(
                explicitPatterns = null,
                availableModels = models,
                cwd = cwd,
                agentDir = agentDir,
                projectTrusted = true,
            )
        val explicit =
            resolveConfiguredModelScope(
                explicitPatterns = listOf("faux-1"),
                availableModels = models,
                cwd = cwd,
                agentDir = agentDir,
                projectTrusted = true,
            )

        assertEquals("faux-2", project.scopedModels.single().model.id)
        assertEquals(AgentThinkingLevel.LOW, project.scopedModels.single().thinkingLevel)
        assertEquals("faux-1", explicit.scopedModels.single().model.id)
    }
}
