package works.earendil.pi.codingagent

import java.io.PrintStream
import java.nio.file.Path

internal fun runPackageCommand(
    arguments: List<String>,
    cwd: Path = Path.of("").toAbsolutePath().normalize(),
    agentDir: Path = defaultAgentDirectory(),
    output: PrintStream = System.out,
    errorOutput: PrintStream = System.err,
    selfUpdater: SelfUpdater = SourceDistributionSelfUpdater(),
    configSelector: ResourceConfigSelector = JLineResourceConfigSelector,
): Int? {
    val rawCommand = arguments.firstOrNull() ?: return null
    val command = if (rawCommand == "uninstall") "remove" else rawCommand
    if (command !in PACKAGE_COMMANDS) {
        return null
    }
    if (command == "update" && "--models" in arguments) {
        return null
    }
    val rest = arguments.drop(1)
    if ("--help" in rest || "-h" in rest) {
        printPackageCommandHelp(command, output)
        return 0
    }
    if (command == "config") {
        return runConfigCommand(
            arguments = rest,
            cwd = cwd,
            agentDir = agentDir,
            output = output,
            errorOutput = errorOutput,
            selector = configSelector,
        )
    }
    validatePackageArguments(command, rest)?.let { message ->
        errorOutput.println(message)
        errorOutput.println(
            if (message.startsWith("Unknown option ")) {
                """Use "pi --help" or "${packageCommandUsage(command)}"."""
            } else {
                "Usage: ${packageCommandUsage(command)}"
            },
        )
        return 1
    }

    val local = rest.any { it == "-l" || it == "--local" }
    val approve = parseApproval(rest)
    val positional = packagePositionals(rest)
    val source =
        when (command) {
            "install", "remove" -> positional.singleOrNull()
            "update" -> positional.singleOrNull()
            else -> null
        }
    if ((command == "install" || command == "remove") && source == null) {
        errorOutput.println("Missing $command source.")
        errorOutput.println("Usage: ${packageCommandUsage(command)}")
        return 1
    }
    if (command != "install" && command != "remove" && local) {
        errorOutput.println("Unknown option --local for \"$command\".")
        errorOutput.println("Usage: ${packageCommandUsage(command)}")
        return 1
    }

    val projectTrusted =
        try {
            resolveProjectTrusted(
                cwd = cwd,
                agentDir = agentDir,
                override = approve,
            )
        } catch (error: Exception) {
            errorOutput.println("Error: ${error.message}")
            return 1
        }
    if (local && !projectTrusted) {
        errorOutput.println("Project is not trusted. Use --approve to modify local package config.")
        return 1
    }
    val warnings = mutableListOf<String>()
    val settings =
        SettingsStore(
            cwd = cwd,
            agentDir = agentDir,
            projectTrusted = projectTrusted,
            onWarning = warnings::add,
        )
    val manager =
        PackageManager(
            cwd = cwd,
            agentDir = agentDir,
            settings = settings,
            projectTrusted = projectTrusted,
        )
    manager.setProgressCallback { event ->
        if (event.type == PackageProgressEvent.Type.START) {
            output.println(event.message)
        }
    }
    warnings.forEach { warning -> errorOutput.println("Warning: $warning") }

    return try {
        when (command) {
            "install" -> {
                manager.installAndPersist(requireNotNull(source), local)
                output.println("Installed $source")
                0
            }

            "remove" -> {
                val removed = manager.removeAndPersist(requireNotNull(source), local)
                if (!removed) {
                    errorOutput.println("No matching package found for $source")
                    1
                } else {
                    output.println("Removed $source")
                    0
                }
            }

            "list" -> {
                printConfiguredPackages(manager.listConfiguredPackages(), output)
                0
            }

            "update" -> runPackageUpdate(rest, source, manager, output, errorOutput, selfUpdater)
            else -> error("Unsupported package command: $command")
        }
    } catch (error: Exception) {
        errorOutput.println("Error: ${error.message ?: "Unknown package command error"}")
        1
    }
}

private fun runPackageUpdate(
    arguments: List<String>,
    source: String?,
    manager: PackageManager,
    output: PrintStream,
    errorOutput: PrintStream,
    selfUpdater: SelfUpdater,
): Int {
    val sourceRequestsSelf = source == "self" || source == "pi"
    val extensionSource =
        when {
            source != null && !sourceRequestsSelf -> source
            "--extension" in arguments -> arguments.getOrNull(arguments.indexOf("--extension") + 1)
            else -> null
        }
    val extensions =
        extensionSource != null ||
            "--extensions" in arguments ||
            "--all" in arguments
    if (extensions) {
        manager.update(extensionSource)
        output.println(if (extensionSource == null) "Updated packages" else "Updated $extensionSource")
    }
    val selfRequested =
        sourceRequestsSelf ||
            "--self" in arguments ||
            "--all" in arguments ||
            (
                source == null &&
                    "--extensions" !in arguments &&
                    "--extension" !in arguments
            )
    if (selfRequested) {
        if (!extensions && source == null && "--self" !in arguments && "--all" !in arguments) {
            output.println("Extensions are skipped. Run pi update --extensions to update extensions.")
        }
        return selfUpdater.update("--force" in arguments, output, errorOutput)
    }
    return 0
}

