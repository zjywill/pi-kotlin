package works.earendil.pi.codingagent

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

internal enum class PackageResourceType(
    val settingsKey: String,
) {
    EXTENSIONS("extensions"),
    SKILLS("skills"),
    PROMPTS("prompts"),
    THEMES("themes"),
}

internal enum class PackageScope(
    val wireName: String,
) {
    USER("user"),
    PROJECT("project"),
    TEMPORARY("temporary"),
}

internal data class ResolvedResource(
    val path: Path,
    val enabled: Boolean,
    val sourceInfo: ResourceSourceInfo,
)

internal data class ResolvedPackageResources(
    val extensions: List<ResolvedResource> = emptyList(),
    val skills: List<ResolvedResource> = emptyList(),
    val prompts: List<ResolvedResource> = emptyList(),
    val themes: List<ResolvedResource> = emptyList(),
) {
    fun resources(type: PackageResourceType): List<ResolvedResource> =
        when (type) {
            PackageResourceType.EXTENSIONS -> extensions
            PackageResourceType.SKILLS -> skills
            PackageResourceType.PROMPTS -> prompts
            PackageResourceType.THEMES -> themes
        }
}

internal data class ConfiguredPackage(
    val source: String,
    val scope: SettingsScope,
    val filtered: Boolean,
    val installedPath: Path?,
)

internal data class PackageUpdate(
    val source: String,
    val displayName: String,
    val type: Type,
    val scope: SettingsScope,
) {
    enum class Type {
        NPM,
        GIT,
    }
}

internal data class PackageProgressEvent(
    val type: Type,
    val action: Action,
    val source: String,
    val message: String? = null,
) {
    enum class Type {
        START,
        COMPLETE,
        ERROR,
    }

    enum class Action {
        INSTALL,
        REMOVE,
        UPDATE,
    }
}

