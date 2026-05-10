package ch.trancee.meshlink.wire

internal enum class WireEnvelopeType {
    MESSAGE,
    ROUTE_UPDATE,
    TRANSFER,
    DIAGNOSTIC,
}

internal class WireEnvelope internal constructor(
    internal val version: UByte,
    internal val type: WireEnvelopeType,
    payload: ByteArray,
) {
    internal val payload: ByteArray = payload.copyOf()

    internal fun encode(): ByteArray {
        val buffer = WriteBuffer()
        buffer.writeByte(version.toByte())
        buffer.writeByte(type.ordinal.toByte())
        buffer.writeIntLittleEndian(payload.size)
        buffer.writeBytes(payload)
        return buffer.toByteArray()
    }

    internal companion object {
        internal fun decode(bytes: ByteArray): WireEnvelope {
            val buffer = ReadBuffer(bytes)
            val version = buffer.readByte().toUByte()
            val typeOrdinal = buffer.readByte().toInt()
            if (typeOrdinal !in WireEnvelopeType.entries.indices) {
                throw IllegalStateException("Unknown WireEnvelopeType ordinal: $typeOrdinal")
            }
            val payloadSize = buffer.readIntLittleEndian()
            if (payloadSize < 0) {
                throw IllegalStateException("Negative payload size in WireEnvelope")
            }
            return WireEnvelope(
                version = version,
                type = WireEnvelopeType.entries[typeOrdinal],
                payload = buffer.readBytes(payloadSize),
            )
        }
    }
}
