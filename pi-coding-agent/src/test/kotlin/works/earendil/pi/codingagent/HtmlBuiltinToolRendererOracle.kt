package works.earendil.pi.codingagent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolResultMessage

fun main() {
    val theme = createBuiltinTheme("dark", ThemeColorMode.COLOR_256)
    val findCall =
        ToolCall(
            id = "find-call",
            name = "find",
            arguments =
                buildJsonObject {
                    put("pattern", "**/*.kt")
                    put("path", "src")
                    put("limit", 25)
                },
        )
    val findResult =
        ToolResultMessage(
            toolCallId = findCall.id,
            toolName = findCall.name,
            content =
                listOf(
                    TextContent(
                        List(22) { index -> "src/File${index + 1}.kt" }.joinToString("\n"),
                    ),
                ),
            details = buildJsonObject { put("resultLimitReached", 25) },
            isError = false,
        )
    val grepCall =
        ToolCall(
            id = "grep-call",
            name = "grep",
            arguments =
                buildJsonObject {
                    put("pattern", "needle")
                    put("path", ".")
                    put("glob", "*.kt")
                    put("limit", 3)
                },
        )
    val grepResult =
        ToolResultMessage(
            toolCallId = grepCall.id,
            toolName = grepCall.name,
            content =
                listOf(
                    TextContent(
                        List(17) { index -> "src/File.kt:${index + 1}:needle" }.joinToString("\n"),
                    ),
                ),
            details =
                buildJsonObject {
                    put("matchLimitReached", 3)
                    put("linesTruncated", true)
                },
            isError = false,
        )
    val output =
        buildJsonObject {
            put("find", builtinToolProjection(findCall, findResult, theme))
            put("grep", builtinToolProjection(grepCall, grepResult, theme))
        }
    println(protocolJson.encodeToString(JsonObject.serializer(), output))
}

private fun builtinToolProjection(
    call: ToolCall,
    result: ToolResultMessage,
    theme: Theme,
): JsonObject =
    buildJsonObject {
        renderBuiltinHtmlToolCall(call, theme)?.let { put("call", ansiLinesToHtml(it)) }
        put(
            "result",
            buildJsonObject {
                val collapsed =
                    renderBuiltinHtmlToolResult(result, theme, expanded = false)
                        ?.let(::trimRenderedResultLines)
                        ?.let(::ansiLinesToHtml)
                        .orEmpty()
                val expanded =
                    renderBuiltinHtmlToolResult(result, theme, expanded = true)
                        ?.let(::trimRenderedResultLines)
                        ?.let(::ansiLinesToHtml)
                        .orEmpty()
                if (collapsed.isNotEmpty() && collapsed != expanded) {
                    put("collapsed", collapsed)
                }
                put("expanded", expanded)
            },
        )
    }
