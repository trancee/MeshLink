package ch.trancee.meshlink.wire

sealed interface WireMessage {
    val type: MessageType

    fun encode(wb: WriteBuffer)
}

object WireCodec {
    fun encode(message: WireMessage): ByteArray {
        val wb = WriteBuffer()
        message.encode(wb)
        val payload = wb.finish()
        val frame = ByteArray(1 + payload.size)
        frame[0] = message.type.code.toByte()
        payload.copyInto(frame, 1)
        return frame
    }

    fun decode(data: ByteArray): WireMessage {
        if (data.isEmpty()) throw IllegalArgumentException("Empty wire frame")
        val typeCode = data[0].toUByte()
        val payload = data.copyOfRange(1, data.size)
        val rb = ReadBuffer(payload)
        return when (MessageType.fromByte(typeCode)) {
            MessageType.HANDSHAKE -> Handshake.decode(rb)
            MessageType.KEEPALIVE -> Keepalive.decode(rb)
            MessageType.ROTATION_ANNOUNCEMENT -> RotationAnnouncementMsg.decode(rb)
            MessageType.HELLO -> Hello.decode(rb)
            MessageType.UPDATE -> Update.decode(rb)
            MessageType.CHUNK -> Chunk.decode(rb)
            MessageType.CHUNK_ACK -> ChunkAck.decode(rb)
            MessageType.NACK -> Nack.decode(rb)
            MessageType.RESUME_REQUEST -> ResumeRequest.decode(rb)
            MessageType.BROADCAST -> Broadcast.decode(rb)
            MessageType.ROUTED_MESSAGE -> RoutedMessage.decode(rb)
            MessageType.DELIVERY_ACK -> DeliveryAck.decode(rb)
            MessageType.UNKNOWN ->
                throw IllegalArgumentException("Unknown message type: 0x${typeCode.toString(16)}")
        }
    }
}
