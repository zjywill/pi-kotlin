package works.earendil.pi.codingagent

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigSelectorTest {
    @Test
    fun `selector keymap keeps space reserved for toggling`() {
        val keys = resourceConfigKeyMap()

        assertEquals("toggle", keys.getBound(" "))
        assertEquals("toggle", keys.getBound("\r"))
        assertEquals("text", keys.getBound("a"))
        assertEquals("close", keys.getBound("\u001b"))
    }

    @Test
    fun `global selector toggles top level resources through settings patterns`() {
        val root = Files.createTempDirectory("pi-kotlin-config-top-level")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val extension = Files.createDirectories(agentDir.resolve("extensions")).resolve("alpha.ts")
        Files.writeString(extension, "export default function() {}")
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)
        val model =
            ResourceConfigModel(
                cwd = cwd,
                agentDir = agentDir,
                settings = settings,
                projectModeAvailable = true,
                initialScope = ConfigWriteScope.GLOBAL,
                resolver = { trusted ->
                    resolvePackageResources(
                        cwd = cwd,
                        agentDir = agentDir,
                        projectTrusted = trusted,
                        homeDir = root.resolve("home"),
                    )
                },
            )
        model.appendQuery("alpha.ts")

        assertTrue(model.visibleItems().single().resource.enabled)
        assertTrue(model.toggleSelected())
        assertEquals(listOf("-extensions/alpha.ts"), settings.global().extensions)
        assertFalse(model.visibleItems().single().resource.enabled)

        assertTrue(model.toggleSelected())
        assertEquals(listOf("+extensions/alpha.ts"), settings.global().extensions)
        assertTrue(model.visibleItems().single().resource.enabled)
    }

    @Test
    fun `project selector cycles inherited package unload load and inherit`() {
        val root = Files.createTempDirectory("pi-kotlin-config-package")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val packageRoot = Files.createDirectories(root.resolve("package"))
        val extension = Files.createDirectories(packageRoot.resolve("extensions")).resolve("index.ts")
        Files.writeString(extension, "export default function() {}")
        Files.writeString(
            packageRoot.resolve("package.json"),
            """{"pi":{"extensions":["extensions/index.ts"]}}""",
        )
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)
        settings.setPackages(SettingsScope.USER, listOf(PackageSourceConfig(packageRoot.toString())))
        val model =
            ResourceConfigModel(
                cwd = cwd,
                agentDir = agentDir,
                settings = settings,
                projectModeAvailable = true,
                initialScope = ConfigWriteScope.PROJECT,
                resolver = { trusted ->
                    resolvePackageResources(
                        cwd = cwd,
                        agentDir = agentDir,
                        projectTrusted = trusted,
                        homeDir = root.resolve("home"),
                    )
                },
            )
        model.appendQuery("index.ts")

        assertEquals(ProjectOverrideState.INHERIT, model.projectOverrideState(model.visibleItems().single()))
        model.toggleSelected()
        assertEquals(ProjectOverrideState.UNLOAD, model.projectOverrideState(model.visibleItems().single()))
        assertEquals(listOf("-extensions/index.ts"), settings.project().packages.single().extensions)

        model.toggleSelected()
        assertEquals(ProjectOverrideState.LOAD, model.projectOverrideState(model.visibleItems().single()))
        assertEquals(listOf("+extensions/index.ts"), settings.project().packages.single().extensions)

        model.toggleSelected()
        assertEquals(ProjectOverrideState.INHERIT, model.projectOverrideState(model.visibleItems().single()))
        assertTrue(settings.project().packages.isEmpty())
    }

    @Test
    fun `selector rendering stays within terminal dimensions`() {
        val root = Files.createTempDirectory("pi-kotlin-config-render")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        Files.createDirectories(agentDir.resolve("skills").resolve("long-resource-name"))
        Files.writeString(
            agentDir.resolve("skills").resolve("long-resource-name").resolve("SKILL.md"),
            "---\nname: long-resource-name\ndescription: test\n---\nbody",
        )
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)
        val model =
            ResourceConfigModel(
                cwd = cwd,
                agentDir = agentDir,
                settings = settings,
                projectModeAvailable = true,
                initialScope = ConfigWriteScope.GLOBAL,
            )

        val lines = model.render(width = 40, height = 10)

        assertEquals(10, lines.size)
        assertTrue(lines.all { it.length <= 40 })
    }

    @Test
    fun `config command opens the project selector after approval`() {
        val root = Files.createTempDirectory("pi-kotlin-config-command")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        var invoked = false
        var local = false
        var trusted = false
        val exit =
            runPackageCommand(
                arguments = listOf("config", "-l", "--approve"),
                cwd = cwd,
                agentDir = agentDir,
                output = PrintStream(ByteArrayOutputStream()),
                errorOutput = PrintStream(ByteArrayOutputStream()),
                configSelector =
                    ResourceConfigSelector { _, _, _, selectedLocal, projectTrusted, _, _ ->
                        invoked = true
                        local = selectedLocal
                        trusted = projectTrusted
                        0
                    },
            )

        assertEquals(0, exit)
        assertTrue(invoked)
        assertTrue(local)
        assertTrue(trusted)
    }
}
