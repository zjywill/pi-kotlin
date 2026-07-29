package works.earendil.pi.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MessagesTest {
    @Test
    fun `contentText extracts only text blocks`() {
        val content =
            listOf(
                ThinkingContent("reasoning"),
                TextContent("first"),
                ToolCall("1", "read", buildJsonObject {}),
                TextContent("second"),
            )

        assertEquals("first\nsecond", contentText(content))
        assertEquals("firstsecond", contentText(content, ""))
        assertEquals("hello", contentText("hello"))
    }

    @Test
    fun `pending stop reason uses the upstream wire value`() {
        assertEquals(
            "\"pending\"",
            Json.encodeToString(StopReason.serializer(), StopReason.PENDING),
        )
    }
}
