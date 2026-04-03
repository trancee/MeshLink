package io.meshlink.wire

import io.meshlink.wire.WireCodec.TYPE_BROADCAST
import io.meshlink.wire.WireCodec.TYPE_DELIVERY_ACK
import io.meshlink.wire.WireCodec.TYPE_ROUTED_MESSAGE

private const val MESSAGE_ID_SIZE = 16
private const val PEER_ID_SIZE = 12
private const val APP_ID_HASH_SIZE = 8
private val EMPTY_BYTES = ByteArray(0)
private val EMPTY_APP_ID_HASH = ByteArray(APP_ID_HASH_SIZE)

private const val ED25519_SIG_SIZE = 64
private const val ED25519_PUB_KEY_SIZE = 32
private const val FLAG_HAS_SIGNATURE = 0x01

/**
 * Encode/decode for routed message (0x0A), broadcast (0x09),
 * and delivery ack (0x0B) wire messages.
 */
object MessagingCodec {

    // routed_message: type(1) + messageId(16) + origin(12) + destination(12)
    //   + hopLimit(1) + replayCounter(8 LE) + visitedCount(1) + visited(N×12)
    //   + priority(1) + payload
    private const val ROUTED_HEADER_SIZE =
        1 + MESSAGE_ID_SIZE + PEER_ID_SIZE + PEER_ID_SIZE + 1 + 8 + 1 + 1 // 52

    fun encodeRoutedMessage(
        messageId: ByteArray,
        origin: ByteArray,
        destination: ByteArray,
        hopLimit: UByte,
        visitedList: List<ByteArray>,
        payload: ByteArray,
        replayCounter: ULong = 0u,
        priority: Byte = 0,
    ): ByteArray {
        require(visitedList.size <= 255) { "visitedList too large: ${visitedList.size} (max 255)" }
        val buf = ByteArray(ROUTED_HEADER_SIZE + visitedList.size * PEER_ID_SIZE + payload.size)
        var offset = 0
        buf[offset++] = TYPE_ROUTED_MESSAGE
        messageId.copyInto(buf, offset)
        offset += MESSAGE_ID_SIZE
        origin.copyInto(buf, offset)
        offset += PEER_ID_SIZE
        destination.copyInto(buf, offset)
        offset += PEER_ID_SIZE
        buf[offset++] = hopLimit.toByte()
        buf.putULongLE(offset, replayCounter)
        offset += 8
        buf[offset++] = visitedList.size.toByte()
        for (hash in visitedList) {
            hash.copyInto(buf, offset)
            offset += PEER_ID_SIZE
        }
        buf[offset++] = priority
        payload.copyInto(buf, offset)
        return buf
    }

