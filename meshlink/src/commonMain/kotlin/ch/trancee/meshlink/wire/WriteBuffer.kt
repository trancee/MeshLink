package ch.trancee.meshlink.wire

internal class WriteBuffer(initialCapacity: Int = DEFAULT_INITIAL_CAPACITY) {
    private var bytes: ByteArray =
        ByteArray(initialCapacity.coerceAtLeast(DEFAULT_INITIAL_CAPACITY))
    private var size: Int = 0

    internal fun writeByte(value: Byte): Unit {
        ensureCapacity(additionalBytes = SINGLE_BYTE_SIZE)
        bytes[size] = value
        size += SINGLE_BYTE_SIZE
    }

    internal fun writeShortLittleEndian(value: Short): Unit {
        val intValue = value.toInt()
        writeByte((intValue and BYTE_MASK).toByte())
        writeByte(((intValue shr BITS_PER_BYTE) and BYTE_MASK).toByte())
    }

    internal fun writeIntLittleEndian(value: Int): Unit {
        writeByte((value and BYTE_MASK).toByte())
        writeByte(((value shr BITS_PER_BYTE) and BYTE_MASK).toByte())
        writeByte(((value shr (BITS_PER_BYTE * THIRD_BYTE_INDEX)) and BYTE_MASK).toByte())
        writeByte(((value shr (BITS_PER_BYTE * FOURTH_BYTE_INDEX)) and BYTE_MASK).toByte())
    }

    internal fun writeLongLittleEndian(value: Long): Unit {
        repeat(LONG_SIZE_BYTES) { index ->
            writeByte(((value shr (index * BITS_PER_BYTE)) and BYTE_MASK.toLong()).toByte())
        }
    }

    internal fun writeBytes(value: ByteArray): Unit {
        ensureCapacity(additionalBytes = value.size)
        value.copyInto(bytes, destinationOffset = size)
        size += value.size
    }

    internal fun toByteArray(): ByteArray {
        return bytes.copyOf(size)
    }

    private fun ensureCapacity(additionalBytes: Int): Unit {
        val requiredCapacity = size + additionalBytes
        if (requiredCapacity <= bytes.size) {
            return
        }

        var newCapacity = bytes.size
        while (newCapacity < requiredCapacity) {
            newCapacity *= GROWTH_FACTOR
        }
        bytes = bytes.copyOf(newCapacity)
    }

    private companion object {
        private const val DEFAULT_INITIAL_CAPACITY: Int = 32
        private const val GROWTH_FACTOR: Int = 2
    }
}

private const val BITS_PER_BYTE: Int = 8
private const val BYTE_MASK: Int = 0xFF
private const val SINGLE_BYTE_SIZE: Int = 1
private const val THIRD_BYTE_INDEX: Int = 2
private const val FOURTH_BYTE_INDEX: Int = 3
private const val LONG_SIZE_BYTES: Int = 8
