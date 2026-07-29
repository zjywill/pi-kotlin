package works.earendil.pi.ai

import java.io.InputStream

data class ProviderHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val timeoutMs: Long? = null,
)

/**
 * A streaming provider response. The caller closes [body] after consuming it.
 */
class ProviderHttpTransportResponse(
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: InputStream = InputStream.nullInputStream(),
) : AutoCloseable {
    override fun close() {
        body.close()
    }
}

/**
 * Per-request HTTP injection point. WebSocket transports and provider SDK transports are unaffected.
 */
fun interface ProviderHttpTransport {
    suspend fun fetch(request: ProviderHttpRequest): ProviderHttpTransportResponse
}
