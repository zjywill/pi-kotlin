package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main() {
    val root = canonicalPath(Files.createTempDirectory("pi-package-oracle-"))
    val home = Files.createDirectories(root.resolve("home"))
    val agentDir = Files.createDirectories(root.resolve("agent"))
    val cwd = Files.createDirectories(root.resolve("workspace").resolve("project"))
    Files.createDirectory(cwd.resolve(".git"))
    val userPackage = createOraclePackage(root.resolve("packages").resolve("user"), "user")
    val projectPackage =
        createOraclePackage(
            cwd.resolve(".pi").resolve("packages").resolve("project"),
            "project",
        )

    try {
        write(agentDir.resolve("prompts").resolve("auto.md"), "auto user prompt")
        write(agentDir.resolve("prompts").resolve("disabled.md"), "disabled user prompt")
        write(agentDir.resolve("prompts").resolve("ignored.md"), "ignored user prompt")
        write(agentDir.resolve("prompts").resolve(".gitignore"), "ignored.md\n")
        write(
            cwd.resolve(".pi").resolve("skills").resolve("local").resolve("SKILL.md"),
            "---\nname: local\ndescription: project local\n---\nlocal",
        )
        write(
            agentDir.resolve("settings.json"),
            """
            {
              "packages": [
                {
                  "source": ${oracleJson.encodeToString(userPackage.toString())},
                  "extensions": [],
                  "prompts": ["!prompts/skip.md"]
                }
              ],
              "prompts": ["!prompts/disabled.md"]
            }
            """.trimIndent(),
        )
        write(
            cwd.resolve(".pi").resolve("settings.json"),
            """
            {
              "packages": [${oracleJson.encodeToString(projectPackage.toString())}],
              "skills": ["+skills/local"]
            }
            """.trimIndent(),
        )

        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)
        val manager =
            PackageManager(
                cwd = cwd,
                agentDir = agentDir,
                settings = settings,
                projectTrusted = true,
                homeDir = home,
            )
        val resolved = manager.resolve()
        val parseInputs =
            listOf(
                "npm:@scope/pkg@1.2.3",
                "npm:plain@^2.0.0",
                "git:github.com/user/repo@v2",
                "git:git@github.com:user/repo@main",
                "https://gitlab.com/group/repo.git",
                "./github.com/user/repo",
            )
        val parsedSources =
            parseInputs.map { source ->
                buildJsonObject {
                    put("source", source)
                    put("parsed", parsedSourceJson(manager.parsePackageSource(source)))
                }
            }
        val configured =
            manager.listConfiguredPackages().map { pkg ->
                buildJsonObject {
                    put("source", pkg.source.replace(root.toString(), "<ROOT>"))
                    put("scope", if (pkg.scope == SettingsScope.USER) "user" else "project")
                    put("filtered", pkg.filtered)
                    if (pkg.installedPath == null) {
                        put("installedPath", JsonNull)
                    } else {
                        put("installedPath", relativeOraclePath(root, pkg.installedPath))
                    }
                }
            }
        val extra = root.resolve("packages").resolve("extra")
        val added = manager.addSourceToSettings(extra.toString(), PackageScope.USER)
        val stored = settings.global().packages.last().source
        val removed = manager.removeSourceFromSettings(extra.toString(), PackageScope.USER)

        val output =
            buildJsonObject {
                put(
                    "resolved",
                    buildJsonObject {
                        put("extensions", resourcesJson(root, resolved.extensions))
                        put("skills", resourcesJson(root, resolved.skills))
                        put("prompts", resourcesJson(root, resolved.prompts))
                        put("themes", resourcesJson(root, resolved.themes))
                    },
                )
                put("configured", JsonArray(configured))
                put(
                    "settingsMutation",
                    buildJsonObject {
                        put("added", added)
                        put("stored", stored)
                        put("removed", removed)
                    },
                )
                put("parsedSources", JsonArray(parsedSources))
                put(
                    "installPaths",
                    buildJsonObject {
                        put(
                            "npm",
                            buildJsonObject {
                                put(
                                    "user",
                                    relativeOraclePath(
                                        root,
                                        manager.plannedInstallPath("npm:@scope/pkg@1.2.3", PackageScope.USER),
                                    ),
                                )
                                put(
                                    "project",
                                    relativeOraclePath(
                                        root,
                                        manager.plannedInstallPath("npm:@scope/pkg@1.2.3", PackageScope.PROJECT),
                                    ),
                                )
                                put(
                                    "temporary",
                                    relativeOraclePath(
                                        root,
                                        manager.plannedInstallPath("npm:@scope/pkg@1.2.3", PackageScope.TEMPORARY),
                                    ),
                                )
                            },
                        )
                        put(
                            "git",
                            buildJsonObject {
                                put(
                                    "user",
                                    relativeOraclePath(
                                        root,
                                        manager.plannedInstallPath("git:github.com/user/repo@v2", PackageScope.USER),
                                    ),
                                )
                                put(
                                    "project",
                                    relativeOraclePath(
                                        root,
                                        manager.plannedInstallPath("git:github.com/user/repo@v2", PackageScope.PROJECT),
                                    ),
                                )
                                put(
                                    "temporary",
                                    relativeOraclePath(
                                        root,
                                        manager.plannedInstallPath("git:github.com/user/repo@v2", PackageScope.TEMPORARY),
                                    ),
                                )
                            },
                        )
                    },
                )
            }
        println(oracleJson.encodeToString(JsonObject.serializer(), output))
    } finally {
        root.toFile().deleteRecursively()
    }
}

