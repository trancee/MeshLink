package ch.trancee.meshlink.wire

import ch.trancee.meshlink.api.PeerId

internal object MessagePayloadCodec {
    fun decode(table: FlatBufferTable): WireFrame.Message {
        return WireFrame.Message(
            messageId = requireString(table, MESSAGE_ID_FIELD_INDEX, "MESSAGE.messageId"),
            originPeerId =
                PeerId(requireString(table, MESSAGE_ORIGIN_FIELD_INDEX, "MESSAGE.originPeerId")),
            destinationPeerId =
                PeerId(
                    requireString(
                        table,
                        MESSAGE_DESTINATION_FIELD_INDEX,
                        "MESSAGE.destinationPeerId",
                    )
                ),
            priority = priorityFromCode(table.readByte(MESSAGE_PRIORITY_FIELD_INDEX)),
            ttlMillis = table.readInt(MESSAGE_TTL_FIELD_INDEX),
            encryptedPayload =
                requireByteVector(table, MESSAGE_PAYLOAD_FIELD_INDEX, "MESSAGE.encryptedPayload"),
        )
    }

    fun encode(frame: WireFrame.Message): ByteArray {
        return FlatBufferTableBuilder(fieldCount = MESSAGE_FIELD_COUNT)
            .addString(MESSAGE_ID_FIELD_INDEX, frame.messageId)
            .addString(MESSAGE_ORIGIN_FIELD_INDEX, frame.originPeerId.value)
            .addString(MESSAGE_DESTINATION_FIELD_INDEX, frame.destinationPeerId.value)
            .addByte(MESSAGE_PRIORITY_FIELD_INDEX, priorityCode(frame.priority))
            .addInt(MESSAGE_TTL_FIELD_INDEX, frame.ttlMillis)
            .addByteVector(MESSAGE_PAYLOAD_FIELD_INDEX, frame.encryptedPayload)
            .finish()
    }
}
