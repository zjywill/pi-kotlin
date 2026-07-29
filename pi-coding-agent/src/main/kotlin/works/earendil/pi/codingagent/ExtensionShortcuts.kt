package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import works.earendil.pi.tui.TUI_KEYBINDINGS

private val RESERVED_EXTENSION_SHORTCUT_KEYBINDINGS =
    setOf(
        "app.interrupt",
        "app.clear",
        "app.exit",
        "app.suspend",
        "app.thinking.cycle",
        "app.model.cycleForward",
        "app.model.cycleBackward",
        "app.model.select",
        "app.tools.expand",
        "app.thinking.toggle",
        "app.editor.external",
        "app.message.copy",
        "app.message.followUp",
        "tui.input.submit",
        "tui.select.confirm",
        "tui.select.cancel",
        "tui.input.copy",
        "tui.editor.deleteToLineEnd",
    )

private val LEGACY_KEYBINDING_NAMES =
    mapOf(
        "cursorUp" to "tui.editor.cursorUp",
        "cursorDown" to "tui.editor.cursorDown",
        "cursorLeft" to "tui.editor.cursorLeft",
        "cursorRight" to "tui.editor.cursorRight",
        "cursorWordLeft" to "tui.editor.cursorWordLeft",
        "cursorWordRight" to "tui.editor.cursorWordRight",
        "cursorLineStart" to "tui.editor.cursorLineStart",
        "cursorLineEnd" to "tui.editor.cursorLineEnd",
        "jumpForward" to "tui.editor.jumpForward",
        "jumpBackward" to "tui.editor.jumpBackward",
        "pageUp" to "tui.editor.pageUp",
        "pageDown" to "tui.editor.pageDown",
        "deleteCharBackward" to "tui.editor.deleteCharBackward",
        "deleteCharForward" to "tui.editor.deleteCharForward",
        "deleteWordBackward" to "tui.editor.deleteWordBackward",
        "deleteWordForward" to "tui.editor.deleteWordForward",
        "deleteToLineStart" to "tui.editor.deleteToLineStart",
        "deleteToLineEnd" to "tui.editor.deleteToLineEnd",
        "yank" to "tui.editor.yank",
        "yankPop" to "tui.editor.yankPop",
        "undo" to "tui.editor.undo",
        "newLine" to "tui.input.newLine",
        "submit" to "tui.input.submit",
        "tab" to "tui.input.tab",
        "copy" to "tui.input.copy",
        "selectUp" to "tui.select.up",
        "selectDown" to "tui.select.down",
        "selectPageUp" to "tui.select.pageUp",
        "selectPageDown" to "tui.select.pageDown",
        "selectConfirm" to "tui.select.confirm",
        "selectCancel" to "tui.select.cancel",
        "interrupt" to "app.interrupt",
        "clear" to "app.clear",
        "exit" to "app.exit",
        "suspend" to "app.suspend",
        "cycleThinkingLevel" to "app.thinking.cycle",
        "cycleModelForward" to "app.model.cycleForward",
        "cycleModelBackward" to "app.model.cycleBackward",
        "selectModel" to "app.model.select",
        "expandTools" to "app.tools.expand",
        "toggleThinking" to "app.thinking.toggle",
        "toggleSessionNamedFilter" to "app.session.toggleNamedFilter",
        "externalEditor" to "app.editor.external",
        "followUp" to "app.message.followUp",
        "dequeue" to "app.message.dequeue",
        "pasteImage" to "app.clipboard.pasteImage",
        "newSession" to "app.session.new",
        "tree" to "app.session.tree",
        "fork" to "app.session.fork",
        "resume" to "app.session.resume",
        "treeFoldOrUp" to "app.tree.foldOrUp",
        "treeUnfoldOrDown" to "app.tree.unfoldOrDown",
        "treeEditLabel" to "app.tree.editLabel",
        "treeToggleLabelTimestamp" to "app.tree.toggleLabelTimestamp",
        "toggleSessionPath" to "app.session.togglePath",
        "toggleSessionSort" to "app.session.toggleSort",
        "renameSession" to "app.session.rename",
        "deleteSession" to "app.session.delete",
        "deleteSessionNoninvasive" to "app.session.deleteNoninvasive",
    )

internal data class ExtensionShortcutResolution(
    val shortcuts: Map<String, ExtensionShortcutRegistration>,
    val diagnostics: List<ExtensionDiagnostic>,
)

private data class BuiltInShortcutClaim(
    val keybinding: String,
    val restrictOverride: Boolean,
)

internal fun loadExtensionShortcutKeybindings(
    agentDir: Path,
    osName: String = System.getProperty("os.name"),
): Map<String, List<String>> {
    val defaults = defaultCodingAgentKeybindings(osName)
    val path = agentDir.resolve("keybindings.json")
    if (!Files.isRegularFile(path)) {
        return defaults
    }
    val raw =
        runCatching {
            protocolJson.parseToJsonElement(Files.readString(path)).jsonObject
        }.getOrNull() ?: return defaults
    val userBindings = linkedMapOf<String, List<String>>()
    raw.forEach { (rawName, value) ->
        val name = LEGACY_KEYBINDING_NAMES[rawName] ?: rawName
        if (name != rawName && name in raw) {
            return@forEach
        }
        parseKeybindingValue(value)?.let { userBindings[name] = it }
    }
    return defaults.mapValues { (name, keys) -> userBindings[name] ?: keys }
}