private fun parsedSourceJson(source: ParsedPackageSource): JsonObject =
    when (source) {
        is ParsedPackageSource.Npm ->
            buildJsonObject {
                put("type", "npm")
                put("spec", source.spec)
                put("name", source.name)
                if (source.version == null) {
                    put("version", JsonNull)
                } else {
                    put("version", source.version)
                }
                put("pinned", source.pinned)
            }

        is ParsedPackageSource.Git ->
            buildJsonObject {
                put("type", "git")
                put("repo", source.repo)
                put("host", source.host)
                put("path", source.path)
                if (source.ref == null) {
                    put("ref", JsonNull)
                } else {
                    put("ref", source.ref)
                }
                put("pinned", source.pinned)
            }

        is ParsedPackageSource.Local ->
            buildJsonObject {
                put("type", "local")
                put("path", source.path)
            }
    }

private fun createOraclePackage(
    root: Path,
    prefix: String,
): Path {
    write(root.resolve("extensions").resolve("main.ts"), "export default function() {}")
    write(root.resolve("extensions").resolve("skip.ts"), "export default function() {}")
    write(root.resolve("~extensions").resolve("tilde.ts"), "export default function() {}")
    write(root.resolve("~").resolve("extensions").resolve("home-like.ts"), "export default function() {}")
    write(
        root.resolve("skills").resolve("$prefix-skill").resolve("SKILL.md"),
        "---\nname: $prefix-skill\ndescription: $prefix skill\n---\n$prefix body",
    )
    write(root.resolve("prompts").resolve("$prefix.md"), "$prefix prompt")
    write(root.resolve("prompts").resolve("skip.md"), "skip prompt")
    write(root.resolve("themes").resolve("$prefix.json"), """{"name":"$prefix"}""")
    write(
        root.resolve("package.json"),
        """
        {
          "name": "$prefix-package",
          "version": "1.0.0",
          "pi": {
            "extensions": ["extensions/*.ts", "~extensions/tilde.ts", "~/extensions/home-like.ts"],
            "skills": ["skills/**"],
            "prompts": ["prompts/*.md"],
            "themes": ["themes/*.json"]
          }
        }
        """.trimIndent(),
    )
    return root
}

private fun resourcesJson(
    root: Path,
    resources: List<ResolvedResource>,
) = buildJsonArray {
    resources.forEach { resource ->
        add(
            buildJsonObject {
                put("path", relativeOraclePath(root, resource.path))
                put("enabled", resource.enabled)
                put(
                    "metadata",
                    buildJsonObject {
                        put("source", resource.sourceInfo.source.replace(root.toString(), "<ROOT>"))
                        put("scope", resource.sourceInfo.scope)
                        put("origin", resource.sourceInfo.origin)
                        if (resource.sourceInfo.baseDir == null) {
                            put("baseDir", JsonNull)
                        } else {
                            put("baseDir", relativeOraclePath(root, resource.sourceInfo.baseDir))
                        }
                    },
                )
            },
        )
    }
}

private fun relativeOraclePath(
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

private val oracleJson =
    kotlinx.serialization.json.Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
