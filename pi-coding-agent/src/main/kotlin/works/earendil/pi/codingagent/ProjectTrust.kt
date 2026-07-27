package works.earendil.pi.codingagent

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class ProjectTrustStoreEntry(
    val path: Path,
    val decision: Boolean,
)

internal data class ProjectTrustUpdate(
    val path: Path,
    val decision: Boolean?,
)

internal data class ProjectTrustOption(
    val label: String,
    val trusted: Boolean,
    val updates: List<ProjectTrustUpdate>,
    val savedPath: Path? = null,
)

internal class ProjectTrustStore(
    agentDir: Path,
) {
    private val trustPath = agentDir.toAbsolutePath().normalize().resolve("trust.json")
    private val lockPath = trustPath.resolveSibling("${trustPath.fileName}.lock")

    fun get(cwd: Path): Boolean? = getEntry(cwd)?.decision

    fun getEntry(cwd: Path): ProjectTrustStoreEntry? =
        withLock {
            val data = readTrustFile()
            var current = canonicalPath(cwd)
            while (true) {
                data[current.toString()]?.let { decision ->
                    return@withLock ProjectTrustStoreEntry(current, decision)
                }
                val parent = current.parent ?: break
                current = parent
            }
            null
        }

    fun set(
        cwd: Path,
        decision: Boolean?,
    ) {
        setMany(listOf(ProjectTrustUpdate(cwd, decision)))
    }

    fun setMany(updates: List<ProjectTrustUpdate>) {
        withLock {
            val data = readTrustFile().toMutableMap()
            updates.forEach { update ->
                val key = canonicalPath(update.path).toString()
                if (update.decision == null) {
                    data.remove(key)
                } else {
                    data[key] = update.decision
                }
            }
            writeTrustFile(data)
        }
    }

    private fun readTrustFile(): Map<String, Boolean?> {
        if (!Files.exists(trustPath)) {
            return emptyMap()
        }
        val parsed =
            try {
                trustJson.parseToJsonElement(Files.readString(trustPath)).jsonObject
            } catch (error: Exception) {
                error("Failed to read trust store $trustPath: ${error.message}")
        }
        return parsed.mapValues { (key, value) ->
            when (value) {
                JsonNull -> null
                is JsonPrimitive ->
                    value.booleanOrNull
                        ?: invalidTrustValue(key)

                else -> invalidTrustValue(key)
            }
        }
    }

    private fun writeTrustFile(data: Map<String, Boolean?>) {
        Files.createDirectories(trustPath.parent)
        val sorted =
            buildJsonObject {
                data.toSortedMap().forEach { (path, decision) ->
                    if (decision == null) {
                        put(path, JsonNull)
                    } else {
                        put(path, decision)
                    }
                }
            }
        val temporary = Files.createTempFile(trustPath.parent, ".trust-", ".tmp")
        try {
            Files.writeString(temporary, trustJson.encodeToString(JsonObject.serializer(), sorted) + "\n")
            setOwnerOnlyPermissions(temporary)
            try {
                Files.move(
                    temporary,
                    trustPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(temporary, trustPath, StandardCopyOption.REPLACE_EXISTING)
            }
            setOwnerOnlyPermissions(trustPath)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun <T> withLock(block: () -> T): T {
        Files.createDirectories(lockPath.parent)
        var acquired = false
        for (attempt in 0 until TRUST_LOCK_ATTEMPTS) {
            try {
                Files.createDirectory(lockPath)
                acquired = true
                break
            } catch (error: FileAlreadyExistsException) {
                if (attempt == TRUST_LOCK_ATTEMPTS - 1) {
                    throw error
                }
                Thread.sleep(TRUST_LOCK_RETRY_DELAY_MS)
            }
        }
        check(acquired) { "Failed to acquire trust store lock" }
        return try {
            block()
        } finally {
            Files.deleteIfExists(lockPath)
        }
    }

    private fun invalidTrustValue(key: String): Nothing =
        error(
            "Invalid trust store $trustPath: value for ${trustJson.encodeToString(key)} " +
                "must be true, false, or null",
        )
}

internal fun resolveProjectTrusted(
    cwd: Path,
    agentDir: Path,
    override: Boolean?,
    homeDir: Path = defaultHomeDirectory(),
    extensionHost: ExtensionHost? = null,
    extensionContext: JsonObject = JsonObject(emptyMap()),
    onExtensionActions: (List<ExtensionAction>) -> Unit = {},
    onTrustPrompt: ((Path, List<ProjectTrustOption>) -> ProjectTrustOption?)? = null,
): Boolean {
    if (override != null) {
        return override
    }
    if (!hasTrustRequiringProjectResources(cwd, homeDir)) {
        return true
    }
    val trustStore = ProjectTrustStore(agentDir)
    if (extensionHost != null) {
        val invocation =
            extensionHost.emit(
                event =
                    buildJsonObject {
                        put("type", "project_trust")
                        put("cwd", cwd.toAbsolutePath().normalize().toString())
                    },
                context = extensionContext,
            )
        onExtensionActions(invocation.actions)
        val result = invocation.result as? JsonObject
        val trusted =
            when (result?.stringValue("trusted")) {
                "yes" -> true
                "no" -> false
                else -> null
            }
        if (trusted != null) {
            if (result?.get("remember")?.jsonPrimitive?.booleanOrNull == true) {
                trustStore.set(cwd, trusted)
            }
            return trusted
        }
    }
    trustStore.get(cwd)?.let { return it }
    when (
        SettingsStore(
            cwd = cwd,
            agentDir = agentDir,
            projectTrusted = false,
        ).global().raw.stringValue("defaultProjectTrust")
    ) {
        "always" -> return true
        "never" -> return false
    }
    val normalizedCwd = canonicalPath(cwd)
    val selected = onTrustPrompt?.invoke(normalizedCwd, projectTrustOptions(normalizedCwd))
    if (selected != null) {
        if (selected.updates.isNotEmpty()) {
            trustStore.setMany(selected.updates)
        }
        return selected.trusted
    }
    return false
}

internal fun projectTrustOptions(
    cwd: Path,
    includeSessionOnly: Boolean = true,
): List<ProjectTrustOption> {
    val trustPath = canonicalPath(cwd)
    return buildList {
        add(
            ProjectTrustOption(
                label = "Trust",
                trusted = true,
                updates = listOf(ProjectTrustUpdate(trustPath, true)),
                savedPath = trustPath,
            ),
        )
        trustPath.parent?.let { parentPath ->
            add(
                ProjectTrustOption(
                    label = "Trust parent folder ($parentPath)",
                    trusted = true,
                    updates =
                        listOf(
                            ProjectTrustUpdate(parentPath, true),
                            ProjectTrustUpdate(trustPath, null),
                        ),
                    savedPath = parentPath,
                ),
            )
        }
        if (includeSessionOnly) {
            add(
                ProjectTrustOption(
                    label = "Trust (this session only)",
                    trusted = true,
                    updates = emptyList(),
                ),
            )
        }
        add(
            ProjectTrustOption(
                label = "Do not trust",
                trusted = false,
                updates = listOf(ProjectTrustUpdate(trustPath, false)),
                savedPath = trustPath,
            ),
        )
        if (includeSessionOnly) {
            add(
                ProjectTrustOption(
                    label = "Do not trust (this session only)",
                    trusted = false,
                    updates = emptyList(),
                ),
            )
        }
    }
}

internal fun hasTrustRequiringProjectResources(
    cwd: Path,
    homeDir: Path = defaultHomeDirectory(),
): Boolean {
    val normalizedCwd = canonicalPath(cwd)
    val normalizedHome = canonicalPath(homeDir)
    val userAgentsSkills = normalizedHome.resolve(".agents").resolve("skills")
    val projectConfig = normalizedCwd.resolve(".pi")
    if (TRUST_REQUIRING_PROJECT_CONFIG_RESOURCES.any { Files.exists(projectConfig.resolve(it)) }) {
        return true
    }
    var current: Path? = normalizedCwd
    while (current != null) {
        val skills = current.resolve(".agents").resolve("skills")
        if (skills != userAgentsSkills && Files.exists(skills)) {
            return true
        }
        current = current.parent
    }
    return false
}

internal fun canonicalPath(path: Path): Path {
    val normalized = path.toAbsolutePath().normalize()
    return runCatching(normalized::toRealPath).getOrDefault(normalized)
}

private fun setOwnerOnlyPermissions(path: Path) {
    runCatching {
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
        )
    }
}

private val trustJson =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

private val TRUST_REQUIRING_PROJECT_CONFIG_RESOURCES =
    listOf(
        "settings.json",
        "extensions",
        "skills",
        "prompts",
        "themes",
        "SYSTEM.md",
        "APPEND_SYSTEM.md",
    )

private const val TRUST_LOCK_ATTEMPTS = 10
private const val TRUST_LOCK_RETRY_DELAY_MS = 20L
