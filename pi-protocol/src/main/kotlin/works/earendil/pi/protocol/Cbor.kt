package works.earendil.pi.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs

const val DEFAULT_MAX_CBOR_BYTE_LENGTH: Int = 16 * 1024 * 1024
const val DEFAULT_MAX_CBOR_CONTAINER_LENGTH: Int = 1_000_000
const val DEFAULT_MAX_CBOR_DEPTH: Int = 64

data class CborOptions(
    val maxByteLength: Int = DEFAULT_MAX_CBOR_BYTE_LENGTH,
    val maxContainerLength: Int = DEFAULT_MAX_CBOR_CONTAINER_LENGTH,
    val maxDepth: Int = DEFAULT_MAX_CBOR_DEPTH,
) {
    init {
        require(maxByteLength >= 0) { "maxByteLength must be non-negative" }
        require(maxContainerLength >= 0) { "maxContainerLength must be non-negative" }
        require(maxDepth >= 0) { "maxDepth must be non-negative" }
    }
}

class CborException(
    message: String,
) : IllegalArgumentException(message)

fun encodeCbor(
    value: Any?,
    options: CborOptions = CborOptions(),
): ByteArray {
    val output = ByteArrayOutputStream()
    val ancestors = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    CborEncoder(output, options, ancestors).encode(value, 0)
    val bytes = output.toByteArray()
    if (bytes.size > options.maxByteLength) {
        throw CborException("Encoded CBOR exceeds configured byte limit")
    }
    return bytes
}

fun decodeCbor(
    bytes: ByteArray,
    options: CborOptions = CborOptions(),
): Any? {
    if (bytes.size > options.maxByteLength) {
        throw CborException("CBOR input exceeds configured byte limit")
    }
    val decoder = CborDecoder(bytes, options)
    val value = decoder.decode(0)
    if (!decoder.exhausted()) {
        throw CborException("CBOR input contains trailing data")
    }
    return value
}

