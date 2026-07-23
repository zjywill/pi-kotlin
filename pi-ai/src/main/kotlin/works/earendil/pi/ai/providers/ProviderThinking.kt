package works.earendil.pi.ai.providers

import works.earendil.pi.ai.Model
import works.earendil.pi.ai.ModelThinkingLevel
import works.earendil.pi.ai.ThinkingBudgets
import works.earendil.pi.ai.ThinkingLevel

internal fun Model.clampThinkingLevel(level: ThinkingLevel): ModelThinkingLevel {
    val requested = ModelThinkingLevel.valueOf(level.name)
    val available = supportedThinkingLevels()
    if (requested in available) {
        return requested
    }
    val requestedIndex = ModelThinkingLevel.entries.indexOf(requested)
    for (index in requestedIndex until ModelThinkingLevel.entries.size) {
        val candidate = ModelThinkingLevel.entries[index]
        if (candidate in available) {
            return candidate
        }
    }
    for (index in requestedIndex - 1 downTo 0) {
        val candidate = ModelThinkingLevel.entries[index]
        if (candidate in available) {
            return candidate
        }
    }
    return available.firstOrNull() ?: ModelThinkingLevel.OFF
}

internal fun Model.mappedThinkingLevel(level: ThinkingLevel): String? {
    val clamped = clampThinkingLevel(level)
    return thinkingLevelMap[clamped] ?: clamped.name.lowercase()
}

internal fun Model.supportsThinkingOff(): Boolean =
    !thinkingLevelMap.containsKey(ModelThinkingLevel.OFF) ||
        thinkingLevelMap[ModelThinkingLevel.OFF] != null

internal fun Model.mappedThinkingOff(default: String): String? =
    if (supportsThinkingOff()) {
        thinkingLevelMap[ModelThinkingLevel.OFF] ?: default
    } else {
        null
    }

internal fun thinkingBudget(
    level: ModelThinkingLevel,
    custom: ThinkingBudgets?,
): Int {
    val clamped =
        when (level) {
            ModelThinkingLevel.MINIMAL -> ModelThinkingLevel.MINIMAL
            ModelThinkingLevel.LOW -> ModelThinkingLevel.LOW
            ModelThinkingLevel.MEDIUM -> ModelThinkingLevel.MEDIUM
            else -> ModelThinkingLevel.HIGH
        }
    return when (clamped) {
        ModelThinkingLevel.MINIMAL -> custom?.minimal ?: 1_024
        ModelThinkingLevel.LOW -> custom?.low ?: 2_048
        ModelThinkingLevel.MEDIUM -> custom?.medium ?: 8_192
        else -> custom?.high ?: 16_384
    }
}

private fun Model.supportedThinkingLevels(): List<ModelThinkingLevel> {
    if (!reasoning) {
        return listOf(ModelThinkingLevel.OFF)
    }
    return ModelThinkingLevel.entries.filter { level ->
        val mapped = thinkingLevelMap[level]
        when {
            mapped == null && thinkingLevelMap.containsKey(level) -> false
            level == ModelThinkingLevel.XHIGH || level == ModelThinkingLevel.MAX ->
                thinkingLevelMap.containsKey(level)
            else -> true
        }
    }
}