private fun runConfigCommand(
    arguments: List<String>,
    cwd: Path,
    agentDir: Path,
    output: PrintStream,
    errorOutput: PrintStream,
    selector: ResourceConfigSelector,
): Int {
    val invalid =
        arguments.firstOrNull { argument ->
            argument !in setOf("-l", "--local", "-a", "--approve", "-na", "--no-approve")
        }
    if (invalid != null) {
        errorOutput.println(
            if (invalid.startsWith("-")) {
                "Unknown option $invalid for \"config\"."
            } else {
                "Unexpected argument $invalid."
            },
        )
        errorOutput.println(
            if (invalid.startsWith("-")) {
                """Use "pi --help" or "${packageCommandUsage("config")}"."""
            } else {
                "Usage: ${packageCommandUsage("config")}"
            },
        )
        return 1
    }
    val local = arguments.any { it == "-l" || it == "--local" }
    val projectTrusted =
        try {
            resolveProjectTrusted(
                cwd = cwd,
                agentDir = agentDir,
                override = parseApproval(arguments),
            )
        } catch (error: Exception) {
            errorOutput.println("Error: ${error.message}")
            return 1
        }
    if (local && !projectTrusted) {
        errorOutput.println("Project is not trusted. Use --approve to modify local resource config.")
        return 1
    }
    val warnings = mutableListOf<String>()
    val settings =
        SettingsStore(
            cwd = cwd,
            agentDir = agentDir,
            projectTrusted = projectTrusted,
            onWarning = warnings::add,
        )
    warnings.forEach { warning -> errorOutput.println("Warning: $warning") }
    return selector.run(
        cwd = cwd,
        agentDir = agentDir,
        settings = settings,
        local = local,
        projectTrusted = projectTrusted,
        output = output,
        errorOutput = errorOutput,
    )
}

private fun parseApproval(arguments: List<String>): Boolean? {
    var value: Boolean? = null
    arguments.forEach { argument ->
        when (argument) {
            "-a", "--approve" -> value = true
            "-na", "--no-approve" -> value = false
        }
    }
    return value
}

private fun packagePositionals(arguments: List<String>): List<String> {
    val result = mutableListOf<String>()
    var index = 0
    while (index < arguments.size) {
        val argument = arguments[index]
        when {
            argument == "--extension" -> index++
            argument in PACKAGE_OPTIONS -> Unit
            else -> result += argument
        }
        index++
    }
    return result
}

private fun validatePackageArguments(
    command: String,
    arguments: List<String>,
): String? {
    val allowed =
        when (command) {
            "install", "remove" -> COMMON_PACKAGE_OPTIONS + setOf("-l", "--local")
            "list" -> COMMON_PACKAGE_OPTIONS
            "update" ->
                COMMON_PACKAGE_OPTIONS +
                    setOf("--self", "--extensions", "--all", "--force", "--extension")

            else -> COMMON_PACKAGE_OPTIONS
        }
    var index = 0
    var extensionCount = 0
    while (index < arguments.size) {
        val argument = arguments[index]
        if (argument == "--extension") {
            if (argument !in allowed) {
                return "Unknown option $argument for \"$command\"."
            }
            val value = arguments.getOrNull(index + 1)
            if (value == null || value.startsWith("-")) {
                return "Missing value for --extension."
            }
            extensionCount++
            if (extensionCount > 1) {
                return "--extension can only be provided once"
            }
            index += 2
            continue
        }
        if (argument.startsWith("-") && argument !in allowed) {
            return "Unknown option $argument for \"$command\"."
        }
        index++
    }
    val positional = packagePositionals(arguments)
    if (positional.size > 1) {
        return "Unexpected argument ${positional[1]}."
    }
    if (command != "update") {
        return null
    }
    val source = positional.singleOrNull()
    val self = "--self" in arguments
    val extensions = "--extensions" in arguments
    val all = "--all" in arguments
    val extension = "--extension" in arguments
    if (all && (self || extensions || extension)) {
        return "--all cannot be combined with --self, --extensions, --models, or --extension"
    }
    if (all && source != null) {
        return "--all cannot be combined with a positional source"
    }
    if (extension && (self || extensions || all)) {
        return "--extension cannot be combined with --self, --extensions, or --all"
    }
    if (extension && source != null) {
        return "--extension cannot be combined with a positional source"
    }
    if (source != null && source != "self" && source != "pi" && (self || extensions || all)) {
        return "positional update targets cannot be combined with --self, --extensions, or --all"
    }
    return null
}

