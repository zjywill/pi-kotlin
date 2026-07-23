package works.earendil.pi.tui

typealias KeyId = String

object Key {
    const val escape = "escape"
    const val esc = "esc"
    const val enter = "enter"
    const val returnKey = "return"
    const val tab = "tab"
    const val space = "space"
    const val backspace = "backspace"
    const val delete = "delete"
    const val insert = "insert"
    const val clear = "clear"
    const val home = "home"
    const val end = "end"
    const val pageUp = "pageUp"
    const val pageDown = "pageDown"
    const val up = "up"
    const val down = "down"
    const val left = "left"
    const val right = "right"
    const val f1 = "f1"
    const val f2 = "f2"
    const val f3 = "f3"
    const val f4 = "f4"
    const val f5 = "f5"
    const val f6 = "f6"
    const val f7 = "f7"
    const val f8 = "f8"
    const val f9 = "f9"
    const val f10 = "f10"
    const val f11 = "f11"
    const val f12 = "f12"

    fun ctrl(key: String): KeyId = "ctrl+$key"

    fun shift(key: String): KeyId = "shift+$key"

    fun alt(key: String): KeyId = "alt+$key"

    fun superKey(key: String): KeyId = "super+$key"

    fun ctrlShift(key: String): KeyId = "ctrl+shift+$key"

    fun ctrlAlt(key: String): KeyId = "ctrl+alt+$key"

    fun ctrlSuper(key: String): KeyId = "ctrl+super+$key"

    fun ctrlShiftAlt(key: String): KeyId = "ctrl+shift+alt+$key"

    fun ctrlShiftSuper(key: String): KeyId = "ctrl+shift+super+$key"
}

enum class KeyEventType {
    PRESS,
    REPEAT,
    RELEASE,
}

private const val SHIFT = 1
private const val ALT = 2
private const val CTRL = 4
private const val SUPER = 8
private const val LOCK_MASK = 64 + 128
private const val ESCAPE_CODEPOINT = 27
private const val TAB_CODEPOINT = 9
private const val ENTER_CODEPOINT = 13
private const val SPACE_CODEPOINT = 32
private const val BACKSPACE_CODEPOINT = 127
private const val KEYPAD_ENTER_CODEPOINT = 57414
private const val ARROW_UP = -1
private const val ARROW_DOWN = -2
private const val ARROW_RIGHT = -3
private const val ARROW_LEFT = -4
private const val DELETE_CODEPOINT = -10
private const val INSERT_CODEPOINT = -11
private const val PAGE_UP_CODEPOINT = -12
private const val PAGE_DOWN_CODEPOINT = -13
private const val HOME_CODEPOINT = -14
private const val END_CODEPOINT = -15

private val symbols = setOf('`', '-', '=', '[', ']', '\\', ';', '\'', ',', '.', '/', '!', '@', '#', '$', '%', '^', '&', '*', '(', ')', '_', '+', '|', '~', '{', '}', ':', '<', '>', '?')
private val keypadEquivalents =
    buildMap {
        (0..9).forEach { digit -> put(57399 + digit, '0'.code + digit) }
        put(57409, '.'.code)
        put(57410, '/'.code)
        put(57411, '*'.code)
        put(57412, '-'.code)
        put(57413, '+'.code)
        put(57415, '='.code)
        put(57416, ','.code)
        put(57417, ARROW_LEFT)
        put(57418, ARROW_RIGHT)
        put(57419, ARROW_UP)
        put(57420, ARROW_DOWN)
        put(57421, PAGE_UP_CODEPOINT)
        put(57422, PAGE_DOWN_CODEPOINT)
        put(57423, HOME_CODEPOINT)
        put(57424, END_CODEPOINT)
        put(57425, INSERT_CODEPOINT)
        put(57426, DELETE_CODEPOINT)
    }
