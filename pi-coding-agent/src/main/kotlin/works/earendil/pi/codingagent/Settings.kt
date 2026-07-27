package works.earendil.pi.codingagent

import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal enum class SettingsScope {
    USER,
    PROJECT,
}

internal data class PackageSourceConfig(
    val source: String,
    val autoload: Boolean? = null,
    val extensions: List<String>? = null,
    val skills: List<String>? = null,
    val prompts: List<String>? = null,
    val themes: List<String>? = null,
    val objectForm: Boolean = false,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val filtered: Boolean
        get() = objectForm

    fun withSource(value: String): PackageSourceConfig = copy(source = value)

    fun patterns(type: PackageResourceType): List<String>? =
        when (type) {
            PackageResourceType.EXTENSIONS -> extensions
            PackageResourceType.SKILLS -> skills
            PackageResourceType.PROMPTS -> prompts
            PackageResourceType.THEMES -> themes
        }

    fun toJson(): JsonElement {
        if (!objectForm) {
            return JsonPrimitive(source)
        }
        return buildJsonObject {
            raw.forEach { (key, value) -> put(key, value) }
            put("source", source)
            putNullableBoolean("autoload", autoload)
            putNullableStrings("extensions", extensions)
            putNullableStrings("skills", skills)
            putNullableStrings("prompts", prompts)
            putNullableStrings("themes", themes)
        }
    }
}

internal data class SettingsSnapshot(
    val raw: JsonObject = JsonObject(emptyMap()),
    val packages: List<PackageSourceConfig> = emptyList(),
    val extensions: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val prompts: List<String> = emptyList(),
    val themes: List<String> = emptyList(),
    val npmCommand: List<String>? = null,
) {
    fun resourceEntries(type: PackageResourceType): List<String> =
        when (type) {
            PackageResourceType.EXTENSIONS -> extensions
            PackageResourceType.SKILLS -> skills
            PackageResourceType.PROMPTS -> prompts
            PackageResourceType.THEMES -> themes
        }
}

