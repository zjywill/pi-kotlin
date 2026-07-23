package works.earendil.pi.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalTextTest {
    @Test
    fun `measures ascii ansi cjk and emoji`() {
        assertEquals(5, visibleWidth("hello"))
        assertEquals(3, visibleWidth("\u001B[31mred\u001B[0m"))
        assertEquals(4, visibleWidth("中文"))
        assertEquals(2, visibleWidth("🙂"))
        assertEquals(3, visibleWidth("\t"))
    }

    @Test
    fun `measures grapheme clusters and terminal control sequences`() {
        assertEquals(2, visibleWidth("🇨🇳"))
        assertEquals(2, visibleWidth("👩‍💻"))
        assertEquals(1, visibleWidth("e\u0301"))
        assertEquals(5, visibleWidth("\u001B]133;A\u0007hello\u001B]133;B\u0007"))
        assertEquals(5, visibleWidth("\u001B]8;;https://example.com\u001B\\hello\u001B]8;;\u001B\\"))
    }

    @Test
    fun `normalizes only visible tabs and Thai Lao AM vowels`() {
        val control = "\u001B]8;;https://example.test/a\tb\u0007"
        assertEquals("${control}label   text", normalizeTerminalOutput("${control}label\ttext"))
        assertEquals("\u0E4D\u0E32", normalizeTerminalOutput("\u0E33"))
        assertEquals("\u0ECD\u0EB2", normalizeTerminalOutput("\u0EB3"))
        assertEquals(visibleWidth("\u0E33abc"), visibleWidth(normalizeTerminalOutput("\u0E33abc")))
    }

    @Test
    fun `truncates wide text and preserves ansi resets`() {
        val truncated = truncateToWidth("\u001B[31m${"hello ".repeat(20)}\u001B[0m", 20, "…")
        assertTrue(visibleWidth(truncated) <= 20)
        assertTrue(truncated.contains("\u001B[31m"))
        assertTrue(truncated.endsWith("\u001B[0m…\u001B[0m"))
        assertEquals("", truncateToWidth("abcdef", 1, "🙂"))
        assertEquals("\u001B[0m🙂\u001B[0m", truncateToWidth("abcdef", 2, "🙂"))
        assertEquals(8, visibleWidth(truncateToWidth("🙂界🙂界🙂界", 8, "…", pad = true)))
    }

    @Test
    fun `wraps ANSI and CJK text without style loss`() {
        val red = "\u001B[31m"
        val reset = "\u001B[0m"
        val wrapped = wrapTextWithAnsi("${red}This is an example 中文汉字测试段落内容中文汉字测试段落内容.${reset}", 40)
        assertEquals(2, wrapped.size)
        assertEquals("${red}This is an example 中文汉字测试段落内容", wrapped[0])
        assertEquals("${red}中文汉字测试段落内容.${reset}", wrapped[1])
        assertTrue(wrapped.all { visibleWidth(it) <= 40 })
    }

    @Test
    fun `slices by terminal columns`() {
        val line = "\u001B[31mA界BC\u001B[0m"
        val slice = sliceWithWidth(line, 1, 3, strict = true)
        assertEquals("\u001B[31m界B", slice.text)
        assertEquals(3, slice.width)
        assertEquals("\u001B[31mA", sliceByColumn(line, 0, 1))
    }
}
