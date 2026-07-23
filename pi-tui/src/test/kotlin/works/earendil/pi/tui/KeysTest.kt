package works.earendil.pi.tui

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeysTest {
    @AfterTest
    fun resetKitty() {
        setKittyProtocolActive(false)
    }

    @Test
    fun `matches and parses Kitty alternate layout and modifiers`() {
        setKittyProtocolActive(true)
        assertTrue(matchesKey("\u001B[1089::99;5u", "ctrl+c"))
        assertEquals("ctrl+c", parseKey("\u001B[1089::99;5u"))
        assertTrue(matchesKey("\u001B[107;9u", "super+k"))
        assertEquals("ctrl+super+k", parseKey("\u001B[107;13u"))
        assertEquals("shift+ctrl+super+k", parseKey("\u001B[107;14u"))
        assertFalse(matchesKey("\u001B[107;13u", "super+k"))
        assertTrue(matchesKey("\u001B[49;5u", "ctrl+1"))
    }

    @Test
    fun `normalizes Kitty keypad keys`() {
        setKittyProtocolActive(true)
        assertEquals("0", parseKey("\u001B[57399u"))
        assertEquals("+", parseKey("\u001B[57413u"))
        assertEquals("left", parseKey("\u001B[57417u"))
        assertEquals("delete", parseKey("\u001B[57426u"))
        assertEquals("1", decodeKittyPrintable("\u001B[57400u"))
        assertNull(decodeKittyPrintable("\u001B[57417u"))
    }

    @Test
    fun `matches xterm modifyOtherKeys`() {
        assertTrue(matchesKey("\u001B[27;5;99~", "ctrl+c"))
        assertEquals("ctrl+c", parseKey("\u001B[27;5;99~"))
        assertEquals("shift+enter", parseKey("\u001B[27;2;13~"))
        assertEquals("alt+tab", parseKey("\u001B[27;3;9~"))
        assertEquals("ctrl+backspace", parseKey("\u001B[27;5;127~"))
        assertEquals("ctrl+/", parseKey("\u001B[27;5;47~"))
        assertEquals("E", decodePrintableKey("\u001B[27;2;69~"))
        assertNull(decodePrintableKey("\u001B[27;6;69~"))
    }

    @Test
    fun `matches legacy control alt navigation and function keys`() {
        assertTrue(matchesKey("\u0003", "ctrl+c"))
        assertTrue(matchesKey("\u001C", "ctrl+\\"))
        assertTrue(matchesKey("\u001F", "ctrl+_"))
        assertEquals("ctrl+-", parseKey("\u001F"))
        assertTrue(matchesKey("\u001B\u0003", "ctrl+alt+c"))
        assertEquals("alt+a", parseKey("\u001Ba"))
        assertEquals("up", parseKey("\u001B[A"))
        assertEquals("home", parseKey("\u001BOH"))
        assertEquals("f12", parseKey("\u001B[24~"))
        assertEquals("ctrl+insert", parseKey("\u001B[2^"))
        assertEquals("alt+up", parseKey("\u001Bp"))
    }

    @Test
    fun `uses kitty mode for ambiguous newline and alt sequences`() {
        assertEquals("enter", parseKey("\n"))
        setKittyProtocolActive(true)
        assertEquals("shift+enter", parseKey("\n"))
        assertTrue(matchesKey("\n", "shift+enter"))
        assertFalse(matchesKey("\n", "enter"))
        assertNull(parseKey("\u001Ba"))
        assertTrue(matchesKey("\u001B\b", "alt+backspace"))
    }

    @Test
    fun `recognizes repeat and release without inspecting paste contents`() {
        assertTrue(isKeyRelease("\u001B[99;5:3u"))
        assertTrue(isKeyRepeat("\u001B[99;5:2u"))
        assertFalse(isKeyRelease("\u001B[200~90:62:3F:A5\u001B[201~"))
        assertFalse(isKeyRepeat("\u001B[200~value:2F\u001B[201~"))
    }
}
