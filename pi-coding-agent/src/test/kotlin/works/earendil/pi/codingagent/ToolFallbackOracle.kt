package works.earendil.pi.codingagent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun main() {
    val output = (1..15).joinToString("\n") { index -> "line-$index" }
    val collapsed = fallbackToolResultLines(output, expanded = false, expandKey = "Ctrl+O").joinToString("\n")
    val expanded = fallbackToolResultLines(output, expanded = true, expandKey = "Ctrl+O").joinToString("\n")
    println(
        buildJsonObject {
            put("collapsedHasLine10", "line-10" in collapsed)
            put("collapsedHasLine11", "line-11" in collapsed)
            put("collapsedHasRemaining", "5 more lines" in collapsed)
            put("expandedHasLine15", "line-15" in expanded)
            put("expandedHasRemaining", "more lines" in expanded)
        },
    )
}
