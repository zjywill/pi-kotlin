package works.earendil.pi.tui

import com.ibm.icu.text.BreakIterator
import java.util.Locale

private val punctuation = "(){}[]<>.,;:'\"!?+-=*/\\|&%^$#@~`"

fun findWordBackward(
    text: String,
    cursor: Int,
): Int {
    if (cursor <= 0) {
        return 0
    }
    var position = cursor.coerceAtMost(text.length)
    while (position > 0 && text.codePointBefore(position).let(Character::isWhitespace)) {
        position -= Character.charCount(text.codePointBefore(position))
    }
    if (position == 0) {
        return 0
    }

    val previous = text.codePointBefore(position)
    return when {
        previous.toChar() in punctuation -> {
            while (
                position > 0 &&
                text.codePointBefore(position).toChar() in punctuation
            ) {
                position -= Character.charCount(text.codePointBefore(position))
            }
            position
        }

        isCjk(previous) -> previousWordBoundary(text, position)
        else -> {
            while (position > 0) {
                val codePoint = text.codePointBefore(position)
                if (Character.isWhitespace(codePoint) || codePoint.toChar() in punctuation || isCjk(codePoint)) {
                    break
                }
                position -= Character.charCount(codePoint)
            }
            position
        }
    }
}

fun findWordForward(
    text: String,
    cursor: Int,
): Int {
    if (cursor >= text.length) {
        return text.length
    }
    var position = cursor.coerceAtLeast(0)
    while (position < text.length && text.codePointAt(position).let(Character::isWhitespace)) {
        position += Character.charCount(text.codePointAt(position))
    }
    if (position >= text.length) {
        return text.length
    }

    val next = text.codePointAt(position)
    return when {
        next.toChar() in punctuation -> {
            while (position < text.length && text.codePointAt(position).toChar() in punctuation) {
                position += Character.charCount(text.codePointAt(position))
            }
            position
        }

        isCjk(next) -> nextWordBoundary(text, position)
        else -> {
            while (position < text.length) {
                val codePoint = text.codePointAt(position)
                if (Character.isWhitespace(codePoint) || codePoint.toChar() in punctuation || isCjk(codePoint)) {
                    break
                }
                position += Character.charCount(codePoint)
            }
            position
        }
    }
}

private fun previousWordBoundary(
    text: String,
    position: Int,
): Int {
    val iterator = BreakIterator.getWordInstance(Locale.ROOT)
    iterator.setText(text)
    return iterator.preceding(position).takeIf { it != BreakIterator.DONE } ?: 0
}

private fun nextWordBoundary(
    text: String,
    position: Int,
): Int {
    val iterator = BreakIterator.getWordInstance(Locale.ROOT)
    iterator.setText(text)
    return iterator.following(position).takeIf { it != BreakIterator.DONE } ?: text.length
}

private fun isCjk(codePoint: Int): Boolean =
    Character.UnicodeScript.of(codePoint) in
        setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.BOPOMOFO,
        )
