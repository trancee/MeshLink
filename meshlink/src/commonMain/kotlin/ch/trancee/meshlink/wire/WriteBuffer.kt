package ch.trancee.meshlink.wire

internal class WriteBuffer {
    private val bytes: MutableList<Byte> = mutableListOf()

    internal fun writeByte(value: Byte): Unit {
        bytes += value
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

    internal fun writeBytes(value: ByteArray): Unit {
        value.forEach(::writeByte)
    }

    internal fun toByteArray(): ByteArray {
        return bytes.toByteArray()
    }
}
