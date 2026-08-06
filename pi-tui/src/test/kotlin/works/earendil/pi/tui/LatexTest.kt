package works.earendil.pi.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LatexTest {
    @Test
    fun `renders common inline symbols scripts fractions and roots`() {
        assertEquals("ℂ³ → ℝ", renderLatex("\\mathbb{C}^3 \\to \\mathbb{R}"))
        assertEquals("F₁ = u²", renderLatex("F_1 = u^2"))
        assertEquals("√(x + 1) ≤ 3⁄4", renderLatex("\\sqrt{x + 1} \\le \\frac{3}{4}"))
        assertNull(renderLatex("x + \\unknown{y}"))
    }

    @Test
    fun `renders markdown math but preserves code currency and incomplete input`() {
        val source =
            listOf(
                "Map \$\\mathbb{C}^3 \\to \\mathbb{R}\$",
                "Costs \$5 and use `\$x\$` or \$HOME.",
                "",
                "\$\$",
                "\\sum_{i=1}^n i",
                "\$\$",
                "",
                "Streaming \$\\mathbb{C}^3",
                "",
                "```text",
                "\$\\mathbb{C}^3\$",
                "```",
            ).joinToString("\n")

        assertEquals(
            listOf(
                "Map ℂ³ → ℝ",
                "Costs \$5 and use `\$x\$` or \$HOME.",
                "",
                "∑ᵢ₌₁ⁿ i",
                "",
                "Streaming \$\\mathbb{C}^3",
                "",
                "```text",
                "\$\\mathbb{C}^3\$",
                "```",
            ).joinToString("\n"),
            renderMarkdownLatex(source),
        )
    }
}
