package works.earendil.pi.ai

import java.security.SecureRandom

class UuidV7Generator(
    private val now: () -> Long = System::currentTimeMillis,
    private val fillRandom: (ByteArray) -> Unit = SecureRandom()::nextBytes,
) {
    private var lastTimestamp = Long.MIN_VALUE
    private var sequence = 0L

    @Synchronized
    fun next(): String {
        val random = ByteArray(16)
        fillRandom(random)
        val timestamp = now()

        if (timestamp > lastTimestamp) {
            sequence =
                ((random[6].toLong() and 0xff) shl 24) or
                ((random[7].toLong() and 0xff) shl 16) or
                ((random[8].toLong() and 0xff) shl 8) or
                (random[9].toLong() and 0xff)
            lastTimestamp = timestamp
        } else {
            sequence = (sequence + 1) and 0xffff_ffffL
            if (sequence == 0L) {
                lastTimestamp++
            }
        }

        val bytes = ByteArray(16)
        bytes[0] = (lastTimestamp ushr 40).toByte()
        bytes[1] = (lastTimestamp ushr 32).toByte()
        bytes[2] = (lastTimestamp ushr 24).toByte()
        bytes[3] = (lastTimestamp ushr 16).toByte()
        bytes[4] = (lastTimestamp ushr 8).toByte()
        bytes[5] = lastTimestamp.toByte()
        bytes[6] = (0x70 or ((sequence ushr 28) and 0x0f).toInt()).toByte()
        bytes[7] = (sequence ushr 20).toByte()
        bytes[8] = (0x80 or ((sequence ushr 14) and 0x3f).toInt()).toByte()
        bytes[9] = (sequence ushr 6).toByte()
        bytes[10] = (((sequence and 0x3f) shl 2).toInt() or (random[10].toInt() and 0x03)).toByte()
        for (index in 11..15) {
            bytes[index] = random[index]
        }

        val hex = bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return buildString(36) {
            append(hex, 0, 8)
            append('-')
            append(hex, 8, 12)
            append('-')
            append(hex, 12, 16)
            append('-')
            append(hex, 16, 20)
            append('-')
            append(hex, 20, 32)
        }
    }
}

private val defaultUuidV7Generator = UuidV7Generator()

fun uuidv7(): String = defaultUuidV7Generator.next()