internal class PackageManager(
    cwd: Path,
    agentDir: Path,
    private val settings: SettingsStore,
    private val projectTrusted: Boolean,
    private val homeDir: Path = defaultHomeDirectory(),
    private val environment: Map<String, String> = System.getenv(),
    private val commandRunner: PackageCommandRunner = ProcessPackageCommandRunner(),
) {
    private val cwd = cwd.toAbsolutePath().normalize()
    private val agentDir = agentDir.toAbsolutePath().normalize()
    private var progressCallback: ((PackageProgressEvent) -> Unit)? = null

    fun setProgressCallback(callback: ((PackageProgressEvent) -> Unit)?) {
        progressCallback = callback
    }

    fun resolve(): ResolvedPackageResources {
        val accumulator = ResourceAccumulator()
        val globalSettings = settings.global()
        val projectSettings = settings.project()
        val configured =
            buildList {
                projectSettings.packages.forEach { add(ScopedPackage(it, PackageScope.PROJECT)) }
                globalSettings.packages.forEach { add(ScopedPackage(it, PackageScope.USER)) }
            }
        resolvePackageSources(dedupePackages(configured), accumulator)

        val globalBaseDir = agentDir
        val projectBaseDir = cwd.resolve(".pi")
        PackageResourceType.entries.forEach { type ->
            resolveLocalEntries(
                entries = projectSettings.resourceEntries(type),
                type = type,
                target = accumulator.target(type),
                sourceInfo =
                    ResourceSourceInfo(
                        path = projectBaseDir,
                        source = "local",
                        scope = "project",
                        origin = "top-level",
                        baseDir = projectBaseDir,
                    ),
                baseDir = projectBaseDir,
            )
            resolveLocalEntries(
                entries = globalSettings.resourceEntries(type),
                type = type,
                target = accumulator.target(type),
                sourceInfo =
                    ResourceSourceInfo(
                        path = globalBaseDir,
                        source = "local",
                        scope = "user",
                        origin = "top-level",
                        baseDir = globalBaseDir,
                    ),
                baseDir = globalBaseDir,
            )
        }
        addAutoDiscoveredResources(
            accumulator = accumulator,
            globalSettings = globalSettings,
            projectSettings = projectSettings,
            globalBaseDir = globalBaseDir,
            projectBaseDir = projectBaseDir,
        )
        return accumulator.toResolved()
    }

    fun resolveSources(
        sources: List<String>,
        local: Boolean = false,
        temporary: Boolean = false,
    ): ResolvedPackageResources {
        val scope =
            when {
                temporary -> PackageScope.TEMPORARY
                local -> PackageScope.PROJECT
                else -> PackageScope.USER
            }
        val accumulator = ResourceAccumulator()
        resolvePackageSources(
            sources.map { source -> ScopedPackage(PackageSourceConfig(source), scope) },
            accumulator,
        )
        return accumulator.toResolved()
    }

    fun listConfiguredPackages(): List<ConfiguredPackage> =
        buildList {
            settings.global().packages.forEach { pkg ->
                add(
                    ConfiguredPackage(
                        source = pkg.source,
                        scope = SettingsScope.USER,
                        filtered = pkg.filtered,
                        installedPath = getInstalledPath(pkg.source, PackageScope.USER),
                    ),
                )
            }
            settings.project().packages.forEach { pkg ->
                add(
                    ConfiguredPackage(
                        source = pkg.source,
                        scope = SettingsScope.PROJECT,
                        filtered = pkg.filtered,
                        installedPath = getInstalledPath(pkg.source, PackageScope.PROJECT),
                    ),
                )
            }
        }

    fun installAndPersist(
        source: String,
        local: Boolean = false,
    ) {
        val scope = if (local) PackageScope.PROJECT else PackageScope.USER
        install(source, scope)
        addSourceToSettings(source, scope)
    }

    fun removeAndPersist(
        source: String,
        local: Boolean = false,
    ): Boolean {
        val scope = if (local) PackageScope.PROJECT else PackageScope.USER
        remove(source, scope)
        return removeSourceFromSettings(source, scope)
    }

    fun update(source: String? = null) {
        if (offlineMode()) {
            return
        }
        val requestedIdentity = source?.let { packageIdentity(it, null) }
        val configured =
            buildList {
                settings.global().packages.forEach { add(ScopedPackage(it, PackageScope.USER)) }
                settings.project().packages.forEach { add(ScopedPackage(it, PackageScope.PROJECT)) }
            }.filter { entry ->
                requestedIdentity == null ||
                    packageIdentity(entry.config.source, entry.scope) == requestedIdentity
            }
        if (source != null && configured.isEmpty()) {
            error(noMatchingPackageMessage(source, settings.global().packages + settings.project().packages))
        }
        val npmUpdates = linkedMapOf<PackageScope, MutableList<ParsedPackageSource.Npm>>()
        val gitUpdates = mutableListOf<Pair<ScopedPackage, ParsedPackageSource.Git>>()
        dedupePackages(configured).forEach { entry ->
            val parsed = parsePackageSource(entry.config.source)
            when (parsed) {
                is ParsedPackageSource.Npm -> {
                    if (!parsed.pinned && shouldUpdateNpm(parsed, entry.scope)) {
                        npmUpdates.getOrPut(entry.scope) { mutableListOf() } += parsed
                    }
                }

                is ParsedPackageSource.Git -> gitUpdates += entry to parsed

                is ParsedPackageSource.Local -> Unit
            }
        }
        npmUpdates.forEach { (scope, packages) ->
            val label =
                if (packages.size == 1) {
                    "npm:${packages.single().spec}"
                } else {
                    "${scope.wireName} npm packages"
                }
            withProgress(
                PackageProgressEvent.Action.UPDATE,
                label,
                "Updating $label...",
            ) {
                installNpmBatch(
                    packages.map { parsed ->
                        if (parsed.version == null) "${parsed.name}@latest" else parsed.spec
                    },
                    scope,
                )
            }
        }
        gitUpdates.forEach { (entry, parsed) ->
            withProgress(
                PackageProgressEvent.Action.UPDATE,
                entry.config.source,
                "Updating ${entry.config.source}...",
            ) {
                installGit(parsed, entry.scope)
            }
        }
    }

    fun checkForAvailableUpdates(): List<PackageUpdate> {
        if (offlineMode()) {
            return emptyList()
        }
        val configured =
            buildList {
                settings.project().packages.forEach { add(ScopedPackage(it, PackageScope.PROJECT)) }
                settings.global().packages.forEach { add(ScopedPackage(it, PackageScope.USER)) }
            }
        return dedupePackages(configured).mapNotNull { entry ->
            val source = entry.config.source
            when (val parsed = parsePackageSource(source)) {
                is ParsedPackageSource.Local -> null
                is ParsedPackageSource.Npm -> {
                    if (parsed.pinned) {
                        return@mapNotNull null
                    }
                    val installedPath = npmInstallPath(parsed, entry.scope)
                    if (!Files.exists(installedPath) || !npmHasAvailableUpdate(parsed, installedPath)) {
                        return@mapNotNull null
                    }
                    PackageUpdate(
                        source = source,
                        displayName = parsed.name,
                        type = PackageUpdate.Type.NPM,
                        scope = entry.scope.toSettingsScope(),
                    )
                }

                is ParsedPackageSource.Git -> {
                    if (parsed.pinned) {
                        return@mapNotNull null
                    }
                    val installedPath = gitInstallPath(parsed, entry.scope)
                    if (!Files.exists(installedPath) || !gitHasAvailableUpdate(installedPath)) {
                        return@mapNotNull null
                    }
                    PackageUpdate(
                        source = source,
                        displayName = "${parsed.host}/${parsed.path}",
                        type = PackageUpdate.Type.GIT,
                        scope = entry.scope.toSettingsScope(),
                    )
                }
            }
        }
    }

    fun addSourceToSettings(
        source: String,
        scope: PackageScope,
    ): Boolean {
        requirePersistentScope(scope)
        val settingsScope = scope.toSettingsScope()
        val current = settings.packages(settingsScope)
        val normalized = normalizePackageSourceForSettings(source, scope)
        val match = current.indexOfFirst { existing -> packageSourcesMatch(existing, source, scope) }
        if (match >= 0) {
            val existing = current[match]
            if (existing.source == normalized) {
                return false
            }
            val updated = current.toMutableList()
            updated[match] = existing.withSource(normalized)
            settings.setPackages(settingsScope, updated)
            return true
        }
        settings.setPackages(settingsScope, current + PackageSourceConfig(normalized))
        return true
    }

    fun removeSourceFromSettings(
        source: String,
        scope: PackageScope,
    ): Boolean {
        requirePersistentScope(scope)
        val settingsScope = scope.toSettingsScope()
        val current = settings.packages(settingsScope)
        val updated = current.filterNot { existing -> packageSourcesMatch(existing, source, scope) }
        if (updated.size == current.size) {
            return false
        }
        settings.setPackages(settingsScope, updated)
        return true
    }

    fun getInstalledPath(
        source: String,
        scope: PackageScope,
    ): Path? =
        plannedInstallPath(source, scope).takeIf(Files::exists)

    internal fun plannedInstallPath(
        source: String,
        scope: PackageScope,
    ): Path =
        when (val parsed = parsePackageSource(source)) {
            is ParsedPackageSource.Npm -> npmInstallPath(parsed, scope)
            is ParsedPackageSource.Git -> gitInstallPath(parsed, scope)
            is ParsedPackageSource.Local -> resolveFromBase(parsed.path, baseDir(scope), homeDir)
        }

    internal fun parsePackageSource(source: String): ParsedPackageSource {
        val trimmed = source.trim()
        if (trimmed.startsWith("npm:")) {
            val spec = trimmed.removePrefix("npm:").trim()
            val match = NPM_SPEC_PATTERN.matchEntire(spec)
            val name = match?.groups?.get(1)?.value ?: spec
            val version = match?.groups?.get(2)?.value
            return ParsedPackageSource.Npm(
                spec = spec,
                name = name,
                version = version,
                pinned = version?.matches(EXACT_NPM_VERSION_PATTERN) == true,
            )
        }
        if (isLocalSource(trimmed)) {
            return ParsedPackageSource.Local(trimmed)
        }
        parseGitSource(trimmed)?.let { return it }
        return ParsedPackageSource.Local(trimmed)
    }

    private fun resolvePackageSources(
        sources: List<ScopedPackage>,
        accumulator: ResourceAccumulator,
    ) {
        sources.forEach { entry ->
            val deltaBase = findAutoloadDeltaBase(entry, sources)
            val resolvedSource = deltaBase?.config?.source ?: entry.config.source
            val resolvedScope = deltaBase?.scope ?: entry.scope
            val parsed = parsePackageSource(resolvedSource)
            val sourceInfo =
                ResourceSourceInfo(
                    path = cwd,
                    source = entry.config.source,
                    scope = entry.scope.wireName,
                    origin = "package",
                )
            when (parsed) {
                is ParsedPackageSource.Local -> {
                    val resolved = resolveFromBase(parsed.path, baseDir(resolvedScope), homeDir)
                    if (!Files.exists(resolved)) {
                        return@forEach
                    }
                    if (Files.isRegularFile(resolved)) {
                        accumulator.add(
                            PackageResourceType.EXTENSIONS,
                            resolved,
                            sourceInfo.copy(path = resolved, baseDir = resolved.parent),
                            enabled = true,
                        )
                    } else if (Files.isDirectory(resolved)) {
                        val metadata = sourceInfo.copy(path = resolved, baseDir = resolved)
                        val found = collectPackageResources(resolved, accumulator, entry.config, metadata)
                        if (!found) {
                            accumulator.add(PackageResourceType.EXTENSIONS, resolved, metadata, enabled = true)
                        }
                    }
                }

                is ParsedPackageSource.Npm -> {
                    var installedPath = npmInstallPath(parsed, resolvedScope)
                    val needsInstall =
                        !Files.exists(installedPath) ||
                            (parsed.pinned && installedNpmVersion(installedPath) != parsed.version)
                    if (needsInstall) {
                        if (offlineMode()) {
                            return@forEach
                        }
                        installNpm(parsed, resolvedScope)
                        installedPath = npmInstallPath(parsed, resolvedScope)
                    }
                    collectPackageResources(
                        packageRoot = installedPath,
                        accumulator = accumulator,
                        filter = entry.config,
                        sourceInfo = sourceInfo.copy(path = installedPath, baseDir = installedPath),
                    )
                }

                is ParsedPackageSource.Git -> {
                    val installedPath = gitInstallPath(parsed, resolvedScope)
                    if (!Files.exists(installedPath)) {
                        if (offlineMode()) {
                            return@forEach
                        }
                        installGit(parsed, resolvedScope)
                    } else if (resolvedScope == PackageScope.TEMPORARY && !parsed.pinned && !offlineMode()) {
                        installGit(parsed, resolvedScope)
                    }
                    collectPackageResources(
                        packageRoot = installedPath,
                        accumulator = accumulator,
                        filter = entry.config,
                        sourceInfo = sourceInfo.copy(path = installedPath, baseDir = installedPath),
                    )
                }
            }
        }
    }

    private fun install(
        source: String,
        scope: PackageScope,
    ) {
        assertProjectTrusted(scope)
        withProgress(PackageProgressEvent.Action.INSTALL, source, "Installing $source...") {
            when (val parsed = parsePackageSource(source)) {
                is ParsedPackageSource.Npm -> installNpm(parsed, scope)
                is ParsedPackageSource.Git -> installGit(parsed, scope)
                is ParsedPackageSource.Local -> {
                    val resolved = resolveFromBase(parsed.path, cwd, homeDir)
                    require(Files.exists(resolved)) { "Path does not exist: $resolved" }
                }
            }
        }
    }

    private fun remove(
        source: String,
        scope: PackageScope,
    ) {
        assertProjectTrusted(scope)
        withProgress(PackageProgressEvent.Action.REMOVE, source, "Removing $source...") {
            when (val parsed = parsePackageSource(source)) {
                is ParsedPackageSource.Npm -> uninstallNpm(parsed, scope)
                is ParsedPackageSource.Git -> {
                    val path = gitInstallPath(parsed, scope)
                    if (Files.exists(path)) {
                        path.toFile().deleteRecursively()
                        pruneEmptyGitParents(path, gitInstallRoot(scope))
                    }
                }

                is ParsedPackageSource.Local -> Unit
            }
        }
    }

    private fun installNpm(
        source: ParsedPackageSource.Npm,
        scope: PackageScope,
    ) = installNpmBatch(listOf(source.spec), scope)

    private fun installNpmBatch(
        specs: List<String>,
        scope: PackageScope,
    ) {
        if (specs.isEmpty()) {
            return
        }
        val root = npmInstallRoot(scope)
        Files.createDirectories(root)
        ensureNpmPackageJson(root)
        val npm = npmCommand()
        val manager = npm.last().substringAfterLast('/').substringBeforeLast('.').lowercase()
        val args =
            when (manager) {
                "bun" ->
                    npm.dropLast(1) +
                        listOf(npm.last(), "install") +
                        specs +
                        listOf("--cwd", root.toString(), "--omit=peer")

                "pnpm" ->
                    npm.dropLast(1) +
                        listOf(
                            npm.last(),
                            "install",
                        ) +
                        specs +
                        listOf(
                            "--prefix",
                            root.toString(),
                            "--config.auto-install-peers=false",
                            "--config.strict-peer-dependencies=false",
                            "--config.strict-dep-builds=false",
                        )

                else ->
                    npm.dropLast(1) + listOf(npm.last(), "install") + specs +
                        listOf("--prefix", root.toString(), "--legacy-peer-deps")
            }
        commandRunner.run(args, cwd = null, environment = environment, timeoutSeconds = PACKAGE_COMMAND_TIMEOUT_SECONDS)
    }

    private fun uninstallNpm(
        source: ParsedPackageSource.Npm,
        scope: PackageScope,
    ) {
        val root = npmInstallRoot(scope)
        if (!Files.exists(root)) {
            return
        }
        val npm = npmCommand()
        val manager = npm.last().substringAfterLast('/').substringBeforeLast('.').lowercase()
        val args =
            when (manager) {
                "bun" -> npm.dropLast(1) + listOf(npm.last(), "remove", source.name, "--cwd", root.toString())
                "pnpm" -> npm.dropLast(1) + listOf(npm.last(), "remove", source.name, "--prefix", root.toString())
                else ->
                    npm.dropLast(1) +
                        listOf(npm.last(), "uninstall", source.name, "--prefix", root.toString(), "--legacy-peer-deps")
            }
        commandRunner.run(args, cwd = null, environment = environment, timeoutSeconds = PACKAGE_COMMAND_TIMEOUT_SECONDS)
    }

    private fun installGit(
        source: ParsedPackageSource.Git,
        scope: PackageScope,
    ) {
        val target = gitInstallPath(source, scope)
        Files.createDirectories(target.parent)
        val checkoutExisted = Files.exists(target.resolve(".git"))
        if (!checkoutExisted) {
            if (Files.exists(target)) {
                target.toFile().deleteRecursively()
            }
        }
        try {
            if (!checkoutExisted) {
                commandRunner.run(
                    listOf("git", "clone", source.repo, target.toString()),
                    cwd = null,
                    environment = environment + ("GIT_TERMINAL_PROMPT" to "0"),
                    timeoutSeconds = PACKAGE_COMMAND_TIMEOUT_SECONDS,
                )
            }
            if (source.ref != null) {
                reconcileGitRef(
                    target = target,
                    fetchArguments = listOf("fetch", "origin", source.ref),
                    ref = "FETCH_HEAD",
                )
            } else if (checkoutExisted) {
                val updateTarget = localGitUpdateTarget(target)
                reconcileGitRef(
                    target = target,
                    fetchArguments = updateTarget.fetchArguments,
                    ref = updateTarget.ref,
                )
            } else {
                installGitDependencies(target)
            }
        } catch (error: Exception) {
            if (!checkoutExisted) {
                target.toFile().deleteRecursively()
                pruneEmptyGitParents(target, gitInstallRoot(scope))
            }
            throw error
        }
    }

    private fun localGitUpdateTarget(target: Path): GitUpdateTarget =
        runCatching {
            val upstream =
                commandRunner
                    .run(
                        listOf("git", "rev-parse", "--abbrev-ref", "@{upstream}"),
                        cwd = target,
                        environment = environment,
                        timeoutSeconds = NETWORK_COMMAND_TIMEOUT_SECONDS,
                    ).trim()
            require(upstream.startsWith("origin/")) { "Unsupported upstream remote: $upstream" }
            val branch = upstream.removePrefix("origin/")
            require(branch.isNotBlank()) { "Missing upstream branch name" }
            GitUpdateTarget(
                ref = "@{upstream}",
                fetchArguments =
                    listOf(
                        "fetch",
                        "--prune",
                        "--no-tags",
                        "origin",
                        "+refs/heads/$branch:refs/remotes/origin/$branch",
                    ),
            )
        }.getOrElse {
            runCatching {
                commandRunner.run(
                    listOf("git", "remote", "set-head", "origin", "-a"),
                    cwd = target,
                    environment = environment + ("GIT_TERMINAL_PROMPT" to "0"),
                    timeoutSeconds = NETWORK_COMMAND_TIMEOUT_SECONDS,
                )
            }
            val symbolic =
                runCatching {
                    commandRunner
                        .run(
                            listOf("git", "symbolic-ref", "refs/remotes/origin/HEAD"),
                            cwd = target,
                            environment = environment,
                            timeoutSeconds = NETWORK_COMMAND_TIMEOUT_SECONDS,
                        ).trim()
                }.getOrDefault("")
            val branch = symbolic.removePrefix("refs/remotes/origin/").takeIf(String::isNotBlank)
            GitUpdateTarget(
                ref = "origin/HEAD",
                fetchArguments =
                    if (branch == null) {
                        listOf("fetch", "--prune", "--no-tags", "origin", "+HEAD:refs/remotes/origin/HEAD")
                    } else {
                        listOf(
                            "fetch",
                            "--prune",
                            "--no-tags",
                            "origin",
                            "+refs/heads/$branch:refs/remotes/origin/$branch",
                        )
                    },
            )
        }

    private fun reconcileGitRef(
        target: Path,
        fetchArguments: List<String>,
        ref: String,
    ) {
        commandRunner.run(
            listOf("git") + fetchArguments,
            cwd = target,
            environment = environment + ("GIT_TERMINAL_PROMPT" to "0"),
            timeoutSeconds = PACKAGE_COMMAND_TIMEOUT_SECONDS,
        )
        val localHead =
            commandRunner
                .run(
                    listOf("git", "rev-parse", "HEAD"),
                    cwd = target,
                    environment = environment,
                    timeoutSeconds = NETWORK_COMMAND_TIMEOUT_SECONDS,
                ).trim()
        val commitRef = "$ref^{commit}"
        val targetHead =
            commandRunner
                .run(
                    listOf("git", "rev-parse", commitRef),
                    cwd = target,
                    environment = environment,
                    timeoutSeconds = NETWORK_COMMAND_TIMEOUT_SECONDS,
                ).trim()
        if (localHead == targetHead) {
            return
        }
        commandRunner.run(
            listOf("git", "reset", "--hard", commitRef),
            cwd = target,
            environment = environment,
            timeoutSeconds = PACKAGE_COMMAND_TIMEOUT_SECONDS,
        )
        commandRunner.run(
            listOf("git", "clean", "-fdx"),
            cwd = target,
            environment = environment,
            timeoutSeconds = PACKAGE_COMMAND_TIMEOUT_SECONDS,
        )
        installGitDependencies(target)
    }

    private fun installGitDependencies(target: Path) {
        if (!Files.exists(target.resolve("package.json"))) {
            return
        }
        val npm = npmCommand()
        val manager = npm.last().substringAfterLast('/').substringBeforeLast('.').lowercase()
        val configuredNpmCommand = settings.project().npmCommand ?: settings.global().npmCommand
        val args =
            if (configuredNpmCommand == null && manager == "npm") {
                npm.dropLast(1) + listOf(npm.last(), "install", "--omit=dev")
            } else {
                npm.dropLast(1) + listOf(npm.last(), "install")
            }
        commandRunner.run(args, cwd = target, environment = environment, timeoutSeconds = PACKAGE_COMMAND_TIMEOUT_SECONDS)
    }

    private fun collectPackageResources(
        packageRoot: Path,
        accumulator: ResourceAccumulator,
        filter: PackageSourceConfig,
        sourceInfo: ResourceSourceInfo,
    ): Boolean {
        if (filter.objectForm) {
            PackageResourceType.entries.forEach { type ->
                val patterns = filter.patterns(type)
                when {
                    filter.autoload == false ->
                        applyPackageDeltaFilter(packageRoot, patterns.orEmpty(), type, accumulator, sourceInfo)

                    patterns != null ->
                        applyPackageFilter(packageRoot, patterns, type, accumulator, sourceInfo)

                    else ->
                        collectDefaultResources(packageRoot, type, accumulator, sourceInfo)
                }
            }
            return true
        }
        val manifest = readManifest(packageRoot)
        if (manifest != null) {
            PackageResourceType.entries.forEach { type ->
                addManifestEntries(manifest[type], packageRoot, type, accumulator, sourceInfo)
            }
            return true
        }
        var found = false
        PackageResourceType.entries.forEach { type ->
            val directory = packageRoot.resolve(type.settingsKey)
            if (Files.exists(directory)) {
                collectResourceFiles(directory, type).forEach { path ->
                    accumulator.add(type, path, sourceInfo.copy(path = path), enabled = true)
                }
                found = true
            }
        }
        return found
    }

    private fun collectDefaultResources(
        packageRoot: Path,
        type: PackageResourceType,
        accumulator: ResourceAccumulator,
        sourceInfo: ResourceSourceInfo,
    ) {
        val manifest = readManifest(packageRoot)
        val entries = manifest?.get(type)
        if (entries != null) {
            addManifestEntries(entries, packageRoot, type, accumulator, sourceInfo)
            return
        }
        val directory = packageRoot.resolve(type.settingsKey)
        if (Files.exists(directory)) {
            collectResourceFiles(directory, type).forEach { path ->
                accumulator.add(type, path, sourceInfo.copy(path = path), enabled = true)
            }
        }
    }

    private fun applyPackageFilter(
        packageRoot: Path,
        patterns: List<String>,
        type: PackageResourceType,
        accumulator: ResourceAccumulator,
        sourceInfo: ResourceSourceInfo,
    ) {
        val allFiles = collectManifestFiles(packageRoot, type)
        val enabled = if (patterns.isEmpty()) emptySet() else applyPatterns(allFiles, patterns, packageRoot)
        allFiles.forEach { path ->
            accumulator.add(type, path, sourceInfo.copy(path = path), path in enabled)
        }
    }

    private fun applyPackageDeltaFilter(
        packageRoot: Path,
        patterns: List<String>,
        type: PackageResourceType,
        accumulator: ResourceAccumulator,
        sourceInfo: ResourceSourceInfo,
    ) {
        if (patterns.isEmpty()) {
            return
        }
        val allFiles = collectManifestFiles(packageRoot, type)
        applyAutoloadDisabledPatterns(allFiles, patterns, packageRoot).forEach { (path, enabled) ->
            accumulator.add(type, path, sourceInfo.copy(path = path), enabled)
        }
    }

    private fun collectManifestFiles(
        packageRoot: Path,
        type: PackageResourceType,
    ): List<Path> {
        val entries = readManifest(packageRoot)?.get(type)
        if (!entries.isNullOrEmpty()) {
            val allFiles = collectFilesFromManifestEntries(entries, packageRoot, type)
            val overrides = entries.filter(::isOverridePattern)
            return if (overrides.isEmpty()) {
                allFiles
            } else {
                applyPatterns(allFiles, overrides, packageRoot).toList()
            }
        }
        val directory = packageRoot.resolve(type.settingsKey)
        return if (Files.exists(directory)) collectResourceFiles(directory, type) else emptyList()
    }

    private fun addManifestEntries(
        entries: List<String>?,
        packageRoot: Path,
        type: PackageResourceType,
        accumulator: ResourceAccumulator,
        sourceInfo: ResourceSourceInfo,
    ) {
        if (entries == null) {
            return
        }
        val allFiles = collectFilesFromManifestEntries(entries, packageRoot, type)
        val enabled = applyPatterns(allFiles, entries.filter(::isOverridePattern), packageRoot)
        allFiles.filter { it in enabled }.forEach { path ->
            accumulator.add(type, path, sourceInfo.copy(path = path), enabled = true)
        }
    }

    private fun collectFilesFromManifestEntries(
        entries: List<String>,
        packageRoot: Path,
        type: PackageResourceType,
    ): List<Path> {
        val sourceEntries = entries.filterNot(::isOverridePattern)
        val paths =
            sourceEntries.flatMap { entry ->
                if (entry.contains('*') || entry.contains('?')) {
                    walkVisible(packageRoot)
                        .filter { candidate ->
                            globMatches(entry.replace('\\', '/'), packageRoot.relativize(candidate).toString().replace('\\', '/'))
                        }.sortedByDescending { candidate ->
                            packageRoot.relativize(candidate).toString().replace('\\', '/')
                        }.toList()
                } else {
                    listOf(resolveManifestPath(entry, packageRoot))
                }
            }
        return collectFilesFromPaths(paths, type)
    }

    private fun resolveLocalEntries(
        entries: List<String>,
        type: PackageResourceType,
        target: LinkedHashMap<Path, ResourceValue>,
        sourceInfo: ResourceSourceInfo,
        baseDir: Path,
    ) {
        if (entries.isEmpty()) {
            return
        }
        val plain = entries.filterNot(::isPattern)
        val patterns = entries.filter(::isPattern)
        val files = collectFilesFromPaths(plain.map { resolveFromBase(it, baseDir) }, type)
        val enabled = applyPatterns(files, patterns, baseDir)
        files.forEach { path ->
            addResource(target, path, sourceInfo.copy(path = path), path in enabled)
        }
    }

    private fun addAutoDiscoveredResources(
        accumulator: ResourceAccumulator,
        globalSettings: SettingsSnapshot,
        projectSettings: SettingsSnapshot,
        globalBaseDir: Path,
        projectBaseDir: Path,
    ) {
        val projectInfo =
            ResourceSourceInfo(
                path = projectBaseDir,
                source = "auto",
                scope = "project",
                origin = "top-level",
                baseDir = projectBaseDir,
            )
        val userInfo =
            ResourceSourceInfo(
                path = globalBaseDir,
                source = "auto",
                scope = "user",
                origin = "top-level",
                baseDir = globalBaseDir,
            )
        if (projectTrusted) {
            addAuto(
                accumulator,
                PackageResourceType.EXTENSIONS,
                collectAutoExtensions(projectBaseDir.resolve("extensions")),
                projectInfo,
                projectSettings.extensions,
                projectBaseDir,
            )
            addAuto(
                accumulator,
                PackageResourceType.SKILLS,
                collectSkillEntries(projectBaseDir.resolve("skills"), includeRootMarkdown = true),
                projectInfo,
                projectSettings.skills,
                projectBaseDir,
            )
            collectAncestorAgentsSkillDirectories(cwd).forEach { directory ->
                val userAgents = canonicalPath(homeDir.resolve(".agents").resolve("skills"))
                if (canonicalPath(directory) != userAgents) {
                    val baseDir = directory.parent
                    addAuto(
                        accumulator,
                        PackageResourceType.SKILLS,
                        collectSkillEntries(directory, includeRootMarkdown = false),
                        projectInfo.copy(baseDir = baseDir),
                        projectSettings.skills,
                        baseDir,
                    )
                }
            }
            addAuto(
                accumulator,
                PackageResourceType.PROMPTS,
                collectAutoPrompts(projectBaseDir.resolve("prompts")),
                projectInfo,
                projectSettings.prompts,
                projectBaseDir,
            )
            addAuto(
                accumulator,
                PackageResourceType.THEMES,
                collectAutoThemes(projectBaseDir.resolve("themes")),
                projectInfo,
                projectSettings.themes,
                projectBaseDir,
            )
        }
        addAuto(
            accumulator,
            PackageResourceType.EXTENSIONS,
            collectAutoExtensions(globalBaseDir.resolve("extensions")),
            userInfo,
            globalSettings.extensions,
            globalBaseDir,
        )
        addAuto(
            accumulator,
            PackageResourceType.SKILLS,
            collectSkillEntries(globalBaseDir.resolve("skills"), includeRootMarkdown = true),
            userInfo,
            globalSettings.skills,
            globalBaseDir,
        )
        val userAgentsBase = homeDir.resolve(".agents")
        addAuto(
            accumulator,
            PackageResourceType.SKILLS,
            collectSkillEntries(userAgentsBase.resolve("skills"), includeRootMarkdown = false),
            userInfo.copy(baseDir = userAgentsBase),
            globalSettings.skills,
            userAgentsBase,
        )
        addAuto(
            accumulator,
            PackageResourceType.PROMPTS,
            collectAutoPrompts(globalBaseDir.resolve("prompts")),
            userInfo,
            globalSettings.prompts,
            globalBaseDir,
        )
        addAuto(
            accumulator,
            PackageResourceType.THEMES,
            collectAutoThemes(globalBaseDir.resolve("themes")),
            userInfo,
            globalSettings.themes,
            globalBaseDir,
        )
    }

    private fun addAuto(
        accumulator: ResourceAccumulator,
        type: PackageResourceType,
        paths: List<Path>,
        sourceInfo: ResourceSourceInfo,
        overrides: List<String>,
        baseDir: Path,
    ) {
        paths.forEach { path ->
            accumulator.add(
                type,
                path,
                sourceInfo.copy(path = path),
                isEnabledByOverrides(path, overrides, baseDir),
            )
        }
    }

    private fun dedupePackages(packages: List<ScopedPackage>): List<ScopedPackage> {
        val result = mutableListOf<ScopedPackage>()
        val seen = mutableMapOf<String, Int>()
        packages.forEach { entry ->
            val identity = packageIdentity(entry.config.source, entry.scope)
            val index = seen[identity]
            if (index == null) {
                seen[identity] = result.size
                result += entry
            } else {
                val existing = result[index]
                if (existing.scope == PackageScope.PROJECT && entry.scope == PackageScope.USER) {
                    if (existing.config.objectForm && existing.config.autoload == false) {
                        result += entry
                    }
                } else if (entry.scope == PackageScope.PROJECT) {
                    result[index] = entry
                }
            }
        }
        return result
    }

    private fun findAutoloadDeltaBase(
        entry: ScopedPackage,
        sources: List<ScopedPackage>,
    ): ScopedPackage? {
        if (
            entry.scope != PackageScope.PROJECT ||
            !entry.config.objectForm ||
            entry.config.autoload != false
        ) {
            return null
        }
        val identity = packageIdentity(entry.config.source, entry.scope)
        return sources.firstOrNull { candidate ->
            candidate.scope == PackageScope.USER &&
                packageIdentity(candidate.config.source, candidate.scope) == identity
        }
    }

    private fun packageSourcesMatch(
        existing: PackageSourceConfig,
        input: String,
        scope: PackageScope,
    ): Boolean =
        packageIdentity(existing.source, scope) == packageIdentity(input, null)

    private fun packageIdentity(
        source: String,
        scope: PackageScope?,
    ): String =
        when (val parsed = parsePackageSource(source)) {
            is ParsedPackageSource.Npm -> "npm:${parsed.name}"
            is ParsedPackageSource.Git -> "git:${parsed.host}/${parsed.path}"
            is ParsedPackageSource.Local -> {
                val resolved =
                    if (scope == null) {
                        resolveFromBase(parsed.path, cwd, homeDir)
                    } else {
                        resolveFromBase(parsed.path, baseDir(scope), homeDir)
                    }
                "local:${canonicalPath(resolved)}"
            }
        }

    private fun normalizePackageSourceForSettings(
        source: String,
        scope: PackageScope,
    ): String {
        val parsed = parsePackageSource(source)
        if (parsed !is ParsedPackageSource.Local) {
            return source
        }
        val baseDir = baseDir(scope)
        val resolved = resolveFromBase(parsed.path, cwd, homeDir)
        val relative = baseDir.relativize(resolved).toString()
        return relative.ifEmpty { "." }
    }

    private fun npmInstallRoot(scope: PackageScope): Path =
        when (scope) {
            PackageScope.USER -> agentDir.resolve("npm")
            PackageScope.PROJECT -> {
                assertProjectTrusted(scope)
                cwd.resolve(".pi").resolve("npm")
            }

            PackageScope.TEMPORARY -> temporaryDirectory("npm")
        }

    private fun npmInstallPath(
        source: ParsedPackageSource.Npm,
        scope: PackageScope,
    ): Path {
        val managed = managedNpmInstallPath(source, scope)
        if (scope != PackageScope.USER || Files.exists(managed)) {
            return managed
        }
        val legacy = legacyGlobalNpmInstallPath(source)
        return legacy?.takeIf(Files::exists) ?: managed
    }

    private fun managedNpmInstallPath(
        source: ParsedPackageSource.Npm,
        scope: PackageScope,
    ): Path = managedPath(npmInstallRoot(scope), "node_modules", source.name)

    private fun legacyGlobalNpmInstallPath(source: ParsedPackageSource.Npm): Path? =
        runCatching {
            val npm = npmCommand()
            val manager = npm.last().substringAfterLast('/').substringBeforeLast('.').lowercase()
            if (manager == "pnpm") {
                val output =
                    commandRunner.run(
                        npm + listOf("list", "-g", "--depth", "0", "--json"),
                        cwd = null,
                        environment = environment,
                        timeoutSeconds = NETWORK_COMMAND_TIMEOUT_SECONDS,
                    )
                parsePnpmGlobalPackagePath(output, source.name)?.let(Path::of)
            } else {
                val command =
                    if (manager == "bun") {
                        npm + listOf("pm", "bin", "-g")
                    } else {
                        npm + listOf("root", "-g")
                    }
                val output =
                    commandRunner.run(
                        command,
                        cwd = null,
                        environment = environment,
                        timeoutSeconds = NETWORK_COMMAND_TIMEOUT_SECONDS,
                    )
                val root =
                    if (manager == "bun") {
                        Path.of(output.trim()).parent.resolve("install").resolve("global").resolve("node_modules")
                    } else {
                        Path.of(output.trim())
                    }
                root.resolve(source.name)
            }
        }.getOrNull()

    private fun gitInstallPath(
        source: ParsedPackageSource.Git,
        scope: PackageScope,
    ): Path {
        val root =
            when (scope) {
                PackageScope.USER -> agentDir.resolve("git")
                PackageScope.PROJECT -> {
                    assertProjectTrusted(scope)
                    cwd.resolve(".pi").resolve("git")
                }

                PackageScope.TEMPORARY -> return temporaryDirectory("git-${source.host}", source.path)
            }
        return managedPath(root, source.host, source.path)
    }

    private fun gitInstallRoot(scope: PackageScope): Path =
        when (scope) {
            PackageScope.USER -> agentDir.resolve("git")
            PackageScope.PROJECT -> {
                assertProjectTrusted(scope)
                cwd.resolve(".pi").resolve("git")
            }

            PackageScope.TEMPORARY -> agentDir.resolve("tmp").resolve("extensions")
        }.toAbsolutePath().normalize()

    private fun pruneEmptyGitParents(
        target: Path,
        installRoot: Path,
    ) {
        var current = target.toAbsolutePath().normalize().parent
        while (current != null && current.startsWith(installRoot) && current != installRoot) {
            if (Files.exists(current)) {
                val empty = Files.list(current).use { entries -> entries.findAny().isEmpty }
                if (!empty) {
                    break
                }
                Files.deleteIfExists(current)
            }
            current = current.parent
        }
    }

    private fun temporaryDirectory(
        prefix: String,
        suffix: String? = null,
    ): Path {
        val base = agentDir.resolve("tmp").resolve("extensions")
        Files.createDirectories(base)
        runCatching {
            Files.setPosixFilePermissions(
                base,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
        val root = managedPath(base, prefix)
        val hash =
            MessageDigest
                .getInstance("SHA-256")
                .digest("$prefix-${suffix.orEmpty()}".toByteArray())
                .take(4)
                .joinToString("") { byte -> "%02x".format(byte) }
        return managedPath(root, hash, suffix.orEmpty())
    }

    private fun managedPath(
        root: Path,
        vararg parts: String,
    ): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val result = parts.fold(normalizedRoot) { current, part -> current.resolve(part) }.normalize()
        require(result == normalizedRoot || result.startsWith(normalizedRoot)) {
            "Refusing to use path outside package install root: $result"
        }
        return result
    }

    private fun baseDir(scope: PackageScope): Path =
        when (scope) {
            PackageScope.USER -> agentDir
            PackageScope.PROJECT -> {
                assertProjectTrusted(scope)
                cwd.resolve(".pi")
            }

            PackageScope.TEMPORARY -> cwd
        }

    private fun assertProjectTrusted(scope: PackageScope) {
        require(scope != PackageScope.PROJECT || projectTrusted) {
            "Project is not trusted; refusing to access project package storage"
        }
    }

    private fun requirePersistentScope(scope: PackageScope) {
        require(scope != PackageScope.TEMPORARY) { "Temporary package sources cannot be persisted" }
        assertProjectTrusted(scope)
    }

    private fun PackageScope.toSettingsScope(): SettingsScope =
        when (this) {
            PackageScope.USER -> SettingsScope.USER
            PackageScope.PROJECT -> SettingsScope.PROJECT
            PackageScope.TEMPORARY -> error("Temporary package scope has no settings file")
        }

    private fun npmCommand(): List<String> =
        (
            settings.project().npmCommand
                ?: settings.global().npmCommand
        )?.takeIf(List<String>::isNotEmpty) ?: listOf("npm")

    private fun ensureNpmPackageJson(root: Path) {
        val ignore = root.resolve(".gitignore")
        if (!Files.exists(ignore)) {
            Files.writeString(ignore, "*\n!.gitignore\n", StandardOpenOption.CREATE_NEW)
        }
        val packageJson = root.resolve("package.json")
        if (!Files.exists(packageJson)) {
            Files.writeString(
                packageJson,
                "{\n  \"name\": \"pi-extensions\",\n  \"private\": true\n}\n",
                StandardOpenOption.CREATE_NEW,
            )
        }
    }

    private fun installedNpmVersion(path: Path): String? =
        runCatching {
            packageJson
                .parseToJsonElement(Files.readString(path.resolve("package.json")))
                .jsonObject["version"]
                ?.jsonPrimitive
                ?.content
        }.getOrNull()

    private fun shouldUpdateNpm(
        source: ParsedPackageSource.Npm,
        scope: PackageScope,
    ): Boolean {
        val installedPath = managedNpmInstallPath(source, scope)
        val installedVersion = installedNpmVersion(installedPath) ?: return true
        return runCatching { latestNpmVersion(source) != installedVersion }.getOrDefault(true)
    }

    private fun npmHasAvailableUpdate(
        source: ParsedPackageSource.Npm,
        installedPath: Path,
    ): Boolean {
        val installedVersion = installedNpmVersion(installedPath) ?: return false
        return runCatching { latestNpmVersion(source) != installedVersion }.getOrDefault(false)
    }

    private fun latestNpmVersion(source: ParsedPackageSource.Npm): String {
        val npm = npmCommand()
        val packageSpec = if (source.version == null) source.name else source.spec
        val output =
            commandRunner.run(
                npm + listOf("view", packageSpec, "version", "--json"),
                cwd = cwd,
                environment = environment,
                timeoutSeconds = NETWORK_COMMAND_TIMEOUT_SECONDS,
            )
        val parsed = packageJson.parseToJsonElement(output)
        return when (parsed) {
            is JsonPrimitive ->
                parsed.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?: error("Unexpected response from npm view")

            is JsonArray ->
                parsed
                    .mapNotNull { value -> value.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
                    .maxWithOrNull(Comparator(::comparePackageVersions))
                    ?: error("Unexpected response from npm view")

            else -> error("Unexpected response from npm view")
        }
    }

    private fun gitHasAvailableUpdate(installedPath: Path): Boolean =
        runCatching {
            val local =
                commandRunner.run(
                    listOf("git", "rev-parse", "HEAD"),
                    cwd = installedPath,
                    environment = environment,
                    timeoutSeconds = NETWORK_COMMAND_TIMEOUT_SECONDS,
                )
            val upstream =
                commandRunner
                    .run(
                        listOf("git", "rev-parse", "--abbrev-ref", "@{upstream}"),
                        cwd = installedPath,
                        environment = environment,
                        timeoutSeconds = NETWORK_COMMAND_TIMEOUT_SECONDS,
                    ).trim()
            val remoteRef = upstream.removePrefix("origin/").takeIf(String::isNotBlank) ?: "HEAD"
            val remote =
                commandRunner.run(
                    listOf("git", "ls-remote", "origin", remoteRef),
                    cwd = installedPath,
                    environment = environment + ("GIT_TERMINAL_PROMPT" to "0"),
                    timeoutSeconds = NETWORK_COMMAND_TIMEOUT_SECONDS,
                )
            val remoteHead = remote.lineSequence().firstNotNullOfOrNull { line -> GIT_HEAD_PATTERN.find(line)?.groupValues?.get(1) }
                ?: error("Failed to determine remote HEAD")
            local.trim() != remoteHead
        }.getOrDefault(false)

    private fun noMatchingPackageMessage(
        source: String,
        configured: List<PackageSourceConfig>,
    ): String {
        val trimmed = source.trim()
        val suggestion =
            configured
                .asSequence()
                .map(PackageSourceConfig::source)
                .firstOrNull { candidate ->
                    when (val parsed = parsePackageSource(candidate)) {
                        is ParsedPackageSource.Npm -> trimmed == parsed.name || trimmed == parsed.spec
                        is ParsedPackageSource.Git ->
                            trimmed == "${parsed.host}/${parsed.path}" ||
                                (
                                    parsed.ref != null &&
                                        trimmed == "${parsed.host}/${parsed.path}@${parsed.ref}"
                                )

                        is ParsedPackageSource.Local -> false
                    }
                }
        return if (suggestion == null) {
            "No matching package found for $source"
        } else {
            "No matching package found for $source. Did you mean $suggestion?"
        }
    }

    private fun offlineMode(): Boolean =
        environment["PI_OFFLINE"]
            ?.trim()
            ?.lowercase()
            ?.let { it == "1" || it == "true" || it == "yes" }
            ?: false

    private fun withProgress(
        action: PackageProgressEvent.Action,
        source: String,
        message: String,
        operation: () -> Unit,
    ) {
        progressCallback?.invoke(PackageProgressEvent(PackageProgressEvent.Type.START, action, source, message))
        try {
            operation()
            progressCallback?.invoke(PackageProgressEvent(PackageProgressEvent.Type.COMPLETE, action, source))
        } catch (error: Exception) {
            progressCallback?.invoke(
                PackageProgressEvent(
                    PackageProgressEvent.Type.ERROR,
                    action,
                    source,
                    error.message,
                ),
            )
            throw error
        }
    }
}

internal sealed interface ParsedPackageSource {
    data class Npm(
        val spec: String,
        val name: String,
        val version: String?,
        val pinned: Boolean,
    ) : ParsedPackageSource

    data class Git(
        val repo: String,
        val host: String,
        val path: String,
        val ref: String?,
    ) : ParsedPackageSource {
        val pinned: Boolean
            get() = ref != null
    }

    data class Local(
        val path: String,
    ) : ParsedPackageSource
}

private data class GitUpdateTarget(
    val ref: String,
    val fetchArguments: List<String>,
)

internal interface PackageCommandRunner {
    fun run(
        command: List<String>,
        cwd: Path?,
        environment: Map<String, String>,
        timeoutSeconds: Long,
    ): String
}

private class ProcessPackageCommandRunner : PackageCommandRunner {
    override fun run(
        command: List<String>,
        cwd: Path?,
        environment: Map<String, String>,
        timeoutSeconds: Long,
    ): String {
        return runPackageProcess(command, cwd, environment, timeoutSeconds)
    }
}

internal fun runPackageProcess(
    command: List<String>,
    cwd: Path?,
    environment: Map<String, String>,
    timeoutSeconds: Long,
): String {
    require(command.isNotEmpty()) { "Package command cannot be empty" }
    val process =
        ProcessBuilder(command)
            .apply {
                if (cwd != null) {
                    directory(cwd.toFile())
                }
                environment().putAll(environment)
                redirectErrorStream(true)
            }.start()
    val outputBytes = ByteArrayOutputStream()
    val outputThread =
        Thread {
            process.inputStream.use { input -> input.copyTo(outputBytes) }
        }.apply {
            isDaemon = true
            start()
        }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        outputThread.join(1_000)
        error("${command.joinToString(" ")} timed out after ${timeoutSeconds}s")
    }
    outputThread.join()
    val output = outputBytes.toString(Charsets.UTF_8)
    check(process.exitValue() == 0) {
        "${command.joinToString(" ")} failed with ${process.exitValue()}: ${output.trim()}"
    }
    return output.trim()
}

private data class ScopedPackage(
    val config: PackageSourceConfig,
    val scope: PackageScope,
)

private data class ResourceValue(
    val sourceInfo: ResourceSourceInfo,
    val enabled: Boolean,
)

private class ResourceAccumulator {
    private val extensions = linkedMapOf<Path, ResourceValue>()
    private val skills = linkedMapOf<Path, ResourceValue>()
    private val prompts = linkedMapOf<Path, ResourceValue>()
    private val themes = linkedMapOf<Path, ResourceValue>()

    fun target(type: PackageResourceType): LinkedHashMap<Path, ResourceValue> =
        when (type) {
            PackageResourceType.EXTENSIONS -> extensions
            PackageResourceType.SKILLS -> skills
            PackageResourceType.PROMPTS -> prompts
            PackageResourceType.THEMES -> themes
        }

    fun add(
        type: PackageResourceType,
        path: Path,
        sourceInfo: ResourceSourceInfo,
        enabled: Boolean,
    ) {
        addResource(target(type), path, sourceInfo, enabled)
    }

    fun toResolved(): ResolvedPackageResources =
        ResolvedPackageResources(
            extensions = resolveResources(extensions),
            skills = resolveResources(skills),
            prompts = resolveResources(prompts),
            themes = resolveResources(themes),
        )
}

private fun addResource(
    target: LinkedHashMap<Path, ResourceValue>,
    path: Path,
    sourceInfo: ResourceSourceInfo,
    enabled: Boolean,
) {
    val normalized = path.toAbsolutePath().normalize()
    target.putIfAbsent(normalized, ResourceValue(sourceInfo.copy(path = normalized), enabled))
}

private fun resolveResources(entries: LinkedHashMap<Path, ResourceValue>): List<ResolvedResource> {
    val seen = mutableSetOf<Path>()
    return entries
        .map { (path, value) -> ResolvedResource(path, value.enabled, value.sourceInfo) }
        .sortedBy { resourcePrecedence(it.sourceInfo) }
        .filter { resource -> seen.add(canonicalPath(resource.path)) }
}

private fun resourcePrecedence(info: ResourceSourceInfo): Int {
    if (info.origin == "package") {
        return 4
    }
    val scopeBase = if (info.scope == "project") 0 else 2
    return scopeBase + if (info.source == "local") 0 else 1
}

private fun collectFilesFromPaths(
    paths: List<Path>,
    type: PackageResourceType,
): List<Path> =
    paths.flatMap { path ->
        when {
            Files.isRegularFile(path) -> listOf(path.toAbsolutePath().normalize())
            Files.isDirectory(path) -> collectResourceFiles(path, type)
            else -> emptyList()
        }
    }.distinct()

private fun collectResourceFiles(
    directory: Path,
    type: PackageResourceType,
): List<Path> =
    when (type) {
        PackageResourceType.SKILLS -> collectSkillEntries(directory, includeRootMarkdown = true)
        PackageResourceType.EXTENSIONS -> collectAutoExtensions(directory)
        PackageResourceType.PROMPTS ->
            collectFiles(
                directory = directory,
                matcher = { path -> path.fileName.toString().endsWith(".md") },
            )

        PackageResourceType.THEMES ->
            collectFiles(
                directory = directory,
                matcher = { path -> path.fileName.toString().endsWith(".json") },
            )
    }

private fun collectFiles(
    directory: Path,
    matcher: (Path) -> Boolean,
    root: Path = directory,
    inheritedRules: List<PackageIgnoreRule> = emptyList(),
    visited: MutableSet<Path> = mutableSetOf(),
): List<Path> {
    if (!Files.isDirectory(directory) || !visited.add(canonicalPath(directory))) {
        return emptyList()
    }
    val rules = inheritedRules + readPackageIgnoreRules(directory, root)
    val result = mutableListOf<Path>()
    listDirectory(directory).forEach { path ->
        val name = path.fileName.toString()
        if (name.startsWith(".") || name == "node_modules") {
            return@forEach
        }
        val isDirectory = Files.isDirectory(path)
        if (isPackageIgnored(root, path, isDirectory, rules)) {
            return@forEach
        }
        if (isDirectory) {
            result.addAll(collectFiles(path, matcher, root, rules, visited))
        } else if (Files.isRegularFile(path) && matcher(path)) {
            result.add(path.toAbsolutePath().normalize())
        }
    }
    return result
}

private fun collectSkillEntries(
    directory: Path,
    includeRootMarkdown: Boolean,
    root: Path = directory,
    inheritedRules: List<PackageIgnoreRule> = emptyList(),
    visited: MutableSet<Path> = mutableSetOf(),
): List<Path> {
    if (!Files.isDirectory(directory) || !visited.add(canonicalPath(directory))) {
        return emptyList()
    }
    val rules = inheritedRules + readPackageIgnoreRules(directory, root)
    val entries = listDirectory(directory)
    entries.firstOrNull { path ->
        path.fileName.toString() == "SKILL.md" &&
            Files.isRegularFile(path) &&
            !isPackageIgnored(root, path, false, rules)
    }?.let { return listOf(it.toAbsolutePath().normalize()) }

    val result = mutableListOf<Path>()
    entries.forEach { path ->
        val name = path.fileName.toString()
        if (name.startsWith(".") || name == "node_modules") {
            return@forEach
        }
        val isDirectory = Files.isDirectory(path)
        if (isPackageIgnored(root, path, isDirectory, rules)) {
            return@forEach
        }
        when {
            isDirectory ->
                result.addAll(collectSkillEntries(path, false, root, rules, visited))

            includeRootMarkdown && Files.isRegularFile(path) && name.endsWith(".md") ->
                result.add(path.toAbsolutePath().normalize())
        }
    }
    return result
}

private fun collectAutoPrompts(directory: Path): List<Path> =
    collectAutoFiles(directory, ".md")

private fun collectAutoThemes(directory: Path): List<Path> =
    collectAutoFiles(directory, ".json")

private fun collectAutoFiles(
    directory: Path,
    suffix: String,
): List<Path> {
    val rules = readPackageIgnoreRules(directory, directory)
    return listDirectory(directory).filter { path ->
        !path.fileName.toString().startsWith(".") &&
            Files.isRegularFile(path) &&
            path.fileName.toString().endsWith(suffix) &&
            !isPackageIgnored(directory, path, false, rules)
    }.map { it.toAbsolutePath().normalize() }
}

private fun collectAutoExtensions(directory: Path): List<Path> {
    if (!Files.isDirectory(directory)) {
        return emptyList()
    }
    resolveExtensionEntries(directory)?.let { return it }
    val result = mutableListOf<Path>()
    val rules = readPackageIgnoreRules(directory, directory)
    listDirectory(directory).forEach { path ->
        val name = path.fileName.toString()
        if (name.startsWith(".") || name == "node_modules") {
            return@forEach
        }
        val isDirectory = Files.isDirectory(path)
        if (isPackageIgnored(directory, path, isDirectory, rules)) {
            return@forEach
        }
        when {
            Files.isRegularFile(path) && (name.endsWith(".ts") || name.endsWith(".js")) ->
                result.add(path.toAbsolutePath().normalize())

            isDirectory ->
                resolveExtensionEntries(path)?.let(result::addAll)
        }
    }
    return result
}

private fun resolveExtensionEntries(directory: Path): List<Path>? {
    readManifest(directory)
        ?.get(PackageResourceType.EXTENSIONS)
        ?.takeIf(List<String>::isNotEmpty)
        ?.map { entry -> resolveManifestPath(entry, directory) }
        ?.filter(Files::exists)
        ?.takeIf(List<Path>::isNotEmpty)
        ?.let { return it }
    listOf("index.ts", "index.js").forEach { name ->
        val path = directory.resolve(name)
        if (Files.isRegularFile(path)) {
            return listOf(path.toAbsolutePath().normalize())
        }
    }
    return null
}

private fun readManifest(root: Path): Map<PackageResourceType, List<String>>? =
    runCatching {
        val pi =
            packageJson
                .parseToJsonElement(Files.readString(root.resolve("package.json")))
                .jsonObject["pi"]
                ?.jsonObject
                ?: return null
        PackageResourceType.entries.associateWith { type ->
            pi[type.settingsKey]
                ?.let { value -> runCatching { value.jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList()) }
                .orEmpty()
        }
    }.getOrNull()

private fun applyPatterns(
    allPaths: List<Path>,
    patterns: List<String>,
    baseDir: Path,
): Set<Path> {
    val includes = patterns.filterNot(::isOverridePattern)
    val excludes = patterns.filter { it.startsWith("!") }.map { it.drop(1) }
    val forceIncludes = patterns.filter { it.startsWith("+") }.map { it.drop(1) }
    val forceExcludes = patterns.filter { it.startsWith("-") }.map { it.drop(1) }
    var result =
        if (includes.isEmpty()) {
            allPaths.toMutableList()
        } else {
            allPaths.filterTo(mutableListOf()) { matchesAnyPattern(it, includes, baseDir) }
        }
    if (excludes.isNotEmpty()) {
        result = result.filterTo(mutableListOf()) { !matchesAnyPattern(it, excludes, baseDir) }
    }
    allPaths.forEach { path ->
        if (path !in result && matchesAnyExactPattern(path, forceIncludes, baseDir)) {
            result.add(path)
        }
    }
    if (forceExcludes.isNotEmpty()) {
        result = result.filterTo(mutableListOf()) { !matchesAnyExactPattern(it, forceExcludes, baseDir) }
    }
    return result.toSet()
}

private fun applyAutoloadDisabledPatterns(
    allPaths: List<Path>,
    patterns: List<String>,
    baseDir: Path,
): Map<Path, Boolean> {
    val result = linkedMapOf<Path, Boolean>()
    patterns.forEach { pattern ->
        val target = pattern.removePrefix("+").removePrefix("-").removePrefix("!")
        val enabled = !pattern.startsWith("-") && !pattern.startsWith("!")
        val exact = pattern.startsWith("+") || pattern.startsWith("-")
        allPaths.forEach { path ->
            val matches =
                if (exact) {
                    matchesAnyExactPattern(path, listOf(target), baseDir)
                } else {
                    matchesAnyPattern(path, listOf(target), baseDir)
                }
            if (matches) {
                result[path] = enabled
            }
        }
    }
    return result
}

private fun isEnabledByOverrides(
    path: Path,
    patterns: List<String>,
    baseDir: Path,
): Boolean {
    val excludes = patterns.filter { it.startsWith("!") }.map { it.drop(1) }
    val forceIncludes = patterns.filter { it.startsWith("+") }.map { it.drop(1) }
    val forceExcludes = patterns.filter { it.startsWith("-") }.map { it.drop(1) }
    var enabled = !matchesAnyPattern(path, excludes, baseDir)
    if (matchesAnyExactPattern(path, forceIncludes, baseDir)) {
        enabled = true
    }
    if (matchesAnyExactPattern(path, forceExcludes, baseDir)) {
        enabled = false
    }
    return enabled
}

private fun matchesAnyPattern(
    path: Path,
    patterns: List<String>,
    baseDir: Path,
): Boolean {
    if (patterns.isEmpty()) {
        return false
    }
    val relative = runCatching { baseDir.relativize(path).toString() }.getOrDefault(path.toString()).replace('\\', '/')
    val name = path.fileName.toString()
    val absolute = path.toAbsolutePath().normalize().toString().replace('\\', '/')
    val skillParent = if (name == "SKILL.md") path.parent else null
    val candidates =
        buildList {
            add(relative)
            add(name)
            add(absolute)
            skillParent?.let { parent ->
                add(runCatching { baseDir.relativize(parent).toString() }.getOrDefault(parent.toString()).replace('\\', '/'))
                add(parent.fileName.toString())
                add(parent.toAbsolutePath().normalize().toString().replace('\\', '/'))
            }
        }
    return patterns.any { pattern ->
        val normalized = pattern.replace('\\', '/')
        candidates.any { candidate -> globMatches(normalized, candidate) }
    }
}

private fun matchesAnyExactPattern(
    path: Path,
    patterns: List<String>,
    baseDir: Path,
): Boolean {
    if (patterns.isEmpty()) {
        return false
    }
    val relative = runCatching { baseDir.relativize(path).toString() }.getOrDefault(path.toString()).replace('\\', '/')
    val absolute = path.toAbsolutePath().normalize().toString().replace('\\', '/')
    val name = path.fileName.toString()
    val skillParent = if (name == "SKILL.md") path.parent else null
    val candidates =
        buildList {
            add(relative)
            add(absolute)
            skillParent?.let { parent ->
                add(runCatching { baseDir.relativize(parent).toString() }.getOrDefault(parent.toString()).replace('\\', '/'))
                add(parent.toAbsolutePath().normalize().toString().replace('\\', '/'))
            }
        }
    return patterns.any { pattern ->
        val normalized = pattern.removePrefix("./").replace('\\', '/')
        normalized in candidates
    }
}

private fun globMatches(
    pattern: String,
    value: String,
): Boolean = globRegex(pattern).matches(value)

private fun globRegex(pattern: String): Regex {
    val result = StringBuilder("^")
    var index = 0
    while (index < pattern.length) {
        val character = pattern[index]
        when (character) {
            '*' -> {
                if (pattern.getOrNull(index + 1) == '*') {
                    result.append(".*")
                    index++
                } else {
                    result.append("[^/]*")
                }
            }

            '?' -> result.append("[^/]")
            '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> result.append('\\').append(character)
            else -> result.append(character)
        }
        index++
    }
    result.append('$')
    return Regex(result.toString())
}

private fun walkVisible(root: Path): Sequence<Path> =
    if (!Files.exists(root)) {
        emptySequence()
    } else {
        Files.walk(root).use { stream ->
            stream
                .filter { path ->
                    val relative = root.relativize(path)
                    path != root &&
                        relative.none { segment ->
                            segment.toString().startsWith(".") || segment.toString() == "node_modules"
                        }
                }.toList()
                .asSequence()
        }
    }

private fun listDirectory(directory: Path): List<Path> =
    if (!Files.isDirectory(directory)) {
        emptyList()
    } else {
        runCatching {
            Files.list(directory).use { stream ->
                stream.sorted(compareBy<Path> { it.fileName.toString() }).toList()
            }
        }.getOrDefault(emptyList())
    }

private fun collectAncestorAgentsSkillDirectories(cwd: Path): List<Path> {
    val result = mutableListOf<Path>()
    val gitRoot = findPackageGitRoot(cwd)
    var current: Path? = cwd.toAbsolutePath().normalize()
    while (current != null) {
        result.add(current.resolve(".agents").resolve("skills"))
        if (current == gitRoot) {
            break
        }
        current = current.parent
    }
    return result
}

private fun findPackageGitRoot(start: Path): Path? {
    var current: Path? = start.toAbsolutePath().normalize()
    while (current != null) {
        if (Files.exists(current.resolve(".git"))) {
            return current
        }
        current = current.parent
    }
    return null
}

private data class PackageIgnoreRule(
    val pattern: String,
    val negated: Boolean,
)

private fun readPackageIgnoreRules(
    directory: Path,
    root: Path,
): List<PackageIgnoreRule> {
    val prefix =
        root.relativize(directory)
            .toString()
            .replace('\\', '/')
            .takeIf(String::isNotEmpty)
            ?.plus("/")
            .orEmpty()
    return PACKAGE_IGNORE_FILES.flatMap { name ->
        val path = directory.resolve(name)
        if (!Files.isRegularFile(path)) {
            return@flatMap emptyList()
        }
        runCatching {
            Files.readAllLines(path).mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || (trimmed.startsWith("#") && !trimmed.startsWith("\\#"))) {
                    return@mapNotNull null
                }
                var pattern = line
                val negated = pattern.startsWith("!")
                pattern =
                    when {
                        negated -> pattern.drop(1)
                        pattern.startsWith("\\!") -> pattern.drop(1)
                        else -> pattern
                    }.removePrefix("/")
                PackageIgnoreRule(prefix + pattern, negated)
            }
        }.getOrDefault(emptyList())
    }
}

