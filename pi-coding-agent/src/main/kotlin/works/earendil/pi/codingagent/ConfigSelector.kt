package works.earendil.pi.codingagent

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.jline.keymap.BindingReader
import org.jline.keymap.KeyMap
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.AttributedString
import org.jline.utils.Display
import org.jline.utils.InfoCmp

internal fun interface ResourceConfigSelector {
    fun run(
        cwd: Path,
        agentDir: Path,
        settings: SettingsStore,
        local: Boolean,
        projectTrusted: Boolean,
        output: PrintStream,
        errorOutput: PrintStream,
    ): Int
}

internal object JLineResourceConfigSelector : ResourceConfigSelector {
    override fun run(
        cwd: Path,
        agentDir: Path,
        settings: SettingsStore,
        local: Boolean,
        projectTrusted: Boolean,
        output: PrintStream,
        errorOutput: PrintStream,
    ): Int {
        val model =
            ResourceConfigModel(
                cwd = cwd,
                agentDir = agentDir,
                settings = settings,
                projectModeAvailable = projectTrusted,
                initialScope = if (local) ConfigWriteScope.PROJECT else ConfigWriteScope.GLOBAL,
            )
        val terminal =
            runCatching {
                TerminalBuilder
                    .builder()
                    .system(true)
                    .build()
            }.getOrElse { failure ->
                errorOutput.println("Error: Unable to open resource configuration terminal: ${failure.message}")
                return 1
            }
        return terminal.use {
            runSelectorTerminal(terminal, model)
            0
        }
    }
}

internal enum class ConfigWriteScope {
    GLOBAL,
    PROJECT,
}

internal enum class ProjectOverrideState {
    INHERIT,
    LOAD,
    UNLOAD,
}

internal data class ConfigResourceItem(
    val type: PackageResourceType,
    val resource: ResolvedResource,
) {
    val key: String
        get() = "${type.settingsKey}:${resource.path.toAbsolutePath().normalize()}"
}

