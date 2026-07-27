package works.earendil.pi.codingagent

import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class ExtensionBootstrapResult(
    val projectTrusted: Boolean,
    val packageResources: ResolvedPackageResources,
    val host: ExtensionHost?,
)

internal fun bootstrapExtensions(
    cwd: Path,
    agentDir: Path,
    trustOverride: Boolean?,
    explicitPaths: List<String>,
    noExtensions: Boolean,
    mode: ExtensionMode,
    flagValues: Map<String, Any>,
    context: (Boolean) -> JsonObject,
    homeDir: Path = defaultHomeDirectory(),
    onWarning: (String) -> Unit = {},
    onDiagnostic: (ExtensionDiagnostic) -> Unit = {},
    onLog: (String) -> Unit = {},
    onBootstrapActions: (List<ExtensionAction>) -> Unit = {},
): ExtensionBootstrapResult {
    val normalizedCwd = cwd.toAbsolutePath().normalize()
    val requiresDecision =
        trustOverride == null && hasTrustRequiringProjectResources(normalizedCwd, homeDir)
    if (!requiresDecision) {
        val trusted =
            resolveProjectTrusted(
                cwd = normalizedCwd,
                agentDir = agentDir,
                override = trustOverride,
                homeDir = homeDir,
            )
        val resources =
            resolvePackageResources(
                cwd = normalizedCwd,
                agentDir = agentDir,
                projectTrusted = trusted,
                homeDir = homeDir,
                onWarning = onWarning,
            )
        return ExtensionBootstrapResult(
            projectTrusted = trusted,
            packageResources = resources,
            host =
                startExtensionHost(
                    sources =
                        resolveExtensionSources(
                            cwd = normalizedCwd,
                            explicitPaths = explicitPaths,
                            packageResources = resources,
                            noExtensions = noExtensions,
                        ),
                    agentDir = agentDir,
                    cwd = normalizedCwd,
                    mode = mode,
                    projectTrusted = trusted,
                    flagValues = flagValues,
                    context = context(trusted),
                    onDiagnostic = onDiagnostic,
                    onLog = onLog,
                ),
        )
    }

    val preTrustResources =
        resolvePackageResources(
            cwd = normalizedCwd,
            agentDir = agentDir,
            projectTrusted = false,
            homeDir = homeDir,
            onWarning = onWarning,
        )
    val preTrustSources =
        resolveExtensionSources(
            cwd = normalizedCwd,
            explicitPaths = explicitPaths,
            packageResources = preTrustResources,
            noExtensions = noExtensions,
        )
    val preTrustHost =
        startExtensionHost(
            sources = preTrustSources,
            agentDir = agentDir,
            cwd = normalizedCwd,
            mode = mode,
            projectTrusted = false,
            flagValues = flagValues,
            context = context(false),
            onDiagnostic = onDiagnostic,
            onLog = onLog,
        )
    onBootstrapActions(preTrustHost?.drainStartupActions().orEmpty())
    val trusted =
        resolveProjectTrusted(
            cwd = normalizedCwd,
            agentDir = agentDir,
            override = null,
            homeDir = homeDir,
            extensionHost = preTrustHost,
            extensionContext = context(false),
            onExtensionActions = onBootstrapActions,
        )
    val finalResources =
        if (trusted) {
            resolvePackageResources(
                cwd = normalizedCwd,
                agentDir = agentDir,
                projectTrusted = true,
                homeDir = homeDir,
                onWarning = onWarning,
            )
        } else {
            preTrustResources
        }
    val finalSources =
        resolveExtensionSources(
            cwd = normalizedCwd,
            explicitPaths = explicitPaths,
            packageResources = finalResources,
            noExtensions = noExtensions,
        )
    val host =
        finalizeExtensionHost(
            preTrustHost = preTrustHost,
            preTrustSources = preTrustSources,
            finalSources = finalSources,
            agentDir = agentDir,
            cwd = normalizedCwd,
            mode = mode,
            projectTrusted = trusted,
            flagValues = flagValues,
            context = context(trusted),
            onDiagnostic = onDiagnostic,
            onLog = onLog,
        )
    return ExtensionBootstrapResult(trusted, finalResources, host)
}

