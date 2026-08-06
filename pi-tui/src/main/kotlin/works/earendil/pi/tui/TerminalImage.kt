package works.earendil.pi.tui

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.LinkedHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class TerminalImageProtocol {
    KITTY,
    ITERM2,
}

data class TerminalCapabilities(
    val images: TerminalImageProtocol?,
    val trueColor: Boolean,
    val hyperlinks: Boolean,
)

data class ImageDimensions(
    val widthPx: Int,
    val heightPx: Int,
)

data class ImageCellSize(
    val columns: Int,
    val rows: Int,
)

data class KittyImageMetadata(
    val imageId: Long,
    val columns: Int,
    val rows: Int,
    val widthPx: Int,
    val heightPx: Int,
)

data class KittyImagePlacement(
    val imageId: Long,
    val transmissionGeneration: Long,
    val transmissionBytes: Int,
    val estimatedDecodedBytes: Long,
    val sequence: String,
    val replacementLine: String,
)

private data class RegisteredKittyImageMetadata(
    val metadata: KittyImageMetadata,
    val transmissionGeneration: Long,
)

private val kittyGeneration = AtomicLong()
private val kittyMetadata =
    object : LinkedHashMap<Long, RegisteredKittyImageMetadata>(128, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, RegisteredKittyImageMetadata>?,
        ): Boolean = size > MAX_KITTY_METADATA
    }

fun detectTerminalCapabilities(
    environment: Map<String, String> = System.getenv(),
    osName: String = System.getProperty("os.name").orEmpty(),
): TerminalCapabilities {
    val termProgram = environment["TERM_PROGRAM"].orEmpty().lowercase()
    val terminalEmulator = environment["TERMINAL_EMULATOR"].orEmpty().lowercase()
    val term = environment["TERM"].orEmpty().lowercase()
    val colorTerm = environment["COLORTERM"].orEmpty().lowercase()
    val trueColorHint = colorTerm == "truecolor" || colorTerm == "24bit"

    if (environment["TMUX"] != null || term.startsWith("tmux")) {
        return TerminalCapabilities(null, trueColorHint, false)
    }
    if (term.startsWith("screen")) {
        return TerminalCapabilities(null, trueColorHint, false)
    }
    if (
        environment["KITTY_WINDOW_ID"] != null ||
        termProgram == "kitty" ||
        termProgram == "ghostty" ||
        term.contains("ghostty") ||
        environment["GHOSTTY_RESOURCES_DIR"] != null ||
        environment["WEZTERM_PANE"] != null ||
        termProgram == "wezterm" ||
        termProgram == "warpterminal" ||
        environment["WARP_SESSION_ID"] != null ||
        environment["WARP_TERMINAL_SESSION_UUID"] != null
    ) {
        return TerminalCapabilities(TerminalImageProtocol.KITTY, true, true)
    }
    if (environment["ITERM_SESSION_ID"] != null || termProgram == "iterm.app") {
        return TerminalCapabilities(TerminalImageProtocol.ITERM2, true, true)
    }
    if (
        environment["WT_SESSION"] != null ||
        termProgram == "vscode" ||
        termProgram == "alacritty"
    ) {
        return TerminalCapabilities(null, true, true)
    }
    if (terminalEmulator == "jetbrains-jediterm") {
        return TerminalCapabilities(null, trueColorHint, false)
    }
    if (osName.lowercase().startsWith("windows")) {
        return TerminalCapabilities(null, true, false)
    }
    return TerminalCapabilities(null, trueColorHint, false)
}

fun allocateImageId(): Long = ThreadLocalRandom.current().nextLong(1, 0x1_0000_0000L)

fun encodeKitty(
    base64Data: String,
    columns: Int? = null,
    rows: Int? = null,
    imageId: Long? = null,
    moveCursor: Boolean = true,
): String {
    val parameters =
        buildList {
            add("a=T")
            add("f=100")
            add("q=2")
            if (!moveCursor) add("C=1")
            columns?.let { add("c=$it") }
            rows?.let { add("r=$it") }
            imageId?.let { add("i=$it") }
        }
    if (base64Data.length <= KITTY_CHUNK_SIZE) {
        return "$KITTY_PREFIX${parameters.joinToString(",")};$base64Data$STRING_TERMINATOR"
    }
    return buildString {
        var offset = 0
        var first = true
        while (offset < base64Data.length) {
            val end = min(base64Data.length, offset + KITTY_CHUNK_SIZE)
            val chunk = base64Data.substring(offset, end)
            val last = end == base64Data.length
            when {
                first -> append("$KITTY_PREFIX${parameters.joinToString(",")},m=1;$chunk$STRING_TERMINATOR")
                last -> append("${KITTY_PREFIX}m=0;$chunk$STRING_TERMINATOR")
                else -> append("${KITTY_PREFIX}m=1;$chunk$STRING_TERMINATOR")
            }
            first = false
            offset = end
        }
    }
}

