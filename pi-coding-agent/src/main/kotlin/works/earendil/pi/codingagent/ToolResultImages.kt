package works.earendil.pi.codingagent

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.TextContent

internal fun normalizeToolResultImages(
    content: List<ContentBlock>,
    autoResizeImages: Boolean = true,
): List<ContentBlock> {
    if (content.none { it is ImageContent }) {
        return content
    }
    val normalized = mutableListOf<ContentBlock>()
    var changed = false
    content.forEach { block ->
        if (block !is ImageContent) {
            normalized += block
            return@forEach
        }
        val processed = processToolResultImage(block, autoResizeImages)
        if (processed == null) {
            normalized += block
        } else {
            normalized += processed.blocks
            changed = changed || processed.changed
        }
    }
    return if (changed) normalized else content
}

private data class ProcessedImage(
    val blocks: List<ContentBlock>,
    val changed: Boolean,
)

private fun processToolResultImage(
    image: ImageContent,
    autoResizeImages: Boolean,
): ProcessedImage? {
    val bytes = runCatching { Base64.getDecoder().decode(image.data) }.getOrNull() ?: return null
    val decoded = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
    val supportedMime = image.mimeType in PROVIDER_IMAGE_MIME_TYPES
    val oversized = decoded.width > MAX_IMAGE_DIMENSION || decoded.height > MAX_IMAGE_DIMENSION
    if ((!oversized || !autoResizeImages) && supportedMime) {
        return ProcessedImage(listOf(image), changed = false)
    }

    val target =
        if (oversized && autoResizeImages) {
            resizeImage(decoded)
        } else {
            decoded
        }
    val output = ByteArrayOutputStream()
    if (!ImageIO.write(target, "png", output)) {
        return null
    }
    val hints =
        buildList {
            if (oversized && autoResizeImages) {
                add(
                    "[Image resized from original ${decoded.width}x${decoded.height} " +
                        "to ${target.width}x${target.height}.]",
                )
            }
            if (image.mimeType != "image/png") {
                add("[Image converted from ${image.mimeType} to image/png.]")
            }
        }
    return ProcessedImage(
        blocks =
            buildList {
                add(ImageContent(Base64.getEncoder().encodeToString(output.toByteArray()), "image/png"))
                hints.forEach { add(TextContent(it)) }
            },
        changed = true,
    )
}

private fun resizeImage(source: BufferedImage): BufferedImage {
    val scale =
        minOf(
            MAX_IMAGE_DIMENSION.toDouble() / source.width,
            MAX_IMAGE_DIMENSION.toDouble() / source.height,
        )
    val width = (source.width * scale).roundToInt().coerceAtLeast(1)
    val height = (source.height * scale).roundToInt().coerceAtLeast(1)
    val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    target.createGraphics().use { graphics ->
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(source, 0, 0, width, height, null)
    }
    return target
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}

private const val MAX_IMAGE_DIMENSION = 2_000
private val PROVIDER_IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
