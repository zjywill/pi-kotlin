package works.earendil.pi.codingagent.tools

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.earendil.pi.ai.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BuiltInToolsTest {
    @Test
    fun `write read and edit form a compatible file workflow`() =
        runTest {
            val cwd = Files.createTempDirectory("pi-kotlin-tools")
            WriteTool(cwd).execute(
                "write-1",
                buildJsonObject {
                    put("path", "src/example.txt")
                    put("content", "alpha\nbeta\ngamma")
                },
            )
            val read =
                ReadTool(cwd).execute(
                    "read-1",
                    buildJsonObject {
                        put("path", "src/example.txt")
                        put("offset", 2)
                        put("limit", 1)
                    },
                )
            assertTrue((read.content.single() as TextContent).text.startsWith("beta"))

            EditTool(cwd).execute(
                "edit-1",
                buildJsonObject {
                    put("path", "src/example.txt")
                    put(
                        "edits",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("oldText", "beta")
                                    put("newText", "delta")
                                },
                            ),
                        ),
                    )
                },
            )

            assertEquals("alpha\ndelta\ngamma", Files.readString(cwd.resolve("src/example.txt")))
        }

    @Test
    fun `edit rejects ambiguous replacements`() =
        runTest {
            val cwd = Files.createTempDirectory("pi-kotlin-tools")
            Files.writeString(cwd.resolve("file.txt"), "same\nsame")

            assertFailsWith<IllegalArgumentException> {
                EditTool(cwd).execute(
                    "edit-1",
                    buildJsonObject {
                        put("path", "file.txt")
                        put(
                            "edits",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("oldText", "same")
                                        put("newText", "other")
                                    },
                                ),
                            ),
                        )
                    },
                )
            }
        }

    @Test
    fun `bash returns output and reports nonzero exits`() =
        runTest {
            val cwd = Files.createTempDirectory("pi-kotlin-tools")
            val success =
                BashTool(cwd).execute(
                    "bash-1",
                    buildJsonObject { put("command", "printf ok") },
                )
            assertEquals("ok", (success.content.single() as TextContent).text)

            assertFailsWith<IllegalStateException> {
                BashTool(cwd).execute(
                    "bash-2",
                    buildJsonObject { put("command", "printf bad; exit 7") },
                )
            }
        }

    @Test
    fun `find grep and ls discover local files`() =
        runTest {
            val cwd = Files.createTempDirectory("pi-kotlin-tools")
            Files.createDirectories(cwd.resolve("src"))
            Files.writeString(cwd.resolve("src/Main.kt"), "fun main() = println(\"hello\")")

            val found =
                FindTool(cwd).execute(
                    "find-1",
                    buildJsonObject { put("pattern", "**/*.kt") },
                )
            assertTrue((found.content.single() as TextContent).text.contains("src/Main.kt"))

            val grepped =
                GrepTool(cwd).execute(
                    "grep-1",
                    buildJsonObject {
                        put("pattern", "println")
                        put("path", "src")
                    },
                )
            assertTrue((grepped.content.single() as TextContent).text.contains("Main.kt:1"))

            val listed =
                LsTool(cwd).execute(
                    "ls-1",
                    buildJsonObject { put("path", ".") },
                )
            assertEquals("src/", (listed.content.single() as TextContent).text)
        }
}