fun encodeITerm2(
    base64Data: String,
    width: Int,
    height: String? = null,
    preserveAspectRatio: Boolean = true,
): String {
    val size = Base64.getDecoder().decode(base64Data).size
    val heightParameter = height?.let { ";height=$it" }.orEmpty()
    val ratio = if (preserveAspectRatio) "" else ";preserveAspectRatio=0"
    return "\u001B]1337;File=inline=1;size=$size;width=$width$heightParameter$ratio:$base64Data\u0007"
}

fun deleteKittyImage(imageId: Long): String = "$KITTY_PREFIX" + "a=d,d=I,i=$imageId,q=2$STRING_TERMINATOR"

fun deleteAllKittyImages(): String = "$KITTY_PREFIX" + "a=d,d=A,q=2$STRING_TERMINATOR"

fun deleteAllKittyPlacements(): String = "$KITTY_PREFIX" + "a=d,d=a,q=2$STRING_TERMINATOR"

fun isImageLine(line: String): Boolean = KITTY_PREFIX in line || ITERM2_PREFIX in line

fun registerKittyImageMetadata(metadata: KittyImageMetadata) {
    synchronized(kittyMetadata) {
        kittyMetadata.remove(metadata.imageId)
        kittyMetadata[metadata.imageId] =
            RegisteredKittyImageMetadata(
                metadata = metadata,
                transmissionGeneration = kittyGeneration.incrementAndGet(),
            )
    }
}

fun getKittyImageMetadata(line: String): KittyImageMetadata? =
    registeredKittyImageMetadata(line)?.metadata

fun getKittyImagePlacement(line: String): KittyImagePlacement? {
    val firstCommand = KITTY_COMMAND.find(line) ?: return null
    val registered = registeredKittyImageMetadata(line) ?: return null
    var commandStart = firstCommand.range.first
    var controls = firstCommand.groupValues[1]
    var transmissionEnd: Int
    while (true) {
        val terminator = line.indexOf(STRING_TERMINATOR, commandStart + KITTY_PREFIX.length)
        if (terminator < 0) return null
        transmissionEnd = terminator + STRING_TERMINATOR.length
        if (!CONTINUED_KITTY_COMMAND.containsMatchIn(controls)) break
        commandStart = transmissionEnd
        if (!line.startsWith(KITTY_PREFIX, commandStart)) return null
        val controlsEnd = line.indexOf(';', commandStart + KITTY_PREFIX.length)
        if (controlsEnd < 0) return null
        controls = line.substring(commandStart + KITTY_PREFIX.length, controlsEnd)
    }
    val placementControls =
        firstCommand.groupValues[1]
            .split(',')
            .filter { control ->
                control.substringBefore('=') in KITTY_PLACEMENT_CONTROL_KEYS
            }
    val sequence = "$KITTY_PREFIX" + "a=p,q=2,${placementControls.joinToString(",")}$STRING_TERMINATOR"
    return KittyImagePlacement(
        imageId = registered.metadata.imageId,
        transmissionGeneration = registered.transmissionGeneration,
        transmissionBytes = transmissionEnd - firstCommand.range.first,
        estimatedDecodedBytes =
            registered.metadata.widthPx.toLong() *
                registered.metadata.heightPx.toLong() *
                4L,
        sequence = sequence,
        replacementLine =
            line.substring(0, firstCommand.range.first) +
                sequence +
                line.substring(transmissionEnd),
    )
}

