package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

fun main() {
    val root = canonicalPath(Files.createTempDirectory("pi-resource-oracle-"))
    val home = Files.createDirectories(root.resolve("home"))
    val agentDir = Files.createDirectories(root.resolve("agent"))
    val cwd = Files.createDirectories(root.resolve("workspace").resolve("project"))
    val child = Files.createDirectories(cwd.resolve("child"))
    Files.createDirectory(cwd.resolve(".git"))

    try {
        write(
            agentDir.resolve("skills").resolve("shared").resolve("SKILL.md"),
            "---\nname: shared\ndescription: User skill\n---\nUser skill body.",
        )
        write(
            agentDir.resolve("skills").resolve("manual").resolve("SKILL.md"),
            "---\nname: manual\ndescription: Manual only\ndisable-model-invocation: true\n---\nManual body.",
        )
        write(
            cwd.resolve(".pi").resolve("skills").resolve("shared").resolve("SKILL.md"),
            "---\nname: shared\ndescription: Project skill\n---\nProject skill body.",
        )
        write(
            cwd.resolve(".agents").resolve("skills").resolve("ancestor").resolve("SKILL.md"),
            "---\nname: ancestor\ndescription: Ancestor skill\n---\nAncestor body.",
        )
        write(agentDir.resolve("prompts").resolve("review.md"), "User review ${'$'}ARGUMENTS")
        write(
            cwd.resolve(".pi").resolve("prompts").resolve("review.md"),
            "---\ndescription: Project review\nargument-hint: \"<target>\"\n---\n" +
                "Review ${'$'}1 with \${@:2}",
        )
        write(agentDir.resolve("SYSTEM.md"), "Global system.")
        write(cwd.resolve(".pi").resolve("SYSTEM.md"), "Project system.")
        write(cwd.resolve(".pi").resolve("APPEND_SYSTEM.md"), "Project append.")
        write(agentDir.resolve("AGENTS.md"), "Global context.")
        write(cwd.resolve("AGENTS.md"), "Project context.")

        val trusted =
            loadPromptResources(
                cwd = cwd,
                agentDir = agentDir,
                projectTrusted = true,
                homeDir = home,
            )
        val untrusted =
            loadPromptResources(
                cwd = cwd,
                agentDir = agentDir,
                projectTrusted = false,
                homeDir = home,
            )

        val trustStore = ProjectTrustStore(agentDir)
        val trustBefore = trustStore.get(child)
        trustStore.set(cwd, true)
        val inheritedTrust = trustStore.get(child)
        trustStore.set(child, false)
        val childTrust = trustStore.get(child)
        trustStore.set(child, null)
        val restoredTrust = trustStore.get(child)

        val output =
            buildJsonObject {
                put("commandArgs", JsonArray(parseCommandArgs("\"auth flow\" strict mode").map(::jsonString)))
                put(
                    "substitution",
                    substituteArgs(
                        "${'$'}1|\${@:2}|\${4:-fallback}|${'$'}ARGUMENTS",
                        listOf("auth flow", "strict", "mode"),
                    ),
                )
                put(
                    "templateExpansion",
                    expandPromptTemplate("/review \"auth flow\" strict mode", trusted.promptTemplates),
                )
                put("trusted", resourcesJson(root, trusted))
                put("untrusted", resourcesJson(root, untrusted))
                put(
                    "trust",
                    buildJsonObject {
                        put("requiresTrust", hasTrustRequiringProjectResources(cwd, home))
                        putNullableBoolean("before", trustBefore)
                        putNullableBoolean("inherited", inheritedTrust)
                        putNullableBoolean("child", childTrust)
                        putNullableBoolean("restored", restoredTrust)
                        val trustFile =
                            protocolJson
                                .parseToJsonElement(Files.readString(agentDir.resolve("trust.json")))
                                .jsonObject
                        put(
                            "file",
                            buildJsonObject {
                                trustFile.forEach { (path, decision) ->
                                    put(relativePath(root, Path.of(path)), decision)
                                }
                            },
                        )
                    },
                )
            }
        println(protocolJson.encodeToString(JsonObject.serializer(), output))
    } finally {
        root.toFile().deleteRecursively()
    }
}