private val legacySequences =
    mapOf(
        "\u001BOA" to "up",
        "\u001BOB" to "down",
        "\u001BOC" to "right",
        "\u001BOD" to "left",
        "\u001B[A" to "up",
        "\u001B[B" to "down",
        "\u001B[C" to "right",
        "\u001B[D" to "left",
        "\u001B[H" to "home",
        "\u001BOH" to "home",
        "\u001B[1~" to "home",
        "\u001B[7~" to "home",
        "\u001B[F" to "end",
        "\u001BOF" to "end",
        "\u001B[4~" to "end",
        "\u001B[8~" to "end",
        "\u001B[E" to "clear",
        "\u001BOE" to "clear",
        "\u001BOe" to "ctrl+clear",
        "\u001B[e" to "shift+clear",
        "\u001B[2~" to "insert",
        "\u001B[2$" to "shift+insert",
        "\u001B[2^" to "ctrl+insert",
        "\u001B[3~" to "delete",
        "\u001B[3$" to "shift+delete",
        "\u001B[3^" to "ctrl+delete",
        "\u001B[5~" to "pageUp",
        "\u001B[[5~" to "pageUp",
        "\u001B[6~" to "pageDown",
        "\u001B[[6~" to "pageDown",
        "\u001B[a" to "shift+up",
        "\u001B[b" to "shift+down",
        "\u001B[c" to "shift+right",
        "\u001B[d" to "shift+left",
        "\u001BOa" to "ctrl+up",
        "\u001BOb" to "ctrl+down",
        "\u001BOc" to "ctrl+right",
        "\u001BOd" to "ctrl+left",
        "\u001B[5$" to "shift+pageUp",
        "\u001B[6$" to "shift+pageDown",
        "\u001B[7$" to "shift+home",
        "\u001B[8$" to "shift+end",
        "\u001B[5^" to "ctrl+pageUp",
        "\u001B[6^" to "ctrl+pageDown",
        "\u001B[7^" to "ctrl+home",
        "\u001B[8^" to "ctrl+end",
        "\u001BOP" to "f1",
        "\u001BOQ" to "f2",
        "\u001BOR" to "f3",
        "\u001BOS" to "f4",
        "\u001B[11~" to "f1",
        "\u001B[12~" to "f2",
        "\u001B[13~" to "f3",
        "\u001B[14~" to "f4",
        "\u001B[[A" to "f1",
        "\u001B[[B" to "f2",
        "\u001B[[C" to "f3",
        "\u001B[[D" to "f4",
        "\u001B[[E" to "f5",
        "\u001B[15~" to "f5",
        "\u001B[17~" to "f6",
        "\u001B[18~" to "f7",
        "\u001B[19~" to "f8",
        "\u001B[20~" to "f9",
        "\u001B[21~" to "f10",
        "\u001B[23~" to "f11",
        "\u001B[24~" to "f12",
        "\u001Bb" to "alt+left",
        "\u001Bf" to "alt+right",
        "\u001Bp" to "alt+up",
        "\u001Bn" to "alt+down",
    )

private var kittyProtocolActive = false

fun setKittyProtocolActive(active: Boolean) {
    kittyProtocolActive = active
}

fun isKittyProtocolActive(): Boolean = kittyProtocolActive

fun isKeyRelease(data: String): Boolean =
    "\u001B[200~" !in data &&
        listOf(":3u", ":3~", ":3A", ":3B", ":3C", ":3D", ":3H", ":3F").any(data::contains)

fun isKeyRepeat(data: String): Boolean =
    "\u001B[200~" !in data &&
        listOf(":2u", ":2~", ":2A", ":2B", ":2C", ":2D", ":2H", ":2F").any(data::contains)

