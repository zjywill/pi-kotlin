package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import works.earendil.pi.ai.AssistantMessage
import works.earendil.pi.ai.BashExecutionMessage
import works.earendil.pi.ai.BranchSummaryMessage
import works.earendil.pi.ai.CompactionSummaryMessage
import works.earendil.pi.ai.ContentBlock
import works.earendil.pi.ai.CustomMessage
import works.earendil.pi.ai.ImageContent
import works.earendil.pi.ai.Message
import works.earendil.pi.ai.MessageContent
import works.earendil.pi.ai.TextContent
import works.earendil.pi.ai.ThinkingContent
import works.earendil.pi.ai.ToolCall
import works.earendil.pi.ai.ToolResultMessage
import works.earendil.pi.ai.UserMessage
import works.earendil.pi.codingagent.session.SessionManager

fun exportSessionFile(
    inputPath: Path,
    outputPath: Path? = null,
): Path {
    val resolvedInput = inputPath.toAbsolutePath().normalize()
    require(Files.exists(resolvedInput)) { "File not found: $resolvedInput" }
    return exportSession(
        SessionManager.open(resolvedInput),
        outputPath ?: defaultExportPath(resolvedInput),
    )
}

fun exportSession(
    sessionManager: SessionManager,
    outputPath: Path? = null,
): Path {
    val sessionFile = sessionManager.getSessionFile()
        ?: error("Cannot export in-memory session to HTML")
    require(Files.exists(sessionFile)) { "Nothing to export yet - start a conversation first" }
    val resolvedOutput =
        (outputPath ?: defaultExportPath(sessionFile))
            .toAbsolutePath()
            .normalize()
    resolvedOutput.parent?.let(Files::createDirectories)
    Files.writeString(
        resolvedOutput,
        generateSessionHtml(sessionManager),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )
    return resolvedOutput
}

internal fun generateSessionHtml(sessionManager: SessionManager): String {
    val header = sessionManager.getHeader()
    val title = sessionManager.getSessionName() ?: "Pi session"
    val messages = sessionManager.buildSessionContext().messages
    return buildString {
        appendLine("<!doctype html>")
        appendLine("<html lang=\"en\">")
        appendLine("<head>")
        appendLine("<meta charset=\"utf-8\">")
        appendLine("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        appendLine("<meta name=\"color-scheme\" content=\"light dark\">")
        appendLine("<title>${escapeHtml(title)}</title>")
        appendLine("<style>")
        appendLine(EXPORT_CSS)
        appendLine("</style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("<main>")
        appendLine("<header>")
        appendLine("<h1>${escapeHtml(title)}</h1>")
        appendLine("<dl>")
        appendLine("<dt>Session</dt><dd>${escapeHtml(header?.id.orEmpty())}</dd>")
        appendLine("<dt>Working directory</dt><dd>${escapeHtml(header?.cwd.orEmpty())}</dd>")
        appendLine("<dt>Created</dt><dd>${escapeHtml(header?.timestamp.orEmpty())}</dd>")
        appendLine("</dl>")
        appendLine("</header>")
        appendLine("<section class=\"conversation\">")
        messages.forEach { message -> append(renderMessage(message)) }
        appendLine("</section>")
        appendLine("</main>")
        appendLine("</body>")
        appendLine("</html>")
    }
}

private fun renderMessage(message: Message): String {
    val (role, body, cssClass) =
        when (message) {
            is UserMessage -> Triple("User", renderContent(message.content), "user")
            is AssistantMessage -> Triple("Assistant", message.content.joinToString("") { renderBlock(it) }, "assistant")
            is ToolResultMessage ->
                Triple(
                    "Tool result: ${message.toolName}",
                    message.content.joinToString("") { renderBlock(it) },
                    if (message.isError) "tool error" else "tool",
                )

            is CustomMessage -> Triple(message.customType, renderContent(message.content), "custom")
            is BashExecutionMessage ->
                Triple(
                    "Shell",
                    "<pre><code>${escapeHtml("$ ${message.command}\n${message.output}")}</code></pre>",
                    "tool",
                )

            is BranchSummaryMessage -> Triple("Branch summary", renderPre(message.summary), "summary")
            is CompactionSummaryMessage -> Triple("Compaction summary", renderPre(message.summary), "summary")
        }
    return buildString {
        append("<article class=\"message ")
        append(cssClass)
        append("\"><h2>")
        append(escapeHtml(role))
        append("</h2>")
        append(body)
        appendLine("</article>")
    }
}

private fun renderContent(content: MessageContent): String =
    when (content) {
        is MessageContent.Text -> renderPre(content.text)
        is MessageContent.Blocks -> content.blocks.joinToString("") { renderBlock(it) }
    }

private fun renderBlock(block: ContentBlock): String =
    when (block) {
        is TextContent -> renderPre(block.text)
        is ThinkingContent ->
            "<details><summary>Thinking</summary>${renderPre(block.thinking)}</details>"

        is ToolCall ->
            "<details><summary>${escapeHtml(block.name)}</summary>" +
                "<pre><code>${escapeHtml(block.arguments.toString())}</code></pre></details>"

        is ImageContent -> renderImage(block)
    }

private fun renderImage(image: ImageContent): String {
    val safeMime =
        image.mimeType.takeIf {
            it in setOf("image/png", "image/jpeg", "image/gif", "image/webp")
        } ?: return "<p class=\"notice\">Unsupported image omitted.</p>"
    if (!image.data.matches(Regex("[A-Za-z0-9+/=\\r\\n]+"))) {
        return "<p class=\"notice\">Invalid image omitted.</p>"
    }
    return "<img alt=\"Attached image\" src=\"data:${escapeHtml(safeMime)};base64,${escapeHtml(image.data)}\">"
}

private fun renderPre(value: String): String = "<pre>${escapeHtml(value)}</pre>"

internal fun escapeHtml(value: String): String =
    buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> character
                },
            )
        }
    }

private fun defaultExportPath(sessionFile: Path): Path {
    val name = sessionFile.fileName.toString().removeSuffix(".jsonl")
    return Path.of("pi-session-$name.html")
}

private const val EXPORT_CSS = """
:root {
  color-scheme: light dark;
  font-family: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  line-height: 1.5;
  background: Canvas;
  color: CanvasText;
}
body { margin: 0; }
main { width: min(960px, calc(100% - 32px)); margin: 0 auto; padding: 32px 0 64px; }
header { border-bottom: 1px solid color-mix(in srgb, CanvasText 18%, transparent); padding-bottom: 20px; }
h1 { font-size: 24px; margin: 0 0 16px; }
dl { display: grid; grid-template-columns: max-content 1fr; gap: 4px 16px; margin: 0; font-size: 13px; }
dt { font-weight: 650; }
dd { margin: 0; overflow-wrap: anywhere; }
.conversation { display: grid; gap: 12px; padding-top: 20px; }
.message { border: 1px solid color-mix(in srgb, CanvasText 16%, transparent); border-radius: 8px; padding: 14px 16px; }
.message.user { background: color-mix(in srgb, AccentColor 10%, Canvas); }
.message.summary { border-style: dashed; }
.message.error { border-color: #c33; }
.message h2 { font-size: 13px; margin: 0 0 8px; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 0; font: 13px/1.55 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
details + pre, pre + details, details + details { margin-top: 10px; }
summary { cursor: pointer; font-size: 13px; }
img { display: block; max-width: 100%; height: auto; margin-top: 8px; }
.notice { margin: 0; opacity: .7; }
"""
