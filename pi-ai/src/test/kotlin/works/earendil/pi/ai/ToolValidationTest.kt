package works.earendil.pi.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolValidationTest {
    @Test
    fun `coerces primitive values like the TypeScript validator`() {
        val tool =
            ToolDefinition(
                name = "echo",
                description = "Echo",
                parameters =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put("count", buildJsonObject { put("type", "number") })
                            },
                        )
                        put("required", JsonArray(listOf(JsonPrimitive("count"))))
                    },
            )
        val call =
            ToolCall(
                id = "tool-1",
                name = "echo",
                arguments = buildJsonObject { put("count", "42") },
            )

        val result = validateToolArguments(tool, call)

        assertEquals(42.0, result.getValue("count").jsonPrimitive.double)
    }

    @Test
    fun `rejects missing required values`() {
        val tool =
            ToolDefinition(
                name = "echo",
                description = "Echo",
                parameters =
                    buildJsonObject {
                        put("type", "object")
                        put("required", JsonArray(listOf(JsonPrimitive("value"))))
                    },
            )

        assertFailsWith<IllegalStateException> {
            validateToolArguments(
                tool,
                ToolCall("tool-1", "echo", buildJsonObject {}),
            )
        }
    }
}
