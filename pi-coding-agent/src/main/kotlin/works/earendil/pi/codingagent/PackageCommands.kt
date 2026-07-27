package works.earendil.pi.codingagent

import java.io.PrintStream
import java.nio.file.Path

internal fun runPackageCommand(
    arguments: List<String>,
    cwd: Path = Path.of("").toAbsolutePath().normalize(),
    agentDir: Path = defaultAgentDirectory(),
    output: PrintStream = System.out,
    errorOutput: PrintStream = System.err,
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
        return runConfigCommand(rest, output, errorOutput)
    }
    validatePackageArguments(command, rest)?.let { message ->
        errorOutput.println(message)
        errorOutput.println("Usage: ${packageCommandUsage(command)}")
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
    if (command == "list" && positional.isNotEmpty()) {
        errorOutput.println("Unexpected argument ${positional.first()}.")
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

            "update" -> runPackageUpdate(rest, source, manager, output, errorOutput)
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
): Int {
    val extensions =
        source != null ||
            "--extensions" in arguments ||
            "--all" in arguments ||
            "--extension" in arguments
    val extensionSource =
        when {
            source != null && source != "self" && source != "pi" -> source
            "--extension" in arguments -> arguments.getOrNull(arguments.indexOf("--extension") + 1)
            else -> null
        }
    if (extensions) {
        manager.update(extensionSource)
        output.println(if (extensionSource == null) "Updated packages" else "Updated $extensionSource")
    }
    val selfRequested =
        !extensions ||
            source == "self" ||
            source == "pi" ||
            "--self" in arguments ||
            "--all" in arguments
    if (selfRequested) {
        errorOutput.println(
            "Error: Self-update is not available in this Kotlin distribution; update the installed application package.",
        )
        return 1
    }
    return 0
}

private fun runConfigCommand(
    arguments: List<String>,
    output: PrintStream,
    errorOutput: PrintStream,
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
        errorOutput.println("Usage: ${packageCommandUsage("config")}")
        return 1
    }
    output.println("Interactive resource configuration is not migrated yet.")
    output.println("Edit settings.json package/resource filters directly for this stage.")
    return 1
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
        return "--all cannot be combined with --self, --extensions, or --extension"
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
    val description =
        when (command) {
            "install" -> "Install a package and add it to settings."
            "remove" -> "Remove a package and its source from settings."
            "update" -> "Update pi, installed packages, or model catalogs."
            "list" -> "List installed packages from user and project settings."
            "config" -> "Open the resource configuration TUI to enable or disable package resources."
            else -> ""
        }
    output.println("Usage:")
    output.println("  ${packageCommandUsage(command)}")
    output.println()
    output.println(description)
}

private fun packageCommandUsage(command: String): String =
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
