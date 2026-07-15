package ch.trancee.meshlink.wire

internal enum class WireEnvelopeType private constructor(internal val code: Byte) {
    ROUTE_UPDATE(ROUTE_UPDATE_CODE),
    ROUTE_RETRACTION(ROUTE_RETRACTION_CODE),
    SEQNO_REQUEST(SEQNO_REQUEST_CODE),
    ROUTE_DIGEST(ROUTE_DIGEST_CODE),
    MESSAGE(MESSAGE_CODE),
    TRANSFER_START(TRANSFER_START_CODE),
    TRANSFER_CHUNK(TRANSFER_CHUNK_CODE),
    TRANSFER_ACK(TRANSFER_ACK_CODE),
    TRANSFER_COMPLETE(TRANSFER_COMPLETE_CODE),
    TRANSFER_ABORT(TRANSFER_ABORT_CODE),
    E2E_HANDSHAKE_MESSAGE_1(E2E_HANDSHAKE_MESSAGE_1_CODE),
    E2E_HANDSHAKE_MESSAGE_2(E2E_HANDSHAKE_MESSAGE_2_CODE),
    E2E_HANDSHAKE_MESSAGE_3(E2E_HANDSHAKE_MESSAGE_3_CODE),
    LINK_IDENTITY(LINK_IDENTITY_CODE),
    LINK_KEEPALIVE(LINK_KEEPALIVE_CODE);

    internal companion object {
        internal fun fromCode(code: Byte): WireEnvelopeType {
            return entries.firstOrNull { type -> type.code == code }
                ?: error("Unknown WireEnvelopeType code ${code.toInt() and BYTE_MASK}")
        }
    }
}

internal class WireEnvelope
internal constructor(
    internal val version: UByte,
    internal val type: WireEnvelopeType,
    // Not copied here: both call sites (WireCodec.encodeEnvelope's freshly-built
    // FlatBufferTableBuilder.finish() output, and WireEnvelope.decode's freshly-read
    // table.readByteVector() result) hand in a ByteArray that was just allocated locally and
    // never escapes to any other mutable-owning reference before reaching this constructor, so an
    // additional defensive copy here would only duplicate bytes that are already provably
    // exclusively owned by this instance going forward.
    internal val payload: ByteArray,
) {
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
            val payload =
                table.readByteVector(PAYLOAD_FIELD_INDEX)
                    ?: error("WireEnvelope payload is missing")
            val version = table.readByte(VERSION_FIELD_INDEX).toUByte()
            check(version == WireCodec.CURRENT_WIRE_VERSION) {
                "Unsupported WireEnvelope version ${version.toInt()}"
            }
            return WireEnvelope(
                version = version,
                type = WireEnvelopeType.fromCode(table.readByte(TYPE_FIELD_INDEX)),
                payload = payload,
            )
        }
    }
}

private const val BYTE_MASK: Int = 0xFF
private const val ROUTE_UPDATE_CODE: Byte = 3
private const val ROUTE_RETRACTION_CODE: Byte = 4
private const val SEQNO_REQUEST_CODE: Byte = 5
private const val ROUTE_DIGEST_CODE: Byte = 6
private const val MESSAGE_CODE: Byte = 7
private const val TRANSFER_START_CODE: Byte = 8
private const val TRANSFER_CHUNK_CODE: Byte = 9
private const val TRANSFER_ACK_CODE: Byte = 10
private const val TRANSFER_COMPLETE_CODE: Byte = 11
private const val TRANSFER_ABORT_CODE: Byte = 12
private const val E2E_HANDSHAKE_MESSAGE_1_CODE: Byte = 13
private const val E2E_HANDSHAKE_MESSAGE_2_CODE: Byte = 14
private const val E2E_HANDSHAKE_MESSAGE_3_CODE: Byte = 15
private const val LINK_IDENTITY_CODE: Byte = 16
private const val LINK_KEEPALIVE_CODE: Byte = 17
