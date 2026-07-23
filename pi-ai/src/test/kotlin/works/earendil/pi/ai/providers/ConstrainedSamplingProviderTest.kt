package works.earendil.pi.ai.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import works.earendil.pi.ai.ConstrainedSamplingStrict
import works.earendil.pi.ai.Context
import works.earendil.pi.ai.GrammarConstrainedSamplingConfig
import works.earendil.pi.ai.GrammarVariants
import works.earendil.pi.ai.JsonSchemaConstrainedSampling
import works.earendil.pi.ai.StreamOptions
import works.earendil.pi.ai.ToolDefinition
import works.earendil.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ConstrainedSamplingProviderTest {
    @Test
    fun `openai responses emits strict tools only when supported`() {
        val context =
            Context(
                messages = mutableListOf(UserMessage("hello")),
                tools = listOf(strictTool(ConstrainedSamplingStrict.PREFER)),
            )
        val unsupported =
            buildOpenAIResponsesRequestBody(
                fixtureModel("openai-responses"),
                context,
                StreamOptions(),
            )
        val unsupportedTool = unsupported.getValue("tools").jsonArray.single().jsonObject
        assertFalse("strict" in unsupportedTool)

        val supported =
            buildOpenAIResponsesRequestBody(
                fixtureModel("openai-responses").copy(
                    compat = buildJsonObject { put("supportsStrictMode", true) },
                ),
                context,
                StreamOptions(),
            )
        assertEquals(
            true,
            supported
                .getValue("tools")
                .jsonArray
                .single()
                .jsonObject
                .getValue("strict")
                .jsonPrimitive
                .content
                .toBoolean(),
        )
    }

    @Test
    fun `strict requirement fails on unsupported providers`() {
        val context =
            Context(
                messages = mutableListOf(UserMessage("hello")),
                tools = listOf(strictTool(ConstrainedSamplingStrict.REQUIRE)),
            )

        assertFailsWith<IllegalStateException> {
            buildOpenAIResponsesRequestBody(
                fixtureModel("openai-responses"),
                context,
                StreamOptions(),
            )
        }
    }

    @Test
    fun `openai chat and responses emit grammar tools`() {
        val context =
            Context(
                messages = mutableListOf(UserMessage("hello")),
                tools = listOf(grammarTool()),
            )
        val compat =
            buildJsonObject {
                put("supportsStrictMode", true)
                put("supportsOpenAIGrammarTools", true)
            }
        val chat =
            buildOpenAIChatRequestBody(
                fixtureModel("openai-completions").copy(compat = compat),
                context,
                StreamOptions(),
            )
        val chatTool = chat.getValue("tools").jsonArray.single().jsonObject
        assertEquals("custom", chatTool.getValue("type").jsonPrimitive.content)
        assertEquals(
            "regex",
            chatTool
                .getValue("custom")
                .jsonObject
                .getValue("format")
                .jsonObject
                .getValue("grammar")
                .jsonObject
                .getValue("syntax")
                .jsonPrimitive
                .content,
        )

        val responses =
            buildOpenAIResponsesRequestBody(
                fixtureModel("openai-responses").copy(compat = compat),
                context,
                StreamOptions(),
            )
        val responsesTool = responses.getValue("tools").jsonArray.single().jsonObject
        assertEquals("custom", responsesTool.getValue("type").jsonPrimitive.content)
        assertEquals(
            "regex",
            responsesTool
                .getValue("format")
                .jsonObject
                .getValue("syntax")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `google strict sampling selects validated function calling`() {
        val body =
            buildGoogleRequestBody(
                fixtureModel("google-generative-ai", provider = "google").copy(
                    id = "gemini-3.1-pro-preview",
                ),
                Context(
                    messages = mutableListOf(UserMessage("hello")),
                    tools = listOf(strictTool(ConstrainedSamplingStrict.PREFER)),
                ),
                StreamOptions(),
            )

        assertEquals(
            "VALIDATED",
            body
                .getValue("toolConfig")
                .jsonObject
                .getValue("functionCallingConfig")
                .jsonObject
                .getValue("mode")
                .jsonPrimitive
                .content,
        )
    }

    private fun strictTool(strict: ConstrainedSamplingStrict): ToolDefinition =
        ToolDefinition(
            name = "echo",
            description = "Echo",
            parameters = toolParameters(),
            constrainedSampling = JsonSchemaConstrainedSampling(strict),
        )

    private fun grammarTool(): ToolDefinition =
        ToolDefinition(
            name = "grammar",
            description = "Grammar",
            parameters = toolParameters(),
            constrainedSampling =
                GrammarConstrainedSamplingConfig(
                    GrammarVariants(openAIRegex = "[a-z]+"),
                ),
        )

    private fun toolParameters() =
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
        }

    private fun fixtureModel(
        api: String,
        provider: String = "fixture",
    ) = model(
        id = "fixture",
        api = api,
        provider = provider,
        baseUrl = "https://fixture.invalid",
    )
}
