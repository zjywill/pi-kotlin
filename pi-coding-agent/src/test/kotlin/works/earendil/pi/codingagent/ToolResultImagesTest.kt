package works.earendil.pi.codingagent

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolResultImagesTest {
    @Test
    fun `returns the original list when it contains no images`() {
        val content = listOf(TextContent("text"))

        assertSame(content, normalizeToolResultImages(content))
    }

    @Test
    fun `resizes oversized tool images before persistence`() {
        val content = listOf(image(2_400, 4_800, "png", "image/png"))

        val normalized = normalizeToolResultImages(content)

        val result = normalized.first() as ImageContent
        val decoded = ImageIO.read(java.io.ByteArrayInputStream(Base64.getDecoder().decode(result.data)))
        assertTrue(decoded.width <= 2_000)
        assertTrue(decoded.height <= 2_000)
        assertTrue((normalized[1] as TextContent).text.contains("original 2400x4800"))
    }

    @Test
    fun `honors disabled auto resize`() {
        val content = listOf(image(2_400, 4_800, "png", "image/png"))

        assertSame(content, normalizeToolResultImages(content, autoResizeImages = false))
    }

    @Test
    fun `converts unsupported image formats even when resize is disabled`() {
        val content = listOf(image(1, 1, "bmp", "image/bmp"))

        val normalized = normalizeToolResultImages(content, autoResizeImages = false)

        assertEquals("image/png", (normalized[0] as ImageContent).mimeType)
        assertEquals("[Image converted from image/bmp to image/png.]", (normalized[1] as TextContent).text)
    }

    private fun image(
        width: Int,
        height: Int,
        format: String,
        mimeType: String,
    ): ImageContent {
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        check(ImageIO.write(buffered, format, output))
        return ImageContent(Base64.getEncoder().encodeToString(output.toByteArray()), mimeType)
    }
}
