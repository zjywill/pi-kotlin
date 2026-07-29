package works.earendil.pi.codingagent

import java.nio.file.Files
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtensionStartupTest {
    @Test
    fun `user extension decides trust before project extension loads and bootstrap is reused`() {
        assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
        val root = Files.createTempDirectory("pi-kotlin-extension-trust")
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val project = Files.createDirectories(root.resolve("project"))
        val userExtensions = Files.createDirectories(agentDir.resolve("extensions"))
        val projectExtensions = Files.createDirectories(project.resolve(".pi").resolve("extensions"))
        val loadCount = root.resolve("user-load-count.txt")
        val projectLoaded = root.resolve("project-loaded.txt")
        Files.writeString(
            userExtensions.resolve("trust.ts"),
            """
            import { appendFileSync, existsSync } from "node:fs";
            export default function(pi) {
              appendFileSync(${jsString(loadCount.toString())}, "loaded\n");
              pi.on("project_trust", () => ({
                trusted: existsSync(${jsString(projectLoaded.toString())}) ? "no" : "yes",
                remember: true,
              }));
            }
            """.trimIndent(),
        )
        Files.writeString(
            projectExtensions.resolve("project.ts"),
            """
            import { writeFileSync } from "node:fs";
            export default function(pi) {
              writeFileSync(${jsString(projectLoaded.toString())}, "loaded");
              pi.registerCommand("project-command", { handler() {} });
            }
            """.trimIndent(),
        )
        ProjectTrustStore(agentDir).set(project, false)

        val result =
            bootstrapExtensions(
                cwd = project,
                agentDir = agentDir,
                trustOverride = null,
                explicitPaths = emptyList(),
                noExtensions = false,
                mode = ExtensionMode.PRINT,
                flagValues = emptyMap(),
                context = { trusted -> startupContext(project, trusted) },
            )

        result.host.use { host ->
            assertTrue(result.projectTrusted)
            assertEquals(true, ProjectTrustStore(agentDir).get(project))
            assertEquals(listOf("loaded"), Files.readAllLines(loadCount))
            assertTrue(Files.exists(projectLoaded))
            assertEquals("project-command", assertNotNull(host).registrations.commands.single().name)
        }
    }

    @Test
    fun `global project trust defaults apply after stored and extension decisions`() {
        val root = Files.createTempDirectory("pi-kotlin-project-trust-default")
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val project = Files.createDirectories(root.resolve("project"))
        Files.createDirectories(project.resolve(".pi"))
        Files.writeString(project.resolve(".pi").resolve("settings.json"), "{}")
        Files.writeString(agentDir.resolve("settings.json"), """{"defaultProjectTrust":"always"}""")

        assertTrue(resolveProjectTrusted(project, agentDir, override = null))

        Files.writeString(agentDir.resolve("settings.json"), """{"defaultProjectTrust":"never"}""")
        assertFalse(resolveProjectTrusted(project, agentDir, override = null))
    }

    @Test
    fun `bootstrap actions retain factory trust handler and project load order`() {
        assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
        val root = Files.createTempDirectory("pi-kotlin-extension-action-order")
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val project = Files.createDirectories(root.resolve("project"))
        val userExtensions = Files.createDirectories(agentDir.resolve("extensions"))
        val projectExtensions = Files.createDirectories(project.resolve(".pi").resolve("extensions"))
        Files.writeString(
            userExtensions.resolve("trust.ts"),
            """
            export default function(pi) {
              pi.registerProvider("fixture", { name: "factory" });
              pi.on("project_trust", () => {
                pi.registerProvider("fixture", { name: "trust" });
                return { trusted: "yes" };
              });
            }
            """.trimIndent(),
        )
        Files.writeString(
            projectExtensions.resolve("project.ts"),
            """
            export default function(pi) {
              pi.registerProvider("fixture", { name: "project" });
            }
            """.trimIndent(),
        )
        val actions = mutableListOf<ExtensionAction>()

        val result =
            bootstrapExtensions(
                cwd = project,
                agentDir = agentDir,
                trustOverride = null,
                explicitPaths = emptyList(),
                noExtensions = false,
                mode = ExtensionMode.PRINT,
                flagValues = emptyMap(),
                context = { trusted -> startupContext(project, trusted) },
                onBootstrapActions = actions::addAll,
            )

        result.host.use { host ->
            actions += assertNotNull(host).drainStartupActions()
            assertEquals(
                listOf("factory", "trust", "project"),
                actions
                    .filter { it.type == "register_provider" }
                    .map { it.data["config"]?.let { config -> (config as JsonObject).stringValue("name") } },
            )
        }
    }

    @Test
    fun `extension discovered resources retain extension source metadata`() {
        assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
        val root = Files.createTempDirectory("pi-kotlin-extension-resources")
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val skillDir = Files.createDirectories(root.resolve("skills").resolve("discovered"))
        val promptDir = Files.createDirectories(root.resolve("prompts"))
        val themeDir = Files.createDirectories(root.resolve("themes"))
        val packageRoot = Files.createDirectories(root.resolve("package"))
        val packageSkillDir = Files.createDirectories(packageRoot.resolve("skills").resolve("package-skill"))
        val packagePromptDir = Files.createDirectories(packageRoot.resolve("prompts"))
        val packageTheme = Files.createDirectories(packageRoot.resolve("themes")).resolve("package.json")
        Files.writeString(
            skillDir.resolve("SKILL.md"),
            "---\nname: discovered\ndescription: Extension discovered skill\n---\nUse it.",
        )
        Files.writeString(promptDir.resolve("discovered.md"), "Discovered prompt")
        Files.writeString(themeDir.resolve("discovered.json"), "{}")
        Files.writeString(
            packageSkillDir.resolve("SKILL.md"),
            "---\nname: package-skill\ndescription: Package skill\n---\nPackage body.",
        )
        Files.writeString(packagePromptDir.resolve("package-prompt.md"), "Package prompt")
        Files.writeString(packageTheme, """{"name":"package"}""")
        val packageSource =
            ResourceSourceInfo(
                path = packageRoot,
                source = "npm:metadata-pkg",
                scope = "user",
                origin = "package",
                baseDir = packageRoot,
            )
        val packageResources =
            ResolvedPackageResources(
                skills =
                    listOf(
                        ResolvedResource(
                            packageSkillDir,
                            true,
                            packageSource.copy(path = packageSkillDir),
                        ),
                    ),
                prompts =
                    listOf(
                        ResolvedResource(
                            packagePromptDir,
                            true,
                            packageSource.copy(path = packagePromptDir),
                        ),
                    ),
                themes =
                    listOf(
                        ResolvedResource(
                            packageTheme,
                            true,
                            packageSource.copy(path = packageTheme),
                        ),
                    ),
            )
        val extension =
            root.resolve("resources.ts").also { path ->
                Files.writeString(
                    path,
                    """
                    export default function(pi) {
                      pi.on("resources_discover", () => ({
                        skillPaths: ["skills/discovered"],
                        promptPaths: ["prompts"],
                        themePaths: ["themes/discovered.json"],
                      }));
                    }
                    """.trimIndent(),
                )
            }
        val source =
            ExtensionSource(
                extension,
                ResourceSourceInfo(extension, "local", "temporary", "top-level", root),
            )
        val host =
            assertNotNull(
                ExtensionHost.start(
                    sources = listOf(source),
                    agentDir = agentDir,
                    cwd = root,
                    mode = ExtensionMode.PRINT,
                    projectTrusted = true,
                    flagValues = emptyMap(),
                    context = startupContext(root, true),
                ),
            )

        host.use {
            val discovered =
                discoverExtensionResources(
                    host = host,
                    cwd = root,
                    reason = "startup",
                    context = startupContext(root, true),
                    onActions = {},
                )
            val initialResources =
                loadPromptResources(
                    cwd = root,
                    agentDir = agentDir,
                    projectTrusted = true,
                    resolvedPackageResources = packageResources.merge(discovered),
                )
            val rediscovered =
                discoverExtensionResources(
                    host = host,
                    cwd = root,
                    reason = "reload",
                    context = startupContext(root, true),
                    onActions = {},
                )
            val resources =
                loadPromptResources(
                    cwd = root,
                    agentDir = agentDir,
                    projectTrusted = true,
                    resolvedPackageResources = initialResources.packageResources.merge(rediscovered),
                )

            assertEquals(
                "npm:metadata-pkg",
                resources.skills.single { it.name == "package-skill" }.sourceInfo.source,
            )
            assertEquals(
                "user",
                resources.promptTemplates.single { it.name == "package-prompt" }.sourceInfo.scope,
            )
            assertEquals(
                "package",
                resources.packageResources.themes.single { it.path == packageTheme }.sourceInfo.origin,
            )
            assertEquals(
                "extension:resources",
                resources.skills.single { it.name == "discovered" }.sourceInfo.source,
            )
            assertEquals(
                "temporary",
                resources.promptTemplates.single { it.name == "discovered" }.sourceInfo.scope,
            )
            assertEquals(
                "top-level",
                resources.packageResources.themes
                    .single { it.path == themeDir.resolve("discovered.json") }
                    .sourceInfo
                    .origin,
            )
        }
    }

    private fun startupContext(
        cwd: java.nio.file.Path,
        trusted: Boolean,
    ) = extensionContextJson(
        cwd = cwd,
        mode = ExtensionMode.PRINT,
        projectTrusted = trusted,
        model = null,
        thinkingLevel = "off",
        systemPrompt = "",
        activeTools = emptyList(),
        allTools = emptyList(),
        sessionName = null,
        sessionId = null,
        sessionFile = null,
        isIdle = true,
        hasPendingMessages = false,
        flagValues = emptyMap(),
    )

    private fun jsString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun nodeAvailable(): Boolean =
        runCatching {
            val process = ProcessBuilder("node", "--version").start()
            process.waitFor()
            process.exitValue() == 0 &&
                process.inputStream.bufferedReader().readText().trim().removePrefix("v").substringBefore('.').toInt() >= 22
        }.getOrDefault(false)
}
