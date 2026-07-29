package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionShortcutsTest {
    @Test
    fun `reserved shortcuts are skipped and later extension registrations win`() {
        val root = Files.createTempDirectory("pi-kotlin-extension-shortcuts")
        val firstPath = root.resolve("first.ts")
        val secondPath = root.resolve("second.ts")
        val registrations =
            listOf(
                extensionRegistration(
                    firstPath,
                    shortcut(firstPath, "reserved", "ctrl+c", "Reserved"),
                    shortcut(firstPath, "non-reserved", "ctrl+y", "Non-reserved"),
                    shortcut(firstPath, "first", "ctrl+shift+x", "First"),
                ),
                extensionRegistration(
                    secondPath,
                    shortcut(secondPath, "second", "CTRL+SHIFT+X", "Second"),
                    shortcut(secondPath, "free", "ctrl+q", "Free"),
                ),
            )

        val resolution =
            resolveExtensionShortcuts(
                registrations,
                loadExtensionShortcutKeybindings(root),
            )

        assertFalse("ctrl+c" in resolution.shortcuts)
        assertEquals(setOf("ctrl+y", "ctrl+shift+x", "ctrl+q"), resolution.shortcuts.keys)
        assertEquals("Second", resolution.shortcuts.getValue("ctrl+shift+x").description)
        assertTrue(resolution.diagnostics.any { it.error.contains("conflicts with built-in shortcut. Skipping.") })
        assertTrue(resolution.diagnostics.any { it.error.contains("built-in shortcut for tui.editor.yank") })
        assertTrue(resolution.diagnostics.any { it.error.contains("registered by both") })
    }

    @Test
    fun `user keybindings free defaults and keep rebound reserved actions protected`() {
        val root = Files.createTempDirectory("pi-kotlin-extension-shortcut-keybindings")
        Files.writeString(
            root.resolve("keybindings.json"),
            """
            {
              "app.interrupt": "ctrl+q",
              "app.model.cycleForward": "ctrl+n"
            }
            """.trimIndent(),
        )
        val extensionPath = root.resolve("extension.ts")
        val registrations =
            listOf(
                extensionRegistration(
                    extensionPath,
                    shortcut(extensionPath, "rebound", "ctrl+q", "Rebound"),
                    shortcut(extensionPath, "freed", "ctrl+p", "Freed"),
                ),
            )

        val resolution =
            resolveExtensionShortcuts(
                registrations,
                loadExtensionShortcutKeybindings(root),
            )

        assertFalse("ctrl+q" in resolution.shortcuts)
        assertEquals("Freed", resolution.shortcuts.getValue("ctrl+p").description)
        assertTrue(resolution.diagnostics.any { it.error.contains("'ctrl+q'") && it.error.contains("Skipping") })
        assertTrue(resolution.diagnostics.any { it.error.contains("'ctrl+p'") && it.error.contains("Using") })
    }

    private fun extensionRegistration(
        path: Path,
        vararg shortcuts: ExtensionShortcutRegistration,
    ): ExtensionRegistration =
        ExtensionRegistration(
            path = path,
            events = emptySet(),
            shortcuts = shortcuts.toList(),
            messageRenderers = emptyList(),
            entryRenderers = emptyList(),
        )

    private fun shortcut(
        path: Path,
        id: String,
        key: String,
        description: String,
    ): ExtensionShortcutRegistration =
        ExtensionShortcutRegistration(
            id = id,
            shortcut = key,
            description = description,
            extensionPath = path,
        )
}
