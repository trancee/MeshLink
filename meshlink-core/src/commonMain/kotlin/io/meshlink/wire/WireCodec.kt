package io.meshlink.wire

private const val MESSAGE_ID_SIZE = 16

object WireCodec {

    const val TYPE_BROADCAST: Byte = 0x00
    const val TYPE_CHUNK: Byte = 0x03
    const val TYPE_CHUNK_ACK: Byte = 0x04
    const val TYPE_ROUTED_MESSAGE: Byte = 0x05
    const val TYPE_DELIVERY_ACK: Byte = 0x06

    // chunk: type(1) + messageId(16) + seqNum(2 LE) + totalChunks(2 LE) + payload
    const val CHUNK_HEADER_SIZE = 1 + MESSAGE_ID_SIZE + 2 + 2 // 21

    // chunk_ack: type(1) + messageId(16) + ackSeq(2 LE) + sackBitmask(8 LE)
    private const val CHUNK_ACK_SIZE = 1 + MESSAGE_ID_SIZE + 2 + 8 // 27

    fun encodeChunk(
        messageId: ByteArray,
        sequenceNumber: UShort,
        totalChunks: UShort,
        payload: ByteArray,
    ): ByteArray {
        val buf = ByteArray(CHUNK_HEADER_SIZE + payload.size)
        var offset = 0
        buf[offset++] = TYPE_CHUNK
        messageId.copyInto(buf, offset); offset += MESSAGE_ID_SIZE
        buf.putUShortLE(offset, sequenceNumber); offset += 2
        buf.putUShortLE(offset, totalChunks); offset += 2
        payload.copyInto(buf, offset)
        return buf
    }

