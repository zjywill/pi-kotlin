package works.earendil.pi.codingagent

enum class OutputMode {
    TEXT,
    JSON,
    RPC,
}

enum class AgentThinkingLevel {
    OFF,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX,
}

data class Diagnostic(
    val type: Type,
    val message: String,
) {
    enum class Type {
        WARNING,
        ERROR,
    }
}

data class Args(
    var provider: String? = null,
    var model: String? = null,
    var apiKey: String? = null,
    var systemPrompt: String? = null,
    val appendSystemPrompt: MutableList<String> = mutableListOf(),
    var thinking: AgentThinkingLevel? = null,
    var continueSession: Boolean = false,
    var resume: Boolean = false,
    var help: Boolean = false,
    var version: Boolean = false,
    var mode: OutputMode? = null,
    var name: String? = null,
    var noSession: Boolean = false,
    var session: String? = null,
    var sessionId: String? = null,
    var fork: String? = null,
    var sessionDir: String? = null,
    var models: List<String>? = null,
    var tools: List<String>? = null,
    var excludeTools: List<String>? = null,
    var noTools: Boolean = false,
    var noBuiltinTools: Boolean = false,
    val extensions: MutableList<String> = mutableListOf(),
    var noExtensions: Boolean = false,
    var print: Boolean = false,
    var export: String? = null,
    var noSkills: Boolean = false,
    val skills: MutableList<String> = mutableListOf(),
    val promptTemplates: MutableList<String> = mutableListOf(),
    var noPromptTemplates: Boolean = false,
    val themes: MutableList<String> = mutableListOf(),
    var noThemes: Boolean = false,
    var noContextFiles: Boolean = false,
    var listModels: String? = null,
    var listModelsRequested: Boolean = false,
    var offline: Boolean = false,
    var uiMode: UiMode? = null,
    var verbose: Boolean = false,
    var projectTrustOverride: Boolean? = null,
    val messages: MutableList<String> = mutableListOf(),
    val fileArgs: MutableList<String> = mutableListOf(),
    val unknownFlags: MutableMap<String, Any> = linkedMapOf(),
    val diagnostics: MutableList<Diagnostic> = mutableListOf(),
)