private fun printConfiguredPackages(
    packages: List<ConfiguredPackage>,
    output: PrintStream,
) {
    if (packages.isEmpty()) {
        output.println("No packages installed.")
        return
    }
    val user = packages.filter { it.scope == SettingsScope.USER }
    val project = packages.filter { it.scope == SettingsScope.PROJECT }
    fun printPackage(pkg: ConfiguredPackage) {
        output.println("  ${pkg.source}${if (pkg.filtered) " (filtered)" else ""}")
        pkg.installedPath?.let { output.println("    $it") }
    }
    if (user.isNotEmpty()) {
        output.println("User packages:")
        user.forEach(::printPackage)
    }
    if (project.isNotEmpty()) {
        if (user.isNotEmpty()) {
            output.println()
        }
        output.println("Project packages:")
        project.forEach(::printPackage)
    }
}

private fun printPackageCommandHelp(
    command: String,
    output: PrintStream,
) {
    val text =
        when (command) {
            "install" ->
                """
                Usage:
                  ${packageCommandUsage("install")}

                Install a package and add it to settings.

                Options:
                  -l, --local       Install project-locally (.pi/settings.json)
                  -a, --approve     Trust project-local files for this command
                  -na, --no-approve Ignore project-local files for this command

                Examples:
                  pi install npm:@foo/bar
                  pi install git:github.com/user/repo
                  pi install git:git@github.com:user/repo
                  pi install https://github.com/user/repo
                  pi install ssh://git@github.com/user/repo
                  pi install ./local/path
                """

            "remove" ->
                """
                Usage:
                  ${packageCommandUsage("remove")}

                Remove a package and its source from settings.
                Alias: pi uninstall <source> [-l]

                Options:
                  -l, --local       Remove from project settings (.pi/settings.json)
                  -a, --approve     Trust project-local files for this command
                  -na, --no-approve Ignore project-local files for this command

                Examples:
                  pi remove npm:@foo/bar
                  pi uninstall npm:@foo/bar
                """

            "update" ->
                """
                Usage:
                  ${packageCommandUsage("update")}

                Update pi, installed packages, or model catalogs.

                Options:
                  --self                  Update pi only (default when no target is given)
                  --extensions            Update installed packages only
                  --models                Refresh model catalogs only
                  --all                   Update pi and installed packages
                  --extension <source>    Update one package only
                  -a, --approve           Trust project-local files for this command
                  -na, --no-approve       Ignore project-local files for this command
                  --force                 Reinstall pi even if the current version is latest

                Short forms:
                  pi update                Update pi only
                  pi update --all          Update pi and all extensions
                  pi update --models       Refresh model catalogs only
                  pi update <source>       Update one package
                  pi update pi             Update pi only (self works as alias to pi)
                """

            "list" ->
                """
                Usage:
                  ${packageCommandUsage("list")}

                List installed packages from user and project settings.

                Options:
                  -a, --approve      Trust project-local files for this command
                  -na, --no-approve  Ignore project-local files for this command
                """

            "config" ->
                """
                Usage:
                  ${packageCommandUsage("config")}

                Open the resource configuration TUI to enable or disable package resources.
                Without -l, starts in global settings (~/.pi/agent/settings.json).
                Press Tab in the TUI to switch between global and project-local modes.

                Options:
                  -l, --local       Edit project overrides (.pi/settings.json)
                  -a, --approve     Trust project-local files for this command with -l
                  -na, --no-approve Ignore project-local files for this command with -l
                """

            else -> return
        }
    output.println(text.trimIndent())
    output.println()
}

internal fun packageCommandUsage(command: String): String =
    when (command) {
        "install" -> "pi install <source> [-l] [--approve|--no-approve]"
        "remove" -> "pi remove <source> [-l] [--approve|--no-approve]"
        "update" ->
            "pi update [source|self|pi] [--self|--extensions|--models|--all] " +
                "[--extension <source>] [--approve|--no-approve] [--force]"

        "list" -> "pi list [--approve|--no-approve]"
        "config" -> "pi config [-l] [--approve|--no-approve]"
        else -> "pi $command"
    }

private val PACKAGE_COMMANDS = setOf("install", "remove", "update", "list", "config")
private val PACKAGE_OPTIONS =
    setOf(
        "-l",
        "--local",
        "-a",
        "--approve",
        "-na",
        "--no-approve",
        "--extensions",
        "--self",
        "--all",
        "--force",
    )
private val COMMON_PACKAGE_OPTIONS =
    setOf(
        "-a",
        "--approve",
        "-na",
        "--no-approve",
    )
