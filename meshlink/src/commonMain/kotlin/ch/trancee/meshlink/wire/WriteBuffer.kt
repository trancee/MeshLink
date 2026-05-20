package ch.trancee.meshlink.wire

internal class WriteBuffer(initialCapacity: Int = DEFAULT_INITIAL_CAPACITY) {
    private var bytes: ByteArray =
        ByteArray(initialCapacity.coerceAtLeast(DEFAULT_INITIAL_CAPACITY))
    private var size: Int = 0

    internal fun writeByte(value: Byte): Unit {
        ensureCapacity(additionalBytes = 1)
        bytes[size] = value
        size += 1
    }

    internal fun writeShortLittleEndian(value: Short): Unit {
        val intValue = value.toInt()
        writeByte((intValue and 0xFF).toByte())
        writeByte(((intValue shr 8) and 0xFF).toByte())
    }

    internal fun writeIntLittleEndian(value: Int): Unit {
        writeByte((value and 0xFF).toByte())
        writeByte(((value shr 8) and 0xFF).toByte())
        writeByte(((value shr 16) and 0xFF).toByte())
        writeByte(((value shr 24) and 0xFF).toByte())
    }

    internal fun writeLongLittleEndian(value: Long): Unit {
        repeat(8) { index -> writeByte(((value shr (index * 8)) and 0xFF).toByte()) }
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