internal class ResourceConfigModel(
    private val cwd: Path,
    private val agentDir: Path,
    private val settings: SettingsStore,
    private val projectModeAvailable: Boolean,
    initialScope: ConfigWriteScope,
    private val resolver: (Boolean) -> ResolvedPackageResources = { trusted ->
        resolvePackageResources(
            cwd = cwd,
            agentDir = agentDir,
            projectTrusted = trusted,
        )
    },
) {
    var scope: ConfigWriteScope =
        if (initialScope == ConfigWriteScope.PROJECT && !projectModeAvailable) {
            ConfigWriteScope.GLOBAL
        } else {
            initialScope
        }
        private set
    var query: String = ""
        private set
    var selectedIndex: Int = 0
        private set

    private var globalItems = flatten(resolver(false))
    private var projectItems = if (projectModeAvailable) flatten(resolver(true)) else globalItems

    fun visibleItems(): List<ConfigResourceItem> {
        val source = if (scope == ConfigWriteScope.PROJECT) projectItems else globalItems
        val normalizedQuery = query.trim().lowercase()
        val visible =
            source.filter { item ->
                if (scope == ConfigWriteScope.GLOBAL && item.resource.sourceInfo.scope != "user") {
                    return@filter false
                }
                normalizedQuery.isEmpty() ||
                    item.resource.path.toString().lowercase().contains(normalizedQuery) ||
                    item.resource.sourceInfo.source.lowercase().contains(normalizedQuery) ||
                    item.type.settingsKey.contains(normalizedQuery)
            }
        selectedIndex = selectedIndex.coerceIn(0, (visible.size - 1).coerceAtLeast(0))
        return visible
    }

    fun move(delta: Int) {
        val size = visibleItems().size
        if (size == 0) {
            selectedIndex = 0
            return
        }
        selectedIndex = (selectedIndex + delta).coerceIn(0, size - 1)
    }

    fun page(delta: Int) {
        move(delta * 8)
    }

    fun appendQuery(value: String) {
        query += value.filter { character -> !character.isISOControl() }
        selectedIndex = 0
    }

    fun backspaceQuery() {
        if (query.isNotEmpty()) {
            query = query.dropLast(1)
            selectedIndex = 0
        }
    }

    fun switchScope() {
        if (!projectModeAvailable) {
            return
        }
        scope =
            if (scope == ConfigWriteScope.GLOBAL) {
                ConfigWriteScope.PROJECT
            } else {
                ConfigWriteScope.GLOBAL
            }
        selectedIndex = 0
    }

    fun toggleSelected(): Boolean {
        val selected = visibleItems().getOrNull(selectedIndex) ?: return false
        if (scope == ConfigWriteScope.GLOBAL && selected.resource.sourceInfo.scope != "user") {
            return false
        }
        val selectedKey = selected.key
        if (selected.resource.sourceInfo.origin == "package") {
            togglePackage(selected)
        } else {
            toggleTopLevel(selected)
        }
        refresh(selectedKey)
        return true
    }

    fun projectOverrideState(item: ConfigResourceItem): ProjectOverrideState {
        if (scope != ConfigWriteScope.PROJECT) {
            return ProjectOverrideState.INHERIT
        }
        return if (item.resource.sourceInfo.origin == "package") {
            packageOverrideState(item)
        } else {
            topLevelOverrideState(item)
        }
    }

    fun render(
        width: Int,
        height: Int,
    ): List<String> {
        val safeWidth = width.coerceAtLeast(20)
        val safeHeight = height.coerceAtLeast(8)
        val title =
            if (scope == ConfigWriteScope.PROJECT) {
                "Project Local Resources"
            } else {
                "Global Resources"
            }
        val lines =
            mutableListOf(
                truncate(title, safeWidth),
                truncate(
                    if (projectModeAvailable) {
                        "Tab: switch scope  Space/Enter: toggle  Esc/Ctrl-C: close"
                    } else {
                        "Space/Enter: toggle  Esc/Ctrl-C: close"
                    },
                    safeWidth,
                ),
                truncate("Search: $query", safeWidth),
                "",
            )
        val items = visibleItems()
        val availableRows = (safeHeight - lines.size - 1).coerceAtLeast(1)
        val start = (selectedIndex - availableRows / 2).coerceIn(0, (items.size - availableRows).coerceAtLeast(0))
        val end = (start + availableRows).coerceAtMost(items.size)
        for (index in start until end) {
            val item = items[index]
            val cursor = if (index == selectedIndex) ">" else " "
            val state =
                if (scope == ConfigWriteScope.PROJECT) {
                    when (projectOverrideState(item)) {
                        ProjectOverrideState.INHERIT -> if (item.resource.enabled) "[x]" else "[ ]"
                        ProjectOverrideState.LOAD -> "[+]"
                        ProjectOverrideState.UNLOAD -> "[-]"
                    }
                } else if (item.resource.enabled) {
                    "[x]"
                } else {
                    "[ ]"
                }
            val scopeLabel = if (item.resource.sourceInfo.scope == "project") "project" else "user"
            val label =
                "$cursor $state ${item.type.settingsKey}: ${item.resource.path.fileName} " +
                    "(${item.resource.sourceInfo.source}; $scopeLabel)"
            lines += truncate(label, safeWidth)
        }
        if (items.isEmpty()) {
            lines += truncate("  No matching resources", safeWidth)
        } else if (start > 0 || end < items.size) {
            lines += truncate("  ${selectedIndex + 1}/${items.size}", safeWidth)
        }
        while (lines.size < safeHeight) {
            lines += ""
        }
        return lines.take(safeHeight)
    }

    private fun refresh(selectedKey: String) {
        globalItems = flatten(resolver(false))
        projectItems = if (projectModeAvailable) flatten(resolver(true)) else globalItems
        val index = visibleItems().indexOfFirst { item -> item.key == selectedKey }
        if (index >= 0) {
            selectedIndex = index
        }
    }

    private fun toggleTopLevel(item: ConfigResourceItem) {
        val targetScope =
            if (scope == ConfigWriteScope.PROJECT) {
                SettingsScope.PROJECT
            } else {
                SettingsScope.USER
            }
        val current = settingsSnapshot(targetScope).resourceEntries(item.type)
        val patterns = topLevelPatterns(item, targetScope)
        val updated =
            current
                .filterNot { entry -> patternTarget(entry) in patterns }
                .toMutableList()
        if (targetScope == SettingsScope.USER) {
            val enabled = !item.resource.enabled
            updated += "${if (enabled) "+" else "-"}${resourcePattern(item, targetScope)}"
        } else {
            val next = nextProjectState(projectOverrideState(item), inheritedEnabled(item))
            if (next != ProjectOverrideState.INHERIT) {
                val pattern =
                    if (item.resource.sourceInfo.scope == "user") {
                        item.resource.path.toString()
                    } else {
                        resourcePattern(item, targetScope)
                    }
                if (item.resource.sourceInfo.scope == "user") {
                    updated += pattern
                }
                updated += "${if (next == ProjectOverrideState.LOAD) "+" else "-"}$pattern"
            }
        }
        settings.setResourceEntries(targetScope, item.type, updated)
    }

    private fun togglePackage(item: ConfigResourceItem) {
        val targetScope =
            if (scope == ConfigWriteScope.PROJECT) {
                SettingsScope.PROJECT
            } else {
                SettingsScope.USER
            }
        val packages = settings.packages(targetScope).toMutableList()
        var index =
            packages.indexOfFirst { configured ->
                packageSourcesMatch(
                    item.resource.sourceInfo.source,
                    item.resource.sourceInfo.scope.toSettingsScope(),
                    configured.source,
                    targetScope,
                )
            }
        if (index < 0) {
            if (targetScope == SettingsScope.USER) {
                return
            }
            packages += createProjectPackageOverride(item)
            index = packages.lastIndex
        }
        var configured = packages[index]
        val pattern = packageResourcePattern(item)
        val current = configured.patterns(item.type).orEmpty()
        val updated = current.filterNot { entry -> patternTarget(entry) == pattern }.toMutableList()
        if (targetScope == SettingsScope.USER) {
            updated += "${if (!item.resource.enabled) "+" else "-"}$pattern"
        } else {
            val next = nextProjectState(packageOverrideState(item), inheritedEnabled(item))
            if (next != ProjectOverrideState.INHERIT) {
                updated += "${if (next == ProjectOverrideState.LOAD) "+" else "-"}$pattern"
            }
        }
        configured = configured.withPatterns(item.type, updated.takeIf(List<String>::isNotEmpty))
        if (!configured.hasResourceFilters()) {
            if (targetScope == SettingsScope.PROJECT && configured.autoload == false) {
                packages.removeAt(index)
                settings.setPackages(targetScope, packages)
                return
            }
            configured = PackageSourceConfig(configured.source)
        }
        packages[index] = configured
        settings.setPackages(targetScope, packages)
    }

    private fun topLevelOverrideState(item: ConfigResourceItem): ProjectOverrideState {
        val entries = settings.project().resourceEntries(item.type)
        val patterns = topLevelPatterns(item, SettingsScope.PROJECT)
        var result = ProjectOverrideState.INHERIT
        entries.forEach { entry ->
            if (patternTarget(entry) !in patterns) {
                return@forEach
            }
            result =
                if (entry.startsWith("-") || entry.startsWith("!")) {
                    ProjectOverrideState.UNLOAD
                } else {
                    ProjectOverrideState.LOAD
                }
        }
        return result
    }

    private fun packageOverrideState(item: ConfigResourceItem): ProjectOverrideState {
        val configured =
            settings
                .project()
                .packages
                .firstOrNull { candidate ->
                    packageSourcesMatch(
                        item.resource.sourceInfo.source,
                        item.resource.sourceInfo.scope.toSettingsScope(),
                        candidate.source,
                        SettingsScope.PROJECT,
                    )
                } ?: return ProjectOverrideState.INHERIT
        val entries = configured.patterns(item.type)
        if (entries.isNullOrEmpty()) {
            return if (configured.autoload == false) {
                ProjectOverrideState.UNLOAD
            } else {
                ProjectOverrideState.INHERIT
            }
        }
        val pattern = packageResourcePattern(item)
        var result = ProjectOverrideState.INHERIT
        entries.forEach { entry ->
            if (patternTarget(entry) == pattern) {
                result =
                    if (entry.startsWith("-") || entry.startsWith("!")) {
                        ProjectOverrideState.UNLOAD
                    } else {
                        ProjectOverrideState.LOAD
                    }
            }
        }
        return result
    }

    private fun inheritedEnabled(item: ConfigResourceItem): Boolean {
        val inherited = globalItems.firstOrNull { candidate -> candidate.key == item.key }
        return inherited?.resource?.enabled
            ?: if (item.resource.sourceInfo.scope == "user") item.resource.enabled else true
    }

    private fun settingsSnapshot(scope: SettingsScope): SettingsSnapshot =
        if (scope == SettingsScope.PROJECT) settings.project() else settings.global()

    private fun topLevelPatterns(
        item: ConfigResourceItem,
        targetScope: SettingsScope,
    ): Set<String> {
        val base = baseDir(targetScope)
        return buildSet {
            add(resourcePattern(item, targetScope))
            add(item.resource.path.toString())
            runCatching { base.relativize(item.resource.path).toString() }.getOrNull()?.let(::add)
            item.resource.sourceInfo.baseDir
                ?.let { sourceBase -> runCatching { sourceBase.relativize(item.resource.path).toString() }.getOrNull() }
                ?.let(::add)
        }
    }

    private fun resourcePattern(
        item: ConfigResourceItem,
        targetScope: SettingsScope,
    ): String {
        val sourceScope = item.resource.sourceInfo.scope.toSettingsScope()
        if (sourceScope != targetScope) {
            return item.resource.path.toString()
        }
        val base = item.resource.sourceInfo.baseDir ?: baseDir(targetScope)
        return runCatching { base.relativize(item.resource.path).toString() }.getOrDefault(item.resource.path.toString())
    }

    private fun packageResourcePattern(item: ConfigResourceItem): String {
        val base = item.resource.sourceInfo.baseDir ?: item.resource.path.parent
        return runCatching { base.relativize(item.resource.path).toString() }.getOrDefault(item.resource.path.fileName.toString())
    }

    private fun createProjectPackageOverride(item: ConfigResourceItem): PackageSourceConfig {
        val source = item.resource.sourceInfo.source
        val projectedSource =
            if (isLocalPackageSource(source)) {
                val sourcePath = resolvePackageSource(source, item.resource.sourceInfo.scope.toSettingsScope())
                runCatching { baseDir(SettingsScope.PROJECT).relativize(sourcePath).toString() }
                    .getOrDefault(sourcePath.toString())
                    .ifEmpty { "." }
            } else {
                source
            }
        return PackageSourceConfig(
            source = projectedSource,
            autoload = false,
            objectForm = true,
        )
    }

    private fun packageSourcesMatch(
        left: String,
        leftScope: SettingsScope,
        right: String,
        rightScope: SettingsScope,
    ): Boolean {
        if (left == right) {
            return true
        }
        if (!isLocalPackageSource(left) || !isLocalPackageSource(right)) {
            return false
        }
        return resolvePackageSource(left, leftScope) == resolvePackageSource(right, rightScope)
    }

    private fun resolvePackageSource(
        source: String,
        scope: SettingsScope,
    ): Path {
        val raw = Path.of(source)
        return (if (raw.isAbsolute) raw else baseDir(scope).resolve(raw)).toAbsolutePath().normalize()
    }

    private fun baseDir(scope: SettingsScope): Path =
        if (scope == SettingsScope.PROJECT) cwd.resolve(".pi") else agentDir

    private fun flatten(resources: ResolvedPackageResources): List<ConfigResourceItem> =
        PackageResourceType.entries
            .flatMap { type -> resources.resources(type).map { resource -> ConfigResourceItem(type, resource) } }
            .distinctBy(ConfigResourceItem::key)
            .sortedWith(
                compareBy(
                    { it.resource.sourceInfo.origin },
                    { it.resource.sourceInfo.scope },
                    { it.resource.sourceInfo.source },
                    { it.type.ordinal },
                    { it.resource.path.toString() },
                ),
            )
}