private class CborEncoder(
    private val output: ByteArrayOutputStream,
    private val options: CborOptions,
    private val ancestors: MutableSet<Any>,
) {
    fun encode(
        value: Any?,
        depth: Int,
    ) {
        if (depth > options.maxDepth) {
            throw CborException("CBOR value exceeds configured depth limit")
        }
        when (value) {
            null -> output.write(0xf6)
            is Boolean -> output.write(if (value) 0xf5 else 0xf4)
            is Byte,
            is Short,
            is Int,
            is Long,
            -> encodeInteger((value as Number).toLong())

            is Float -> encodeFloating(value.toDouble())
            is Double -> encodeFloating(value)
            is String -> encodeString(value)
            is ByteArray -> encodeBytes(value)
            is List<*> -> encodeArray(value, depth)
            is Array<*> -> encodeArray(value.asList(), depth)
            is Map<*, *> -> encodeMap(value, depth)
            else -> throw CborException("Unsupported CBOR value: ${value::class.qualifiedName}")
        }
    }

    private fun encodeFloating(value: Double) {
        if (!value.isFinite()) {
            throw CborException("CBOR numbers must be finite")
        }
        val negativeZero = value == 0.0 && 1.0 / value == Double.NEGATIVE_INFINITY
        if (!negativeZero && value % 1.0 == 0.0 && abs(value) <= MAX_SAFE_INTEGER.toDouble()) {
            encodeInteger(value.toLong())
            return
        }
        output.write(0xfb)
        writeLong(java.lang.Double.doubleToRawLongBits(value))
    }

    private fun encodeInteger(value: Long) {
        if (value < -MAX_SAFE_INTEGER || value > MAX_SAFE_INTEGER) {
            throw CborException("CBOR integer exceeds the JavaScript safe integer range")
        }
        if (value >= 0) {
            writeTypeAndLength(0, value)
        } else {
            writeTypeAndLength(1, -1L - value)
        }
    }

    private fun encodeString(value: String) {
        val encoder =
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val bytes =
            try {
                val buffer = encoder.encode(CharBuffer.wrap(value))
                ByteArray(buffer.remaining()).also(buffer::get)
            } catch (_: Exception) {
                throw CborException("CBOR text contains invalid Unicode")
            }
        if (bytes.size > options.maxByteLength) {
            throw CborException("CBOR text exceeds configured byte limit")
        }
        writeTypeAndLength(3, bytes.size.toLong())
        output.write(bytes)
    }

    private fun encodeBytes(value: ByteArray) {
        if (value.size > options.maxByteLength) {
            throw CborException("CBOR byte string exceeds configured byte limit")
        }
        writeTypeAndLength(2, value.size.toLong())
        output.write(value)
    }

    private fun encodeArray(
        value: List<*>,
        depth: Int,
    ) {
        if (value.size > options.maxContainerLength) {
            throw CborException("CBOR array exceeds configured container limit")
        }
        withAncestor(value) {
            writeTypeAndLength(4, value.size.toLong())
            value.forEach { entry -> encode(entry, depth + 1) }
        }
    }

    private fun encodeMap(
        value: Map<*, *>,
        depth: Int,
    ) {
        if (value.size > options.maxContainerLength) {
            throw CborException("CBOR map exceeds configured container limit")
        }
        withAncestor(value) {
            writeTypeAndLength(5, value.size.toLong())
            value.forEach { (key, entry) ->
                if (key !is String) {
                    throw CborException("CBOR map keys must be strings")
                }
                encodeString(key)
                encode(entry, depth + 1)
            }
        }
    }

    private inline fun withAncestor(
        value: Any,
        block: () -> Unit,
    ) {
        if (!ancestors.add(value)) {
            throw CborException("CBOR values must not contain cycles")
        }
        try {
            block()
        } finally {
            ancestors.remove(value)
        }
    }

    private fun writeTypeAndLength(
        majorType: Int,
        value: Long,
    ) {
        when {
            value < 24 -> output.write((majorType shl 5) or value.toInt())
            value <= 0xff -> {
                output.write((majorType shl 5) or 24)
                output.write(value.toInt())
            }

            value <= 0xffff -> {
                output.write((majorType shl 5) or 25)
                output.write((value ushr 8).toInt())
                output.write(value.toInt())
            }

            value <= 0xffff_ffffL -> {
                output.write((majorType shl 5) or 26)
                writeInt(value.toInt())
            }

            else -> {
                output.write((majorType shl 5) or 27)
                writeLong(value)
            }
        }
    }

    private fun writeInt(value: Int) {
        output.write(value ushr 24)
        output.write(value ushr 16)
        output.write(value ushr 8)
        output.write(value)
    }

    private fun writeLong(value: Long) {
        repeat(Long.SIZE_BYTES) { index ->
            output.write((value ushr ((Long.SIZE_BYTES - index - 1) * 8)).toInt())
        }
    }
}

