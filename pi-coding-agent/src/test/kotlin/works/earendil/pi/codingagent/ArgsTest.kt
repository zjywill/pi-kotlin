package works.earendil.pi.codingagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArgsTest {
    @Test
    fun `parses core flags and messages`() {
        val result =
            parseArgs(
                listOf(
                    "--provider",
                    "anthropic",
                    "--model",
                    "claude-sonnet",
                    "--print",
                    "--thinking",
                    "high",
                    "@prompt.md",
                    "Do the task",
                ),
            )

        assertEquals("anthropic", result.provider)
        assertEquals("claude-sonnet", result.model)
        assertTrue(result.print)
        assertEquals(AgentThinkingLevel.HIGH, result.thinking)
        assertEquals(listOf("prompt.md"), result.fileArgs)
        assertEquals(listOf("Do the task"), result.messages)
    }

    @Test
    fun `print consumes yaml frontmatter but not options`() {
        val prompt = "---\ntitle: hello\n---\nSay hi."
        assertEquals(listOf(prompt), parseArgs(listOf("-p", prompt)).messages)

        val withOption = parseArgs(listOf("-p", "--provider", "openai", "Say hi."))
        assertEquals("openai", withOption.provider)
        assertEquals(listOf("Say hi."), withOption.messages)
    }

    @Test
    fun `captures extension flags`() {
        val result = parseArgs(listOf("--plan", "--depth=deep", "--name", "session"))

        assertEquals(true, result.unknownFlags["plan"])
        assertEquals("deep", result.unknownFlags["depth"])
        assertEquals("session", result.name)
    }

    @Test
    fun `unknown short options are diagnostics`() {
        val result = parseArgs(listOf("-z"))

        assertFalse(result.diagnostics.isEmpty())
        assertEquals("Unknown option: -z", result.diagnostics.single().message)
    }

    @Test
    fun `parses UI modes and reports invalid values`() {
        assertEquals(UiMode.REGULAR, parseArgs(listOf("--ui-mode", "regular")).uiMode)
        assertEquals(UiMode.FULLSCREEN, parseArgs(listOf("--ui-mode", "fullscreen")).uiMode)
        assertEquals(UiMode.FULLSCREEN, parseArgs(listOf("--alt")).uiMode)

        val invalid = parseArgs(listOf("--ui-mode", "other"))
        assertEquals(
            "Invalid UI mode \"other\". Valid values: regular, fullscreen",
            invalid.diagnostics.single().message,
        )

        val missing = parseArgs(listOf("--ui-mode", "--offline"))
        assertEquals("--ui-mode requires regular or fullscreen", missing.diagnostics.single().message)
        assertTrue(missing.offline)
    }

    @Test
    fun `model references preserve slash ids and parse thinking suffixes`() {
        assertEquals(
            ModelReference("openrouter", "moonshotai/kimi-k2.6", AgentThinkingLevel.HIGH),
            parseModelReference("openrouter", "moonshotai/kimi-k2.6:high"),
        )
        assertEquals(
            ModelReference("openrouter", "moonshotai/kimi-k2.6", AgentThinkingLevel.XHIGH),
            parseModelReference(null, "openrouter/moonshotai/kimi-k2.6:xhigh"),
        )
        assertEquals(
            ModelReference("openrouter", "moonshotai/kimi-k2.6", null),
            parseModelReference("openrouter", "openrouter/moonshotai/kimi-k2.6"),
        )
        assertEquals(
            ModelReference("custom", "bracketed-model[1m]", AgentThinkingLevel.HIGH),
            parseModelReference(null, "custom/bracketed-model[1m]:high"),
        )
    }
}
