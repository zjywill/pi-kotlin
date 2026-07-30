package works.earendil.pi.tui

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutocompleteTest {
    @Test
    fun `completes slash commands and command arguments`() {
        val provider =
            CombinedAutocompleteProvider(
                commands =
                    listOf(
                        SlashCommand("model", "Select model", "<provider/model>") { prefix ->
                            listOf("faux/one", "faux/two")
                                .filter { it.startsWith(prefix) }
                                .map(::AutocompleteItem)
                        },
                        SlashCommand("help", "Show help"),
                    ),
                basePath = Files.createTempDirectory("pi-kotlin-autocomplete-command"),
            )

        val commands = provider.getSuggestions(listOf("/mo"), 0, 3).join()
        val arguments = provider.getSuggestions(listOf("/model faux/t"), 0, 13).join()

        assertEquals(listOf("model"), commands?.items?.map(AutocompleteItem::value))
        assertEquals("/mo", commands?.prefix)
        assertEquals(listOf("faux/two"), arguments?.items?.map(AutocompleteItem::value))
        assertEquals("faux/t", arguments?.prefix)
    }

    @Test
    fun `returns fuzzy at paths quotes spaces and excludes git`() {
        val root = Files.createTempDirectory("pi-kotlin-autocomplete-files")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src").resolve("index.kt"), "")
        Files.createDirectories(root.resolve("my folder"))
        Files.writeString(root.resolve("my folder").resolve("test.txt"), "")
        Files.createDirectories(root.resolve(".git"))
        Files.writeString(root.resolve(".git").resolve("config"), "")
        val provider = CombinedAutocompleteProvider(basePath = root)

        val index = provider.getSuggestions(listOf("@index"), 0, 6).join()
        val spaced = provider.getSuggestions(listOf("@my"), 0, 3).join()
        val all = provider.getSuggestions(listOf("@"), 0, 1).join()

        assertTrue(index?.items.orEmpty().any { it.value == "@src/index.kt" })
        assertTrue(spaced?.items.orEmpty().any { it.value == "@\"my folder/\"" })
        assertTrue(all?.items.orEmpty().none { ".git" in it.value })
    }

    @Test
    fun `applies command attachment and directory completions with cursor parity`() {
        val root = Files.createTempDirectory("pi-kotlin-autocomplete-apply")
        val provider = CombinedAutocompleteProvider(basePath = root)

        val command =
            provider.applyCompletion(
                listOf("/mo"),
                0,
                3,
                AutocompleteItem("model"),
                "/mo",
            )
        val file =
            provider.applyCompletion(
                listOf("read @RE"),
                0,
                8,
                AutocompleteItem("@README.md", "README.md"),
                "@RE",
            )
        val directory =
            provider.applyCompletion(
                listOf("@sr"),
                0,
                3,
                AutocompleteItem("@src/", "src/"),
                "@sr",
            )

        assertEquals("/model ", command.lines.single())
        assertEquals(7, command.cursorColumn)
        assertEquals("read @README.md ", file.lines.single())
        assertEquals("read @README.md ".length, file.cursorColumn)
        assertEquals("@src/", directory.lines.single())
        assertEquals(5, directory.cursorColumn)
    }

    @Test
    fun `forced path completion does not treat slash command as absolute path`() {
        val provider =
            CombinedAutocompleteProvider(
                commands = listOf(SlashCommand("model")),
                basePath = Files.createTempDirectory("pi-kotlin-autocomplete-force"),
            )

        assertNull(provider.getSuggestions(listOf("/model"), 0, 6, AutocompleteRequest(force = true)).join())
        assertNotNull(provider.getSuggestions(listOf("/model /"), 0, 8, AutocompleteRequest(force = true)).join())
    }
}