private class CborDecoder(
    private val bytes: ByteArray,
    private val options: CborOptions,
) {
    private var offset = 0

    fun exhausted(): Boolean = offset == bytes.size

    fun decode(depth: Int): Any? {
        if (depth > options.maxDepth) {
            throw CborException("CBOR value exceeds configured depth limit")
        }
        val initial = readUnsignedByte()
        val majorType = initial ushr 5
        val additional = initial and 0x1f
        return when (majorType) {
            0 -> decodePositive(readLength(additional))
            1 -> decodeNegative(readLength(additional))
            2 -> readByteString(readLength(additional))
            3 -> readText(readLength(additional))
            4 -> readArray(readLength(additional), depth)
            5 -> readMap(readLength(additional), depth)
            6 -> throw CborException("CBOR tags are not supported")
            7 -> decodeSimple(additional)
            else -> error("Unreachable CBOR major type")
        }
    }

    private fun decodePositive(value: Long): Long {
        ensureSafeInteger(value)
        return value
    }

    private fun decodeNegative(value: Long): Long {
        val decoded = -1L - value
        ensureSafeInteger(decoded)
        return decoded
    }

    private fun readByteString(length: Long): ByteArray {
        val size = checkedLength(length, options.maxByteLength, "byte string")
        return readBytes(size)
    }

    private fun readText(length: Long): String {
        val size = checkedLength(length, options.maxByteLength, "text string")
        val data = readBytes(size)
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(data)).toString()
        } catch (_: Exception) {
            throw CborException("CBOR text contains invalid UTF-8")
        }
    }

    private fun readArray(
        length: Long,
        depth: Int,
    ): List<Any?> {
        val size = checkedLength(length, options.maxContainerLength, "array")
        return List(size) { decode(depth + 1) }
    }

    private fun readMap(
        length: Long,
        depth: Int,
    ): Map<String, Any?> {
        val size = checkedLength(length, options.maxContainerLength, "map")
        val result = linkedMapOf<String, Any?>()
        repeat(size) {
            val key = decode(depth + 1)
            if (key !is String) {
                throw CborException("CBOR map keys must be strings")
            }
            if (result.containsKey(key)) {
                throw CborException("CBOR map contains duplicate key: $key")
            }
            result[key] = decode(depth + 1)
        }
        return result
    }

    private fun decodeSimple(additional: Int): Any? =
        when (additional) {
            20 -> false
            21 -> true
            22 -> null
            25 -> throw CborException("CBOR float16 values are not supported")
            26 -> throw CborException("CBOR float32 values are not supported")
            27 -> {
                val bits = readLong()
                val value = java.lang.Double.longBitsToDouble(bits)
                if (!value.isFinite()) {
                    throw CborException("CBOR numbers must be finite")
                }
                if (value % 1.0 == 0.0 && abs(value) > MAX_SAFE_INTEGER.toDouble()) {
                    throw CborException("CBOR integer exceeds the JavaScript safe integer range")
                }
                value
            }

            31 -> throw CborException("Indefinite-length CBOR values are not supported")
            else -> throw CborException("Unsupported CBOR simple value")
        }

    private fun readLength(additional: Int): Long =
        when {
            additional < 24 -> additional.toLong()
            additional == 24 -> readUnsignedByte().toLong()
            additional == 25 -> readUnsignedShort().toLong()
            additional == 26 -> readUnsignedInt()
            additional == 27 -> {
                val value = readLong()
                if (value < 0) {
                    throw CborException("CBOR length exceeds supported range")
                }
                value
            }

            additional == 31 -> throw CborException("Indefinite-length CBOR values are not supported")
            else -> throw CborException("Reserved CBOR additional information")
        }

    private fun checkedLength(
        length: Long,
        limit: Int,
        label: String,
    ): Int {
        if (length > limit) {
            throw CborException("CBOR $label exceeds configured limit")
        }
        if (length > Int.MAX_VALUE) {
            throw CborException("CBOR $label exceeds supported length")
        }
        return length.toInt()
    }

    private fun ensureSafeInteger(value: Long) {
        if (value < -MAX_SAFE_INTEGER || value > MAX_SAFE_INTEGER) {
            throw CborException("CBOR integer exceeds the JavaScript safe integer range")
        }
    }

    private fun readUnsignedByte(): Int {
        if (offset >= bytes.size) {
            throw CborException("Truncated CBOR input")
        }
        return bytes[offset++].toInt() and 0xff
    }

    private fun readUnsignedShort(): Int =
        (readUnsignedByte() shl 8) or readUnsignedByte()

    private fun readUnsignedInt(): Long =
        (readUnsignedByte().toLong() shl 24) or
            (readUnsignedByte().toLong() shl 16) or
            (readUnsignedByte().toLong() shl 8) or
            readUnsignedByte().toLong()

    private fun readLong(): Long {
        val data = readBytes(Long.SIZE_BYTES)
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).long
    }

    private fun readBytes(length: Int): ByteArray {
        if (length < 0 || bytes.size - offset < length) {
            throw CborException("Truncated CBOR input")
        }
        return bytes.copyOfRange(offset, offset + length).also { offset += length }
    }
}

private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