private fun resourcesJson(
    root: Path,
    resources: PromptResources,
): JsonObject =
    buildJsonObject {
        if (resources.customPrompt == null) {
            put("systemPrompt", JsonNull)
        } else {
            put("systemPrompt", resources.customPrompt)
        }
        put("appendSystemPrompt", JsonArray(resources.appendPrompts.map(::jsonString)))
        put(
            "contextFiles",
            buildJsonArray {
                resources.contextFiles.forEach { file ->
                    add(
                        buildJsonObject {
                            put("path", relativePath(root, file.path))
                            put("content", file.content)
                        },
                    )
                }
            },
        )
        put("skills", JsonArray(resources.skills.map { skillJson(root, it) }))
        put(
            "skillDiagnostics",
            JsonArray(
                resources.diagnostics
                    .filter { diagnostic ->
                        diagnostic.collision?.resourceType == "skill" ||
                            (diagnostic.collision == null && diagnostic.path?.fileName?.toString() == "SKILL.md")
                    }.map { diagnosticJson(root, it) },
            ),
        )
        put("prompts", JsonArray(resources.promptTemplates.map { promptJson(root, it) }))
        put(
            "promptDiagnostics",
            JsonArray(
                resources.diagnostics
                    .filter { it.collision?.resourceType == "prompt" }
                    .map { diagnosticJson(root, it) },
            ),
        )
        put(
            "formattedSkills",
            formatSkillsForPrompt(resources.skills).replace(root.toString(), "<ROOT>"),
        )
    }

private fun skillJson(
    root: Path,
    skill: Skill,
): JsonObject =
    buildJsonObject {
        put("name", skill.name)
        put("description", skill.description)
        put("disableModelInvocation", skill.disableModelInvocation)
        put("filePath", relativePath(root, skill.filePath))
        put("baseDir", relativePath(root, skill.baseDir))
        put("sourceInfo", sourceInfoJson(root, skill.sourceInfo))
    }

private fun promptJson(
    root: Path,
    prompt: PromptTemplate,
): JsonObject =
    buildJsonObject {
        put("name", prompt.name)
        put("description", prompt.description)
        if (prompt.argumentHint == null) {
            put("argumentHint", JsonNull)
        } else {
            put("argumentHint", prompt.argumentHint)
        }
        put("content", prompt.content)
        put("filePath", relativePath(root, prompt.filePath))
        put("sourceInfo", sourceInfoJson(root, prompt.sourceInfo))
    }

private fun sourceInfoJson(
    root: Path,
    sourceInfo: ResourceSourceInfo,
): JsonObject =
    buildJsonObject {
        put("path", relativePath(root, sourceInfo.path))
        put("source", sourceInfo.source)
        put("scope", sourceInfo.scope)
        put("origin", sourceInfo.origin)
        if (sourceInfo.baseDir == null) {
            put("baseDir", JsonNull)
        } else {
            put("baseDir", relativePath(root, sourceInfo.baseDir))
        }
    }

private fun diagnosticJson(
    root: Path,
    diagnostic: ResourceDiagnostic,
): JsonObject =
    buildJsonObject {
        put("type", diagnostic.type.name.lowercase())
        put("message", diagnostic.message)
        if (diagnostic.path == null) {
            put("path", JsonNull)
        } else {
            put("path", relativePath(root, diagnostic.path))
        }
        val collision = diagnostic.collision
        if (collision == null) {
            put("collision", JsonNull)
        } else {
            put(
                "collision",
                buildJsonObject {
                    put("resourceType", collision.resourceType)
                    put("name", collision.name)
                    put("winnerPath", relativePath(root, collision.winnerPath))
                    put("loserPath", relativePath(root, collision.loserPath))
                },
            )
        }
    }

private fun relativePath(
    root: Path,
    path: Path,
): String =
    canonicalPath(root)
        .relativize(canonicalPath(path))
        .toString()
        .replace('\\', '/')

private fun write(
    path: Path,
    content: String,
) {
    Files.createDirectories(path.parent)
    Files.writeString(path, content)
}

private fun jsonString(value: String) = kotlinx.serialization.json.JsonPrimitive(value)

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableBoolean(
    name: String,
    value: Boolean?,
) {
    if (value == null) {
        put(name, JsonNull)
    } else {
        put(name, value)
    }
}
