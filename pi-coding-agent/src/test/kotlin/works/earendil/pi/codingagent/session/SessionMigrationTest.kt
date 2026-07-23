package works.earendil.pi.codingagent.session

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionMigrationTest {
    @Test
    fun `migrates v1 entries to a v3 parent chain`() {
        val entries =
            parseSessionEntries(
                """
                {"type":"session","id":"sess-1","timestamp":"2025-01-01T00:00:00Z","cwd":"/tmp"}
                {"type":"message","timestamp":"2025-01-01T00:00:01Z","message":{"role":"user","content":"hi","timestamp":1}}
                {"type":"message","timestamp":"2025-01-01T00:00:02Z","message":{"role":"assistant","content":[{"type":"text","text":"hello"}],"api":"test","provider":"test","model":"test","usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0},"stopReason":"stop","timestamp":2}}
                """.trimIndent(),
            )

        assertTrue(migrateSessionEntries(entries))

        assertEquals(3, entries.first()["version"]?.jsonPrimitive?.int)
        val firstId = entries[1]["id"]?.jsonPrimitive?.contentOrNull
        assertNotNull(firstId)
        assertEquals(8, firstId.length)
        assertNull(entries[1]["parentId"]?.let { if (it.toString() == "null") null else it })
        assertEquals(firstId, entries[2]["parentId"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `migration is idempotent for current sessions`() {
        val entries =
            parseSessionEntries(
                """
                {"type":"session","version":3,"id":"sess-1","timestamp":"2025-01-01T00:00:00Z","cwd":"/tmp"}
                {"type":"message","id":"abc12345","parentId":null,"timestamp":"2025-01-01T00:00:01Z","message":{"role":"user","content":"hi","timestamp":1}}
                """.trimIndent(),
            )

        assertEquals(false, migrateSessionEntries(entries))
        assertEquals("abc12345", entries[1]["id"]?.jsonPrimitive?.contentOrNull)
    }
}
