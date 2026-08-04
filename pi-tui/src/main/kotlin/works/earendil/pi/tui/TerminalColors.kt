package works.earendil.pi.tui

import kotlin.math.roundToInt

data class RgbColor(
    val red: Int,
    val green: Int,
    val blue: Int,
)

enum class TerminalColorScheme {
    DARK,
    LIGHT,
}

private val osc11Pattern = Regex("^\\u001B]11;([^\\u0007\\u001B]*)(?:\\u0007|\\u001B\\\\)$", RegexOption.IGNORE_CASE)
private val colorSchemePattern = Regex("^(?:\\u001B\\[\\?997;(1|2)n)+$")

fun isOsc11BackgroundColorResponse(data: String): Boolean = osc11Pattern.matches(data)

fun parseOsc11BackgroundColor(data: String): RgbColor? {
    val value = osc11Pattern.matchEntire(data)?.groupValues?.get(1)?.trim() ?: return null
    if (value.startsWith('#')) {
        val hex = value.drop(1)
        return when {
            hex.matches(Regex("[0-9a-fA-F]{6}")) ->
                RgbColor(
                    hex.substring(0, 2).toInt(16),
                    hex.substring(2, 4).toInt(16),
                    hex.substring(4, 6).toInt(16),
                )

            hex.matches(Regex("[0-9a-fA-F]{12}")) ->
                channelsToColor(
                    hex.substring(0, 4),
                    hex.substring(4, 8),
                    hex.substring(8, 12),
                )

            else -> null
        }
    }

    val channels = value.replace(Regex("^rgba?:", RegexOption.IGNORE_CASE), "").split('/')
    if (channels.size < 3) {
        return null
    }
    return channelsToColor(channels[0], channels[1], channels[2])
}

fun parseTerminalColorSchemeReport(data: String): TerminalColorScheme? =
    when (colorSchemePattern.matchEntire(data)?.groupValues?.get(1)) {
        "1" -> TerminalColorScheme.DARK
        "2" -> TerminalColorScheme.LIGHT
        else -> null
    }

private fun channelsToColor(
    red: String,
    green: String,
    blue: String,
): RgbColor? {
    val parsedRed = parseOscHexChannel(red) ?: return null
    val parsedGreen = parseOscHexChannel(green) ?: return null
    val parsedBlue = parseOscHexChannel(blue) ?: return null
    return RgbColor(parsedRed, parsedGreen, parsedBlue)
}

private fun parseOscHexChannel(value: String): Int? {
    if (!value.matches(Regex("[0-9a-fA-F]+"))) {
        return null
    }
    val maximum = Math.pow(16.0, value.length.toDouble()) - 1
    if (maximum <= 0) {
        return null
    }
    return (value.toLong(16) / maximum * 255).roundToInt()
}