internal fun discoverExtensionResources(
    host: ExtensionHost?,
    cwd: Path,
    reason: String,
    context: JsonObject,
    onActions: (List<ExtensionAction>) -> Unit,
): ResolvedPackageResources {
    host ?: return ResolvedPackageResources()
    val invocation =
        host.emit(
            event =
                buildJsonObject {
                    put("type", "resources_discover")
                    put("cwd", cwd.toAbsolutePath().normalize().toString())
                    put("reason", reason)
                },
            context = context,
        )
    onActions(invocation.actions)
    val resources = invocation.resources ?: return ResolvedPackageResources()
    return ResolvedPackageResources(
        skills = resources.skillPaths.map { it.toResolvedResource(cwd) },
        prompts = resources.promptPaths.map { it.toResolvedResource(cwd) },
        themes = resources.themePaths.map { it.toResolvedResource(cwd) },
    )
}

private fun startExtensionHost(
    sources: List<ExtensionSource>,
    agentDir: Path,
    cwd: Path,
    mode: ExtensionMode,
    projectTrusted: Boolean,
    flagValues: Map<String, Any>,
    context: JsonObject,
    onDiagnostic: (ExtensionDiagnostic) -> Unit,
    onLog: (String) -> Unit,
): ExtensionHost? =
    ExtensionHost.start(
        sources = sources,
        agentDir = agentDir,
        cwd = cwd,
        mode = mode,
        projectTrusted = projectTrusted,
        flagValues = flagValues,
        context = context,
        hasUI = mode == ExtensionMode.TUI,
        onDiagnostic = onDiagnostic,
        onLog = onLog,
    )

private fun finalizeExtensionHost(
    preTrustHost: ExtensionHost?,
    preTrustSources: List<ExtensionSource>,
    finalSources: List<ExtensionSource>,
    agentDir: Path,
    cwd: Path,
    mode: ExtensionMode,
    projectTrusted: Boolean,
    flagValues: Map<String, Any>,
    context: JsonObject,
    onDiagnostic: (ExtensionDiagnostic) -> Unit,
    onLog: (String) -> Unit,
): ExtensionHost? {
    if (preTrustHost == null) {
        return startExtensionHost(
            finalSources,
            agentDir,
            cwd,
            mode,
            projectTrusted,
            flagValues,
            context,
            onDiagnostic,
            onLog,
        )
    }
    val finalByPath = finalSources.associateBy { canonicalPath(it.path) }
    val preTrustPaths = preTrustSources.mapTo(mutableSetOf()) { canonicalPath(it.path) }
    if (preTrustPaths.all(finalByPath::containsKey)) {
        preTrustHost.loadAdditional(finalSources, context)
        return preTrustHost
    }
    preTrustHost.close()
    return startExtensionHost(
        finalSources,
        agentDir,
        cwd,
        mode,
        projectTrusted,
        flagValues,
        context,
        onDiagnostic,
        onLog,
    )
}

private fun ExtensionResourcePath.toResolvedResource(cwd: Path): ResolvedResource {
    val raw = Path.of(path)
    val resolved =
        (if (raw.isAbsolute) raw else cwd.resolve(raw))
            .toAbsolutePath()
            .normalize()
    val extensionName =
        extensionPath.fileName
            ?.toString()
            ?.removeSuffix(".ts")
            ?.removeSuffix(".js")
            .orEmpty()
            .ifBlank { "anonymous" }
    return ResolvedResource(
        path = resolved,
        enabled = true,
        sourceInfo =
            ResourceSourceInfo(
                path = resolved,
                source = "extension:$extensionName",
                scope = "temporary",
                origin = "top-level",
                baseDir = extensionPath.parent,
            ),
    )
}