internal class SettingsStore(
    private val cwd: Path,
    private val agentDir: Path,
    private val projectTrusted: Boolean,
    private val onWarning: (String) -> Unit = {},
) {
    private val globalPath = agentDir.toAbsolutePath().normalize().resolve("settings.json")
    private val projectPath = cwd.toAbsolutePath().normalize().resolve(".pi").resolve("settings.json")

    fun global(): SettingsSnapshot = read(globalPath, SettingsScope.USER)

    fun project(): SettingsSnapshot =
        if (projectTrusted) {
            read(projectPath, SettingsScope.PROJECT)
        } else {
            SettingsSnapshot()
        }

    fun packages(scope: SettingsScope): List<PackageSourceConfig> =
        when (scope) {
            SettingsScope.USER -> global()
            SettingsScope.PROJECT -> project()
        }.packages

    fun setPackages(
        scope: SettingsScope,
        packages: List<PackageSourceConfig>,
    ) {
        requireProjectTrusted(scope)
        val path = path(scope)
        withSettingsLock(path) {
            val current = readRaw(path, scope)
            val updated =
                buildJsonObject {
                    current.forEach { (key, value) -> put(key, value) }
                    put("packages", JsonArray(packages.map(PackageSourceConfig::toJson)))
                }
            writeRaw(path, updated)
        }
    }

    fun path(scope: SettingsScope): Path =
        when (scope) {
            SettingsScope.USER -> globalPath
            SettingsScope.PROJECT -> projectPath
        }

    private fun read(
        path: Path,
        scope: SettingsScope,
    ): SettingsSnapshot {
        val raw = readRaw(path, scope)
        return SettingsSnapshot(
            raw = raw,
            packages =
                raw["packages"]
                    ?.let { value ->
                        runCatching {
                            value.jsonArray.mapNotNull(::parsePackageSource)
                        }.getOrElse {
                            onWarning("Invalid packages setting in $path: ${it.message}")
                            emptyList()
                        }
                    }.orEmpty(),
            extensions = raw.stringList("extensions", path),
            skills = raw.stringList("skills", path),
            prompts = raw.stringList("prompts", path),
            themes = raw.stringList("themes", path),
            npmCommand =
                raw["npmCommand"]?.let { value ->
                    runCatching { value.jsonArray.map { it.jsonPrimitive.content } }
                        .onFailure { onWarning("Invalid npmCommand setting in $path: ${it.message}") }
                        .getOrNull()
                },
        )
    }

    private fun readRaw(
        path: Path,
        scope: SettingsScope,
    ): JsonObject {
        if (scope == SettingsScope.PROJECT && !projectTrusted) {
            return JsonObject(emptyMap())
        }
        if (!Files.exists(path)) {
            return JsonObject(emptyMap())
        }
        return try {
            settingsJson.parseToJsonElement(Files.readString(path)).jsonObject
        } catch (error: Exception) {
            onWarning("Failed to read settings $path: ${error.message}")
            JsonObject(emptyMap())
        }
    }

    private fun writeRaw(
        path: Path,
        value: JsonObject,
    ) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".settings-", ".tmp")
        try {
            Files.writeString(temporary, settingsJson.encodeToString(JsonObject.serializer(), value) + "\n")
            setOwnerOnlySettingsPermissions(temporary)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            setOwnerOnlySettingsPermissions(path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun requireProjectTrusted(scope: SettingsScope) {
        require(scope != SettingsScope.PROJECT || projectTrusted) {
            "Project is not trusted; refusing to modify project settings"
        }
    }

    private fun JsonObject.stringList(
        key: String,
        path: Path,
    ): List<String> =
        this[key]?.let { value ->
            runCatching { value.jsonArray.map { it.jsonPrimitive.content } }
                .onFailure { onWarning("Invalid $key setting in $path: ${it.message}") }
                .getOrDefault(emptyList())
        }.orEmpty()
}

private fun parsePackageSource(value: JsonElement): PackageSourceConfig? =
    when (value) {
        is JsonPrimitive ->
            value.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?.let(::PackageSourceConfig)

        is JsonObject -> {
            val source = value["source"]?.jsonPrimitive?.contentOrNull ?: return null
            PackageSourceConfig(
                source = source,
                autoload = value["autoload"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                extensions = value.optionalStringList("extensions"),
                skills = value.optionalStringList("skills"),
                prompts = value.optionalStringList("prompts"),
                themes = value.optionalStringList("themes"),
                objectForm = true,
                raw = value,
            )
        }

        else -> null
    }

private fun JsonObject.optionalStringList(key: String): List<String>? =
    this[key]?.let { value ->
        runCatching { value.jsonArray.map { it.jsonPrimitive.content } }.getOrNull()
    }

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableBoolean(
    key: String,
    value: Boolean?,
) {
    if (value != null) {
        put(key, value)
    }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableStrings(
    key: String,
    value: List<String>?,
) {
    if (value != null) {
        put(key, JsonArray(value.map(::JsonPrimitive)))
    }
}

private fun withSettingsLock(
    path: Path,
    block: () -> Unit,
) {
    Files.createDirectories(path.parent)
    val lockPath = path.resolveSibling("${path.fileName}.lock")
    var acquired = false
    for (attempt in 0 until SETTINGS_LOCK_ATTEMPTS) {
        try {
            Files.createDirectory(lockPath)
            acquired = true
            break
        } catch (error: FileAlreadyExistsException) {
            if (attempt == SETTINGS_LOCK_ATTEMPTS - 1) {
                throw error
            }
            Thread.sleep(SETTINGS_LOCK_RETRY_DELAY_MS)
        }
    }
    check(acquired) { "Failed to acquire settings lock" }
    try {
        block()
    } finally {
        Files.deleteIfExists(lockPath)
    }
}

private fun setOwnerOnlySettingsPermissions(path: Path) {
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

private val settingsJson =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
    }

private const val SETTINGS_LOCK_ATTEMPTS = 10
private const val SETTINGS_LOCK_RETRY_DELAY_MS = 20L
