package works.earendil.pi.codingagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `clipboard writes prefer Wayland and fall back to X11`() {
        val calls = mutableListOf<List<String>>()

        val copied =
            writeClipboardText(
                text = "message",
                environment =
                    mapOf(
                        "WAYLAND_DISPLAY" to "wayland-0",
                        "DISPLAY" to ":0",
                    ),
                osName = "Linux",
            ) { command, text ->
                calls += command
                assertEquals("message", text)
                command.first() == "xclip"
            }

        assertTrue(copied)
        assertEquals(listOf("wl-copy", "xclip"), calls.map(List<String>::first))
    }

    @Test
    fun `clipboard writes fail without a local clipboard command`() {
        assertFalse(
            writeClipboardText(
                text = "message",
                environment = emptyMap(),
                osName = "Linux",
            ) { _, _ -> true },
        )
    }
}
