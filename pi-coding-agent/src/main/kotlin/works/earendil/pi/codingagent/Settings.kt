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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal enum class SettingsScope {
    USER,
    PROJECT,
}

enum class UiMode(
    val wireValue: String,
) {
    REGULAR("regular"),
    FULLSCREEN("fullscreen"),
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
    val theme: String? = null,
    val enabledModels: List<String>? = null,
    val npmCommand: List<String>? = null,
    val quietStartup: Boolean? = null,
    val uiMode: UiMode? = null,
    val fullscreenScrollbar: works.earendil.pi.tui.ScrollViewScrollbar? = null,
    val autocompleteMaxVisible: Int? = null,
) {
    fun resourceEntries(type: PackageResourceType): List<String> =
        when (type) {
            PackageResourceType.EXTENSIONS -> extensions
            PackageResourceType.SKILLS -> skills
            PackageResourceType.PROMPTS -> prompts
            PackageResourceType.THEMES -> themes
    }
}

internal data class AgentRuntimeSettings(
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
    val defaultThinkingLevel: String? = null,
    val steeringMode: String = "one-at-a-time",
    val followUpMode: String = "one-at-a-time",
    val autoCompactionEnabled: Boolean = true,
    val compactionReserveTokens: Int = 16_384,
    val compactionKeepRecentTokens: Int = 20_000,
    val imageAutoResize: Boolean = true,
    val autoRetryEnabled: Boolean = true,
    val retryMaxAttempts: Int = 3,
    val retryBaseDelayMs: Long = 2_000,
)

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

    fun mergedThemeSetting(): String? = project().theme ?: global().theme

    fun mergedUiMode(): UiMode = project().uiMode ?: global().uiMode ?: UiMode.REGULAR

    fun mergedFullscreenScrollbar(): works.earendil.pi.tui.ScrollViewScrollbar =
        project().fullscreenScrollbar
            ?: global().fullscreenScrollbar
            ?: works.earendil.pi.tui.ScrollViewScrollbar.AUTO

    fun mergedAutocompleteMaxVisible(): Int =
        project().autocompleteMaxVisible
            ?: global().autocompleteMaxVisible
            ?: DEFAULT_AUTOCOMPLETE_MAX_VISIBLE

    fun agentRuntimeSettings(): AgentRuntimeSettings {
        val global = global().raw
        val project = project().raw

        fun topLevel(name: String): JsonElement? = project[name] ?: global[name]

        fun nested(
            section: String,
            name: String,
        ): JsonElement? =
            (project[section] as? JsonObject)?.get(name)
                ?: (global[section] as? JsonObject)?.get(name)

        return AgentRuntimeSettings(
            defaultProvider = topLevel("defaultProvider").stringValue(),
            defaultModel = topLevel("defaultModel").stringValue(),
            defaultThinkingLevel = topLevel("defaultThinkingLevel").stringValue(),
            steeringMode = topLevel("steeringMode").stringValue() ?: "one-at-a-time",
            followUpMode = topLevel("followUpMode").stringValue() ?: "one-at-a-time",
            autoCompactionEnabled = nested("compaction", "enabled").booleanValue() ?: true,
            compactionReserveTokens =
                nested("compaction", "reserveTokens").intValue()?.coerceAtLeast(0) ?: 16_384,
            compactionKeepRecentTokens =
                nested("compaction", "keepRecentTokens").intValue()?.coerceAtLeast(0) ?: 20_000,
            imageAutoResize = nested("images", "autoResize").booleanValue() ?: true,
            autoRetryEnabled = nested("retry", "enabled").booleanValue() ?: true,
            retryMaxAttempts = nested("retry", "maxRetries").intValue()?.coerceAtLeast(0) ?: 3,
            retryBaseDelayMs =
                nested("retry", "baseDelayMs")
                    .intValue()
                    ?.toLong()
                    ?.coerceAtLeast(0)
                    ?: 2_000,
        )
    }

    fun setDefaultModelAndProvider(
        provider: String,
        model: String,
    ) {
        updateGlobal {
            put("defaultProvider", provider)
            put("defaultModel", model)
        }
    }

    fun setDefaultThinkingLevel(level: String) {
        updateGlobal { put("defaultThinkingLevel", level) }
    }

    fun setSteeringMode(mode: String) {
        updateGlobal { put("steeringMode", mode) }
    }

    fun setFollowUpMode(mode: String) {
        updateGlobal { put("followUpMode", mode) }
    }

    fun setAutoCompactionEnabled(enabled: Boolean) {
        updateGlobalNested("compaction", "enabled", JsonPrimitive(enabled))
    }

    fun setAutoRetryEnabled(enabled: Boolean) {
        updateGlobalNested("retry", "enabled", JsonPrimitive(enabled))
    }

    fun setTheme(theme: String) {
        updateGlobal { put("theme", theme) }
    }

    fun setUiMode(mode: UiMode) {
        updateGlobal { put("uiMode", mode.wireValue) }
    }

    fun setFullscreenScrollbar(mode: works.earendil.pi.tui.ScrollViewScrollbar) {
        updateGlobal { put("fullscreenScrollbar", mode.wireValue) }
    }

    fun setAutocompleteMaxVisible(value: Int) {
        updateGlobal { put("autocompleteMaxVisible", value.coerceIn(3, 20)) }
    }

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

    fun setResourceEntries(
        scope: SettingsScope,
        type: PackageResourceType,
        entries: List<String>,
    ) {
        requireProjectTrusted(scope)
        val path = path(scope)
        withSettingsLock(path) {
            val current = readRaw(path, scope)
            val updated =
                buildJsonObject {
                    current.forEach { (key, value) -> put(key, value) }
                    put(type.settingsKey, JsonArray(entries.map(::JsonPrimitive)))
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
            theme =
                (raw["theme"] as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)
                    ?.contentOrNull,
            enabledModels = raw.optionalSettingStringList("enabledModels", path),
            npmCommand =
                raw["npmCommand"]?.let { value ->
                    runCatching { value.jsonArray.map { it.jsonPrimitive.content } }
                        .onFailure { onWarning("Invalid npmCommand setting in $path: ${it.message}") }
                        .getOrNull()
                },
            quietStartup = raw["quietStartup"].booleanValue(),
            uiMode =
                when (raw["uiMode"].stringValue()) {
                    UiMode.FULLSCREEN.wireValue -> UiMode.FULLSCREEN
                    UiMode.REGULAR.wireValue -> UiMode.REGULAR
                    else -> null
                },
            fullscreenScrollbar =
                when (raw["fullscreenScrollbar"].stringValue()) {
                    works.earendil.pi.tui.ScrollViewScrollbar.HIDDEN.wireValue ->
                        works.earendil.pi.tui.ScrollViewScrollbar.HIDDEN

                    works.earendil.pi.tui.ScrollViewScrollbar.ALWAYS.wireValue ->
                        works.earendil.pi.tui.ScrollViewScrollbar.ALWAYS

                    works.earendil.pi.tui.ScrollViewScrollbar.AUTO.wireValue ->
                        works.earendil.pi.tui.ScrollViewScrollbar.AUTO

                    else -> null
                },
            autocompleteMaxVisible =
                raw["autocompleteMaxVisible"]
                    .intValue()
                    ?.coerceIn(3, 20),
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

    private fun updateGlobal(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        withSettingsLock(globalPath) {
            val current = readRaw(globalPath, SettingsScope.USER)
            val updated =
                buildJsonObject {
                    current.forEach { (key, value) -> put(key, value) }
                    block()
                }
            writeRaw(globalPath, updated)
        }
    }

    private fun updateGlobalNested(
        section: String,
        name: String,
        value: JsonElement,
    ) {
        withSettingsLock(globalPath) {
            val current = readRaw(globalPath, SettingsScope.USER)
            val existing = (current[section] as? JsonObject) ?: JsonObject(emptyMap())
            val updated =
                buildJsonObject {
                    current.forEach { (key, currentValue) -> put(key, currentValue) }
                    put(
                        section,
                        buildJsonObject {
                            existing.forEach { (key, nestedValue) -> put(key, nestedValue) }
                            put(name, value)
                        },
                    )
                }
            writeRaw(globalPath, updated)
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

    private fun JsonObject.optionalSettingStringList(
        key: String,
        path: Path,
    ): List<String>? =
        this[key]?.let { value ->
            runCatching { value.jsonArray.map { it.jsonPrimitive.content } }
                .onFailure { onWarning("Invalid $key setting in $path: ${it.message}") }
                .getOrNull()
        }
}

private const val DEFAULT_AUTOCOMPLETE_MAX_VISIBLE = 5

private fun JsonElement?.stringValue(): String? =
    (this as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull

private fun JsonElement?.booleanValue(): Boolean? =
    (this as? JsonPrimitive)?.booleanOrNull

private fun JsonElement?.intValue(): Int? =
    (this as? JsonPrimitive)?.intOrNull

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