fun cropKittyImageLine(
    line: String,
    hiddenRows: Int,
    visibleRows: Int,
): String {
    val metadata = getKittyImageMetadata(line) ?: return line
    val match = KITTY_COMMAND.find(line) ?: return line
    if (hiddenRows < 0 || hiddenRows >= metadata.rows || visibleRows <= 0) return line
    val croppedRows = min(visibleRows, metadata.rows - hiddenRows)
    if (hiddenRows == 0 && croppedRows == metadata.rows) return line
    val sourceY = floor(metadata.heightPx.toDouble() * hiddenRows / metadata.rows).toInt()
    val sourceEnd = ceil(metadata.heightPx.toDouble() * (hiddenRows + croppedRows) / metadata.rows).toInt()
    val sourceHeight = max(1, min(metadata.heightPx, sourceEnd) - sourceY)
    val controls =
        match.groupValues[1]
            .split(',')
            .filterNot { it.substringBefore('=') in setOf("y", "h", "r") }
            .toMutableList()
            .apply {
                add("y=$sourceY")
                add("h=$sourceHeight")
                add("r=$croppedRows")
            }
    return line.replaceRange(
        match.range,
        "$KITTY_PREFIX${controls.joinToString(",")};",
    )
}

fun calculateImageCellSize(
    imageDimensions: ImageDimensions,
    maxWidthCells: Int,
    maxHeightCells: Int? = null,
    cellWidthPx: Int = 9,
    cellHeightPx: Int = 18,
): ImageCellSize {
    val maxWidth = max(1, maxWidthCells)
    val maxHeight = maxHeightCells?.let { max(1, it) }
    val widthScale = maxWidth.toDouble() * max(1, cellWidthPx) / max(1, imageDimensions.widthPx)
    val heightScale =
        maxHeight
            ?.let { it.toDouble() * max(1, cellHeightPx) / max(1, imageDimensions.heightPx) }
            ?: widthScale
    val scale = min(widthScale, heightScale)
    val columns = ceil(imageDimensions.widthPx * scale / max(1, cellWidthPx)).toInt()
    val rows = ceil(imageDimensions.heightPx * scale / max(1, cellHeightPx)).toInt()
    return ImageCellSize(
        columns = columns.coerceIn(1, maxWidth),
        rows = rows.coerceIn(1, maxHeight ?: rows.coerceAtLeast(1)),
    )
}

fun getImageDimensions(
    base64Data: String,
    mimeType: String,
): ImageDimensions? =
    runCatching { Base64.getDecoder().decode(base64Data) }
        .getOrNull()
        ?.let { bytes ->
            when (mimeType) {
                "image/png" -> pngDimensions(bytes)
                "image/jpeg" -> jpegDimensions(bytes)
                "image/gif" -> gifDimensions(bytes)
                "image/webp" -> webpDimensions(bytes)
                else -> null
            }
        }

fun imageFallback(
    mimeType: String,
    dimensions: ImageDimensions? = null,
    filename: String? = null,
): String {
    val parts = mutableListOf<String>()
    filename?.let { value ->
        val home = System.getProperty("user.home").orEmpty()
        parts +=
            if (home.isNotEmpty() && (value == home || value.startsWith("$home/"))) {
                "~${value.removePrefix(home)}"
            } else {
                value
            }
    }
    parts += "[$mimeType]"
    dimensions?.let { parts += "${it.widthPx}x${it.heightPx}" }
    return "[Image: ${parts.joinToString(" ")}]"
}

fun renderTerminalImageLines(
    base64Data: String,
    mimeType: String,
    width: Int,
    filename: String? = null,
    maxWidthCells: Int = 60,
    capabilities: TerminalCapabilities = detectTerminalCapabilities(),
): List<String> {
    val safeWidth = max(1, width)
    val dimensions = getImageDimensions(base64Data, mimeType) ?: ImageDimensions(800, 600)
    val fallback = truncateToWidth(imageFallback(mimeType, dimensions, filename), safeWidth)
    val protocol = capabilities.images ?: return listOf(fallback)
    if (protocol == TerminalImageProtocol.KITTY && mimeType != "image/png") {
        return listOf(fallback)
    }
    val maxWidth = min(max(1, safeWidth - 2), max(1, maxWidthCells))
    val defaultMaxHeight = max(1, ceil(maxWidth * 9.0 / 18.0).toInt())
    val size = calculateImageCellSize(dimensions, maxWidth, defaultMaxHeight)
    return when (protocol) {
        TerminalImageProtocol.KITTY -> {
            val imageId = allocateImageId()
            registerKittyImageMetadata(
                KittyImageMetadata(
                    imageId = imageId,
                    columns = size.columns,
                    rows = size.rows,
                    widthPx = dimensions.widthPx,
                    heightPx = dimensions.heightPx,
                ),
            )
            buildList {
                add(
                    encodeKitty(
                        base64Data = base64Data,
                        columns = size.columns,
                        rows = size.rows,
                        imageId = imageId,
                        moveCursor = false,
                    ),
                )
                repeat(size.rows - 1) { add("") }
            }
        }

        TerminalImageProtocol.ITERM2 ->
            buildList {
                repeat(size.rows - 1) { add("") }
                val moveUp = if (size.rows > 1) "\u001B[${size.rows - 1}A" else ""
                add(moveUp + encodeITerm2(base64Data, size.columns, height = "auto"))
            }
    }
}