private fun isPackageIgnored(
    root: Path,
    path: Path,
    directory: Boolean,
    rules: List<PackageIgnoreRule>,
): Boolean {
    val relative = root.relativize(path).toString().replace('\\', '/')
    var ignored = false
    rules.forEach { rule ->
        val raw = rule.pattern
        val directoryOnly = raw.endsWith("/")
        val pattern = raw.removeSuffix("/")
        val matches =
            (!directoryOnly || directory) &&
                (
                    if ('/' in pattern) {
                        globMatches(pattern, relative) || (directoryOnly && relative.startsWith("$pattern/"))
                    } else {
                        relative.split('/').any { segment -> globMatches(pattern, segment) }
                    }
                )
        if (matches) {
            ignored = !rule.negated
        }
    }
    return ignored
}

private fun parseGitSource(source: String): ParsedPackageSource.Git? {
    val hasPrefix = source.startsWith("git:")
    val raw = if (hasPrefix) source.removePrefix("git:").trim() else source
    if (!hasPrefix && !raw.matches(Regex("^(https?|ssh|git)://.*", RegexOption.IGNORE_CASE))) {
        return null
    }
    val split = splitGitRef(raw)
    val repo = split.first
    val ref = split.second
    val scp = Regex("^git@([^:]+):(.+)$").matchEntire(repo)
    val host: String
    val path: String
    val cloneUrl: String
    if (scp != null) {
        host = scp.groupValues[1]
        path = scp.groupValues[2]
        cloneUrl = repo
    } else if (repo.contains("://")) {
        val uri = runCatching { URI(repo) }.getOrNull() ?: return null
        host = uri.host ?: return null
        path = uri.path.removePrefix("/")
        cloneUrl = repo
    } else {
        val slash = repo.indexOf('/')
        if (slash < 0) {
            return null
        }
        host = repo.substring(0, slash)
        path = repo.substring(slash + 1)
        if (!host.contains('.') && host != "localhost") {
            return null
        }
        cloneUrl = "https://$repo"
    }
    val normalizedPath = path.removeSuffix(".git").removePrefix("/")
    if (
        host.isBlank() ||
        normalizedPath.split('/').size < 2 ||
        unsafeGitPart(host, allowSlash = false) ||
        unsafeGitPart(normalizedPath, allowSlash = true)
    ) {
        return null
    }
    return ParsedPackageSource.Git(cloneUrl, host, normalizedPath, ref)
}

