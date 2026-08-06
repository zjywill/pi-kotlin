package works.earendil.pi.telemetry

typealias SpanAttributes = Map<String, Any?>

data class SpanOptions(
    val name: String,
    val attributes: SpanAttributes = emptyMap(),
)

sealed interface SpanStatus {
    data object Ok : SpanStatus

    data class Error(
        val error: ErrorInfo? = null,
    ) : SpanStatus
}

data class ErrorInfo(
    val name: String,
    val message: String,
)

interface TelemetryContext {
    suspend fun <T> startSpan(
        options: SpanOptions,
        callback: suspend (TelemetrySpan) -> T,
    ): T
}

interface TelemetrySpan : TelemetryContext {
    fun addEvent(
        name: String,
        attributes: SpanAttributes = emptyMap(),
    )

    fun setAttributes(attributes: SpanAttributes)

    fun setStatus(status: SpanStatus)
}

private object NoopTelemetrySpan : TelemetrySpan {
    override suspend fun <T> startSpan(
        options: SpanOptions,
        callback: suspend (TelemetrySpan) -> T,
    ): T = callback(this)

    override fun addEvent(
        name: String,
        attributes: SpanAttributes,
    ) = Unit

    override fun setAttributes(attributes: SpanAttributes) = Unit

    override fun setStatus(status: SpanStatus) = Unit
}

val NOOP_TELEMETRY_CONTEXT: TelemetryContext = NoopTelemetrySpan

enum class TelemetryAttributeType {
    STRING,
    NUMBER,
    BOOLEAN,
    STRING_ARRAY,
    NUMBER_ARRAY,
    BOOLEAN_ARRAY,
}

data class TelemetryAttributeDefinition(
    val type: TelemetryAttributeType,
    val description: String,
    val required: Boolean = false,
    val sensitive: Boolean = false,
    val cardinality: Cardinality? = null,
    val values: List<Any> = emptyList(),
    val examples: List<Any> = emptyList(),
) {
    enum class Cardinality {
        LOW,
        HIGH,
    }
}

sealed interface TelemetryParentDefinition {
    data object Any : TelemetryParentDefinition

    data object RootOrExternal : TelemetryParentDefinition

    data class Spans(
        val names: List<String>,
    ) : TelemetryParentDefinition
}

data class TelemetryEventDefinition(
    val description: String,
    val attributes: Map<String, TelemetryAttributeDefinition> = emptyMap(),
)

data class TelemetrySpanDefinition(
    val description: String,
    val parents: TelemetryParentDefinition,
    val startAttributes: Map<String, TelemetryAttributeDefinition> = emptyMap(),
    val endAttributes: Map<String, TelemetryAttributeDefinition> = emptyMap(),
    val events: Map<String, TelemetryEventDefinition> = emptyMap(),
    val errorWhen: String,
)

data class TelemetrySchemaDefinition(
    val version: Int,
    val spans: Map<String, TelemetrySpanDefinition>,
)

fun defineTelemetrySchema(schema: TelemetrySchemaDefinition): TelemetrySchemaDefinition = schema
