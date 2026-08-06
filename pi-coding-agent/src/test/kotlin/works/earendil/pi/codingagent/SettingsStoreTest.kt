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
    fun `TUI mode and fullscreen scrollbar default validate and persist`() {
        val root = Files.createTempDirectory("pi-kotlin-ui-settings")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)

        assertEquals(TuiMode.REGULAR, settings.mergedTuiMode())
        assertEquals(ScrollViewScrollbar.AUTO, settings.mergedFullscreenScrollbar())
        assertEquals(5, settings.mergedAutocompleteMaxVisible())

        settings.setTuiMode(TuiMode.FULLSCREEN)
        settings.setFullscreenScrollbar(ScrollViewScrollbar.HIDDEN)
        settings.setAutocompleteMaxVisible(12)

        val persisted =
            Json.parseToJsonElement(Files.readString(agentDir.resolve("settings.json"))).jsonObject
        assertEquals("fullscreen", persisted["tuiMode"]?.jsonPrimitive?.content)
        assertEquals("hidden", persisted["fullscreenScrollbar"]?.jsonPrimitive?.content)
        assertEquals("12", persisted["autocompleteMaxVisible"]?.jsonPrimitive?.content)
        assertEquals(TuiMode.FULLSCREEN, settings.mergedTuiMode())
        assertEquals(ScrollViewScrollbar.HIDDEN, settings.mergedFullscreenScrollbar())
        assertEquals(12, settings.mergedAutocompleteMaxVisible())

        Files.writeString(
            agentDir.resolve("settings.json"),
            """{"tuiMode":"other","fullscreenScrollbar":"sometimes","autocompleteMaxVisible":99}""",
        )
        val invalid = SettingsStore(cwd, agentDir, projectTrusted = true)
        assertEquals(TuiMode.REGULAR, invalid.mergedTuiMode())
        assertEquals(ScrollViewScrollbar.AUTO, invalid.mergedFullscreenScrollbar())
        assertEquals(20, invalid.mergedAutocompleteMaxVisible())

        Files.writeString(agentDir.resolve("settings.json"), """{"uiMode":"fullscreen"}""")
        val legacy = SettingsStore(cwd, agentDir, projectTrusted = true)
        assertEquals(TuiMode.REGULAR, legacy.mergedTuiMode())
    }

    @Test
    fun `mermaid rendering mode defaults validates and persists`() {
        val root = Files.createTempDirectory("pi-kotlin-settings-mermaid")
        val cwd = Files.createDirectories(root.resolve("project"))
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val settings = SettingsStore(cwd, agentDir, projectTrusted = true)

        assertEquals(MermaidRenderingMode.STREAMING, settings.mergedMermaidRenderingMode())
        settings.setMermaidRenderingMode(MermaidRenderingMode.FINAL)
        assertEquals(MermaidRenderingMode.FINAL, settings.mergedMermaidRenderingMode())

        Files.writeString(
            agentDir.resolve("settings.json"),
            """{"markdown":{"mermaid":"sometimes"}}""",
        )
        assertEquals(
            MermaidRenderingMode.STREAMING,
            SettingsStore(cwd, agentDir, projectTrusted = true).mergedMermaidRenderingMode(),
        )
    }
}
