package works.earendil.pi.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UuidV7Test {
    @Test
    fun `uses RFC 9562 layout and preserves monotonic order`() {
        val randomValues =
            ArrayDeque(
                listOf(
                    byteArrayOf(
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0xff.toByte(),
                        0xff.toByte(),
                        0xff.toByte(),
                        0xfe.toByte(),
                        0x01,
                        0x11,
                        0x22,
                        0x33,
                        0x44,
                        0x55,
                    ),
                    ByteArray(16),
                    ByteArray(16),
                ),
            )
        val generator =
            UuidV7Generator(
                now = { 0x0123456789ab },
                fillRandom = { target -> randomValues.removeFirst().copyInto(target) },
            )

        val first = generator.next()
        val second = generator.next()
        val third = generator.next()

        assertEquals("01234567-89ab-7fff-bfff-f91122334455", first)
        assertEquals("01234567-89ab-7fff-bfff-fc0000000000", second)
        assertEquals("01234567-89ac-7000-8000-000000000000", third)
        assertTrue(first < second)
        assertTrue(second < third)
    }
}