private fun runSelectorTerminal(
    terminal: Terminal,
    model: ResourceConfigModel,
) {
    val originalAttributes = terminal.enterRawMode()
    val display = Display(terminal, false)
    val reader = BindingReader(terminal.reader())
    val keys = resourceConfigKeyMap(terminal)
    try {
        while (true) {
            val width = terminal.width.coerceAtLeast(20)
            val height = terminal.height.coerceAtLeast(8)
            display.resize(height, width)
            display.update(
                model.render(width, height).map(AttributedString::fromAnsi),
                -1,
            )
            when (reader.readBinding(keys)) {
                "up" -> model.move(-1)
                "down" -> model.move(1)
                "page-up" -> model.page(-1)
                "page-down" -> model.page(1)
                "toggle" -> model.toggleSelected()
                "switch" -> model.switchScope()
                "backspace" -> model.backspaceQuery()
                "text" -> model.appendQuery(reader.lastBinding)
                "close", null -> return
            }
        }
    } finally {
        display.reset()
        terminal.attributes = originalAttributes
        terminal.writer().println()
        terminal.flush()
    }
}

internal fun resourceConfigKeyMap(terminal: Terminal? = null): KeyMap<String> {
    val keys = KeyMap<String>()
    keys.bind("text", KeyMap.range("!-~"))
    if (terminal != null) {
        keys.bind("up", KeyMap.key(terminal, InfoCmp.Capability.key_up))
        keys.bind("down", KeyMap.key(terminal, InfoCmp.Capability.key_down))
        keys.bind("page-up", KeyMap.key(terminal, InfoCmp.Capability.key_ppage))
        keys.bind("page-down", KeyMap.key(terminal, InfoCmp.Capability.key_npage))
    }
    keys.bind("toggle", " ", "\r", "\n")
    keys.bind("switch", "\t")
    keys.bind("backspace", "\u007f", "\b")
    keys.bind("close", "\u001b", "\u0003")
    return keys
}

