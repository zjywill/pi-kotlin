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
import kotlin.test.assertFailsWith
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
    fun `failed git clone removes partial checkout and empty parents`() {
        val root = Files.createTempDirectory("pi-kotlin-package-git-clone-cleanup")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        var target: Path? = null
        val runner =
            RecordingPackageRunner { command, _ ->
                if (command.take(2) == listOf("git", "clone")) {
                    Files.createDirectories(requireNotNull(target))
                    error("simulated git clone failure")
                }
            }
        val manager =
            PackageManager(
                cwd = cwd,
                agentDir = agentDir,
                settings = SettingsStore(cwd, agentDir, projectTrusted = true),
                projectTrusted = true,
                commandRunner = runner,
            )
        target = manager.plannedInstallPath("git:github.com/user/repo", PackageScope.USER)

        val error =
            assertFailsWith<IllegalStateException> {
                manager.installAndPersist("git:github.com/user/repo")
            }

        assertTrue(error.message.orEmpty().contains("simulated git clone failure"))
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(agentDir.resolve("git").resolve("github.com")))
    }

    @Test
    fun `failed git dependency install removes newly cloned checkout`() {
        val root = Files.createTempDirectory("pi-kotlin-package-git-dependency-cleanup")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        var target: Path? = null
        val runner =
            RecordingPackageRunner { command, _ ->
                when {
                    command.take(2) == listOf("git", "clone") -> {
                        val checkout = requireNotNull(target)
                        Files.createDirectories(checkout.resolve(".git"))
                        Files.writeString(
                            checkout.resolve("package.json"),
                            """{"name":"repo","version":"1.0.0"}""",
                        )
                    }

                    command.firstOrNull() == "npm" ->
                        error("simulated dependency install failure")
                }
            }
        val manager =
            PackageManager(
                cwd = cwd,
                agentDir = agentDir,
                settings = SettingsStore(cwd, agentDir, projectTrusted = true),
                projectTrusted = true,
                commandRunner = runner,
            )
        target = manager.plannedInstallPath("git:github.com/user/repo", PackageScope.USER)

        val error =
            assertFailsWith<IllegalStateException> {
                manager.installAndPersist("git:github.com/user/repo")
            }

        assertTrue(error.message.orEmpty().contains("simulated dependency install failure"))
        assertFalse(Files.exists(target))
        assertFalse(Files.exists(agentDir.resolve("git").resolve("github.com")))
    }

    @Test
    fun `existing git checkout fetches its upstream and resets only when head changed`() {
        val root = Files.createTempDirectory("pi-kotlin-package-git-reconcile")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val target =
            agentDir
                .resolve("git")
                .resolve("github.com")
                .resolve("user")
                .resolve("repo")
        Files.createDirectories(target.resolve(".git"))
        Files.writeString(target.resolve("package.json"), """{"name":"repo","version":"1.0.0"}""")
        val oldHead = "1111111111111111111111111111111111111111"
        val newHead = "2222222222222222222222222222222222222222"
        val runner =
            RecordingPackageRunner(output = { command, _ ->
                when {
                    command == listOf("git", "rev-parse", "--abbrev-ref", "@{upstream}") -> "origin/main"
                    command.take(2) == listOf("git", "fetch") -> ""
                    command == listOf("git", "rev-parse", "HEAD") -> oldHead
                    command == listOf("git", "rev-parse", "@{upstream}^{commit}") -> newHead
                    command == listOf("git", "reset", "--hard", "@{upstream}^{commit}") -> ""
                    command == listOf("git", "clean", "-fdx") -> ""
                    command == listOf("npm", "install", "--omit=dev") -> ""
                    else -> error("Unexpected command: ${command.joinToString(" ")}")
                }
            })
        val manager =
            PackageManager(
                cwd = cwd,
                agentDir = agentDir,
                settings = SettingsStore(cwd, agentDir, projectTrusted = true),
                projectTrusted = true,
                commandRunner = runner,
            )

        manager.installAndPersist("git:github.com/user/repo")

        assertTrue(
            runner.commands.any { recorded ->
                recorded.command ==
                    listOf(
                    "git",
                    "fetch",
                    "--prune",
                    "--no-tags",
                    "origin",
                    "+refs/heads/main:refs/remotes/origin/main",
                )
            },
        )
        assertTrue(runner.commands.any { it.command == listOf("git", "reset", "--hard", "@{upstream}^{commit}") })
        assertTrue(runner.commands.any { it.command == listOf("git", "clean", "-fdx") })
        assertTrue(runner.commands.any { it.command == listOf("npm", "install", "--omit=dev") })
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
        assertEquals(
            """
            Unknown option --unknown for "install".
            Use "pi --help" or "pi install <source> [-l] [--approve|--no-approve]".
            """.trimIndent(),
            unknownError.toString().trim(),
        )
        assertEquals(1, missing)
        assertTrue(missingError.toString().contains("Missing value for --extension"))
    }

    @Test
    fun `package list ignores one positional argument like upstream and rejects the second`() {
        val root = Files.createTempDirectory("pi-kotlin-package-list-positionals")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val output = ByteArrayOutputStream()
        val singleError = ByteArrayOutputStream()
        val multipleError = ByteArrayOutputStream()

        val single =
            runPackageCommand(
                arguments = listOf("list", "ignored"),
                cwd = cwd,
                agentDir = agentDir,
                output = PrintStream(output),
                errorOutput = PrintStream(singleError),
            )
        val multiple =
            runPackageCommand(
                arguments = listOf("list", "ignored", "extra"),
                cwd = cwd,
                agentDir = agentDir,
                output = sink(),
                errorOutput = PrintStream(multipleError),
            )

        assertEquals(0, single)
        assertEquals("No packages installed.", output.toString().trim())
        assertEquals("", singleError.toString())
        assertEquals(1, multiple)
        assertEquals(
            """
            Unexpected argument extra.
            Usage: pi list [--approve|--no-approve]
            """.trimIndent(),
            multipleError.toString().trim(),
        )
    }

    @Test
    fun `package CLI routes self targets without updating extensions`() {
        val root = Files.createTempDirectory("pi-kotlin-package-self-update")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        Files.writeString(
            agentDir.resolve("settings.json"),
            """{"packages":["npm:must-not-update"]}""",
        )
        var force: Boolean? = null
        val output = ByteArrayOutputStream()
        val exit =
            runPackageCommand(
                arguments = listOf("update", "pi", "--force"),
                cwd = cwd,
                agentDir = agentDir,
                output = PrintStream(output),
                errorOutput = sink(),
                selfUpdater =
                    SelfUpdater { requestedForce, _, _ ->
                        force = requestedForce
                        0
                    },
            )

        assertEquals(0, exit)
        assertEquals(true, force)
        assertFalse(output.toString().contains("Updated packages"))
    }

    @Test
    fun `user npm packages resolve from legacy global install roots`() {
        val root = Files.createTempDirectory("pi-kotlin-package-legacy-npm")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val globalRoot = Files.createDirectories(root.resolve("global").resolve("node_modules"))
        val installed = Files.createDirectories(globalRoot.resolve("@scope").resolve("pkg"))
        Files.writeString(installed.resolve("package.json"), """{"name":"@scope/pkg","version":"1.0.0"}""")
        val runner =
            RecordingPackageRunner(output = { command, _ ->
                when (command.dropWhile { it != "npm" }.drop(1)) {
                    listOf("root", "-g") -> globalRoot.toString()
                    else -> error("Unexpected command: ${command.joinToString(" ")}")
                }
            })
        val manager =
            PackageManager(
                cwd = cwd,
                agentDir = agentDir,
                settings = SettingsStore(cwd, agentDir, projectTrusted = true),
                projectTrusted = true,
                commandRunner = runner,
            )

        assertEquals(installed, manager.getInstalledPath("npm:@scope/pkg", PackageScope.USER))
        assertEquals(listOf("npm", "root", "-g"), runner.commands.single().command)
    }

    @Test
    fun `pnpm legacy lookup reads the global package list path`() {
        val root = Files.createTempDirectory("pi-kotlin-package-legacy-pnpm")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        Files.writeString(agentDir.resolve("settings.json"), """{"npmCommand":["pnpm"]}""")
        val installed = Files.createDirectories(root.resolve("pnpm").resolve("global").resolve("pkg"))
        Files.writeString(installed.resolve("package.json"), """{"name":"pkg","version":"1.0.0"}""")
        val runner =
            RecordingPackageRunner(output = { command, _ ->
                if (command == listOf("pnpm", "list", "-g", "--depth", "0", "--json")) {
                    """[{"dependencies":{"pkg":{"path":"$installed"}}}]"""
                } else {
                    error("Unexpected command: ${command.joinToString(" ")}")
                }
            })
        val manager =
            PackageManager(
                cwd = cwd,
                agentDir = agentDir,
                settings = SettingsStore(cwd, agentDir, projectTrusted = true),
                projectTrusted = true,
                commandRunner = runner,
            )

        assertEquals(installed, manager.getInstalledPath("npm:pkg", PackageScope.USER))
    }

    @Test
    fun `available update checks cover unpinned npm and git packages`() {
        val root = Files.createTempDirectory("pi-kotlin-package-updates")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)
        settings.setPackages(
            SettingsScope.PROJECT,
            listOf(
                PackageSourceConfig("npm:example"),
                PackageSourceConfig("git:github.com/example/repo"),
                PackageSourceConfig("npm:pinned@1.0.0"),
            ),
        )
        val npmPath = Files.createDirectories(cwd.resolve(".pi").resolve("npm").resolve("node_modules").resolve("example"))
        Files.writeString(npmPath.resolve("package.json"), """{"name":"example","version":"1.0.0"}""")
        val gitPath = Files.createDirectories(cwd.resolve(".pi").resolve("git").resolve("github.com").resolve("example").resolve("repo"))
        val localHead = "1111111111111111111111111111111111111111"
        val remoteHead = "2222222222222222222222222222222222222222"
        val runner =
            RecordingPackageRunner(output = { command, _ ->
                when {
                    command == listOf("npm", "view", "example", "version", "--json") -> "\"1.2.3\""
                    command == listOf("git", "rev-parse", "HEAD") -> localHead
                    command == listOf("git", "rev-parse", "--abbrev-ref", "@{upstream}") -> "origin/main"
                    command == listOf("git", "ls-remote", "origin", "main") -> "$remoteHead\trefs/heads/main"
                    else -> error("Unexpected command: ${command.joinToString(" ")}")
                }
            })
        val manager =
            PackageManager(
                cwd = cwd,
                agentDir = agentDir,
                settings = settings,
                projectTrusted = true,
                commandRunner = runner,
            )

        assertEquals(
            listOf(
                PackageUpdate("example".let { "npm:$it" }, "example", PackageUpdate.Type.NPM, SettingsScope.PROJECT),
                PackageUpdate(
                    "git:github.com/example/repo",
                    "github.com/example/repo",
                    PackageUpdate.Type.GIT,
                    SettingsScope.PROJECT,
                ),
            ),
            manager.checkForAvailableUpdates(),
        )
        assertTrue(Files.exists(gitPath))
    }

    @Test
    fun `npm updates skip current versions and batch by settings scope`() {
        val root = Files.createTempDirectory("pi-kotlin-package-update-batch")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)
        settings.setPackages(
            SettingsScope.USER,
            listOf(PackageSourceConfig("npm:user-old"), PackageSourceConfig("npm:user-current")),
        )
        settings.setPackages(
            SettingsScope.PROJECT,
            listOf(PackageSourceConfig("npm:project-old")),
        )
        mapOf(
            agentDir.resolve("npm").resolve("node_modules").resolve("user-old") to "1.0.0",
            agentDir.resolve("npm").resolve("node_modules").resolve("user-current") to "2.0.0",
            cwd.resolve(".pi").resolve("npm").resolve("node_modules").resolve("project-old") to "1.0.0",
        ).forEach { (path, version) ->
            Files.createDirectories(path)
            Files.writeString(path.resolve("package.json"), """{"version":"$version"}""")
        }
        val runner =
            RecordingPackageRunner(output = { command, _ ->
                when {
                    "view" in command -> {
                        val name = command[command.indexOf("view") + 1]
                        if (name == "user-current") "\"2.0.0\"" else "\"2.0.0\""
                    }

                    "install" in command -> ""
                    else -> error("Unexpected command: ${command.joinToString(" ")}")
                }
            })
        val manager =
            PackageManager(
                cwd = cwd,
                agentDir = agentDir,
                settings = settings,
                projectTrusted = true,
                commandRunner = runner,
            )

        manager.update()

        val installs = runner.commands.filter { "install" in it.command }
        assertEquals(2, installs.size)
        assertTrue(installs.any { "user-old@latest" in it.command && "user-current@latest" !in it.command })
        assertTrue(installs.any { "project-old@latest" in it.command })
    }

    @Test
    fun `targeted updates suggest missing source prefixes`() {
        val root = Files.createTempDirectory("pi-kotlin-package-update-suggestion")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)
        settings.setPackages(SettingsScope.PROJECT, listOf(PackageSourceConfig("npm:example")))
        val manager = PackageManager(cwd, agentDir, settings, projectTrusted = true)

        val error = assertFailsWith<IllegalStateException> { manager.update("example") }

        assertEquals("No matching package found for example. Did you mean npm:example?", error.message)
    }

    @Test
    fun `malformed package manifest fields do not hide valid resources`() {
        val root = Files.createTempDirectory("pi-kotlin-package-manifest-validation")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val packageRoot = Files.createDirectories(root.resolve("package"))
        val skill = Files.createDirectories(packageRoot.resolve("skills").resolve("bad")).resolve("SKILL.md")
        val prompt = Files.createDirectories(packageRoot.resolve("prompts")).resolve("valid.md")
        Files.writeString(skill, "---\nname: bad\ndescription: bad\n---\n")
        Files.writeString(prompt, "Valid prompt")
        Files.writeString(
            packageRoot.resolve("package.json"),
            """
            {
              "pi": {
                "skills": "./skills",
                "prompts": ["./prompts"]
              }
            }
            """.trimIndent(),
        )
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)
        settings.setPackages(SettingsScope.USER, listOf(PackageSourceConfig(packageRoot.toString())))

        val resources =
            PackageManager(
                cwd = cwd,
                agentDir = agentDir,
                settings = settings,
                projectTrusted = true,
            ).resolve()

        assertFalse(resources.skills.any { it.path == skill })
        assertTrue(resources.prompts.any { it.path == prompt })
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

private class RecordingPackageRunner(
    private val output: (List<String>, Path?) -> String = { _, _ -> "" },
    private val behavior: (List<String>, Path?) -> Unit = { _, _ -> },
) : PackageCommandRunner {
    val commands = mutableListOf<RecordedPackageCommand>()

    override fun run(
        command: List<String>,
        cwd: Path?,
        environment: Map<String, String>,
        timeoutSeconds: Long,
    ): String {
        commands += RecordedPackageCommand(command, cwd)
        behavior(command, cwd)
        return output(command, cwd)
    }
}
