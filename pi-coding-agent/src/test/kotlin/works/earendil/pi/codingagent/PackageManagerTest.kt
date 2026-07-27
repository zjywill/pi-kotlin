package works.earendil.pi.codingagent

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageManagerTest {
    @Test
    fun `settings package updates preserve unknown fields and object filters`() {
        val root = Files.createTempDirectory("pi-kotlin-settings")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        Files.writeString(
            agentDir.resolve("settings.json"),
            """
            {
              "theme": "custom",
              "packages": [
                {
                  "source": "../package",
                  "autoload": false,
                  "skills": ["+skills/review"]
                }
              ]
            }
            """.trimIndent(),
        )
        val store = SettingsStore(cwd, agentDir, projectTrusted = true)
        val configured = store.global().packages.single()

        assertEquals("../package", configured.source)
        assertEquals(false, configured.autoload)
        assertEquals(listOf("+skills/review"), configured.skills)
        store.setPackages(SettingsScope.USER, listOf(configured.withSource("../package-v2")))

        val written = Json.parseToJsonElement(Files.readString(agentDir.resolve("settings.json"))).jsonObject
        assertEquals("custom", written["theme"]?.jsonPrimitive?.content)
        val packageJson = written["packages"]?.jsonArray?.single()?.jsonObject
        assertEquals("../package-v2", packageJson?.get("source")?.jsonPrimitive?.content)
        assertEquals(false, packageJson?.get("autoload")?.jsonPrimitive?.content?.toBoolean())
        assertEquals(
            "+skills/review",
            packageJson?.get("skills")?.jsonArray?.single()?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `resolver loads package manifests filters and project precedence`() {
        val root = Files.createTempDirectory("pi-kotlin-package-resolve")
        val home = Files.createDirectories(root.resolve("home"))
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val userPackage = createResourcePackage(root.resolve("user-package"), "user")
        val projectPackage = createResourcePackage(root.resolve("project-package"), "project")
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)
        settings.setPackages(
            SettingsScope.USER,
            listOf(
                PackageSourceConfig(
                    source = userPackage.toString(),
                    extensions = emptyList(),
                    skills = listOf("skills/**"),
                    objectForm = true,
                ),
            ),
        )
        settings.setPackages(
            SettingsScope.PROJECT,
            listOf(PackageSourceConfig(projectPackage.toString())),
        )

        val resolved =
            PackageManager(
                cwd = cwd,
                agentDir = agentDir,
                settings = settings,
                projectTrusted = true,
                homeDir = home,
            ).resolve()

        assertTrue(resolved.extensions.any { it.sourceInfo.scope == "project" && it.enabled })
        assertTrue(resolved.extensions.any { it.sourceInfo.scope == "user" && !it.enabled })
        assertTrue(resolved.skills.any { it.sourceInfo.scope == "project" && it.enabled })
        assertTrue(resolved.skills.any { it.sourceInfo.scope == "user" && it.enabled })
        assertTrue(resolved.prompts.any { it.sourceInfo.origin == "package" })
    }

    @Test
    fun `project package shadows same user package identity`() {
        val root = Files.createTempDirectory("pi-kotlin-package-dedupe")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val pkg = createResourcePackage(root.resolve("shared"), "shared")
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)
        settings.setPackages(SettingsScope.USER, listOf(PackageSourceConfig(pkg.toString())))
        settings.setPackages(SettingsScope.PROJECT, listOf(PackageSourceConfig(pkg.toString())))

        val resolved =
            PackageManager(
                cwd,
                agentDir,
                settings,
                projectTrusted = true,
                homeDir = root.resolve("home"),
            ).resolve()

        assertTrue(resolved.skills.isNotEmpty())
        assertTrue(resolved.skills.all { it.sourceInfo.scope == "project" })
    }

    @Test
    fun `package sourced skills and prompts enter prompt resources`() {
        val root = Files.createTempDirectory("pi-kotlin-package-runtime")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val pkg = createResourcePackage(root.resolve("runtime-package"), "runtime")
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)
        settings.setPackages(SettingsScope.USER, listOf(PackageSourceConfig(pkg.toString())))

        val resources =
            loadPromptResources(
                cwd = cwd,
                agentDir = agentDir,
                projectTrusted = true,
                homeDir = root.resolve("home"),
            )

        val skill = resources.skills.single { it.name == "runtime-skill" }
        val prompt = resources.promptTemplates.single { it.name == "runtime-prompt" }
        assertEquals("package", skill.sourceInfo.origin)
        assertEquals("package", prompt.sourceInfo.origin)
        assertEquals(pkg, skill.sourceInfo.baseDir)
        assertEquals(pkg, prompt.sourceInfo.baseDir)
    }

    @Test
    fun `local package settings normalize against scope and remove equivalent paths`() {
        val root = Files.createTempDirectory("pi-kotlin-package-settings")
        val cwd = Files.createDirectories(root.resolve("workspace").resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val pkg = Files.createDirectories(root.resolve("packages").resolve("local"))
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)
        val manager = PackageManager(cwd, agentDir, settings, projectTrusted = true)

        assertTrue(manager.addSourceToSettings(pkg.toString(), PackageScope.USER))
        val stored = settings.global().packages.single().source
        assertFalse(Path.of(stored).isAbsolute)
        assertEquals(pkg, manager.getInstalledPath(pkg.toString(), PackageScope.USER))
        assertTrue(manager.removeSourceFromSettings(pkg.toString(), PackageScope.USER))
        assertTrue(settings.global().packages.isEmpty())
    }

    @Test
    fun `updating a configured git ref preserves package filters`() {
        val root = Files.createTempDirectory("pi-kotlin-package-ref")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)
        settings.setPackages(
            SettingsScope.USER,
            listOf(
                PackageSourceConfig(
                    source = "git:github.com/user/repo@v1",
                    autoload = false,
                    skills = listOf("+skills/review"),
                    objectForm = true,
                ),
            ),
        )
        val manager = PackageManager(cwd, agentDir, settings, projectTrusted = true)

        assertTrue(manager.addSourceToSettings("git:github.com/user/repo@v2", PackageScope.USER))

        val updated = settings.global().packages.single()
        assertEquals("git:github.com/user/repo@v2", updated.source)
        assertEquals(false, updated.autoload)
        assertEquals(listOf("+skills/review"), updated.skills)
        assertTrue(updated.objectForm)
    }

    @Test
    fun `npm install uses configured argv and managed package root`() {
        val root = Files.createTempDirectory("pi-kotlin-package-npm")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        Files.writeString(agentDir.resolve("settings.json"), """{"npmCommand":["pnpm"]}""")
        val runner = RecordingPackageRunner()
        val manager =
            PackageManager(
                cwd = cwd,
                agentDir = agentDir,
                settings = SettingsStore(cwd, agentDir, projectTrusted = true),
                projectTrusted = true,
                commandRunner = runner,
            )

        manager.installAndPersist("npm:@scope/pkg")

        assertEquals(
            listOf(
                "pnpm",
                "install",
                "@scope/pkg",
                "--prefix",
                agentDir.resolve("npm").toString(),
                "--config.auto-install-peers=false",
                "--config.strict-peer-dependencies=false",
                "--config.strict-dep-builds=false",
            ),
            runner.commands.single().command,
        )
        assertEquals("npm:@scope/pkg", manager.listConfiguredPackages().single().source)
        assertNull(manager.getInstalledPath("npm:@scope/pkg", PackageScope.USER))
    }

    @Test
    fun `git source parsing normalizes documented URL forms and rejects dot paths`() {
        val root = Files.createTempDirectory("pi-kotlin-package-git")
        val manager =
            PackageManager(
                root,
                root.resolve("agent"),
                SettingsStore(root, root.resolve("agent"), projectTrusted = true),
                projectTrusted = true,
            )

        val https = manager.parsePackageSource("https://github.com/user/repo.git@v2")
        val ssh = manager.parsePackageSource("git:git@github.com:user/repo@main")
        val local = manager.parsePackageSource("./github.com/user/repo")

        assertEquals("github.com", (https as ParsedPackageSource.Git).host)
        assertEquals("user/repo", https.path)
        assertEquals("v2", https.ref)
        assertEquals("github.com", (ssh as ParsedPackageSource.Git).host)
        assertEquals("main", ssh.ref)
        assertTrue(local is ParsedPackageSource.Local)
    }

    @Test
    fun `package CLI installs lists and removes local sources`() {
        val root = Files.createTempDirectory("pi-kotlin-package-cli")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val pkg = createResourcePackage(root.resolve("cli-package"), "cli")

        val install = runPackageCommand(listOf("install", pkg.toString()), cwd, agentDir, sink(), sink())
        val listOutput = ByteArrayOutputStream()
        val listed =
            runPackageCommand(
                listOf("list"),
                cwd,
                agentDir,
                PrintStream(listOutput),
                sink(),
            )
        val removed = runPackageCommand(listOf("remove", pkg.toString()), cwd, agentDir, sink(), sink())

        assertEquals(0, install)
        assertEquals(0, listed)
        assertTrue(listOutput.toString().contains("User packages:"))
        assertTrue(listOutput.toString().contains(pkg.fileName.toString()))
        assertEquals(0, removed)
        assertTrue(SettingsStore(cwd, agentDir, projectTrusted = true).global().packages.isEmpty())
    }

    @Test
    fun `package CLI rejects unknown and incomplete options`() {
        val root = Files.createTempDirectory("pi-kotlin-package-cli-errors")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val unknownError = ByteArrayOutputStream()
        val missingError = ByteArrayOutputStream()

        val unknown =
            runPackageCommand(
                listOf("install", "--unknown", "pkg"),
                cwd,
                agentDir,
                sink(),
                PrintStream(unknownError),
            )
        val missing =
            runPackageCommand(
                listOf("update", "--extension"),
                cwd,
                agentDir,
                sink(),
                PrintStream(missingError),
            )

        assertEquals(1, unknown)
        assertTrue(unknownError.toString().contains("Unknown option --unknown"))
        assertEquals(1, missing)
        assertTrue(missingError.toString().contains("Missing value for --extension"))
    }

    private fun createResourcePackage(
        root: Path,
        prefix: String,
    ): Path {
        Files.createDirectories(root.resolve("extensions"))
        Files.createDirectories(root.resolve("skills").resolve("$prefix-skill"))
        Files.createDirectories(root.resolve("prompts"))
        Files.createDirectories(root.resolve("themes"))
        Files.writeString(root.resolve("extensions").resolve("index.ts"), "export default function() {}")
        Files.writeString(
            root.resolve("skills").resolve("$prefix-skill").resolve("SKILL.md"),
            "---\nname: $prefix-skill\ndescription: $prefix skill\n---\n$prefix body",
        )
        Files.writeString(root.resolve("prompts").resolve("$prefix-prompt.md"), "$prefix prompt")
        Files.writeString(root.resolve("themes").resolve("$prefix.json"), """{"name":"$prefix"}""")
        Files.writeString(
            root.resolve("package.json"),
            """
            {
              "name": "$prefix-package",
              "version": "1.0.0",
              "pi": {
                "extensions": ["extensions/index.ts"],
                "skills": ["skills"],
                "prompts": ["prompts/*.md"],
                "themes": ["themes/*.json"]
              }
            }
            """.trimIndent(),
        )
        return root
    }

    private fun sink(): PrintStream = PrintStream(ByteArrayOutputStream())
}

private data class RecordedPackageCommand(
    val command: List<String>,
    val cwd: Path?,
)

private class RecordingPackageRunner : PackageCommandRunner {
    val commands = mutableListOf<RecordedPackageCommand>()

    override fun run(
        command: List<String>,
        cwd: Path?,
        environment: Map<String, String>,
        timeoutSeconds: Long,
    ): String {
        commands += RecordedPackageCommand(command, cwd)
        return ""
    }
}