fun matchesKey(
    data: String,
    keyId: KeyId,
): Boolean {
    val expected = canonicalKeyId(keyId) ?: return false
    val parsed = parseKey(data)
    if (parsed != null && canonicalKeyId(parsed) == expected) {
        return true
    }

    val parts = expected.split('+')
    val key = parts.last()
    val modifiers = parts.dropLast(1).toSet()
    val rawControl = rawControlCharacter(key)
    if (modifiers == setOf("ctrl") && rawControl != null && data == rawControl.toString()) {
        return true
    }
    if (modifiers == setOf("ctrl", "alt") && !kittyProtocolActive && rawControl != null) {
        return data == "\u001B$rawControl"
    }
    if (modifiers == setOf("alt") && !kittyProtocolActive && key.length == 1) {
        return data == "\u001B$key"
    }
    if (modifiers == setOf("shift") && key.length == 1 && key[0].isLetter()) {
        return data == key.uppercase()
    }
    if (expected == "ctrl+_" && data == "\u001F") {
        return true
    }
    if (expected == "ctrl+alt+_" && data == "\u001B\u001F") {
        return true
    }
    return false
}

fun parseKey(data: String): KeyId? {
    parseKittySequence(data)?.let { parsed ->
        return formatParsedKey(parsed.codepoint, parsed.modifier, parsed.baseLayoutKey)
    }
    parseModifyOtherKeys(data)?.let { parsed ->
        return formatParsedKey(parsed.codepoint, parsed.modifier)
    }

    if (kittyProtocolActive && (data == "\u001B\r" || data == "\n")) {
        return "shift+enter"
    }
    legacySequences[data]?.let { return it }
    when (data) {
        "\u001B" -> return "escape"
        "\u001C" -> return "ctrl+\\"
        "\u001D" -> return "ctrl+]"
        "\u001F" -> return "ctrl+-"
        "\u001B\u001B" -> return "ctrl+alt+["
        "\u001B\u001C" -> return "ctrl+alt+\\"
        "\u001B\u001D" -> return "ctrl+alt+]"
        "\u001B\u001F" -> return "ctrl+alt+-"
        "\t" -> return "tab"
        "\r", "\u001BOM" -> return "enter"
        "\n" -> return if (kittyProtocolActive) "shift+enter" else "enter"
        "\u0000" -> return "ctrl+space"
        " " -> return "space"
        "\u007F" -> return "backspace"
        "\b" -> return if (isWindowsTerminalSession()) "ctrl+backspace" else "backspace"
        "\u001B[Z" -> return "shift+tab"
        "\u001B\r" -> if (!kittyProtocolActive) return "alt+enter"
        "\u001B " -> if (!kittyProtocolActive) return "alt+space"
        "\u001B\u007F", "\u001B\b" -> return "alt+backspace"
        "\u001BB" -> if (!kittyProtocolActive) return "alt+left"
        "\u001BF" -> if (!kittyProtocolActive) return "alt+right"
    }

    if (!kittyProtocolActive && data.length == 2 && data[0] == '\u001B') {
        val code = data[1].code
        if (code in 1..26) {
            return "ctrl+alt+${('a'.code + code - 1).toChar()}"
        }
        if (data[1].isLowerCase() || data[1].isDigit() || data[1] in symbols) {
            return "alt+${data[1]}"
        }
    }
    if (data.length == 1) {
        val code = data[0].code
        if (code in 1..26) {
            return "ctrl+${('a'.code + code - 1).toChar()}"
        }
        if (code in 32..126) {
            return data
        }
    }
    return null
}

fun decodeKittyPrintable(data: String): String? {
    val parsed = parseKittySequence(data) ?: return null
    val modifier = parsed.modifier and LOCK_MASK.inv()
    if (modifier and (ALT or CTRL or SUPER) != 0 || modifier and SHIFT.inv() != 0) {
        return null
    }
    var codepoint =
        if (modifier and SHIFT != 0 && parsed.shiftedKey != null) {
            parsed.shiftedKey
        } else {
            parsed.codepoint
        }
    codepoint = normalizeFunctionalCodepoint(requireNotNull(codepoint))
    if (codepoint < 32 || !Character.isValidCodePoint(codepoint)) {
        return null
    }
    return String(Character.toChars(codepoint))
}