internal fun resolveExtensionShortcuts(
    registrations: List<ExtensionRegistration>,
    resolvedKeybindings: Map<String, List<String>>,
): ExtensionShortcutResolution {
    val builtIns = linkedMapOf<String, BuiltInShortcutClaim>()
    resolvedKeybindings.forEach { (keybinding, keys) ->
        val restrictOverride = keybinding in RESERVED_EXTENSION_SHORTCUT_KEYBINDINGS
        keys.forEach { key ->
            val normalized = key.lowercase()
            val existing = builtIns[normalized]
            if (existing?.restrictOverride == true && !restrictOverride) {
                return@forEach
            }
            builtIns[normalized] = BuiltInShortcutClaim(keybinding, restrictOverride)
        }
    }

    val resolved = linkedMapOf<String, ExtensionShortcutRegistration>()
    val diagnostics = mutableListOf<ExtensionDiagnostic>()
    registrations.forEach { extension ->
        extension.shortcuts.forEach { shortcut ->
            val normalized = shortcut.shortcut.lowercase()
            val builtIn = builtIns[normalized]
            if (builtIn?.restrictOverride == true) {
                diagnostics +=
                    shortcutDiagnostic(
                        shortcut,
                        "Extension shortcut '${shortcut.shortcut}' from ${shortcut.extensionPath} " +
                            "conflicts with built-in shortcut. Skipping.",
                    )
                return@forEach
            }
            if (builtIn != null) {
                diagnostics +=
                    shortcutDiagnostic(
                        shortcut,
                        "Extension shortcut conflict: '${shortcut.shortcut}' is built-in shortcut for " +
                            "${builtIn.keybinding} and ${shortcut.extensionPath}. Using ${shortcut.extensionPath}.",
                    )
            }
            resolved[normalized]?.let { existing ->
                diagnostics +=
                    shortcutDiagnostic(
                        shortcut,
                        "Extension shortcut conflict: '${shortcut.shortcut}' registered by both " +
                            "${existing.extensionPath} and ${shortcut.extensionPath}. Using ${shortcut.extensionPath}.",
                    )
            }
            resolved[normalized] = shortcut
        }
    }
    return ExtensionShortcutResolution(resolved, diagnostics)
}

private fun shortcutDiagnostic(
    shortcut: ExtensionShortcutRegistration,
    message: String,
): ExtensionDiagnostic =
    ExtensionDiagnostic(
        extensionPath = shortcut.extensionPath.toString(),
        event = "shortcut",
        error = message,
    )

private fun parseKeybindingValue(value: kotlinx.serialization.json.JsonElement): List<String>? =
    when (value) {
        is JsonPrimitive -> value.contentOrNull?.let(::listOf)
        is JsonArray ->
            value
                .map { (it as? JsonPrimitive)?.contentOrNull ?: return null }

        else -> null
    }

private fun defaultCodingAgentKeybindings(osName: String): Map<String, List<String>> {
    val isWindows = osName.lowercase().contains("win")
    val isMac = osName.lowercase().contains("mac")
    return buildMap {
        TUI_KEYBINDINGS.forEach { (name, definition) -> put(name, definition.defaultKeys) }
        put("app.interrupt", listOf("escape"))
        put("app.clear", listOf("ctrl+c"))
        put("app.exit", listOf("ctrl+d"))
        put("app.suspend", if (isWindows) emptyList() else listOf("ctrl+z"))
        put("app.thinking.cycle", listOf("shift+tab"))
        put("app.model.cycleForward", listOf("ctrl+p"))
        put("app.model.cycleBackward", listOf("shift+ctrl+p"))
        put("app.model.select", listOf("ctrl+l"))
        put("app.tools.expand", listOf("ctrl+o"))
        put("app.thinking.toggle", listOf("ctrl+t"))
        put("app.session.toggleNamedFilter", listOf("ctrl+n"))
        put("app.editor.external", listOf("ctrl+g"))
        put("app.message.copy", listOf("ctrl+x"))
        put("app.message.followUp", listOf("alt+enter"))
        put("app.message.dequeue", listOf("alt+up"))
        put("app.clipboard.pasteImage", listOf(if (isWindows) "alt+v" else "ctrl+v"))
        put("app.session.new", emptyList())
        put("app.session.tree", emptyList())
        put("app.session.fork", emptyList())
        put("app.session.resume", emptyList())
        put(
            "app.tree.foldOrUp",
            if (isMac) listOf("alt+left", "ctrl+left") else listOf("ctrl+left", "alt+left"),
        )
        put(
            "app.tree.unfoldOrDown",
            if (isMac) listOf("alt+right", "ctrl+right") else listOf("ctrl+right", "alt+right"),
        )
        put("app.tree.editLabel", listOf("shift+l"))
        put("app.tree.toggleLabelTimestamp", listOf("shift+t"))
        put("app.session.togglePath", listOf("ctrl+p"))
        put("app.session.toggleSort", listOf("ctrl+s"))
        put("app.session.rename", listOf("ctrl+r"))
        put("app.session.delete", listOf("ctrl+d"))
        put("app.session.deleteNoninvasive", listOf("ctrl+backspace"))
        put("app.models.save", listOf("ctrl+s"))
        put("app.models.enableAll", listOf("ctrl+a"))
        put("app.models.clearAll", listOf("ctrl+x"))
        put("app.models.toggleProvider", listOf("ctrl+p"))
        put("app.models.reorderUp", listOf("alt+up"))
        put("app.models.reorderDown", listOf("alt+down"))
        put("app.tree.filter.default", listOf("ctrl+d"))
        put("app.tree.filter.noTools", listOf("ctrl+t"))
        put("app.tree.filter.userOnly", listOf("ctrl+u"))
        put("app.tree.filter.labeledOnly", listOf("ctrl+l"))
        put("app.tree.filter.all", listOf("ctrl+a"))
        put("app.tree.filter.cycleForward", listOf("ctrl+o"))
        put("app.tree.filter.cycleBackward", listOf("shift+ctrl+o"))
    }
}
