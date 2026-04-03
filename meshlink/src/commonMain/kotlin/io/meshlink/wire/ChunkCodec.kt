package io.meshlink.wire

import io.meshlink.wire.WireCodec.TYPE_CHUNK
import io.meshlink.wire.WireCodec.TYPE_CHUNK_ACK

private const val MESSAGE_ID_SIZE = 16

/**
 * Encode/decode for chunk (0x05) and chunk_ack (0x06) wire messages.
 */
object ChunkCodec {

    const val CHUNK_HEADER_SIZE_FIRST = 1 + MESSAGE_ID_SIZE + 2 + 2 // 21
    const val CHUNK_HEADER_SIZE_SUBSEQUENT = 1 + MESSAGE_ID_SIZE + 2 // 19

    @Deprecated("Use CHUNK_HEADER_SIZE_FIRST for seq=0 or CHUNK_HEADER_SIZE_SUBSEQUENT for seq>0")
    const val CHUNK_HEADER_SIZE = CHUNK_HEADER_SIZE_FIRST

    private const val CHUNK_ACK_SIZE = 1 + MESSAGE_ID_SIZE + 2 + 8 + 8 // 35

    fun encodeChunk(
        messageId: ByteArray,
        sequenceNumber: UShort,
        totalChunks: UShort,
        payload: ByteArray,
    ): ByteArray {
        val isFirst = sequenceNumber == 0u.toUShort()
        val headerSize = if (isFirst) CHUNK_HEADER_SIZE_FIRST else CHUNK_HEADER_SIZE_SUBSEQUENT
        val buf = ByteArray(headerSize + payload.size)
        var offset = 0
        buf[offset++] = TYPE_CHUNK
        messageId.copyInto(buf, offset)
        offset += MESSAGE_ID_SIZE
        buf.putUShortLE(offset, sequenceNumber)
        offset += 2
        if (isFirst) {
            buf.putUShortLE(offset, totalChunks)
            offset += 2
        }
        payload.copyInto(buf, offset)
        return buf
    }

    fun decodeChunk(data: ByteArray): ChunkMessage {
        require(data.size >= CHUNK_HEADER_SIZE_SUBSEQUENT) { "chunk too short: ${data.size}" }
        require(data[0] == TYPE_CHUNK) { "not a chunk: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE)
        offset += MESSAGE_ID_SIZE
        val sequenceNumber = data.getUShortLE(offset)
        offset += 2
        val totalChunks: UShort? = if (sequenceNumber == 0u.toUShort()) {
            require(data.size >= CHUNK_HEADER_SIZE_FIRST) { "first chunk too short: ${data.size}" }
            val tc = data.getUShortLE(offset)
            offset += 2
            tc
        } else {
            null
        }
        val payload = data.copyOfRange(offset, data.size)
        if (totalChunks != null) {
            require(sequenceNumber < totalChunks) {
                "chunk sequenceNumber ($sequenceNumber) >= totalChunks ($totalChunks)"
            }
        }
        return ChunkMessage(messageId, sequenceNumber, totalChunks, payload)
    }

    fun encodeChunkAck(
        messageId: ByteArray,
        ackSequence: UShort,
        sackBitmask: ULong,
        sackBitmaskHigh: ULong,
        extensions: List<TlvEntry> = emptyList(),
    ): ByteArray {
        val extBytes = TlvCodec.encode(extensions)
        val buf = ByteArray(CHUNK_ACK_SIZE + extBytes.size)
        var offset = 0
        buf[offset++] = TYPE_CHUNK_ACK
        messageId.copyInto(buf, offset)
        offset += MESSAGE_ID_SIZE
        buf.putUShortLE(offset, ackSequence)
        offset += 2
        buf.putULongLE(offset, sackBitmask)
        offset += 8
        buf.putULongLE(offset, sackBitmaskHigh)
        extBytes.copyInto(buf, CHUNK_ACK_SIZE)
        return buf
    }

    fun decodeChunkAck(data: ByteArray): ChunkAckMessage {
        require(data.size >= CHUNK_ACK_SIZE) { "chunk_ack too short: ${data.size}" }
        require(data[0] == TYPE_CHUNK_ACK) { "not a chunk_ack: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE)
        offset += MESSAGE_ID_SIZE
        val ackSequence = data.getUShortLE(offset)
        offset += 2
        val sackBitmask = data.getULongLE(offset)
        offset += 8
        val sackBitmaskHigh = data.getULongLE(offset)
        val extensions = if (data.size > CHUNK_ACK_SIZE) {
            TlvCodec.decode(data, CHUNK_ACK_SIZE).first
        } else {
            emptyList()
        }
        return ChunkAckMessage(messageId, ackSequence, sackBitmask, sackBitmaskHigh, extensions)
    }
}

data class ChunkMessage(
    val messageId: ByteArray,
    val sequenceNumber: UShort,
    val totalChunks: UShort?,
    val payload: ByteArray,
)

data class ChunkAckMessage(
    val messageId: ByteArray,
    val ackSequence: UShort,
    val sackBitmask: ULong,
    val sackBitmaskHigh: ULong,
    val extensions: List<TlvEntry> = emptyList(),
)