fun decodePrintableKey(data: String): String? {
    decodeKittyPrintable(data)?.let { return it }
    val parsed = parseModifyOtherKeys(data) ?: return null
    val modifier = parsed.modifier and LOCK_MASK.inv()
    if (modifier and SHIFT.inv() != 0 || parsed.codepoint < 32 || !Character.isValidCodePoint(parsed.codepoint)) {
        return null
    }
    return String(Character.toChars(parsed.codepoint))
}

private data class ParsedKittySequence(
    val codepoint: Int,
    val shiftedKey: Int?,
    val baseLayoutKey: Int?,
    val modifier: Int,
    val eventType: KeyEventType,
)

private data class ParsedModifyOtherKeys(
    val codepoint: Int,
    val modifier: Int,
)

private fun parseKittySequence(data: String): ParsedKittySequence? {
    Regex("^\\u001B\\[(\\d+)(?::(\\d*))?(?::(\\d+))?(?:;(\\d+))?(?::(\\d+))?u$")
        .matchEntire(data)
        ?.let { match ->
            return ParsedKittySequence(
                codepoint = match.groupValues[1].toInt(),
                shiftedKey = match.groupValues[2].takeIf(String::isNotEmpty)?.toInt(),
                baseLayoutKey = match.groupValues[3].takeIf(String::isNotEmpty)?.toInt(),
                modifier = (match.groupValues[4].takeIf(String::isNotEmpty)?.toInt() ?: 1) - 1,
                eventType = parseEventType(match.groupValues[5]),
            )
        }
    Regex("^\\u001B\\[1;(\\d+)(?::(\\d+))?([ABCD])$")
        .matchEntire(data)
        ?.let { match ->
            val codepoint =
                when (match.groupValues[3]) {
                    "A" -> ARROW_UP
                    "B" -> ARROW_DOWN
                    "C" -> ARROW_RIGHT
                    else -> ARROW_LEFT
                }
            return ParsedKittySequence(
                codepoint,
                null,
                null,
                match.groupValues[1].toInt() - 1,
                parseEventType(match.groupValues[2]),
            )
        }
    Regex("^\\u001B\\[(\\d+)(?:;(\\d+))?(?::(\\d+))?~$")
        .matchEntire(data)
        ?.let { match ->
            val codepoint =
                when (match.groupValues[1].toInt()) {
                    2 -> INSERT_CODEPOINT
                    3 -> DELETE_CODEPOINT
                    5 -> PAGE_UP_CODEPOINT
                    6 -> PAGE_DOWN_CODEPOINT
                    7 -> HOME_CODEPOINT
                    8 -> END_CODEPOINT
                    else -> return@let
                }
            return ParsedKittySequence(
                codepoint,
                null,
                null,
                (match.groupValues[2].takeIf(String::isNotEmpty)?.toInt() ?: 1) - 1,
                parseEventType(match.groupValues[3]),
            )
        }
    Regex("^\\u001B\\[1;(\\d+)(?::(\\d+))?([HF])$")
        .matchEntire(data)
        ?.let { match ->
            return ParsedKittySequence(
                if (match.groupValues[3] == "H") HOME_CODEPOINT else END_CODEPOINT,
                null,
                null,
                match.groupValues[1].toInt() - 1,
                parseEventType(match.groupValues[2]),
            )
        }
    return null
}

private fun parseModifyOtherKeys(data: String): ParsedModifyOtherKeys? {
    val match = Regex("^\\u001B\\[27;(\\d+);(\\d+)~$").matchEntire(data) ?: return null
    return ParsedModifyOtherKeys(match.groupValues[2].toInt(), match.groupValues[1].toInt() - 1)
}

private fun parseEventType(value: String): KeyEventType =
    when (value.toIntOrNull()) {
        2 -> KeyEventType.REPEAT
        3 -> KeyEventType.RELEASE
        else -> KeyEventType.PRESS
    }

