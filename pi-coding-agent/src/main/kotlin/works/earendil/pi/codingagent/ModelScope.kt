package works.earendil.pi.codingagent

import java.nio.file.Path
import works.earendil.pi.ai.Model

internal data class ScopedModel(
    val model: Model,
    val thinkingLevel: AgentThinkingLevel? = null,
)

internal data class ModelScopeDiagnostic(
    val pattern: String,
    val message: String,
)

internal data class ModelScopeResolution(
    val scopedModels: List<ScopedModel>,
    val diagnostics: List<ModelScopeDiagnostic>,
)

internal fun resolveConfiguredModelScope(
    explicitPatterns: List<String>?,
    availableModels: List<Model>,
    cwd: Path,
    agentDir: Path,
    projectTrusted: Boolean,
): ModelScopeResolution {
    val settings = SettingsStore(cwd, agentDir, projectTrusted)
    val projectPatterns = settings.project().enabledModels
    val patterns = explicitPatterns ?: projectPatterns ?: settings.global().enabledModels
    return if (patterns.isNullOrEmpty()) {
        ModelScopeResolution(emptyList(), emptyList())
    } else {
        resolveModelScope(patterns, availableModels)
    }
}

internal fun resolveModelScope(
    patterns: List<String>,
    availableModels: List<Model>,
): ModelScopeResolution {
    val scoped = linkedMapOf<Pair<String, String>, ScopedModel>()
    val diagnostics = mutableListOf<ModelScopeDiagnostic>()

    patterns.forEach { rawPattern ->
        val pattern = rawPattern.trim()
        if (pattern.isEmpty()) {
            return@forEach
        }
        if (pattern.any { it == '*' || it == '?' || it == '[' }) {
            val (globPattern, thinkingLevel) = splitThinkingLevel(pattern)
            val matcher =
                runCatching { globRegex(globPattern) }
                    .getOrElse {
                        diagnostics +=
                            ModelScopeDiagnostic(
                                pattern,
                                "No models match pattern \"$pattern\"",
                            )
                        return@forEach
                    }
            val matches =
                availableModels.filter { model ->
                    matcher.matches("${model.provider}/${model.id}") || matcher.matches(model.id)
                }
            if (matches.isEmpty()) {
                diagnostics +=
                    ModelScopeDiagnostic(
                        pattern,
                        "No models match pattern \"$pattern\"",
                    )
            }
            matches.forEach { model ->
                scoped.putIfAbsent(model.provider to model.id, ScopedModel(model, thinkingLevel))
            }
            return@forEach
        }

        val parsed = parseScopedModelPattern(pattern, availableModels)
        parsed.warning?.let { warning ->
            diagnostics += ModelScopeDiagnostic(pattern, warning)
        }
        val model = parsed.model
        if (model == null) {
            diagnostics +=
                ModelScopeDiagnostic(
                    pattern,
                    "No models match pattern \"$pattern\"",
                )
        } else {
            scoped.putIfAbsent(model.provider to model.id, ScopedModel(model, parsed.thinkingLevel))
        }
    }

    return ModelScopeResolution(scoped.values.toList(), diagnostics)
}

private data class ParsedScopedModel(
    val model: Model?,
    val thinkingLevel: AgentThinkingLevel? = null,
    val warning: String? = null,
)

private fun parseScopedModelPattern(
    pattern: String,
    availableModels: List<Model>,
): ParsedScopedModel {
    tryMatchModel(pattern, availableModels)?.let { return ParsedScopedModel(it) }
    val separator = pattern.lastIndexOf(':')
    if (separator < 0) {
        return ParsedScopedModel(null)
    }
    val prefix = pattern.substring(0, separator)
    val suffix = pattern.substring(separator + 1)
    val thinkingLevel = parseThinkingLevel(suffix)
    val nested = parseScopedModelPattern(prefix, availableModels)
    if (nested.model == null) {
        return nested
    }
    return if (thinkingLevel != null) {
        nested.copy(thinkingLevel = thinkingLevel)
    } else {
        nested.copy(
            warning = "Invalid thinking level \"$suffix\" in pattern \"$pattern\". Using default instead.",
        )
    }
}

private fun tryMatchModel(
    pattern: String,
    availableModels: List<Model>,
): Model? {
    val canonicalMatches =
        availableModels.filter {
            "${it.provider}/${it.id}".equals(pattern, ignoreCase = true)
        }
    if (canonicalMatches.size == 1) {
        return canonicalMatches.single()
    }
    if (canonicalMatches.size > 1) {
        return null
    }

    val idMatches = availableModels.filter { it.id.equals(pattern, ignoreCase = true) }
    if (idMatches.size == 1) {
        return idMatches.single()
    }
    if (idMatches.size > 1) {
        return null
    }

    val partialMatches =
        availableModels.filter { model ->
            model.id.contains(pattern, ignoreCase = true) ||
                model.name.contains(pattern, ignoreCase = true)
        }
    if (partialMatches.isEmpty()) {
        return null
    }
    val aliases = partialMatches.filter { isModelAlias(it.id) }
    return (aliases.ifEmpty { partialMatches })
        .maxByOrNull { it.id.lowercase() }
}

private fun splitThinkingLevel(pattern: String): Pair<String, AgentThinkingLevel?> {
    val separator = pattern.lastIndexOf(':')
    if (separator < 0) {
        return pattern to null
    }
    val thinkingLevel = parseThinkingLevel(pattern.substring(separator + 1))
    return if (thinkingLevel == null) {
        pattern to null
    } else {
        pattern.substring(0, separator) to thinkingLevel
    }
}

private fun isModelAlias(id: String): Boolean =
    id.endsWith("-latest") || !MODEL_DATE_SUFFIX.containsMatchIn(id)

private fun globRegex(pattern: String): Regex {
    val result = StringBuilder("^")
    var index = 0
    while (index < pattern.length) {
        when (val character = pattern[index]) {
            '*' -> result.append(".*")
            '?' -> result.append('.')
            '[' -> {
                val closing = pattern.indexOf(']', index + 1)
                if (closing < 0) {
                    result.append("\\[")
                } else {
                    val body = pattern.substring(index + 1, closing)
                    result.append('[')
                    if (body.startsWith('!')) {
                        result.append('^')
                        appendCharacterClass(result, body.drop(1))
                    } else {
                        appendCharacterClass(result, body)
                    }
                    result.append(']')
                    index = closing
                }
            }

            '\\', '.', '(', ')', '+', '|', '^', '$', '{', '}' ->
                result.append('\\').append(character)

            else -> result.append(character)
        }
        index++
    }
    result.append('$')
    return Regex(result.toString(), RegexOption.IGNORE_CASE)
}

private fun appendCharacterClass(
    target: StringBuilder,
    body: String,
) {
    body.forEachIndexed { index, character ->
        when {
            character == '\\' || character == ']' -> target.append('\\').append(character)
            character == '^' && index == 0 -> target.append("\\^")
            else -> target.append(character)
        }
    }
}

private val MODEL_DATE_SUFFIX = Regex("-\\d{8}$")
