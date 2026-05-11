package ch.trancee.meshlink.wire

internal enum class WireEnvelopeType private constructor(
    internal val code: Byte,
) {
    HELLO(1),
    IHU(2),
    ROUTE_UPDATE(3),
    ROUTE_RETRACTION(4),
    SEQNO_REQUEST(5),
    ROUTE_DIGEST(6),
    MESSAGE(7),
    TRANSFER_START(8),
    TRANSFER_CHUNK(9),
    TRANSFER_ACK(10),
    TRANSFER_COMPLETE(11),
    TRANSFER_ABORT(12),
    ;

    internal companion object {
        internal fun fromCode(code: Byte): WireEnvelopeType {
            return entries.firstOrNull { type -> type.code == code }
                ?: throw IllegalStateException("Unknown WireEnvelopeType code ${code.toInt() and 0xFF}")
        }
    }
}

internal class WireEnvelope internal constructor(
    internal val version: UByte,
    internal val type: WireEnvelopeType,
    payload: ByteArray,
) {
    internal val payload: ByteArray = payload.copyOf()

    internal fun encode(): ByteArray {
        return FlatBufferTableBuilder(fieldCount = FIELD_COUNT)
            .addByte(fieldIndex = VERSION_FIELD_INDEX, value = version.toByte())
            .addByte(fieldIndex = TYPE_FIELD_INDEX, value = type.code)
            .addByteVector(fieldIndex = PAYLOAD_FIELD_INDEX, value = payload)
            .finish()
    }

    internal companion object {
        private const val FIELD_COUNT: Int = 3
        private const val VERSION_FIELD_INDEX: Int = 0
        private const val TYPE_FIELD_INDEX: Int = 1
        private const val PAYLOAD_FIELD_INDEX: Int = 2

        internal fun decode(bytes: ByteArray): WireEnvelope {
            val table = FlatBufferTable.fromRoot(bytes)
            val payload = table.readByteVector(PAYLOAD_FIELD_INDEX)
                ?: throw IllegalStateException("WireEnvelope payload is missing")
            return WireEnvelope(
                version = table.readByte(VERSION_FIELD_INDEX).toUByte(),
                type = WireEnvelopeType.fromCode(table.readByte(TYPE_FIELD_INDEX)),
                payload = payload,
            )
        }
    }
}
