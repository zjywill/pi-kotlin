package works.earendil.pi.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalColorsTest {
    @Test
    fun `parses strict OSC 11 color responses`() {
        assertEquals(
            RgbColor(0, 128, 255),
            parseOsc11BackgroundColor("\u001B]11;rgb:0000/8000/ffff\u0007"),
        )
        assertEquals(RgbColor(255, 255, 255), parseOsc11BackgroundColor("\u001B]11;#ffffff\u001B\\"))
        assertEquals(RgbColor(0, 0, 0), parseOsc11BackgroundColor("\u001B]11;#000000\u0007"))
        assertTrue(isOsc11BackgroundColorResponse("\u001B]11;not-a-color\u0007"))
        assertNull(parseOsc11BackgroundColor("x\u001B]11;#ffffff\u0007"))
        assertNull(parseOsc11BackgroundColor("\u001B]10;#ffffff\u0007"))
    }

    @Test
    fun `parses terminal color scheme report`() {
        assertEquals(TerminalColorScheme.DARK, parseTerminalColorSchemeReport("\u001B[?997;1n"))
        assertEquals(TerminalColorScheme.LIGHT, parseTerminalColorSchemeReport("\u001B[?997;2n"))
        assertNull(parseTerminalColorSchemeReport("\u001B[?997;3n"))
    }
}
