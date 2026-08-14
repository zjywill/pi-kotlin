package works.earendil.pi.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun `serializes response namespace and end turn diagnostics`() {
        val message =
            AssistantMessage(
                content =
                    listOf(
                        ToolCall(
                            id = "call|fc_1",
                            name = "search",
                            arguments = buildJsonObject { },
                            namespace = "web",
                        ),
                    ),
                api = "openai-responses",
                provider = "openai",
                model = "gpt",
                endTurn = true,
            )

        val encoded = Json.encodeToJsonElement(AssistantMessage.serializer(), message).jsonObject
        assertEquals(
            "web",
            encoded["content"]!!.jsonArray.single().jsonObject["namespace"]!!.jsonPrimitive.content,
        )
        assertEquals(true, encoded["endTurn"]!!.jsonPrimitive.boolean)
    }
}
