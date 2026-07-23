package works.earendil.pi.tui

class KillRing {
    private val ring = mutableListOf<String>()

    fun push(
        text: String,
        prepend: Boolean,
        accumulate: Boolean = false,
    ) {
        if (text.isEmpty()) {
            return
        }
        if (accumulate && ring.isNotEmpty()) {
            val previous = ring.removeLast()
            ring += if (prepend) text + previous else previous + text
        } else {
            ring += text
        }
    }

    fun peek(): String? = ring.lastOrNull()

    fun rotate() {
        if (ring.size > 1) {
            ring.add(0, ring.removeLast())
        }
    }

    val length: Int
        get() = ring.size
}

class UndoStack<S>(
    private val clone: (S) -> S = { it },
) {
    private val stack = mutableListOf<S>()

    fun push(state: S) {
        stack += clone(state)
    }

    fun pop(): S? = stack.removeLastOrNull()

    fun clear() {
        stack.clear()
    }

    val length: Int
        get() = stack.size
}
