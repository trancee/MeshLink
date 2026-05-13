package ch.trancee.meshlink.wire

internal class ReadBuffer
internal constructor(private val bytes: ByteArray, private var position: Int = 0) {
    internal fun remaining(): Int {
        return bytes.size - position
    }

    internal fun readByte(): Byte {
        ensureAvailable(1)
        return bytes[position++]
    }

    internal fun readShortLittleEndian(): Short {
        ensureAvailable(2)
        val value =
            (bytes[position].toInt() and 0xFF) or ((bytes[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return value.toShort()
    }

    internal fun readIntLittleEndian(): Int {
        ensureAvailable(4)
        val value =
            (bytes[position].toInt() and 0xFF) or
                ((bytes[position + 1].toInt() and 0xFF) shl 8) or
                ((bytes[position + 2].toInt() and 0xFF) shl 16) or
                ((bytes[position + 3].toInt() and 0xFF) shl 24)
        position += 4
        return value
    }

    internal fun readLongLittleEndian(): Long {
        ensureAvailable(8)
        var value = 0L
        repeat(8) { index ->
            value = value or ((bytes[position + index].toLong() and 0xFF) shl (index * 8))
        }
        position += 8
        return value
    }

    internal fun readBytes(count: Int): ByteArray {
        ensureAvailable(count)
        val slice = bytes.copyOfRange(position, position + count)
        position += count
        return slice
    }

    private fun ensureAvailable(expected: Int): Unit {
        if (remaining() < expected) {
            throw IllegalStateException("Insufficient bytes in ReadBuffer")
        }
    }
}
