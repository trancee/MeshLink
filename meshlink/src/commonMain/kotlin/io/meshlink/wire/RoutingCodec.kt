package io.meshlink.wire

import io.meshlink.wire.WireCodec.TYPE_HELLO
import io.meshlink.wire.WireCodec.TYPE_UPDATE

private const val PEER_ID_SIZE = 12

/**
 * Encode/decode for Babel Hello (0x03) and Update (0x04) wire messages.
 */
object RoutingCodec {

    private const val HELLO_SIZE = 1 + PEER_ID_SIZE + 2 // 15
    private const val UPDATE_SIZE = 1 + PEER_ID_SIZE + 2 + 2 + 32 // 49

    fun encodeHello(sender: ByteArray, seqNo: UShort): ByteArray {
        val buf = ByteArray(HELLO_SIZE)
        buf[0] = TYPE_HELLO
        sender.copyInto(buf, 1)
        buf.putUShortLE(1 + PEER_ID_SIZE, seqNo)
        return buf
    }

    fun decodeHello(data: ByteArray): HelloMessage {
        require(data.size >= HELLO_SIZE) { "hello too short: ${data.size}" }
        require(data[0] == TYPE_HELLO) { "not a hello: 0x${data[0].toUByte().toString(16)}" }
        val sender = data.copyOfRange(1, 1 + PEER_ID_SIZE)
        val seqNo = data.getUShortLE(1 + PEER_ID_SIZE)
        return HelloMessage(sender, seqNo)
    }

    fun encodeUpdate(
        destination: ByteArray,
        metric: UShort,
        seqNo: UShort,
        publicKey: ByteArray,
    ): ByteArray {
        require(publicKey.size == 32) { "publicKey must be 32 bytes" }
        val buf = ByteArray(UPDATE_SIZE)
        var offset = 0
        buf[offset++] = TYPE_UPDATE
        destination.copyInto(buf, offset)
        offset += PEER_ID_SIZE
        buf.putUShortLE(offset, metric)
        offset += 2
        buf.putUShortLE(offset, seqNo)
        offset += 2
        publicKey.copyInto(buf, offset)
        return buf
    }

    fun decodeUpdate(data: ByteArray): UpdateMessage {
        require(data.size >= UPDATE_SIZE) { "update too short: ${data.size}" }
        require(data[0] == TYPE_UPDATE) { "not an update: 0x${data[0].toUByte().toString(16)}" }
        var offset = 1
        val destination = data.copyOfRange(offset, offset + PEER_ID_SIZE)
        offset += PEER_ID_SIZE
        val metric = data.getUShortLE(offset)
        offset += 2
        val seqNo = data.getUShortLE(offset)
        offset += 2
        val publicKey = data.copyOfRange(offset, offset + 32)
        return UpdateMessage(destination, metric, seqNo, publicKey)
    }
}

data class HelloMessage(
    val sender: ByteArray,
    val seqNo: UShort,
)

data class UpdateMessage(
    val destination: ByteArray,
    val metric: UShort,
    val seqNo: UShort,
    val publicKey: ByteArray,
)
