package works.earendil.pi.codingagent

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.FauxProvider
import works.earendil.pi.ai.FauxResponseStep
import works.earendil.pi.ai.Models
import works.earendil.pi.ai.fauxAssistantMessage
import works.earendil.pi.codingagent.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionHtmlExporterTest {
    @Test
    fun `exports a standalone escaped HTML session`() {
        val root = Files.createTempDirectory("pi-kotlin-export")
        val sessionDir = Files.createDirectories(root.resolve("sessions"))
        val session = SessionManager.create(root, sessionDir)
        session.appendMessage(works.earendil.pi.ai.UserMessage("<script>alert('x')</script>\n  kept"))
        session.appendMessage(fauxAssistantMessage("answer & details"))
        session.appendSessionInfo("\"title</title><script>bad()</script>")
        val output = root.resolve("nested").resolve("session.html")

        val path = exportSession(session, output)
        val html = Files.readString(path)

        assertEquals(output, path)
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;\n  kept"))
        assertTrue(html.contains("answer &amp; details"))
        assertFalse(html.contains("<script>alert('x')</script>"))
        assertFalse(html.contains("<script>bad()</script>"))
        assertTrue(html.contains("white-space: pre-wrap"))
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
            assertTrue(Files.readString(output).contains("exported"))
            runtime.close()
        }
}
