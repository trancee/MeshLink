package ch.trancee.meshlink.wire

sealed interface WireMessage {
    val type: MessageType

    fun encode(buffer: WriteBuffer)
}

object WireCodec {
    fun encode(message: WireMessage): ByteArray {
        val buffer = WriteBuffer()
        message.encode(buffer)
        val payload = buffer.finish()
        val frame = ByteArray(1 + payload.size)
        frame[0] = message.type.code.toByte()
        payload.copyInto(frame, 1)
        return frame
    }

    fun decode(data: ByteArray): WireMessage {
        if (data.isEmpty()) throw IllegalArgumentException("Empty wire frame")
        val typeCode = data[0].toUByte()
        val payload = data.copyOfRange(1, data.size)
        val buffer = ReadBuffer(payload)
        return when (MessageType.fromByte(typeCode)) {
            MessageType.HANDSHAKE -> Handshake.decode(buffer)
            MessageType.KEEPALIVE -> Keepalive.decode(buffer)
            MessageType.ROTATION_ANNOUNCEMENT -> RotationAnnouncementMessage.decode(buffer)
            MessageType.HELLO -> Hello.decode(buffer)
            MessageType.UPDATE -> Update.decode(buffer)
            MessageType.CHUNK -> Chunk.decode(buffer)
            MessageType.CHUNK_ACK -> ChunkAck.decode(buffer)
            MessageType.NACK -> Nack.decode(buffer)
            MessageType.RESUME_REQUEST -> ResumeRequest.decode(buffer)
            MessageType.BROADCAST -> Broadcast.decode(buffer)
            MessageType.ROUTED_MESSAGE -> RoutedMessage.decode(buffer)
            MessageType.DELIVERY_ACK -> DeliveryAck.decode(buffer)
            MessageType.UNKNOWN ->
                throw IllegalArgumentException("Unknown message type: 0x${typeCode.toString(16)}")
        }
    }
}