private fun registeredKittyImageMetadata(line: String): RegisteredKittyImageMetadata? {
    val controls = KITTY_COMMAND.find(line)?.groupValues?.get(1) ?: return null
    val imageId = IMAGE_ID.find(controls)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    return synchronized(kittyMetadata) { kittyMetadata[imageId] }
}

private fun pngDimensions(bytes: ByteArray): ImageDimensions? {
    if (bytes.size < 24 || !bytes.copyOfRange(0, 4).contentEquals(PNG_SIGNATURE)) return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    return ImageDimensions(buffer.getInt(16), buffer.getInt(20))
}

private fun jpegDimensions(bytes: ByteArray): ImageDimensions? {
    if (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) return null
    var offset = 2
    while (offset + 8 < bytes.size) {
        if (bytes[offset] != 0xff.toByte()) {
            offset++
            continue
        }
        val marker = bytes[offset + 1].toInt() and 0xff
        if (marker in 0xc0..0xc2) {
            val height = ((bytes[offset + 5].toInt() and 0xff) shl 8) or (bytes[offset + 6].toInt() and 0xff)
            val width = ((bytes[offset + 7].toInt() and 0xff) shl 8) or (bytes[offset + 8].toInt() and 0xff)
            return ImageDimensions(width, height)
        }
        val length = ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)
        if (length < 2) return null
        offset += 2 + length
    }
    return null
}

private fun gifDimensions(bytes: ByteArray): ImageDimensions? {
    if (bytes.size < 10) return null
    val signature = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
    if (signature != "GIF87a" && signature != "GIF89a") return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return ImageDimensions(buffer.getShort(6).toInt() and 0xffff, buffer.getShort(8).toInt() and 0xffff)
}

private fun webpDimensions(bytes: ByteArray): ImageDimensions? {
    if (bytes.size < 30) return null
    if (bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF") return null
    if (bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) != "WEBP") return null
    val chunk = bytes.copyOfRange(12, 16).toString(Charsets.US_ASCII)
    return when (chunk) {
        "VP8 " -> {
            val width = littleEndianShort(bytes, 26) and 0x3fff
            val height = littleEndianShort(bytes, 28) and 0x3fff
            ImageDimensions(width, height)
        }

        "VP8L" -> {
            val bits = littleEndianInt(bytes, 21)
            ImageDimensions((bits and 0x3fff) + 1, ((bits ushr 14) and 0x3fff) + 1)
        }

        "VP8X" ->
            ImageDimensions(
                (bytes[24].toInt() and 0xff) +
                    ((bytes[25].toInt() and 0xff) shl 8) +
                    ((bytes[26].toInt() and 0xff) shl 16) +
                    1,
                (bytes[27].toInt() and 0xff) +
                    ((bytes[28].toInt() and 0xff) shl 8) +
                    ((bytes[29].toInt() and 0xff) shl 16) +
                    1,
            )

        else -> null
    }
}

private fun littleEndianShort(
    bytes: ByteArray,
    offset: Int,
): Int =
    (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun littleEndianInt(
    bytes: ByteArray,
    offset: Int,
): Int =
    (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or
        ((bytes[offset + 3].toInt() and 0xff) shl 24)

private const val KITTY_PREFIX = "\u001B_G"
private const val ITERM2_PREFIX = "\u001B]1337;File="
private const val STRING_TERMINATOR = "\u001B\\"
private const val KITTY_CHUNK_SIZE = 4096
private const val MAX_KITTY_METADATA = 1000
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
private val KITTY_COMMAND = Regex("""\u001B_G([^;]*);""")
private val IMAGE_ID = Regex("""(?:^|,)i=(\d+)(?:,|$)""")
private val CONTINUED_KITTY_COMMAND = Regex("""(?:^|,)m=1(?:,|$)""")
private val KITTY_PLACEMENT_CONTROL_KEYS =
    setOf("i", "p", "x", "y", "w", "h", "X", "Y", "c", "r", "C", "U", "z", "P", "Q", "H", "V")
