package works.earendil.pi.tui

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main() {
    val metadata =
        KittyImageMetadata(
            imageId = 42,
            columns = 3,
            rows = 3,
            widthPx = 100,
            heightPx = 100,
        )
    registerKittyImageMetadata(metadata)
    val kitty =
        encodeKitty(
            base64Data = "QUFB",
            columns = metadata.columns,
            rows = metadata.rows,
            imageId = metadata.imageId,
            moveCursor = false,
        )
    val cropped = cropKittyImageLine(kitty, hiddenRows = 2, visibleRows = 1)
    val placement = requireNotNull(getKittyImagePlacement("left $cropped right"))
    val size = calculateImageCellSize(ImageDimensions(100, 50), maxWidthCells = 20, maxHeightCells = 10)

    println(
        buildJsonObject {
            put(
                "size",
                buildJsonObject {
                    put("columns", size.columns)
                    put("rows", size.rows)
                },
            )
            put("fallback", imageFallback("image/png", ImageDimensions(100, 50)))
            put("kitty", kitty)
            put("cropped", cropped)
            put("placement", placement.sequence)
            put("replacement", placement.replacementLine)
            put("iterm2", encodeITerm2("QUFB", width = 20))
            put("deleteImage", deleteKittyImage(42))
            put("deleteAll", deleteAllKittyImages())
            put("deletePlacements", deleteAllKittyPlacements())
        },
    )
}