private fun PackageSourceConfig.withPatterns(
    type: PackageResourceType,
    patterns: List<String>?,
): PackageSourceConfig =
    when (type) {
        PackageResourceType.EXTENSIONS -> copy(extensions = patterns, objectForm = true)
        PackageResourceType.SKILLS -> copy(skills = patterns, objectForm = true)
        PackageResourceType.PROMPTS -> copy(prompts = patterns, objectForm = true)
        PackageResourceType.THEMES -> copy(themes = patterns, objectForm = true)
    }

private fun PackageSourceConfig.hasResourceFilters(): Boolean =
    extensions != null || skills != null || prompts != null || themes != null

private fun String.toSettingsScope(): SettingsScope =
    if (this == "project") SettingsScope.PROJECT else SettingsScope.USER

private fun nextProjectState(
    current: ProjectOverrideState,
    inheritedEnabled: Boolean,
): ProjectOverrideState =
    when (current) {
        ProjectOverrideState.INHERIT ->
            if (inheritedEnabled) ProjectOverrideState.UNLOAD else ProjectOverrideState.LOAD

        ProjectOverrideState.UNLOAD ->
            if (inheritedEnabled) ProjectOverrideState.LOAD else ProjectOverrideState.INHERIT

        ProjectOverrideState.LOAD ->
            if (inheritedEnabled) ProjectOverrideState.INHERIT else ProjectOverrideState.UNLOAD
    }

private fun patternTarget(value: String): String =
    if (value.startsWith("+") || value.startsWith("-") || value.startsWith("!")) value.drop(1) else value

private fun isLocalPackageSource(source: String): Boolean {
    val lower = source.lowercase()
    return listOf("npm:", "git:", "github:", "http:", "https:", "ssh:").none(lower::startsWith)
}

private fun truncate(
    value: String,
    width: Int,
): String =
    if (value.length <= width) {
        value
    } else if (width <= 3) {
        value.take(width)
    } else {
        value.take(width - 3) + "..."
    }