private fun formatParsedKey(
    rawCodepoint: Int,
    modifier: Int,
    baseLayoutKey: Int? = null,
): KeyId? {
    val supportedMask = SHIFT or ALT or CTRL or SUPER
    val effectiveModifier = modifier and LOCK_MASK.inv()
    if (effectiveModifier and supportedMask.inv() != 0) {
        return null
    }
    val normalized = normalizeShiftedLetter(normalizeFunctionalCodepoint(rawCodepoint), modifier)
    val authoritative = normalized in 'a'.code..'z'.code || normalized in '0'.code..'9'.code || normalized.toChar() in symbols
    val codepoint = if (authoritative) normalized else baseLayoutKey ?: normalized
    val keyName =
        when (codepoint) {
            ESCAPE_CODEPOINT -> "escape"
            TAB_CODEPOINT -> "tab"
            ENTER_CODEPOINT, KEYPAD_ENTER_CODEPOINT -> "enter"
            SPACE_CODEPOINT -> "space"
            BACKSPACE_CODEPOINT -> "backspace"
            DELETE_CODEPOINT -> "delete"
            INSERT_CODEPOINT -> "insert"
            HOME_CODEPOINT -> "home"
            END_CODEPOINT -> "end"
            PAGE_UP_CODEPOINT -> "pageUp"
            PAGE_DOWN_CODEPOINT -> "pageDown"
            ARROW_UP -> "up"
            ARROW_DOWN -> "down"
            ARROW_LEFT -> "left"
            ARROW_RIGHT -> "right"
            in '0'.code..'9'.code, in 'a'.code..'z'.code -> codepoint.toChar().toString()
            else -> codepoint.toChar().takeIf { it in symbols }?.toString()
        } ?: return null
    return formatWithModifiers(keyName, modifier)
}

private fun formatWithModifiers(
    keyName: String,
    modifier: Int,
): String? {
    val effective = modifier and LOCK_MASK.inv()
    if (effective and (SHIFT or ALT or CTRL or SUPER).inv() != 0) {
        return null
    }
    val modifiers = mutableListOf<String>()
    if (effective and SHIFT != 0) modifiers += "shift"
    if (effective and CTRL != 0) modifiers += "ctrl"
    if (effective and ALT != 0) modifiers += "alt"
    if (effective and SUPER != 0) modifiers += "super"
    return (modifiers + keyName).joinToString("+")
}

private fun canonicalKeyId(keyId: String): String? {
    val parts = keyId.lowercase().split('+')
    val rawKey = parts.lastOrNull()?.takeIf(String::isNotEmpty) ?: return null
    val key =
        when (rawKey) {
            "esc" -> "escape"
            "return" -> "enter"
            "pageup" -> "pageUp"
            "pagedown" -> "pageDown"
            else -> rawKey
        }
    val modifiers = parts.dropLast(1).toSet()
    val ordered = listOf("shift", "ctrl", "alt", "super").filter(modifiers::contains)
    return (ordered + key).joinToString("+")
}

private fun normalizeFunctionalCodepoint(codepoint: Int): Int = keypadEquivalents[codepoint] ?: codepoint

private fun normalizeShiftedLetter(
    codepoint: Int,
    modifier: Int,
): Int =
    if (modifier and LOCK_MASK.inv() and SHIFT != 0 && codepoint in 'A'.code..'Z'.code) {
        codepoint + ('a'.code - 'A'.code)
    } else {
        codepoint
    }

private fun rawControlCharacter(key: String): Char? {
    val character = key.lowercase().singleOrNull() ?: return null
    return when {
        character in 'a'..'z' || character in setOf('[', '\\', ']', '_') -> (character.code and 0x1F).toChar()
        character == '-' -> '\u001F'
        else -> null
    }
}

private fun isWindowsTerminalSession(): Boolean =
    !System.getenv("WT_SESSION").isNullOrBlank() &&
        System.getenv("SSH_CONNECTION").isNullOrBlank() &&
        System.getenv("SSH_CLIENT").isNullOrBlank() &&
        System.getenv("SSH_TTY").isNullOrBlank()