private fun splitGitRef(value: String): Pair<String, String?> {
    val scp = Regex("^git@([^:]+):(.+)$").matchEntire(value)
    if (scp != null) {
        val path = scp.groupValues[2]
        val separator = path.indexOf('@')
        return if (separator > 0 && separator < path.lastIndex) {
            "git@${scp.groupValues[1]}:${path.substring(0, separator)}" to path.substring(separator + 1)
        } else {
            value to null
        }
    }
    if (value.contains("://")) {
        val uri = runCatching { URI(value) }.getOrNull() ?: return value to null
        val path = uri.path.removePrefix("/")
        val separator = path.indexOf('@')
        if (separator > 0 && separator < path.lastIndex) {
            val repoPath = path.substring(0, separator)
            val ref = path.substring(separator + 1)
            val rebuilt =
                URI(uri.scheme, uri.userInfo, uri.host, uri.port, "/$repoPath", uri.query, uri.fragment)
                    .toString()
                    .removeSuffix("/")
            return rebuilt to ref
        }
        return value to null
    }
    val slash = value.indexOf('/')
    if (slash < 0) {
        return value to null
    }
    val path = value.substring(slash + 1)
    val separator = path.indexOf('@')
    return if (separator > 0 && separator < path.lastIndex) {
        "${value.substring(0, slash)}/${path.substring(0, separator)}" to path.substring(separator + 1)
    } else {
        value to null
    }
}

