package works.earendil.pi.codingagent

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.FauxResponseStep
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.StopReason
import works.earendil.pi.ai.fauxAssistantMessage
import works.earendil.pi.ai.fauxToolCall
import works.earendil.pi.codingagent.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionHtmlExporterTest {
    @Test
    fun `exports an upstream-compatible standalone HTML session`() {
        val root = Files.createTempDirectory("pi-kotlin-export")
        val sessionDir = Files.createDirectories(root.resolve("sessions"))
        val session = SessionManager.create(root, sessionDir)
        session.appendMessage(works.earendil.pi.ai.UserMessage("<script>alert('x')</script>\n  kept"))
        session.appendMessage(fauxAssistantMessage("answer & details"))
        session.appendSessionInfo("\"title</title><script>bad()</script>")
        val output = root.resolve("nested").resolve("session.html")

        val path = exportSession(session, output)
        val html = Files.readString(path)
        val data = decodeSessionData(html)
        val messages =
            data["entries"]
                ?.jsonArray
                .orEmpty()
                .mapNotNull { it.jsonObject["message"]?.jsonObject }

        assertEquals(output, path)
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertFalse(html.contains("<script>alert('x')</script>"))
        assertFalse(html.contains("<script>bad()</script>"))
        assertEquals("<script>alert('x')</script>\n  kept", messages.first()["content"]?.jsonPrimitive?.content)
        assertEquals(
            "answer & details",
            messages[1]["content"]
                ?.jsonArray
                ?.first()
                ?.jsonObject
                ?.get("text")
                ?.jsonPrimitive
                ?.content,
        )
        assertTrue(html.contains("--accent: #8abeb7;"))
        assertTrue(html.contains("--searchMatchBg: #3a3a4a;"))
        assertTrue(html.contains("--searchMatchText: #d4d4d4;"))
        assertTrue(html.contains("--exportPageBg: #18181e;"))
        assertTrue(html.contains("function safeMarkedParse(text)"))
        assertTrue(html.contains("hljs.highlight(code"))
        assertTrue(html.contains("white-space: pre-wrap"))
    }

    @Test
    fun `exports resolved custom theme and explicit export colors`() {
        val root = Files.createTempDirectory("pi-kotlin-export-theme")
        val session = SessionManager.create(root, Files.createDirectories(root.resolve("sessions")))
        session.appendMessage(works.earendil.pi.ai.UserMessage("theme"))
        session.appendMessage(fauxAssistantMessage("answer"))
        val custom = readBuiltinThemeJson("dark").toMutableMap()
        custom["name"] = kotlinx.serialization.json.JsonPrimitive("custom-export")
        val colors = custom.getValue("colors").jsonObject.toMutableMap()
        colors["accent"] = kotlinx.serialization.json.JsonPrimitive("#123456")
        custom["colors"] = kotlinx.serialization.json.JsonObject(colors)
        val export =
            buildJsonObject {
                put("pageBg", "#112233")
                put("cardBg", 24)
                put("infoBg", "#445566")
            }
        custom["export"] = export
        val themePath = root.resolve("custom-export.json")
        Files.writeString(
            themePath,
            protocolJson.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.JsonObject(custom),
            ),
        )

        val html =
            generateSessionHtml(
                session,
                SessionHtmlExportOptions(
                    theme = loadThemeFromPath(themePath, ThemeColorMode.TRUECOLOR),
                ),
            )

        assertTrue(html.contains("--accent: #123456;"))
        assertTrue(html.contains("--exportPageBg: #112233;"))
        assertTrue(html.contains("--exportCardBg: #005f87;"))
        assertTrue(html.contains("--exportInfoBg: #445566;"))
        assertTrue(html.contains("--body-bg: #112233;"))
    }

    @Test
    fun `rpc exports its persisted session`() =
        runTest {
            val provider = FauxProvider()
            provider.setResponses(listOf(FauxResponseStep.Message(fauxAssistantMessage("exported"))))
            val root = Files.createTempDirectory("pi-kotlin-rpc-export")
            val runtime =
                RpcRuntime(
                    Models(listOf(provider)),
                    RpcRuntimeOptions(
                        cwd = root,
                        sessionDir = root.resolve("sessions"),
                        provider = "faux",
                        model = "faux-1",
                    ),
                )
            runtime.handle(
                buildJsonObject {
                    put("type", "prompt")
                    put("message", "hello")
                },
            )
            runtime.waitForIdle()
            val output = root.resolve("rpc.html")

            val response =
                requireNotNull(
                    runtime.handle(
                        buildJsonObject {
                            put("type", "export_html")
                            put("outputPath", output.toString())
                        },
                    ),
                )

            assertTrue(response["success"]?.jsonPrimitive?.boolean ?: false)
            assertEquals(output.toString(), response["data"]?.jsonObject?.get("path")?.jsonPrimitive?.content)
            val data = decodeSessionData(Files.readString(output))
            assertTrue(data.toString().contains("exported"))
            assertTrue(data["systemPrompt"]?.jsonPrimitive?.content?.isNotEmpty() == true)
            assertTrue(data["tools"]?.jsonArray?.isNotEmpty() == true)
            runtime.close()
        }

    @Test
    fun `rpc pre-renders extension tool calls and results`() =
        runTest {
            org.junit.jupiter.api.Assumptions.assumeTrue(
                htmlExportNodeAvailable(),
                "Node.js 22+ is required for extension runtime tests",
            )
            val projectRoot = java.nio.file.Path.of(requireNotNull(System.getProperty("pi.project.root")))
            val extension = projectRoot.resolve("migration/fixtures/html-export/extension-tool.ts")
            val root = Files.createTempDirectory("pi-kotlin-rpc-export-tool")
            val provider = FauxProvider()
            provider.setResponses(
                listOf(
                    FauxResponseStep.Message(
                        fauxAssistantMessage(
                            content =
                                listOf(
                                    fauxToolCall(
                                        id = "html-call",
                                        name = "html_probe",
                                        arguments = buildJsonObject { put("text", "hello") },
                                    ),
                                ),
                            stopReason = StopReason.TOOL_USE,
                        ),
                    ),
                    FauxResponseStep.Message(fauxAssistantMessage("done")),
                ),
            )
            val runtime =
                RpcRuntime(
                    Models(listOf(provider)),
                    RpcRuntimeOptions(
                        cwd = root,
                        agentDir = Files.createDirectories(root.resolve("agent")),
                        sessionDir = root.resolve("sessions"),
                        provider = "faux",
                        model = "faux-1",
                        extensionPaths = listOf(extension.toString()),
                    ),
                )
            runtime.handle(
                buildJsonObject {
                    put("type", "prompt")
                    put("message", "render")
                },
            )
            runtime.waitForIdle()
            val output = root.resolve("tool.html")
            runtime.handle(
                buildJsonObject {
                    put("type", "export_html")
                    put("outputPath", output.toString())
                },
            )

            val rendered =
                decodeSessionData(Files.readString(output))["renderedTools"]
                    ?.jsonObject
                    ?.get("html-call")
                    ?.jsonObject
            assertTrue(rendered?.get("callHtml")?.jsonPrimitive?.content.orEmpty().contains("call:hello:100:true"))
            assertTrue(
                rendered
                    ?.get("resultHtmlCollapsed")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                    .contains("collapsed:result:hello:100:false"),
            )
            assertTrue(
                rendered
                    ?.get("resultHtmlExpanded")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                    .contains("expanded:result:hello:100:false"),
            )
            runtime.close()
        }

    private fun decodeSessionData(html: String): kotlinx.serialization.json.JsonObject {
        val encoded =
            requireNotNull(
                Regex(
                    """<script id="session-data" type="application/json">([^<]+)</script>""",
                ).find(html),
            ).groupValues[1]
        val decoded =
            String(
                Base64.getDecoder().decode(encoded),
                StandardCharsets.UTF_8,
            )
        return Json.parseToJsonElement(decoded).jsonObject
    }
}

private fun htmlExportNodeAvailable(): Boolean =
    runCatching {
        val process = ProcessBuilder("node", "--version").start()
        process.waitFor() == 0
    }.getOrDefault(false)