fun parseArgs(arguments: List<String>): Args {
    val result = Args()
    var index = 0
    while (index < arguments.size) {
        val argument = arguments[index]
        fun nextValue(): String? = arguments.getOrNull(index + 1)?.also { index++ }

        when {
            argument == "--help" || argument == "-h" -> result.help = true
            argument == "--version" || argument == "-v" -> result.version = true
            argument == "--mode" -> {
                result.mode =
                    when (nextValue()) {
                        "text" -> OutputMode.TEXT
                        "json" -> OutputMode.JSON
                        "rpc" -> OutputMode.RPC
                        else -> null
                    }
            }

            argument == "--continue" || argument == "-c" -> result.continueSession = true
            argument == "--resume" || argument == "-r" -> result.resume = true
            argument == "--provider" -> result.provider = nextValue()
            argument == "--model" -> result.model = nextValue()
            argument == "--api-key" -> result.apiKey = nextValue()
            argument == "--system-prompt" -> result.systemPrompt = nextValue()
            argument == "--append-system-prompt" -> nextValue()?.let(result.appendSystemPrompt::add)
            argument == "--name" || argument == "-n" -> {
                val value = nextValue()
                if (value == null) {
                    result.diagnostics += Diagnostic(Diagnostic.Type.ERROR, "--name requires a value")
                } else {
                    result.name = value
                }
            }

            argument == "--no-session" -> result.noSession = true
            argument == "--session" -> result.session = nextValue()
            argument == "--session-id" -> result.sessionId = nextValue()
            argument == "--fork" -> result.fork = nextValue()
            argument == "--session-dir" -> result.sessionDir = nextValue()
            argument == "--models" -> result.models = splitCommaList(nextValue())
            argument == "--no-tools" || argument == "-nt" -> result.noTools = true
            argument == "--no-builtin-tools" || argument == "-nbt" -> result.noBuiltinTools = true
            argument == "--tools" || argument == "-t" -> result.tools = splitCommaList(nextValue())
            argument == "--exclude-tools" || argument == "-xt" -> result.excludeTools = splitCommaList(nextValue())
            argument == "--thinking" -> {
                val value = nextValue()
                val parsed = value?.let(::parseThinkingLevel)
                if (parsed == null && value != null) {
                    result.diagnostics +=
                        Diagnostic(
                            Diagnostic.Type.WARNING,
                            "Invalid thinking level \"$value\". Valid values: off, minimal, low, medium, high, xhigh, max",
                        )
                } else {
                    result.thinking = parsed
                }
            }

            argument == "--print" || argument == "-p" -> {
                result.print = true
                val next = arguments.getOrNull(index + 1)
                if (
                    next != null &&
                    !next.startsWith("@") &&
                    (!next.startsWith("-") || next.startsWith("---"))
                ) {
                    result.messages += next
                    index++
                }
            }

            argument == "--export" -> result.export = nextValue()
            argument == "--extension" || argument == "-e" -> nextValue()?.let(result.extensions::add)
            argument == "--no-extensions" || argument == "-ne" -> result.noExtensions = true
            argument == "--skill" -> nextValue()?.let(result.skills::add)
            argument == "--prompt-template" -> nextValue()?.let(result.promptTemplates::add)
            argument == "--theme" -> nextValue()?.let(result.themes::add)
            argument == "--no-skills" || argument == "-ns" -> result.noSkills = true
            argument == "--no-prompt-templates" || argument == "-np" -> result.noPromptTemplates = true
            argument == "--no-themes" -> result.noThemes = true
            argument == "--no-context-files" || argument == "-nc" -> result.noContextFiles = true
            argument == "--list-models" -> {
                result.listModelsRequested = true
                val next = arguments.getOrNull(index + 1)
                if (next != null && !next.startsWith("-") && !next.startsWith("@")) {
                    result.listModels = next
                    index++
                }
            }

            argument == "--ui-mode" -> {
                val value = arguments.getOrNull(index + 1)
                val mode =
                    when (value) {
                        UiMode.REGULAR.wireValue -> UiMode.REGULAR
                        UiMode.FULLSCREEN.wireValue -> UiMode.FULLSCREEN
                        else -> null
                    }
                if (mode != null) {
                    result.uiMode = mode
                    index++
                } else if (value == null || value.startsWith("-")) {
                    result.diagnostics +=
                        Diagnostic(
                            Diagnostic.Type.ERROR,
                            "--ui-mode requires regular or fullscreen",
                        )
                } else {
                    index++
                    result.diagnostics +=
                        Diagnostic(
                            Diagnostic.Type.ERROR,
                            "Invalid UI mode \"$value\". Valid values: regular, fullscreen",
                        )
                }
            }

            argument == "--verbose" -> result.verbose = true
            argument == "--approve" || argument == "-a" -> result.projectTrustOverride = true
            argument == "--no-approve" || argument == "-na" -> result.projectTrustOverride = false
            argument == "--offline" -> result.offline = true
            argument.startsWith("@") -> result.fileArgs += argument.drop(1)
            argument.startsWith("--") -> {
                val equals = argument.indexOf('=')
                if (equals >= 0) {
                    result.unknownFlags[argument.substring(2, equals)] = argument.substring(equals + 1)
                } else {
                    val flagName = argument.drop(2)
                    val next = arguments.getOrNull(index + 1)
                    if (next != null && !next.startsWith("-") && !next.startsWith("@")) {
                        result.unknownFlags[flagName] = next
                        index++
                    } else {
                        result.unknownFlags[flagName] = true
                    }
                }
            }

            argument.startsWith("-") -> {
                result.diagnostics += Diagnostic(Diagnostic.Type.ERROR, "Unknown option: $argument")
            }

            else -> result.messages += argument
        }
        index++
    }
    return result
}

private fun splitCommaList(value: String?): List<String>? =
    value
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)

internal fun parseThinkingLevel(value: String): AgentThinkingLevel? =
    when (value) {
        "off" -> AgentThinkingLevel.OFF
        "minimal" -> AgentThinkingLevel.MINIMAL
        "low" -> AgentThinkingLevel.LOW
        "medium" -> AgentThinkingLevel.MEDIUM
        "high" -> AgentThinkingLevel.HIGH
        "xhigh" -> AgentThinkingLevel.XHIGH
        "max" -> AgentThinkingLevel.MAX
        else -> null
    }
