package works.earendil.pi.tui

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TerminalImageTest {
    @Test
    fun `detects terminal image protocols conservatively`() {
        assertEquals(
            TerminalImageProtocol.KITTY,
            detectTerminalCapabilities(mapOf("TERM_PROGRAM" to "ghostty")).images,
        )
        assertEquals(
            TerminalImageProtocol.ITERM2,
            detectTerminalCapabilities(mapOf("ITERM_SESSION_ID" to "session")).images,
        )
        assertEquals(
            null,
            detectTerminalCapabilities(
                mapOf(
                    "TMUX" to "socket",
                    "KITTY_WINDOW_ID" to "1",
                ),
            ).images,
        )
        assertEquals(
            TerminalCapabilities(images = null, trueColor = true, hyperlinks = false),
            detectTerminalCapabilities(emptyMap(), osName = "Windows 11"),
        )
    }

    @Test
    fun `renders fallback within terminal width`() {
        val lines =
            renderTerminalImageLines(
                base64Data = pngBase64(width = 100, height = 50),
                mimeType = "image/png",
                width = 18,
                filename = "${System.getProperty("user.home")}/long/path/image.png",
                capabilities = TerminalCapabilities(null, trueColor = true, hyperlinks = false),
            )

        assertEquals(1, lines.size)
        assertTrue(visibleWidth(lines.single()) <= 18)
        assertTrue(lines.single().startsWith("[Image: ~/"))
    }

    @Test
    fun `creates placement-only commands and crops kitty rows`() {
        val imageId = 42L
        registerKittyImageMetadata(
            KittyImageMetadata(
                imageId = imageId,
                columns = 3,
                rows = 3,
                widthPx = 100,
                heightPx = 100,
            ),
        )
        val transmission =
            encodeKitty(
                base64Data = "A".repeat(8_192),
                columns = 3,
                rows = 3,
                imageId = imageId,
                moveCursor = false,
            )
        val cropped = cropKittyImageLine(transmission, hiddenRows = 2, visibleRows = 1)
        val placement = assertNotNull(getKittyImagePlacement("left $cropped right"))

        assertEquals(imageId, placement.imageId)
        assertEquals(cropped.length, placement.transmissionBytes)
        assertEquals(40_000L, placement.estimatedDecodedBytes)
        assertTrue(placement.sequence.contains("a=p,q=2"))
        assertTrue(placement.sequence.contains("y=66"))
        assertTrue(placement.sequence.contains("h=34"))
        assertTrue(placement.sequence.contains("r=1"))
        assertFalse(placement.replacementLine.contains("AAAA"))
    }

    @Test
    fun `iTerm image payload includes decoded byte size`() {
        assertEquals(
            "\u001B]1337;File=inline=1;size=3;width=20:QUFB\u0007",
            encodeITerm2("QUFB", width = 20),
        )
    }

    private fun pngBase64(
        width: Int,
        height: Int,
    ): String {
        val bytes = ByteArray(24)
        bytes[0] = 0x89.toByte()
        bytes[1] = 0x50
        bytes[2] = 0x4e
        bytes[3] = 0x47
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(16, width)
            putInt(20, height)
        }
        return Base64.getEncoder().encodeToString(bytes)
    }
}
