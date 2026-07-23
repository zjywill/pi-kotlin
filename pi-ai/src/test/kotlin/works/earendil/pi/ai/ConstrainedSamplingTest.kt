package works.earendil.pi.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConstrainedSamplingTest {
    @Test
    fun `json schema strict sampling honors provider support and requirement`() {
        val preferred = tool(JsonSchemaConstrainedSampling(ConstrainedSamplingStrict.PREFER))
        val required = tool(JsonSchemaConstrainedSampling(ConstrainedSamplingStrict.REQUIRE))

        assertEquals(true, resolveJsonSchemaStrictSampling(preferred, supportsStrictMode = true))
        assertNull(resolveJsonSchemaStrictSampling(preferred, supportsStrictMode = false))
        val error =
            assertFailsWith<IllegalStateException> {
                resolveJsonSchemaStrictSampling(required, supportsStrictMode = false)
            }
        assertTrue(error.message.orEmpty().contains("strict tools are unsupported"))
    }

    @Test
    fun `grammar sampling prefers lark and validates the input schema`() {
        val grammar =
            resolveGrammarConstrainedSampling(
                tool(
                    GrammarConstrainedSamplingConfig(
                        GrammarVariants(
                            openAILark = "start: /.+/",
                            openAIRegex = ".+",
                        ),
                    ),
                ),
                supportsOpenAIGrammarTools = true,
            )

        assertEquals("lark", grammar?.format)
        assertEquals("start: /.+/", grammar?.definition)
        assertEquals("input", grammar?.inputProperty)
    }

    @Test
    fun `grammar sampling falls back when unsupported and rejects invalid variants`() {
        val unsupported =
            tool(
                GrammarConstrainedSamplingConfig(
                    GrammarVariants(openAIRegex = ".+"),
                ),
            )
        assertNull(resolveGrammarConstrainedSampling(unsupported, supportsOpenAIGrammarTools = false))

        val error =
            assertFailsWith<IllegalStateException> {
                resolveGrammarConstrainedSampling(
                    unsupported.copy(
                        parameters =
                            buildJsonObject {
                                put("type", "object")
                                put("properties", JsonObject(emptyMap()))
                                put("required", JsonArray(emptyList()))
                            },
                    ),
                    supportsOpenAIGrammarTools = true,
                )
            }
        assertTrue(error.message.orEmpty().contains("exactly one required string property"))
    }

    @Test
    fun `grammar stream deltas form monotonic json`() {
        val buffer = GrammarToolInputJsonBuffer()

        assertEquals("""{"input":"hel""", appendGrammarToolInputJsonDelta(buffer, "input", "hel", false))
        assertEquals("lo", appendGrammarToolInputJsonDelta(buffer, "input", "hello", false))
        assertEquals("\"}", appendGrammarToolInputJsonDelta(buffer, "input", "hello", true))
        assertNull(appendGrammarToolInputJsonDelta(buffer, "input", "hello", true))
        assertFailsWith<IllegalArgumentException> {
            appendGrammarToolInputJsonDelta(
                GrammarToolInputJsonBuffer(input = "hello", started = true),
                "input",
                "help",
                false,
            )
        }
    }

    @Test
    fun `grammar input requires a string property`() {
        assertEquals(
            "value",
            getGrammarToolInput(
                "grammar",
                buildJsonObject { put("input", "value") },
                "input",
            ),
        )
        assertFailsWith<IllegalStateException> {
            getGrammarToolInput(
                "grammar",
                buildJsonObject { put("input", 1) },
                "input",
            )
        }
    }

    private fun tool(config: ConstrainedSamplingConfig): ToolDefinition =
        ToolDefinition(
            name = "grammar",
            description = "Grammar",
            parameters =
                buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "input",
                                buildJsonObject { put("type", "string") },
                            )
                        },
                    )
                    put("required", JsonArray(listOf(JsonPrimitive("input"))))
                },
            constrainedSampling = config,
        )
}
