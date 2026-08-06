package works.earendil.pi.codingagent

import kotlin.math.max
import works.earendil.pi.tui.renderMarkdownLatex
import works.earendil.pi.tui.visibleWidth

enum class MermaidRenderingMode(
    val wireValue: String,
) {
    OFF("off"),
    FINAL("final"),
    STREAMING("streaming"),
}

internal fun renderBuiltInMarkdown(
    markdown: String,
    messageType: String,
    isStreaming: Boolean,
    availableWidth: Int,
    mermaidMode: MermaidRenderingMode,
): String {
    val withMermaid =
        transformMermaidMarkdown(
            markdown = markdown,
            mode = mermaidMode,
            messageType = messageType,
            isStreaming = isStreaming,
            availableWidth = availableWidth,
        )
    return renderMarkdownLatex(withMermaid)
}

internal fun transformMermaidMarkdown(
    markdown: String,
    mode: MermaidRenderingMode,
    messageType: String,
    isStreaming: Boolean,
    availableWidth: Int,
): String {
    if (
        mode == MermaidRenderingMode.OFF ||
        messageType == "assistant-thinking" ||
        (isStreaming && mode != MermaidRenderingMode.STREAMING)
    ) {
        return markdown
    }

    val lines = markdown.split('\n')
    val result = mutableListOf<String>()
    var index = 0
    while (index < lines.size) {
        val opening = lines[index]
        val trimmedOpening = opening.trimStart()
        val language =
            trimmedOpening
                .removePrefix("```")
                .trim()
                .substringBefore(' ')
        if (!trimmedOpening.startsWith("```") || !language.equals("mermaid", ignoreCase = true)) {
            result += opening
            index++
            continue
        }

        val raw = mutableListOf(opening)
        val diagram = mutableListOf<String>()
        var cursor = index + 1
        var closed = false
        while (cursor < lines.size) {
            val line = lines[cursor]
            raw += line
            if (line.trim() == "```") {
                closed = true
                cursor++
                break
            }
            diagram += line
            cursor++
        }
        if (!closed && !isStreaming) {
            result += raw
            index = cursor
            continue
        }
        result += renderMermaidFlowchart(diagram.joinToString("\n"), availableWidth) ?: raw
        index = cursor
    }
    return result.joinToString("\n")
}

private data class MermaidNode(
    val id: String,
    val label: String,
)

private data class MermaidEdge(
    val from: MermaidNode,
    val to: MermaidNode,
    val kind: String,
    val label: String?,
)

private data class MermaidPath(
    val nodes: List<MermaidNode>,
    val edges: List<MermaidEdge>,
) {
    fun reversedPath(): MermaidPath =
        MermaidPath(
            nodes = nodes.reversed(),
            edges =
                edges
                    .reversed()
                    .map { edge -> edge.copy(from = edge.to, to = edge.from) },
        )
}

private fun renderMermaidFlowchart(
    source: String,
    availableWidth: Int,
): List<String>? {
    val lines =
        source
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("%%") }
            .toList()
    val header =
        Regex("^(?:flowchart|graph)\\s+(LR|RL|TB|TD|BT)\\s*$", RegexOption.IGNORE_CASE)
            .matchEntire(lines.firstOrNull().orEmpty())
            ?: return null
    val edges = lines.drop(1).mapNotNull(::parseMermaidEdge)
    if (edges.isEmpty()) {
        return null
    }
    val ordered = orderMermaidPath(edges) ?: return null
    val direction = header.groupValues[1].uppercase()
    val rendered =
        if (direction == "LR" || direction == "RL") {
            renderHorizontalMermaid(
                if (direction == "RL") ordered.reversedPath() else ordered,
            )
        } else {
            renderVerticalMermaid(
                if (direction == "BT") ordered.reversedPath() else ordered,
            )
        }
    return rendered.takeIf { linesOut ->
        linesOut.none { visibleWidth(it) > availableWidth.coerceAtLeast(1) }
    }
}

