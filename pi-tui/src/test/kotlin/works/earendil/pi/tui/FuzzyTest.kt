package works.earendil.pi.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FuzzyTest {
    @Test
    fun `empty query matches everything`() {
        assertEquals(FuzzyMatch(true, 0.0), fuzzyMatch("", "anything"))
    }

    @Test
    fun `characters must appear in order`() {
        assertTrue(fuzzyMatch("abc", "aXbXc").matches)
        assertFalse(fuzzyMatch("abc", "cba").matches)
    }

    @Test
    fun `consecutive and boundary matches score better`() {
        assertTrue(fuzzyMatch("foo", "foobar").score < fuzzyMatch("foo", "f_o_o_bar").score)
        assertTrue(fuzzyMatch("fb", "foo-bar").score < fuzzyMatch("fb", "afbx").score)
    }

    @Test
    fun `matches swapped alpha numeric tokens`() {
        assertTrue(fuzzyMatch("codex52", "gpt-5.2-codex").matches)
    }

    @Test
    fun `filters slash separated provider model queries`() {
        data class Item(
            val id: String,
            val provider: String,
        )

        val item = Item("gpt-5.5", "openai-codex")
        assertEquals(
            listOf(item),
            fuzzyFilter(listOf(item), "openai-codex/gpt-5.5") { "${it.id} ${it.provider}" },
        )
    }
}
