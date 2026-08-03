package works.earendil.pi.tui

data class KeybindingDefinition(
    val defaultKeys: List<KeyId>,
    val description: String? = null,
) {
    constructor(defaultKey: KeyId, description: String? = null) : this(listOf(defaultKey), description)
}

data class KeybindingConflict(
    val key: KeyId,
    val keybindings: List<String>,
)

val TUI_KEYBINDINGS: Map<String, KeybindingDefinition> =
    mapOf(
        "tui.editor.cursorUp" to KeybindingDefinition("up", "Move cursor up"),
        "tui.editor.cursorDown" to KeybindingDefinition("down", "Move cursor down"),
        "tui.editor.cursorLeft" to KeybindingDefinition(listOf("left", "ctrl+b"), "Move cursor left"),
        "tui.editor.cursorRight" to KeybindingDefinition(listOf("right", "ctrl+f"), "Move cursor right"),
        "tui.editor.cursorWordLeft" to KeybindingDefinition(listOf("alt+left", "ctrl+left", "alt+b"), "Move cursor word left"),
        "tui.editor.cursorWordRight" to KeybindingDefinition(listOf("alt+right", "ctrl+right", "alt+f"), "Move cursor word right"),
        "tui.editor.cursorLineStart" to KeybindingDefinition(listOf("home", "ctrl+a"), "Move to line start"),
        "tui.editor.cursorLineEnd" to KeybindingDefinition(listOf("end", "ctrl+e"), "Move to line end"),
        "tui.editor.jumpForward" to KeybindingDefinition("ctrl+]", "Jump forward to character"),
        "tui.editor.jumpBackward" to KeybindingDefinition("ctrl+alt+]", "Jump backward to character"),
        "tui.editor.pageUp" to KeybindingDefinition("pageUp", "Page up"),
        "tui.editor.pageDown" to KeybindingDefinition("pageDown", "Page down"),
        "tui.editor.deleteCharBackward" to KeybindingDefinition("backspace", "Delete character backward"),
        "tui.editor.deleteCharForward" to KeybindingDefinition(listOf("delete", "ctrl+d"), "Delete character forward"),
        "tui.editor.deleteWordBackward" to KeybindingDefinition(listOf("ctrl+w", "alt+backspace"), "Delete word backward"),
        "tui.editor.deleteWordForward" to KeybindingDefinition(listOf("alt+d", "alt+delete"), "Delete word forward"),
        "tui.editor.deleteToLineStart" to KeybindingDefinition("ctrl+u", "Delete to line start"),
        "tui.editor.deleteToLineEnd" to KeybindingDefinition("ctrl+k", "Delete to line end"),
        "tui.editor.yank" to KeybindingDefinition("ctrl+y", "Yank"),
        "tui.editor.yankPop" to KeybindingDefinition("alt+y", "Yank pop"),
        "tui.editor.undo" to KeybindingDefinition("ctrl+-", "Undo"),
        "tui.input.newLine" to KeybindingDefinition(listOf("shift+enter", "ctrl+j"), "Insert newline"),
        "tui.input.submit" to KeybindingDefinition("enter", "Submit input"),
        "tui.input.tab" to KeybindingDefinition("tab", "Tab / autocomplete"),
        "tui.input.copy" to KeybindingDefinition("ctrl+c", "Copy selection"),
        "tui.select.up" to KeybindingDefinition("up", "Move selection up"),
        "tui.select.down" to KeybindingDefinition("down", "Move selection down"),
        "tui.select.pageUp" to KeybindingDefinition("pageUp", "Selection page up"),
        "tui.select.pageDown" to KeybindingDefinition("pageDown", "Selection page down"),
        "tui.select.confirm" to KeybindingDefinition("enter", "Confirm selection"),
        "tui.select.cancel" to KeybindingDefinition(listOf("escape", "ctrl+c"), "Cancel selection"),
        "tui.altScreen.pageUp" to KeybindingDefinition("pageUp", "Scroll viewport up one page"),
        "tui.altScreen.pageDown" to KeybindingDefinition("pageDown", "Scroll viewport down one page"),
        "tui.altScreen.previousPrompt" to KeybindingDefinition("ctrl+shift+up", "Jump to previous prompt"),
        "tui.altScreen.nextPrompt" to KeybindingDefinition("ctrl+shift+down", "Jump to next prompt"),
        "tui.altScreen.top" to KeybindingDefinition("home", "Scroll viewport to top"),
        "tui.altScreen.bottom" to KeybindingDefinition("end", "Scroll viewport to bottom"),
    )

class KeybindingsManager(
    private val definitions: Map<String, KeybindingDefinition>,
    userBindings: Map<String, List<KeyId>?> = emptyMap(),
) {
    private var userBindings: Map<String, List<KeyId>?> = userBindings
    private var keysById = emptyMap<String, List<KeyId>>()
    private var conflicts = emptyList<KeybindingConflict>()

    init {
        rebuild()
    }

    fun matches(
        data: String,
        keybinding: String,
    ): Boolean = keysById[keybinding].orEmpty().any { matchesKey(data, it) }

    fun getKeys(keybinding: String): List<KeyId> = keysById[keybinding].orEmpty().toList()

    fun getDefinition(keybinding: String): KeybindingDefinition? = definitions[keybinding]

    fun getConflicts(): List<KeybindingConflict> =
        conflicts.map { conflict -> conflict.copy(keybindings = conflict.keybindings.toList()) }

    fun setUserBindings(value: Map<String, List<KeyId>?>) {
        userBindings = value
        rebuild()
    }

    fun getUserBindings(): Map<String, List<KeyId>?> = userBindings.mapValues { it.value?.toList() }

    fun getResolvedBindings(): Map<String, List<KeyId>> = keysById.mapValues { it.value.toList() }

    private fun rebuild() {
        val userClaims = linkedMapOf<KeyId, MutableSet<String>>()
        userBindings.forEach { (keybinding, keys) ->
            if (keybinding !in definitions) {
                return@forEach
            }
            normalizeKeys(keys).forEach { key ->
                userClaims.getOrPut(key, ::linkedSetOf) += keybinding
            }
        }
        conflicts =
            userClaims
                .filterValues { it.size > 1 }
                .map { (key, keybindings) -> KeybindingConflict(key, keybindings.toList()) }
        keysById =
            definitions.mapValues { (id, definition) ->
                if (id in userBindings) {
                    normalizeKeys(userBindings[id])
                } else {
                    normalizeKeys(definition.defaultKeys)
                }
            }
    }
}

private fun normalizeKeys(keys: List<KeyId>?): List<KeyId> = keys.orEmpty().distinct()

private var globalKeybindings: KeybindingsManager? = null

fun setKeybindings(keybindings: KeybindingsManager) {
    globalKeybindings = keybindings
}

fun getKeybindings(): KeybindingsManager =
    globalKeybindings ?: KeybindingsManager(TUI_KEYBINDINGS).also { globalKeybindings = it }
