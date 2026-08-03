package works.earendil.pi.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeybindingsTest {
    @Test
    fun `keeps default aliases and permits shared defaults`() {
        val keybindings = KeybindingsManager(TUI_KEYBINDINGS)
        assertEquals(listOf("shift+enter", "ctrl+j"), keybindings.getKeys("tui.input.newLine"))
        assertTrue(keybindings.matches("\n", "tui.input.newLine"))
        assertEquals(listOf("enter"), keybindings.getKeys("tui.select.confirm"))
        assertEquals(listOf("up"), keybindings.getKeys("tui.editor.cursorUp"))
        assertEquals(listOf("pageUp"), keybindings.getKeys("tui.altScreen.pageUp"))
        assertEquals(listOf("ctrl+shift+down"), keybindings.getKeys("tui.altScreen.nextPrompt"))
    }

    @Test
    fun `reports direct user conflicts without evicting defaults`() {
        val keybindings =
            KeybindingsManager(
                TUI_KEYBINDINGS,
                mapOf(
                    "tui.input.submit" to listOf("ctrl+x"),
                    "tui.select.confirm" to listOf("ctrl+x"),
                ),
            )
        assertEquals(
            listOf(KeybindingConflict("ctrl+x", listOf("tui.input.submit", "tui.select.confirm"))),
            keybindings.getConflicts(),
        )
        assertEquals(listOf("left", "ctrl+b"), keybindings.getKeys("tui.editor.cursorLeft"))
    }
}
