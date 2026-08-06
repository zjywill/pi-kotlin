package works.earendil.pi.telemetry

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class TelemetryTest {
    @Test
    fun `noop context executes nested spans and preserves results`() =
        runTest {
            var outer: TelemetrySpan? = null
            val result =
                NOOP_TELEMETRY_CONTEXT.startSpan(SpanOptions("outer")) { span ->
                    outer = span
                    span.addEvent("ignored", mapOf("secret" to "value"))
                    span.setAttributes(mapOf("count" to 1))
                    span.setStatus(SpanStatus.Ok)
                    span.startSpan(SpanOptions("child")) { child ->
                        assertSame(span, child)
                        42
                    }
                }

            assertEquals(42, result)
            assertSame(NOOP_TELEMETRY_CONTEXT as TelemetrySpan, outer)
        }

    @Test
    fun `schema identity helper preserves the definition`() {
        val schema =
            TelemetrySchemaDefinition(
                version = 1,
                spans =
                    mapOf(
                        "operation" to
                            TelemetrySpanDefinition(
                                description = "Test",
                                parents = TelemetryParentDefinition.Any,
                                errorWhen = "The operation fails",
                            ),
                    ),
            )

        assertSame(schema, defineTelemetrySchema(schema))
    }
}