private fun unsafeGitPart(
    value: String,
    allowSlash: Boolean,
): Boolean =
    value.contains('\u0000') ||
        value.contains('\\') ||
        value.startsWith("/") ||
        (!allowSlash && value.contains('/')) ||
        value.split('/').contains("..")

private fun isLocalSource(source: String): Boolean {
    val lower = source.lowercase()
    return listOf("npm:", "git:", "github:", "http:", "https:", "ssh:")
        .none(lower::startsWith)
}

private fun resolveFromBase(
    input: String,
    baseDir: Path,
    homeDir: Path = defaultHomeDirectory(),
): Path {
    val trimmed = input.trim()
    val expanded =
        when {
            trimmed == "~" -> homeDir
            trimmed.startsWith("~/") -> homeDir.resolve(trimmed.removePrefix("~/"))
            trimmed.startsWith("file://") -> Path.of(URI(trimmed))
            else -> Path.of(trimmed)
        }
    return (if (expanded.isAbsolute) expanded else baseDir.resolve(expanded)).toAbsolutePath().normalize()
}

private fun resolveManifestPath(
    input: String,
    packageRoot: Path,
): Path {
    val path = Path.of(input)
    return (if (path.isAbsolute) path else packageRoot.resolve(path)).toAbsolutePath().normalize()
}