private fun orderMermaidPath(edges: List<MermaidEdge>): MermaidPath? {
    val outgoing = edges.groupBy { it.from.id }
    val incoming = edges.groupBy { it.to.id }
    if (outgoing.values.any { it.size != 1 } || incoming.values.any { it.size != 1 }) {
        return null
    }
    val first = edges.firstOrNull { it.from.id !in incoming } ?: return null
    val orderedEdges = mutableListOf<MermaidEdge>()
    val nodes = mutableListOf(first.from)
    val visited = mutableSetOf<String>()
    var current = first
    while (true) {
        if (!visited.add(current.from.id)) {
            return null
        }
        orderedEdges += current
        nodes += current.to
        current = outgoing[current.to.id]?.singleOrNull() ?: break
    }
    return MermaidPath(nodes, orderedEdges).takeIf { orderedEdges.size == edges.size }
}

private fun parseMermaidEdge(line: String): MermaidEdge? {
    val match =
        Regex("^(.+?)\\s*(-->|---|==>|-\\.->)\\s*(?:\\|([^|]*)\\|\\s*)?(.+?)\\s*$")
            .matchEntire(line)
            ?: return null
    return MermaidEdge(
        from = parseMermaidNode(match.groupValues[1]) ?: return null,
        to = parseMermaidNode(match.groupValues[4]) ?: return null,
        kind = match.groupValues[2],
        label = match.groupValues[3].trim().takeIf(String::isNotEmpty),
    )
}

private fun parseMermaidNode(raw: String): MermaidNode? {
    val value = raw.trim().substringBefore(":::").trim()
    val match =
        Regex("^([A-Za-z_][A-Za-z0-9_-]*)(?:\\(\\((.*?)\\)\\)|\\[(.*?)]|\\{(.*?)}|\\((.*?)\\))?$")
            .matchEntire(value)
            ?: return null
    val id = match.groupValues[1]
    val label =
        match.groupValues
            .drop(2)
            .firstOrNull(String::isNotEmpty)
            ?.trim()
            ?.trim('"')
            ?: id
    return MermaidNode(id, label)
}

private fun renderHorizontalMermaid(path: MermaidPath): List<String> {
    val boxes = path.nodes.map(::mermaidBox)
    val connectors = path.edges.map(::horizontalConnector)
    return listOf(
        joinBoxes(boxes, connectors, 0) { connector -> " ".repeat(visibleWidth(connector)) },
        joinBoxes(boxes, connectors, 1) { it },
        joinBoxes(boxes, connectors, 2) { connector -> " ".repeat(visibleWidth(connector)) },
    )
}

private fun renderVerticalMermaid(path: MermaidPath): List<String> =
    buildList {
        path.nodes.forEachIndexed { index, node ->
            addAll(mermaidBox(node))
            path.edges.getOrNull(index)?.let { edge ->
                add(edge.label?.let { "  │ $it" } ?: "  │")
                add(if (edge.kind == "---") "  │" else "  ▼")
            }
        }
    }

private fun mermaidBox(node: MermaidNode): List<String> {
    val width = max(1, visibleWidth(node.label))
    val padding = " ".repeat(width - visibleWidth(node.label))
    return listOf(
        "┌" + "─".repeat(width + 2) + "┐",
        "│ ${node.label}$padding │",
        "└" + "─".repeat(width + 2) + "┘",
    )
}

private fun horizontalConnector(edge: MermaidEdge): String {
    val shaft = edge.label?.let { "─$it─" } ?: "───"
    return shaft +
        when (edge.kind) {
            "---" -> "─"
            "-.->" -> "╌▶"
            else -> "▶"
        }
}

private fun joinBoxes(
    boxes: List<List<String>>,
    connectors: List<String>,
    row: Int,
    connectorLine: (String) -> String,
): String =
    buildString {
        boxes.forEachIndexed { index, box ->
            if (index > 0) {
                append(connectorLine(connectors[index - 1]))
            }
            append(box[row])
        }
    }
