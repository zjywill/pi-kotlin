package works.earendil.pi.protocol

import java.io.ByteArrayOutputStream

const val DEFAULT_MAX_FRAME_LENGTH: Int = 16 * 1024 * 1024
private const val FRAME_HEADER_LENGTH = 4

class FrameException(
    message: String,
) : IllegalArgumentException(message)

fun encodeFrame(payload: ByteArray): ByteArray {
    val length = payload.size
    return ByteArray(FRAME_HEADER_LENGTH + length).also { frame ->
        frame[0] = (length ushr 24).toByte()
        frame[1] = (length ushr 16).toByte()
        frame[2] = (length ushr 8).toByte()
        frame[3] = length.toByte()
        payload.copyInto(frame, FRAME_HEADER_LENGTH)
    }
}

fun assertCompleteFrame(
    frame: ByteArray,
    maxFrameLength: Int = DEFAULT_MAX_FRAME_LENGTH,
) {
    requireValidFrameLimit(maxFrameLength)
    if (frame.size < FRAME_HEADER_LENGTH) {
        throw FrameException("Frame does not contain a complete length prefix")
    }
    val length = decodeFrameLength(frame, 0)
    if (length > maxFrameLength) {
        throw FrameException("Frame length $length exceeds configured limit of $maxFrameLength")
    }
    if (frame.size != FRAME_HEADER_LENGTH + length) {
        throw FrameException("Frame must contain exactly one complete payload")
    }
}

class FrameDecoder(
    private val maxFrameLength: Int = DEFAULT_MAX_FRAME_LENGTH,
) {
    private enum class State {
        OPEN,
        ENDED,
        FAILED,
    }

    private val header = ByteArray(FRAME_HEADER_LENGTH)
    private var headerLength = 0
    private var expectedPayloadLength: Int? = null
    private var payload = ByteArrayOutputStream()
    private var state = State.OPEN

    init {
        requireValidFrameLimit(maxFrameLength)
    }

    fun push(chunk: ByteArray): List<ByteArray> {
        when (state) {
            State.ENDED -> throw FrameException("Frame decoder has ended")
            State.FAILED -> throw FrameException("Frame decoder has failed")
            State.OPEN -> Unit
        }
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        try {
            while (offset < chunk.size) {
                if (expectedPayloadLength == null) {
                    val copied = minOf(FRAME_HEADER_LENGTH - headerLength, chunk.size - offset)
                    chunk.copyInto(header, headerLength, offset, offset + copied)
                    headerLength += copied
                    offset += copied
                    if (headerLength < FRAME_HEADER_LENGTH) {
                        continue
                    }
                    val length = decodeFrameLength(header, 0)
                    headerLength = 0
                    if (length > maxFrameLength) {
                        fail("Frame length $length exceeds configured limit of $maxFrameLength")
                    }
                    if (length == 0) {
                        frames += ByteArray(0)
                        continue
                    }
                    expectedPayloadLength = length
                    payload = ByteArrayOutputStream(length.coerceAtMost(64 * 1024))
                }

                val expected = requireNotNull(expectedPayloadLength)
                val copied = minOf(expected - payload.size(), chunk.size - offset)
                payload.write(chunk, offset, copied)
                offset += copied
                if (payload.size() == expected) {
                    frames += payload.toByteArray()
                    payload = ByteArrayOutputStream()
                    expectedPayloadLength = null
                }
            }
            return frames
        } catch (error: Throwable) {
            if (state != State.FAILED) {
                state = State.FAILED
            }
            throw error
        }
    }

    fun end() {
        when (state) {
            State.ENDED -> throw FrameException("Frame decoder has ended")
            State.FAILED -> throw FrameException("Frame decoder has failed")
            State.OPEN -> Unit
        }
        if (headerLength != 0 || expectedPayloadLength != null) {
            fail("Truncated frame at end of stream")
        }
        state = State.ENDED
    }

    private fun fail(message: String): Nothing {
        state = State.FAILED
        headerLength = 0
        expectedPayloadLength = null
        payload = ByteArrayOutputStream()
        throw FrameException(message)
    }
}

private fun decodeFrameLength(
    bytes: ByteArray,
    offset: Int,
): Int {
    val value =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)
    if (value > Int.MAX_VALUE) {
        throw FrameException("Frame length $value exceeds JVM byte-array limits")
    }
    return value.toInt()
}

private fun requireValidFrameLimit(maxFrameLength: Int) {
    require(maxFrameLength >= 0) {
        "maxFrameLength must be a non-negative integer"
    }
}
