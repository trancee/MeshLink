package ch.trancee.meshlink.wire

internal class ReadBuffer
internal constructor(private val bytes: ByteArray, private var position: Int = 0) {
    internal fun remaining(): Int {
        return bytes.size - position
    }

    internal fun readByte(): Byte {
        ensureAvailable(SINGLE_BYTE_SIZE)
        return bytes[position++]
    }

    internal fun readShortLittleEndian(): Short {
        ensureAvailable(SHORT_SIZE_BYTES)
        val value =
            (bytes[position].toInt() and BYTE_MASK) or
                ((bytes[position + 1].toInt() and BYTE_MASK) shl BITS_PER_BYTE)
        position += SHORT_SIZE_BYTES
        return value.toShort()
    }

    internal fun readIntLittleEndian(): Int {
        ensureAvailable(INT_SIZE_BYTES)
        val value =
            (bytes[position].toInt() and BYTE_MASK) or
                ((bytes[position + 1].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or
                ((bytes[position + 2].toInt() and BYTE_MASK) shl
                    (BITS_PER_BYTE * THIRD_BYTE_INDEX)) or
                ((bytes[position + FOURTH_BYTE_OFFSET].toInt() and BYTE_MASK) shl
                    (BITS_PER_BYTE * FOURTH_BYTE_INDEX))
        position += INT_SIZE_BYTES
        return value
    }

    internal fun readLongLittleEndian(): Long {
        ensureAvailable(LONG_SIZE_BYTES)
        var value = 0L
        repeat(LONG_SIZE_BYTES) { index ->
            value =
                value or
                    ((bytes[position + index].toLong() and BYTE_MASK.toLong()) shl
                        (index * BITS_PER_BYTE))
        }
        position += LONG_SIZE_BYTES
        return value
    }

    internal fun readBytes(count: Int): ByteArray {
        ensureAvailable(count)
        val slice = bytes.copyOfRange(position, position + count)
        position += count
        return slice
    }

    private fun ensureAvailable(expected: Int): Unit {
        check(remaining() >= expected) { "Insufficient bytes in ReadBuffer" }
    }
}

private const val BITS_PER_BYTE: Int = 8
private const val BYTE_MASK: Int = 0xFF
private const val SINGLE_BYTE_SIZE: Int = 1
private const val SHORT_SIZE_BYTES: Int = 2
private const val THIRD_BYTE_INDEX: Int = 2
private const val INT_SIZE_BYTES: Int = 4
private const val FOURTH_BYTE_INDEX: Int = 3
private const val FOURTH_BYTE_OFFSET: Int = 3
private const val LONG_SIZE_BYTES: Int = 8