private fun isPattern(value: String): Boolean =
    isOverridePattern(value) || value.contains('*') || value.contains('?')

private fun isOverridePattern(value: String): Boolean =
    value.startsWith("!") || value.startsWith("+") || value.startsWith("-")

private fun parsePnpmGlobalPackagePath(
    output: String,
    packageName: String,
): String? =
    runCatching {
        packageJson
            .parseToJsonElement(output)
            .jsonArray
            .firstNotNullOfOrNull { root ->
                root.jsonObject["dependencies"]
                    ?.jsonObject
                    ?.get(packageName)
                    ?.jsonObject
                    ?.get("path")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }
    }.getOrNull()

private fun comparePackageVersions(
    left: String,
    right: String,
): Int {
    val leftParts = parsePackageVersion(left)
    val rightParts = parsePackageVersion(right)
    if (leftParts == null || rightParts == null) {
        return left.compareTo(right)
    }
    for (index in 0..2) {
        val comparison = leftParts.numbers[index].compareTo(rightParts.numbers[index])
        if (comparison != 0) {
            return comparison
        }
    }
    return when {
        leftParts.preRelease == null && rightParts.preRelease != null -> 1
        leftParts.preRelease != null && rightParts.preRelease == null -> -1
        else -> leftParts.preRelease.orEmpty().compareTo(rightParts.preRelease.orEmpty())
    }
}

private fun parsePackageVersion(value: String): ParsedPackageVersion? {
    val match = PACKAGE_VERSION_PATTERN.matchEntire(value.trim()) ?: return null
    return ParsedPackageVersion(
        numbers =
            listOf(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            ),
        preRelease = match.groupValues[4].takeIf(String::isNotEmpty),
    )
}

private data class ParsedPackageVersion(
    val numbers: List<Int>,
    val preRelease: String?,
)

private val packageJson =
    kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }

private val NPM_SPEC_PATTERN = Regex("""^(@?[^@]+(?:/[^@]+)?)(?:@(.+))?$""")
private val EXACT_NPM_VERSION_PATTERN = Regex("""^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$""")
private val PACKAGE_VERSION_PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")
private val GIT_HEAD_PATTERN = Regex("""^([0-9a-fA-F]{40})\s+""")
private val PACKAGE_IGNORE_FILES = listOf(".gitignore", ".ignore", ".fdignore")
private const val NETWORK_COMMAND_TIMEOUT_SECONDS = 10L
private const val PACKAGE_COMMAND_TIMEOUT_SECONDS = 300L