    fun decodeRoutedMessage(data: ByteArray): RoutedMessage {
        require(data.size >= ROUTED_HEADER_SIZE) { "routed_message too short: ${data.size}" }
        require(data[0] == TYPE_ROUTED_MESSAGE) { "not a routed_message: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE)
        offset += MESSAGE_ID_SIZE
        val origin = data.copyOfRange(offset, offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE
        val destination = data.copyOfRange(offset, offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE
        val hopLimit = data[offset++].toUByte()
        val replayCounter = data.getULongLE(offset)
        offset += 8
        val visitedCount = data[offset++].toInt() and 0xFF
        require(data.size >= offset + visitedCount * PEER_ID_SIZE) {
            "routed_message truncated: visitedCount=$visitedCount requires ${offset + visitedCount * PEER_ID_SIZE} bytes, got ${data.size}"
        }
        val visitedList = (0 until visitedCount).map {
            val hash = data.copyOfRange(offset, offset + PEER_ID_SIZE)
            offset += PEER_ID_SIZE
            hash
        }
        require(data.size > offset) { "routed_message missing priority byte" }
        val priority = data[offset++]
        val payload = data.copyOfRange(offset, data.size)
        return RoutedMessage(messageId, origin, destination, hopLimit, replayCounter, visitedList, payload, priority)
    }

    // broadcast: type(1) + messageId(16) + origin(12) + remainingHops(1) + appIdHash(8) + flags(1) + priority(1)
    //   + [signature(64) + signerPubKey(32)] + payload
    private const val BROADCAST_HEADER_SIZE = 1 + MESSAGE_ID_SIZE + PEER_ID_SIZE + 1 + APP_ID_HASH_SIZE + 1 + 1 // 40

    fun encodeBroadcast(
        messageId: ByteArray,
        origin: ByteArray,
        remainingHops: UByte,
        appIdHash: ByteArray = EMPTY_APP_ID_HASH,
        payload: ByteArray,
        signature: ByteArray = EMPTY_BYTES,
        signerPublicKey: ByteArray = EMPTY_BYTES,
        priority: Byte = 0,
    ): ByteArray {
        val sigBlock = if (signature.isNotEmpty()) ED25519_SIG_SIZE + ED25519_PUB_KEY_SIZE else 0
        val buf = ByteArray(BROADCAST_HEADER_SIZE + sigBlock + payload.size)
        var offset = 0
        buf[offset++] = TYPE_BROADCAST
        messageId.copyInto(buf, offset)
        offset += MESSAGE_ID_SIZE
        origin.copyInto(buf, offset)
        offset += PEER_ID_SIZE
        buf[offset++] = remainingHops.toByte()
        appIdHash.copyInto(buf, offset)
        offset += APP_ID_HASH_SIZE
        buf[offset++] = if (signature.isNotEmpty()) FLAG_HAS_SIGNATURE.toByte() else 0
        buf[offset++] = priority
        if (signature.isNotEmpty()) {
            signature.copyInto(buf, offset)
            offset += ED25519_SIG_SIZE
            signerPublicKey.copyInto(buf, offset)
            offset += ED25519_PUB_KEY_SIZE
        }
        payload.copyInto(buf, offset)
        return buf
    }

    fun decodeBroadcast(data: ByteArray): BroadcastMessage {
        require(data.size >= BROADCAST_HEADER_SIZE) { "broadcast too short: ${data.size}" }
        require(data[0] == TYPE_BROADCAST) { "not a broadcast: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE)
        offset += MESSAGE_ID_SIZE
        val origin = data.copyOfRange(offset, offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE
        val remainingHops = data[offset++].toUByte()
        val appIdHash = data.copyOfRange(offset, offset + APP_ID_HASH_SIZE)
        offset += APP_ID_HASH_SIZE
        val flags = data[offset++].toInt() and 0xFF
        val priority = data[offset++]
        val signature: ByteArray
        val signerPublicKey: ByteArray
        if (flags and FLAG_HAS_SIGNATURE != 0) {
            require(data.size >= offset + ED25519_SIG_SIZE + ED25519_PUB_KEY_SIZE) {
                "broadcast signature truncated: requires ${offset + ED25519_SIG_SIZE + ED25519_PUB_KEY_SIZE} bytes, got ${data.size}"
            }
            signature = data.copyOfRange(offset, offset + ED25519_SIG_SIZE)
            offset += ED25519_SIG_SIZE
            signerPublicKey = data.copyOfRange(offset, offset + ED25519_PUB_KEY_SIZE)
            offset += ED25519_PUB_KEY_SIZE
        } else {
            signature = EMPTY_BYTES
            signerPublicKey = EMPTY_BYTES
        }
        val payload = data.copyOfRange(offset, data.size)
        return BroadcastMessage(
            messageId,
            origin,
            remainingHops,
            appIdHash,
            payload,
            signature,
            signerPublicKey,
            priority,
        )
    }

    // delivery_ack: type(1) + messageId(16) + recipientId(12) + flags(1) + [signature(64) + signerPubKey(32)]
    private const val DELIVERY_ACK_HEADER_SIZE = 1 + MESSAGE_ID_SIZE + PEER_ID_SIZE + 1 // 30

    fun encodeDeliveryAck(
        messageId: ByteArray,
        recipientId: ByteArray,
        signature: ByteArray = EMPTY_BYTES,
        signerPublicKey: ByteArray = EMPTY_BYTES,
        extensions: List<TlvEntry> = emptyList(),
    ): ByteArray {
        val sigBlock = if (signature.isNotEmpty()) ED25519_SIG_SIZE + ED25519_PUB_KEY_SIZE else 0
        val extBytes = TlvCodec.encode(extensions)
        val buf = ByteArray(DELIVERY_ACK_HEADER_SIZE + sigBlock + extBytes.size)
        var offset = 0
        buf[offset++] = TYPE_DELIVERY_ACK
        messageId.copyInto(buf, offset)
        offset += MESSAGE_ID_SIZE
        recipientId.copyInto(buf, offset)
        offset += PEER_ID_SIZE
        buf[offset++] = if (signature.isNotEmpty()) FLAG_HAS_SIGNATURE.toByte() else 0
        if (signature.isNotEmpty()) {
            signature.copyInto(buf, offset)
            offset += ED25519_SIG_SIZE
            signerPublicKey.copyInto(buf, offset)
            offset += ED25519_PUB_KEY_SIZE
        }
        extBytes.copyInto(buf, offset)
        return buf
    }

    fun decodeDeliveryAck(data: ByteArray): DeliveryAckMessage {
        require(data.size >= DELIVERY_ACK_HEADER_SIZE) { "delivery_ack too short: ${data.size}" }
        require(data[0] == TYPE_DELIVERY_ACK) { "not a delivery_ack: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val messageId = data.copyOfRange(offset, offset + MESSAGE_ID_SIZE)
        offset += MESSAGE_ID_SIZE
        val recipientId = data.copyOfRange(offset, offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE
        val flags = data[offset++].toInt() and 0xFF
        val signature: ByteArray
        val signerPublicKey: ByteArray
        if (flags and FLAG_HAS_SIGNATURE != 0) {
            require(data.size >= offset + ED25519_SIG_SIZE + ED25519_PUB_KEY_SIZE) {
                "delivery_ack signature truncated: requires ${offset + ED25519_SIG_SIZE + ED25519_PUB_KEY_SIZE} bytes, got ${data.size}"
            }
            signature = data.copyOfRange(offset, offset + ED25519_SIG_SIZE)
            offset += ED25519_SIG_SIZE
            signerPublicKey = data.copyOfRange(offset, offset + ED25519_PUB_KEY_SIZE)
            offset += ED25519_PUB_KEY_SIZE
        } else {
            signature = EMPTY_BYTES
            signerPublicKey = EMPTY_BYTES
        }
        val extensions = if (data.size > offset) {
            TlvCodec.decode(data, offset).first
        } else {
            emptyList()
        }
        return DeliveryAckMessage(messageId, recipientId, signature, signerPublicKey, extensions)
    }
}

data class RoutedMessage(
    val messageId: ByteArray,
    val origin: ByteArray,
    val destination: ByteArray,
    val hopLimit: UByte,
    val replayCounter: ULong,
    val visitedList: List<ByteArray>,
    val payload: ByteArray,
    val priority: Byte = 0,
)

data class BroadcastMessage(
    val messageId: ByteArray,
    val origin: ByteArray,
    val remainingHops: UByte,
    val appIdHash: ByteArray,
    val payload: ByteArray,
    val signature: ByteArray = EMPTY_BYTES,
    val signerPublicKey: ByteArray = EMPTY_BYTES,
    val priority: Byte = 0,
)

data class DeliveryAckMessage(
    val messageId: ByteArray,
    val recipientId: ByteArray,
    val signature: ByteArray = EMPTY_BYTES,
    val signerPublicKey: ByteArray = EMPTY_BYTES,
    val extensions: List<TlvEntry> = emptyList(),
)
