package works.earendil.pi.tui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditingHistoryTest {
    @Test
    fun `kill ring accumulates and rotates entries`() {
        val ring = KillRing()
        ring.push("world", prepend = false)
        ring.push("hello ", prepend = true, accumulate = true)
        ring.push("older", prepend = false)
        assertEquals("older", ring.peek())
        ring.rotate()
        assertEquals("hello world", ring.peek())
        assertEquals(2, ring.length)
    }

    @Test
    fun `undo stack clones on push and clears`() {
        data class State(val values: MutableList<String>)

        val stack = UndoStack<State> { state -> State(state.values.toMutableList()) }
        val original = State(mutableListOf("a"))
        stack.push(original)
        original.values += "b"
        assertTrue(stack.pop()?.values == listOf("a"))
        assertNull(stack.pop())
        stack.push(original)
        stack.clear()
        assertEquals(0, stack.length)
    }
}
