package io.meshlink.wire

private const val MESSAGE_ID_SIZE = 16

object WireCodec {

    const val TYPE_CHUNK: Byte = 0x03
    const val TYPE_CHUNK_ACK: Byte = 0x04

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
