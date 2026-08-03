package works.earendil.pi.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FramingTest {
    @Test
    fun `prefixes payload with unsigned big endian length`() {
        assertContentEquals(
            byteArrayOf(0, 0, 0, 3, 0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte()),
            encodeFrame(byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte())),
        )
        assertContentEquals(byteArrayOf(0, 0, 0, 0), encodeFrame(byteArrayOf()))
    }

    @Test
    fun `decodes fragmented coalesced and empty frames`() {
        val wire =
            encodeFrame(byteArrayOf(1, 2, 3)) +
                encodeFrame(byteArrayOf()) +
                encodeFrame(byteArrayOf(4))
        val fragmented = FrameDecoder()
        val frames = wire.flatMap { fragmented.push(byteArrayOf(it)) }
        fragmented.end()
        assertFrames(listOf(byteArrayOf(1, 2, 3), byteArrayOf(), byteArrayOf(4)), frames)

        val coalesced = FrameDecoder()
        assertFrames(frames, coalesced.push(wire))
        coalesced.end()
    }

    @Test
    fun `rejects truncation oversize and terminal reuse`() {
        val truncated = FrameDecoder()
        truncated.push(byteArrayOf(0, 0, 0, 2, 1))
        assertFailsWith<FrameException> { truncated.end() }
        assertFailsWith<FrameException> { truncated.push(byteArrayOf(2)) }

        val oversized = FrameDecoder(maxFrameLength = 3)
        assertFailsWith<FrameException> { oversized.push(byteArrayOf(0, 0, 0, 4)) }

        val ended = FrameDecoder()
        ended.end()
        assertFailsWith<FrameException> { ended.end() }
        assertFailsWith<FrameException> { ended.push(byteArrayOf()) }
    }

    @Test
    fun `validates exactly one complete frame`() {
        assertCompleteFrame(byteArrayOf(0, 0, 0, 2, 1, 2), maxFrameLength = 2)
        assertFailsWith<FrameException> { assertCompleteFrame(byteArrayOf(0, 0, 0, 2, 1)) }
        assertFailsWith<FrameException> { assertCompleteFrame(byteArrayOf(0, 0, 0, 1, 1, 2)) }
        assertFailsWith<FrameException> {
            assertCompleteFrame(byteArrayOf(0, 0, 0, 3, 1, 2, 3), maxFrameLength = 2)
        }
    }
}

private fun assertFrames(
    expected: List<ByteArray>,
    actual: List<ByteArray>,
) {
    assertEquals(expected.size, actual.size)
    expected.zip(actual).forEach { (left, right) -> assertContentEquals(left, right) }
}
