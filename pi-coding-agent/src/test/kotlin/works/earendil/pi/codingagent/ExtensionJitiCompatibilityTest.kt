package works.earendil.pi.codingagent

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtensionJitiCompatibilityTest {
    @Test
    fun `matches upstream jiti module loading compatibility`() {
        assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
        val fixtureRoot =
            Path
                .of(requireNotNull(System.getProperty("pi.project.root")))
                .resolve("migration/fixtures/extension-jiti-compat")
        val entries =
            mapOf(
                "extensionless" to "index.ts",
                "directory" to "index.ts",
                "interop" to "index.ts",
                "formats" to "index.mts",
                "tsx" to "index.tsx",
                "bare-package" to "index.ts",
                "virtual" to "index.ts",
                "commonjs" to "index.cjs",
                "jsx" to "index.tsx",
            )
        val expected =
            mapOf(
                "jiti-extensionless" to "extensionless:typed-dependency",
                "jiti-directory" to "directory-index",
                "jiti-interop" to "legacy-default:legacy-named:required",
                "jiti-formats" to "mjs:cts-default:cts",
                "jiti-tsx" to "tsx",
                "jiti-bare-package" to "bare-package:42",
                "jiti-virtual" to "virtual-tool:object",
                "jiti-commonjs" to "commonjs",
            )
        val diagnostics = mutableListOf<ExtensionDiagnostic>()
        val host =
            assertNotNull(
                ExtensionHost.start(
                    sources =
                        entries.map { (name, entry) ->
                            val path = fixtureRoot.resolve(name).resolve(entry)
                            ExtensionSource(
                                path,
                                ResourceSourceInfo(path, "local", baseDir = path.parent),
                            )
                        },
                    agentDir = Files.createTempDirectory("pi-jiti-compatibility-test"),
                    cwd = fixtureRoot,
                    mode = ExtensionMode.PRINT,
                    projectTrusted = true,
                    flagValues = emptyMap(),
                    context = extensionContext(fixtureRoot),
                    onDiagnostic = diagnostics::add,
                ),
            )

        host.use {
            assertEquals(
                expected,
                host.registrations.commands.associate { it.name to it.description },
            )
            assertEquals(1, diagnostics.size)
            assertTrue(diagnostics.single().extensionPath.endsWith("/jsx/index.tsx"))
            assertTrue(diagnostics.single().error.contains("ParseError"))
        }
    }

    @Test
    fun `module cache is disabled across additional extension loads`() {
        assumeTrue(nodeAvailable(), "Node.js 22+ is required for extension runtime tests")
        val root = Files.createTempDirectory("pi-jiti-module-cache-test")
        val agentDir = Files.createDirectories(root.resolve("agent"))
        val dependency = root.resolve("shared.ts")
        val first = root.resolve("first.ts")
        val second = root.resolve("second.ts")
        Files.writeString(dependency, """export default "first";""")
        Files.writeString(
            first,
            """
            import value from "./shared";
            export default function(pi) {
              pi.registerCommand("jiti-cache-first", { description: value, handler() {} });
            }
            """.trimIndent(),
        )
        Files.writeString(
            second,
            """
            import value from "./shared";
            export default function(pi) {
              pi.registerCommand("jiti-cache-second", { description: value, handler() {} });
            }
            """.trimIndent(),
        )
        val host =
            assertNotNull(
                ExtensionHost.start(
                    sources = listOf(source(first, root)),
                    agentDir = agentDir,
                    cwd = root,
                    mode = ExtensionMode.PRINT,
                    projectTrusted = true,
                    flagValues = emptyMap(),
                    context = extensionContext(root),
                ),
            )

        host.use {
            assertEquals(
                "first",
                host.registrations.commands.single { it.name == "jiti-cache-first" }.description,
            )
            Files.writeString(dependency, """export default "second";""")
            host.loadAdditional(listOf(source(second, root)), extensionContext(root))
            assertEquals(
                "second",
                host.registrations.commands.single { it.name == "jiti-cache-second" }.description,
            )
        }
    }

    private fun source(
        path: Path,
        root: Path,
    ): ExtensionSource =
        ExtensionSource(
            path,
            ResourceSourceInfo(path, "local", baseDir = root),
        )

    private fun extensionContext(root: Path) =
        buildJsonObject {
            put("cwd", root.toString())
            put("mode", "print")
            put("hasUI", false)
            put("projectTrusted", true)
            put("thinkingLevel", "off")
            put("systemPrompt", "")
        }

    private fun nodeAvailable(): Boolean =
        runCatching {
            val process = ProcessBuilder("node", "--version").start()
            process.waitFor()
            process.exitValue() == 0 &&
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
                    .removePrefix("v")
                    .substringBefore('.')
                    .toInt() >= 22
        }.getOrDefault(false)
}
