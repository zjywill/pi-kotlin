package works.earendil.pi.codingagent

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.earendil.pi.tui.ScrollViewScrollbar
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsStoreTest {
    @Test
    fun `UI mode and fullscreen scrollbar default validate and persist`() {
        val root = Files.createTempDirectory("pi-kotlin-ui-settings")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)

        assertEquals(UiMode.REGULAR, settings.mergedUiMode())
        assertEquals(ScrollViewScrollbar.AUTO, settings.mergedFullscreenScrollbar())
        assertEquals(5, settings.mergedAutocompleteMaxVisible())

        settings.setUiMode(UiMode.FULLSCREEN)
        settings.setFullscreenScrollbar(ScrollViewScrollbar.HIDDEN)
        settings.setAutocompleteMaxVisible(12)

        val persisted =
            Json.parseToJsonElement(Files.readString(agentDir.resolve("settings.json"))).jsonObject
        assertEquals("fullscreen", persisted["uiMode"]?.jsonPrimitive?.content)
        assertEquals("hidden", persisted["fullscreenScrollbar"]?.jsonPrimitive?.content)
        assertEquals("12", persisted["autocompleteMaxVisible"]?.jsonPrimitive?.content)
        assertEquals(UiMode.FULLSCREEN, settings.mergedUiMode())
        assertEquals(ScrollViewScrollbar.HIDDEN, settings.mergedFullscreenScrollbar())
        assertEquals(12, settings.mergedAutocompleteMaxVisible())

        Files.writeString(
            agentDir.resolve("settings.json"),
            """{"uiMode":"other","fullscreenScrollbar":"sometimes","autocompleteMaxVisible":99}""",
        )
        val invalid = SettingsStore(cwd, agentDir, projectTrusted = true)
        assertEquals(UiMode.REGULAR, invalid.mergedUiMode())
        assertEquals(ScrollViewScrollbar.AUTO, invalid.mergedFullscreenScrollbar())
        assertEquals(20, invalid.mergedAutocompleteMaxVisible())
    }
}