    fun decodeChunk(data: ByteArray): ChunkMessage {
        require(data.size >= CHUNK_HEADER_SIZE) { "chunk too short: ${data.size}" }
        require(data[0] == TYPE_CHUNK) { "not a chunk: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE); offset += MESSAGE_ID_SIZE
        val sequenceNumber = data.getUShortLE(offset); offset += 2
        val totalChunks = data.getUShortLE(offset); offset += 2
        val payload = data.copyOfRange(offset, data.size)
        return ChunkMessage(messageId, sequenceNumber, totalChunks, payload)
    }

    fun encodeChunkAck(
        messageId: ByteArray,
        ackSequence: UShort,
        sackBitmask: ULong,
    ): ByteArray {
        val buf = ByteArray(CHUNK_ACK_SIZE)
        var offset = 0
        buf[offset++] = TYPE_CHUNK_ACK
        messageId.copyInto(buf, offset); offset += MESSAGE_ID_SIZE
        buf.putUShortLE(offset, ackSequence); offset += 2
        buf.putULongLE(offset, sackBitmask)
        return buf
    }

    fun decodeChunkAck(data: ByteArray): ChunkAckMessage {
        require(data.size >= CHUNK_ACK_SIZE) { "chunk_ack too short: ${data.size}" }
        require(data[0] == TYPE_CHUNK_ACK) { "not a chunk_ack: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE); offset += MESSAGE_ID_SIZE
        val ackSequence = data.getUShortLE(offset); offset += 2
        val sackBitmask = data.getULongLE(offset)
        return ChunkAckMessage(messageId, ackSequence, sackBitmask)
    }

    // routed_message: type(1) + messageId(16) + origin(16) + destination(16) + hopLimit(1) + visitedCount(1) + visited(N×16) + payload
    private const val ROUTED_HEADER_SIZE = 1 + MESSAGE_ID_SIZE + 16 + 16 + 1 + 1 // 51

    fun encodeRoutedMessage(
        messageId: ByteArray,
        origin: ByteArray,
        destination: ByteArray,
        hopLimit: UByte,
        visitedList: List<ByteArray>,
        payload: ByteArray,
    ): ByteArray {
        val buf = ByteArray(ROUTED_HEADER_SIZE + visitedList.size * 16 + payload.size)
        var offset = 0
        buf[offset++] = TYPE_ROUTED_MESSAGE
        messageId.copyInto(buf, offset); offset += MESSAGE_ID_SIZE
        origin.copyInto(buf, offset); offset += 16
        destination.copyInto(buf, offset); offset += 16
        buf[offset++] = hopLimit.toByte()
        buf[offset++] = visitedList.size.toByte()
        for (hash in visitedList) {
            hash.copyInto(buf, offset); offset += 16
        }
        payload.copyInto(buf, offset)
        return buf
    }

    fun decodeRoutedMessage(data: ByteArray): RoutedMessage {
        require(data.size >= ROUTED_HEADER_SIZE) { "routed_message too short: ${data.size}" }
        require(data[0] == TYPE_ROUTED_MESSAGE) { "not a routed_message: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE); offset += MESSAGE_ID_SIZE
        val origin = data.copyOfRange(offset, offset + 16); offset += 16
        val destination = data.copyOfRange(offset, offset + 16); offset += 16
        val hopLimit = data[offset++].toUByte()
        val visitedCount = data[offset++].toInt() and 0xFF
        val visitedList = (0 until visitedCount).map {
            val hash = data.copyOfRange(offset, offset + 16); offset += 16
            hash
        }
        val payload = data.copyOfRange(offset, data.size)
        return RoutedMessage(messageId, origin, destination, hopLimit, visitedList, payload)
    }

    // broadcast: type(1) + messageId(16) + origin(16) + remainingHops(1) + appIdHash(16) + payload
    private const val BROADCAST_HEADER_SIZE = 1 + MESSAGE_ID_SIZE + 16 + 1 + 16 // 50

    fun encodeBroadcast(
        messageId: ByteArray,
        origin: ByteArray,
        remainingHops: UByte,
        appIdHash: ByteArray = ByteArray(16),
        payload: ByteArray,
    ): ByteArray {
        val buf = ByteArray(BROADCAST_HEADER_SIZE + payload.size)
        var offset = 0
        buf[offset++] = TYPE_BROADCAST
        messageId.copyInto(buf, offset); offset += MESSAGE_ID_SIZE
        origin.copyInto(buf, offset); offset += 16
        buf[offset++] = remainingHops.toByte()
        appIdHash.copyInto(buf, offset); offset += 16
        payload.copyInto(buf, offset)
        return buf
    }

    fun decodeBroadcast(data: ByteArray): BroadcastMessage {
        require(data.size >= BROADCAST_HEADER_SIZE) { "broadcast too short: ${data.size}" }
        require(data[0] == TYPE_BROADCAST) { "not a broadcast: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE); offset += MESSAGE_ID_SIZE
        val origin = data.copyOfRange(offset, offset + 16); offset += 16
        val remainingHops = data[offset++].toUByte()
        val appIdHash = data.copyOfRange(offset, offset + 16); offset += 16
        val payload = data.copyOfRange(offset, data.size)
        return BroadcastMessage(messageId, origin, remainingHops, appIdHash, payload)
    }

    // delivery_ack: type(1) + messageId(16) + recipientId(16)
    private const val DELIVERY_ACK_SIZE = 1 + MESSAGE_ID_SIZE + 16 // 33

    fun encodeDeliveryAck(messageId: ByteArray, recipientId: ByteArray): ByteArray {
        val buf = ByteArray(DELIVERY_ACK_SIZE)
        var offset = 0
        buf[offset++] = TYPE_DELIVERY_ACK
        messageId.copyInto(buf, offset); offset += MESSAGE_ID_SIZE
        recipientId.copyInto(buf, offset)
        return buf
    }

    fun decodeDeliveryAck(data: ByteArray): DeliveryAckMessage {
        require(data.size >= DELIVERY_ACK_SIZE) { "delivery_ack too short: ${data.size}" }
        require(data[0] == TYPE_DELIVERY_ACK) { "not a delivery_ack: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE); offset += MESSAGE_ID_SIZE
        val recipientId = data.copyOfRange(offset, offset + 16)
        return DeliveryAckMessage(messageId, recipientId)
    }
}

// --- Little-endian helpers ---

private fun ByteArray.putUShortLE(offset: Int, value: UShort) {
    val v = value.toInt()
    this[offset] = v.toByte()
    this[offset + 1] = (v shr 8).toByte()
}

private fun ByteArray.getUShortLE(offset: Int): UShort =
    ((this[offset].toInt() and 0xFF) or
     ((this[offset + 1].toInt() and 0xFF) shl 8)).toUShort()

private fun ByteArray.putULongLE(offset: Int, value: ULong) {
    val v = value.toLong()
    for (i in 0..7) {
        this[offset + i] = (v shr (i * 8)).toByte()
    }
}

private fun ByteArray.getULongLE(offset: Int): ULong {
    var result = 0L
    for (i in 0..7) {
        result = result or ((this[offset + i].toLong() and 0xFF) shl (i * 8))
    }
    return result.toULong()
}

// --- Data classes ---

data class ChunkMessage(
    val messageId: ByteArray,
    val sequenceNumber: UShort,
    val totalChunks: UShort,
    val payload: ByteArray,
)

data class ChunkAckMessage(
    val messageId: ByteArray,
    val ackSequence: UShort,
    val sackBitmask: ULong,
)

data class RoutedMessage(
    val messageId: ByteArray,
    val origin: ByteArray,
    val destination: ByteArray,
    val hopLimit: UByte,
    val visitedList: List<ByteArray>,
    val payload: ByteArray,
)

data class BroadcastMessage(
    val messageId: ByteArray,
    val origin: ByteArray,
    val remainingHops: UByte,
    val appIdHash: ByteArray,
    val payload: ByteArray,
)

data class DeliveryAckMessage(
    val messageId: ByteArray,
    val recipientId: ByteArray,
)
