package works.earendil.pi.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WordNavigationTest {
    @Test
    fun `moves backward across words punctuation and paths`() {
        assertEquals(6, findWordBackward("hello world", 11))
        assertEquals(4, findWordBackward("foo.bar", 7))
        assertEquals(3, findWordBackward("foo.bar", 4))
        assertEquals(8, findWordBackward("path/to/file", 12))
        assertEquals(7, findWordBackward("path/to/file", 8))
        assertEquals(3, findWordBackward("foo...bar", 6))
        assertEquals(2, findWordBackward("  hello  ", 9))
    }

    @Test
    fun `moves forward across words punctuation and paths`() {
        assertEquals(5, findWordForward("hello world", 0))
        assertEquals(11, findWordForward("hello world", 5))
        assertEquals(3, findWordForward("foo.bar", 0))
        assertEquals(4, findWordForward("foo.bar", 3))
        assertEquals(5, findWordForward("path/to/file", 4))
        assertEquals(6, findWordForward("foo...bar", 3))
        assertEquals(7, findWordForward("  hello  ", 0))
    }

    @Test
    fun `walks CJK text to the end`() {
        val text = "你好世界 test"
        var position = 0
        while (position < text.length) {
            val next = findWordForward(text, position)
            assertTrue(next > position)
            position = next
        }
        assertEquals(text.length, position)
    }
}
