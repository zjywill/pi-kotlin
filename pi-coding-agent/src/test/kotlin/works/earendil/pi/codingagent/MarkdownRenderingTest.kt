package works.earendil.pi.codingagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownRenderingTest {
    @Test
    fun `renders simple Mermaid flowcharts as terminal diagrams`() {
        val markdown =
            """
            Before

            ```mermaid
            flowchart LR
              A[Start] --> B[Done]
            ```
            After
            """.trimIndent()

        val rendered =
            renderBuiltInMarkdown(
                markdown = markdown,
                messageType = "assistant",
                isStreaming = false,
                availableWidth = 80,
                mermaidMode = MermaidRenderingMode.STREAMING,
            )

        assertTrue("┌───────┐" in rendered)
        assertTrue("│ Start │───▶│ Done │" in rendered)
        assertFalse("```mermaid" in rendered)
        assertTrue(rendered.startsWith("Before"))
        assertTrue(rendered.endsWith("After"))
    }

    @Test
    fun `respects Mermaid modes width and streaming fences`() {
        val complete =
            """
            ```mermaid
            flowchart LR
              A[Start] --> B[Done]
            ```
            """.trimIndent()
        val partial =
            """
            ```mermaid
            flowchart LR
              A --> B
            """.trimIndent()

        assertEquals(
            complete,
            transformMermaidMarkdown(
                complete,
                MermaidRenderingMode.OFF,
                "assistant",
                isStreaming = false,
                availableWidth = 80,
            ),
        )
        assertEquals(
            complete,
            transformMermaidMarkdown(
                complete,
                MermaidRenderingMode.FINAL,
                "assistant",
                isStreaming = true,
                availableWidth = 80,
            ),
        )
        assertEquals(
            complete,
            transformMermaidMarkdown(
                complete,
                MermaidRenderingMode.STREAMING,
                "assistant",
                isStreaming = false,
                availableWidth = 10,
            ),
        )
        assertTrue(
            "───▶" in
                transformMermaidMarkdown(
                    partial,
                    MermaidRenderingMode.STREAMING,
                    "assistant",
                    isStreaming = true,
                    availableWidth = 80,
                ),
        )
        assertEquals(
            partial,
            transformMermaidMarkdown(
                partial,
                MermaidRenderingMode.STREAMING,
                "assistant",
                isStreaming = false,
                availableWidth = 80,
            ),
        )
    }
}
