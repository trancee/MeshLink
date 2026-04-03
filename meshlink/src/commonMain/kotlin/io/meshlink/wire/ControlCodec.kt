package io.meshlink.wire

import io.meshlink.wire.WireCodec.TYPE_HANDSHAKE
import io.meshlink.wire.WireCodec.TYPE_KEEPALIVE
import io.meshlink.wire.WireCodec.TYPE_NACK
import io.meshlink.wire.WireCodec.TYPE_RESUME_REQUEST

private const val MESSAGE_ID_SIZE = 16

/**
 * Encode/decode for connection & control wire messages:
 * handshake (0x00), keepalive (0x01), nack (0x07), resume_request (0x08).
 */
object ControlCodec {

    // handshake: type(1) + step(1) + noiseMessage(variable)
    private const val HANDSHAKE_HEADER_SIZE = 2

    fun encodeHandshake(step: UByte, noiseMessage: ByteArray): ByteArray {
        val buf = ByteArray(HANDSHAKE_HEADER_SIZE + noiseMessage.size)
        buf[0] = TYPE_HANDSHAKE
        buf[1] = step.toByte()
        noiseMessage.copyInto(buf, HANDSHAKE_HEADER_SIZE)
        return buf
    }

    fun decodeHandshake(data: ByteArray): HandshakeMessage {
        require(data.size >= HANDSHAKE_HEADER_SIZE) { "handshake too short: ${data.size}" }
        require(data[0] == TYPE_HANDSHAKE) { "not a handshake: 0x${data[0].toUByte().toString(16)}" }
        val step = data[1].toUByte()
        require(step <= 2u) { "invalid handshake step: $step (must be 0, 1, or 2)" }
        val noiseMessage = data.copyOfRange(HANDSHAKE_HEADER_SIZE, data.size)
        return HandshakeMessage(step, noiseMessage)
    }

    // keepalive: type(1) + flags(1) + timestamp(8 LE ulong)
    private const val KEEPALIVE_SIZE = 10

    fun encodeKeepalive(
        timestampMillis: ULong,
        flags: UByte = 0u,
        extensions: List<TlvEntry> = emptyList(),
    ): ByteArray {
        val extBytes = TlvCodec.encode(extensions)
        val buf = ByteArray(KEEPALIVE_SIZE + extBytes.size)
        buf[0] = TYPE_KEEPALIVE
        buf[1] = flags.toByte()
        buf.putULongLE(2, timestampMillis)
        extBytes.copyInto(buf, KEEPALIVE_SIZE)
        return buf
    }

    fun decodeKeepalive(data: ByteArray): KeepaliveMessage {
        require(data.size >= KEEPALIVE_SIZE) { "keepalive too short: ${data.size}" }
        require(data[0] == TYPE_KEEPALIVE) { "not a keepalive: 0x${data[0].toUByte().toString(16)}" }
        val flags = data[1].toUByte()
        val timestampMillis = data.getULongLE(2)
        val extensions = if (data.size > KEEPALIVE_SIZE) {
            TlvCodec.decode(data, KEEPALIVE_SIZE).first
        } else {
            emptyList()
        }
        return KeepaliveMessage(flags, timestampMillis, extensions)
    }

    // nack: type(1) + messageId(16) + reason(1) = 18
    private const val NACK_SIZE = 1 + MESSAGE_ID_SIZE + 1

    fun encodeNack(
        messageId: ByteArray,
        reason: NackReason = NackReason.UNKNOWN,
        extensions: List<TlvEntry> = emptyList(),
    ): ByteArray {
        require(messageId.size == MESSAGE_ID_SIZE) { "messageId must be $MESSAGE_ID_SIZE bytes" }
        val extBytes = TlvCodec.encode(extensions)
        val buf = ByteArray(NACK_SIZE + extBytes.size)
        buf[0] = TYPE_NACK
        messageId.copyInto(buf, 1)
        buf[1 + MESSAGE_ID_SIZE] = reason.code.toByte()
        extBytes.copyInto(buf, NACK_SIZE)
        return buf
    }

    fun decodeNack(data: ByteArray): NackMessage {
        require(data.size >= NACK_SIZE) { "nack too short: ${data.size}" }
        require(data[0] == TYPE_NACK) { "not a nack: 0x${data[0].toUByte().toString(16)}" }
        val messageId = data.copyOfRange(1, 1 + MESSAGE_ID_SIZE)
        val reason = NackReason.fromCode(data[1 + MESSAGE_ID_SIZE].toUByte())
        val extensions = if (data.size > NACK_SIZE) {
            TlvCodec.decode(data, NACK_SIZE).first
        } else {
            emptyList()
        }
        return NackMessage(messageId, reason, extensions)
    }

    // resume_request: type(1) + messageId(16) + bytesReceived(4 LE) = 21
    private const val RESUME_REQUEST_SIZE = 1 + MESSAGE_ID_SIZE + 4

    fun encodeResumeRequest(
        messageId: ByteArray,
        bytesReceived: UInt,
        extensions: List<TlvEntry> = emptyList(),
    ): ByteArray {
        require(messageId.size == MESSAGE_ID_SIZE) { "messageId must be $MESSAGE_ID_SIZE bytes" }
        val extBytes = TlvCodec.encode(extensions)
        val buf = ByteArray(RESUME_REQUEST_SIZE + extBytes.size)
        var offset = 0
        buf[offset++] = TYPE_RESUME_REQUEST
        messageId.copyInto(buf, offset)
        offset += MESSAGE_ID_SIZE
        buf.putUIntLE(offset, bytesReceived)
        extBytes.copyInto(buf, RESUME_REQUEST_SIZE)
        return buf
    }

    fun decodeResumeRequest(data: ByteArray): ResumeRequestMessage {
        require(data.size >= RESUME_REQUEST_SIZE) { "resume_request too short: ${data.size}" }
        require(data[0] == TYPE_RESUME_REQUEST) { "not a resume_request: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE)
        offset += MESSAGE_ID_SIZE
        val bytesReceived = data.getUIntLE(offset)
        val extensions = if (data.size > RESUME_REQUEST_SIZE) {
            TlvCodec.decode(data, RESUME_REQUEST_SIZE).first
        } else {
            emptyList()
        }
        return ResumeRequestMessage(messageId, bytesReceived, extensions)
    }
}

data class HandshakeMessage(
    val step: UByte,
    val noiseMessage: ByteArray,
)

data class KeepaliveMessage(
    val flags: UByte,
    val timestampMillis: ULong,
    val extensions: List<TlvEntry> = emptyList(),
)

data class NackMessage(
    val messageId: ByteArray,
    val reason: NackReason = NackReason.UNKNOWN,
    val extensions: List<TlvEntry> = emptyList(),
)

data class ResumeRequestMessage(
    val messageId: ByteArray,
    val bytesReceived: UInt,
    val extensions: List<TlvEntry> = emptyList(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ResumeRequestMessage) return false
        return messageId.contentEquals(other.messageId) &&
            bytesReceived == other.bytesReceived &&
            extensions == other.extensions
    }

    override fun hashCode(): Int {
        var result = messageId.contentHashCode()
        result = 31 * result + bytesReceived.hashCode()
        result = 31 * result + extensions.hashCode()
        return result
    }
}

enum class NackReason(val code: UByte) {
    UNKNOWN(0u),
    BUFFER_FULL(1u),
    UNKNOWN_DESTINATION(2u),
    DECRYPT_FAILED(3u),
    RATE_LIMITED(4u);

    companion object {
        fun fromCode(code: UByte): NackReason =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
