package works.earendil.pi.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CborTest {
    @Test
    fun `matches RFC vectors`() {
        val vectors =
            listOf(
                null to "f6",
                false to "f4",
                true to "f5",
                0L to "00",
                24L to "1818",
                1_000_000L to "1a000f4240",
                -1L to "20",
                -1_000L to "3903e7",
                1.1 to "fb3ff199999999999a",
                "" to "60",
                "IETF" to "6449455446",
                "水" to "63e6b0b4",
                listOf(1L, 2L, 3L) to "83010203",
                linkedMapOf("a" to 1L, "b" to listOf(2L, 3L)) to "a26161016162820203",
            )
        vectors.forEach { (value, expectedHex) ->
            val encoded = encodeCbor(value)
            assertEquals(expectedHex, encoded.toHex())
            assertDeepEquals(value, decodeCbor(encoded))
        }
    }

    @Test
    fun `preserves byte strings and negative zero`() {
        assertContentEquals(byteArrayOf(1, 2, 3), decodeCbor(encodeCbor(byteArrayOf(1, 2, 3))) as ByteArray)
        val negativeZero = decodeCbor(hex("fb8000000000000000")) as Double
        assertTrue(negativeZero == 0.0 && 1.0 / negativeZero == Double.NEGATIVE_INFINITY)
    }

    @Test
    fun `rejects unsupported values cycles and malformed input`() {
        assertFailsWith<CborException> { encodeCbor(Double.NaN) }
        assertFailsWith<CborException> { encodeCbor("\ud800") }
        val cyclic = mutableListOf<Any?>()
        cyclic.add(cyclic)
        assertFailsWith<CborException> { encodeCbor(cyclic) }
        listOf(
            "",
            "18",
            "1c",
            "5f",
            "c000",
            "f7",
            "f93c00",
            "fa3f800000",
            "fb7ff0000000000000",
            "0000",
            "a10102",
            "a2616101616102",
            "61ff",
            "1b0020000000000000",
        ).forEach { wire ->
            assertFailsWith<CborException>("wire=$wire") { decodeCbor(hex(wire)) }
        }
    }

    @Test
    fun `enforces configured limits`() {
        assertFailsWith<CborException> {
            encodeCbor(listOf(1, 2, 3), CborOptions(maxContainerLength = 2))
        }
        assertFailsWith<CborException> {
            decodeCbor(hex("83010203"), CborOptions(maxContainerLength = 2))
        }
        assertFailsWith<CborException> {
            encodeCbor("abc", CborOptions(maxByteLength = 2))
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun hex(value: String): ByteArray =
    ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

private fun assertDeepEquals(
    expected: Any?,
    actual: Any?,
) {
    when {
        expected is ByteArray && actual is ByteArray -> assertContentEquals(expected, actual)
        expected is Number && actual is Number -> assertEquals(expected.toDouble(), actual.toDouble())
        expected is List<*> && actual is List<*> -> {
            assertEquals(expected.size, actual.size)
            expected.zip(actual).forEach { (left, right) -> assertDeepEquals(left, right) }
        }

        expected is Map<*, *> && actual is Map<*, *> -> {
            assertEquals(expected.keys, actual.keys)
            expected.forEach { (key, value) -> assertDeepEquals(value, actual[key]) }
        }

        else -> assertEquals(expected, actual)
    }
}
