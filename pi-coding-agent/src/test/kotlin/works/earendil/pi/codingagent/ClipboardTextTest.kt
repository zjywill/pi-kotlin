package works.earendil.pi.codingagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipboardTextTest {
    @Test
    fun `Wayland text is preferred and empty selection does not fall back`() {
        val calls = mutableListOf<List<String>>()
        val result =
            readClipboardText(
                environment = mapOf("WAYLAND_DISPLAY" to "wayland-0"),
                osName = "Linux",
            ) { command ->
                calls += command
                when (command.first()) {
                    "wl-paste" -> ""
                    else -> "stale X11"
                }
            }

        assertNull(result)
        assertEquals(listOf("wl-paste", "--no-newline", "--type", "text"), calls.single())
    }

    @Test
    fun `Wayland command failure falls back to X11`() {
        val calls = mutableListOf<List<String>>()
        val result =
            readClipboardText(
                environment = mapOf("WAYLAND_DISPLAY" to "wayland-0"),
                osName = "Linux",
            ) { command ->
                calls += command
                when (command.first()) {
                    "wl-paste" -> null
                    "xclip" -> "fallback"
                    else -> null
                }
            }

        assertEquals("fallback", result)
        assertEquals(listOf("wl-paste", "xclip"), calls.map(List<String>::first))
    }
}
