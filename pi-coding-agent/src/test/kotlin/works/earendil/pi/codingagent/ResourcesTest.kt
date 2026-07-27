package works.earendil.pi.codingagent

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.FauxResponseStep
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.ai.contentText
import works.earendil.pi.ai.fauxAssistantMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourcesTest {
    @Test
    fun `frontmatter parses YAML values multiline text and normalized body`() {
        val parsed =
            parseFrontmatter(
                "---\r\ndescription: |\r\n  first line\r\n  second line\r\ndisable-model-invocation: true\r\n---\r\n\r\nBody\r\n",
            )

        assertEquals("first line\nsecond line\n", parsed.values["description"])
        assertEquals(true, parsed.values["disable-model-invocation"])
        assertEquals("Body", parsed.body)
    }

    @Test
    fun `prompt template arguments match positional defaults slices and quoting`() {
        assertEquals(
            listOf("first arg", "second", "line\nthree"),
            parseCommandArgs("\"first arg\" second 'line\nthree'"),
        )
        assertEquals(
            "first arg|second line\nthree|fallback|first arg second line\nthree",
            substituteArgs(
                "${'$'}1|\${@:2}|\${4:-fallback}|${'$'}ARGUMENTS",
                listOf("first arg", "second", "line\nthree"),
            ),
        )
        assertEquals(
            "Review auth flow with strict mode",
            expandPromptTemplate(
                "/review \"auth flow\" strict mode",
                listOf(
                    PromptTemplate(
                        name = "review",
                        description = "Review code",
                        content = "Review $1 with \${@:2}",
                        sourceInfo =
                            ResourceSourceInfo(
                                path = Files.createTempFile("review", ".md"),
                                source = "test",
                            ),
                        filePath = Files.createTempFile("review-template", ".md"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `skill discovery prefers project resources and root skill files`() {
        val root = Files.createTempDirectory("pi-kotlin-skills")
        val home = Files.createDirectories(root.resolve("home"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val project = Files.createDirectories(root.resolve("project"))
        Files.createDirectory(project.resolve(".git"))

        val userSkill = Files.createDirectories(agentDir.resolve("skills").resolve("shared"))
        Files.writeString(
            userSkill.resolve("SKILL.md"),
            "---\nname: shared\ndescription: user skill\n---\nUser body",
        )
        val projectSkill = Files.createDirectories(project.resolve(".pi").resolve("skills").resolve("shared"))
        Files.writeString(
            projectSkill.resolve("SKILL.md"),
            "---\nname: shared\ndescription: project skill\n---\nProject body",
        )
        val rootPreferred = Files.createDirectories(agentDir.resolve("skills").resolve("root-preferred"))
        Files.writeString(
            rootPreferred.resolve("SKILL.md"),
            "---\nname: root-preferred\ndescription: root\n---\nRoot",
        )
        val nested = Files.createDirectories(rootPreferred.resolve("nested"))
        Files.writeString(
            nested.resolve("SKILL.md"),
            "---\nname: nested\ndescription: nested\n---\nNested",
        )

        val trusted =
            loadSkills(
                cwd = project,
                agentDir = agentDir,
                projectTrusted = true,
                homeDir = home,
            )
        val untrusted =
            loadSkills(
                cwd = project,
                agentDir = agentDir,
                projectTrusted = false,
                homeDir = home,
            )

        assertEquals("project skill", trusted.skills.single { it.name == "shared" }.description)
        assertEquals("user skill", untrusted.skills.single { it.name == "shared" }.description)
        assertTrue(trusted.diagnostics.any { it.type == ResourceDiagnosticType.COLLISION })
        assertTrue(trusted.skills.any { it.name == "root-preferred" })
        assertFalse(trusted.skills.any { it.name == "nested" })
    }

    @Test
    fun `skills validate metadata hide manual-only entries and expand manual commands`() {
        val root = Files.createTempDirectory("pi-kotlin-skill-command")
        val skillDir = Files.createDirectories(root.resolve("manual"))
        val skillFile = skillDir.resolve("SKILL.md")
        Files.writeString(
            skillFile,
            """
            ---
            name: manual
            description: Manual <skill> & instructions
            disable-model-invocation: true
            ---
            Follow the checklist.
            """.trimIndent(),
        )
        val loaded = loadSkillsFromDirectory(skillDir, "test")
        val skill = loaded.skills.single()

        assertEquals("", formatSkillsForPrompt(listOf(skill)))
        assertEquals(
            """
            <skill name="manual" location="$skillFile">
            References are relative to $skillDir.

            Follow the checklist.
            </skill>

            target
            """.trimIndent(),
            expandSkillCommand("/skill:manual target", listOf(skill)),
        )
    }

    @Test
    fun `prompt discovery prefers project templates and reports collisions`() {
        val root = Files.createTempDirectory("pi-kotlin-prompts")
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val project = Files.createDirectories(root.resolve("project"))
        val userPrompts = Files.createDirectories(agentDir.resolve("prompts"))
        val projectPrompts = Files.createDirectories(project.resolve(".pi").resolve("prompts"))
        Files.writeString(userPrompts.resolve("review.md"), "User review")
        Files.writeString(
            projectPrompts.resolve("review.md"),
            "---\ndescription: Project review\nargument-hint: \"<target>\"\n---\nReview $1",
        )

        val trusted = loadPromptTemplates(project, agentDir, projectTrusted = true)
        val untrusted = loadPromptTemplates(project, agentDir, projectTrusted = false)

        assertEquals("Project review", trusted.prompts.single().description)
        assertEquals("<target>", trusted.prompts.single().argumentHint)
        assertEquals(
            canonicalPath(userPrompts.resolve("review.md")),
            canonicalPath(assertNotNull(trusted.diagnostics.single().collision).loserPath),
        )
        assertEquals("User review", untrusted.prompts.single().content)
    }

    @Test
    fun `project trust persists decisions and inherits from parent directories`() {
        val root = Files.createTempDirectory("pi-kotlin-trust")
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val parent = Files.createDirectories(root.resolve("workspace"))
        val child = Files.createDirectories(parent.resolve("project"))
        val store = ProjectTrustStore(agentDir)

        assertNull(store.get(child))
        store.set(parent, true)
        assertEquals(true, store.get(child))
        store.set(child, false)
        assertEquals(false, store.get(child))
        store.set(child, null)
        assertEquals(true, ProjectTrustStore(agentDir).get(child))
        assertTrue(Files.readString(agentDir.resolve("trust.json")).endsWith("\n"))
        assertFalse(Files.exists(agentDir.resolve("trust.json.lock")))
    }

    @Test
    fun `project trust accepts legacy null entries`() {
        val root = Files.createTempDirectory("pi-kotlin-trust-null")
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val project = Files.createDirectories(root.resolve("project"))
        Files.writeString(
            agentDir.resolve("trust.json"),
            "{\"${project.toString().replace("\\", "\\\\")}\":null}\n",
        )

        assertNull(ProjectTrustStore(agentDir).get(project))
    }

    @Test
    fun `project resource detection excludes user agents skills and gates project resources`() {
        val root = Files.createTempDirectory("pi-kotlin-trust-detection")
        val home = Files.createDirectories(root.resolve("home"))
        val project = Files.createDirectories(home.resolve("workspace").resolve("project"))
        Files.createDirectories(home.resolve(".agents").resolve("skills"))

        assertFalse(hasTrustRequiringProjectResources(home, home))
        assertFalse(hasTrustRequiringProjectResources(project, home))

        Files.createDirectories(project.resolve(".agents").resolve("skills"))
        assertTrue(hasTrustRequiringProjectResources(project, home))
    }

    @Test
    fun `CLI loads skills into system prompt and expands prompt templates`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-resource-cli")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            val project = Files.createDirectories(root.resolve("project"))
            val skillDir = Files.createDirectories(agentDir.resolve("skills").resolve("review"))
            Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: review\ndescription: Review carefully\n---\nReview instructions",
            )
            val prompts = Files.createDirectories(agentDir.resolve("prompts"))
            Files.writeString(
                prompts.resolve("plan.md"),
                "---\ndescription: Plan work\n---\nPlan $1 \${@:2}",
            )
            val provider = FauxProvider()
            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { context, _, _, _ ->
                        assertEquals(
                            "Plan auth flow with tests",
                            contentText(context.messages.filterIsInstance<UserMessage>().single().content),
                        )
                        assertTrue(context.systemPrompt.orEmpty().contains("<name>review</name>"))
                        fauxAssistantMessage("ok")
                    },
                ),
            )
            val stderr = StringWriter()
            val runtime =
                CliRuntime(
                    models = Models(listOf(provider)),
                    cwd = project,
                    agentDir = agentDir,
                    stdout = PrintWriter(StringWriter(), true),
                    stderr = PrintWriter(stderr, true),
                )

            val exit =
                runtime.run(
                    parseArgs(
                        listOf(
                            "--provider",
                            "faux",
                            "--model",
                            "faux-1",
                            "--no-session",
                            "--no-skills",
                            "--skill",
                            skillDir.toString(),
                            "--no-prompt-templates",
                            "--prompt-template",
                            prompts.toString(),
                            "-p",
                            "/plan \"auth flow\" with tests",
                        ),
                    ),
                )

            assertEquals(0, exit)
            assertEquals("", stderr.toString())
        }

    @Test
    fun `RPC exposes resource commands and expands prompt and manual skill commands`() =
        runTest {
            val root = Files.createTempDirectory("pi-kotlin-resource-rpc")
            val agentDir = Files.createDirectories(root.resolve("agent"))
            val skillDir = Files.createDirectories(agentDir.resolve("skills").resolve("manual"))
            Files.writeString(
                skillDir.resolve("SKILL.md"),
                "---\nname: manual\ndescription: Manual instructions\ndisable-model-invocation: true\n---\nDo the work.",
            )
            val prompts = Files.createDirectories(agentDir.resolve("prompts"))
            Files.writeString(
                prompts.resolve("echo.md"),
                "---\ndescription: Echo\n---\nEcho ${'$'}ARGUMENTS",
            )
            val provider = FauxProvider()
            provider.setResponses(
                listOf(
                    FauxResponseStep.Factory { context, _, _, _ ->
                        assertEquals(
                            "Echo hello world",
                            contentText(context.messages.filterIsInstance<UserMessage>().single().content),
                        )
                        fauxAssistantMessage("first")
                    },
                    FauxResponseStep.Factory { context, _, _, _ ->
                        val text = contentText(context.messages.filterIsInstance<UserMessage>().last().content)
                        assertTrue(text.startsWith("<skill name=\"manual\""))
                        assertTrue(text.endsWith("target"))
                        fauxAssistantMessage("second")
                    },
                ),
            )
            val runtime =
                RpcRuntime(
                    Models(listOf(provider)),
                    RpcRuntimeOptions(
                        cwd = root.resolve("project"),
                        agentDir = agentDir,
                        noSession = true,
                        provider = "faux",
                        model = "faux-1",
                        noSkills = true,
                        skillPaths = listOf(skillDir.toString()),
                        noPromptTemplates = true,
                        promptTemplatePaths = listOf(prompts.toString()),
                    ),
                )

            val commands =
                assertNotNull(
                    runtime.handle(buildJsonObject { put("type", "get_commands") }),
                )["data"]
                    ?.jsonObject
                    ?.get("commands")
                    ?.jsonArray
                    .orEmpty()
                    .map { it.jsonObject["name"]?.jsonPrimitive?.content }
            assertEquals(listOf("echo", "skill:manual"), commands)

            assertEquals(
                true,
                runtime
                    .handle(
                        buildJsonObject {
                            put("type", "prompt")
                            put("message", "/echo hello world")
                        },
                    )?.get("success")
                    ?.jsonPrimitive
                    ?.content
                    ?.toBoolean(),
            )
            runtime.waitForIdle()
            assertEquals(
                true,
                runtime
                    .handle(
                        buildJsonObject {
                            put("type", "prompt")
                            put("message", "/skill:manual target")
                        },
                    )?.get("success")
                    ?.jsonPrimitive
                    ?.content
                    ?.toBoolean(),
            )
            runtime.waitForIdle()
            runtime.close()
        }
}
