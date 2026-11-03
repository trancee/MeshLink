package bakeoff

object BakeoffUuids {
    const val SERVICE: String = "6e4b0001-8f3c-4d2a-9b61-2c7a5d3e9f10"
    const val CONTROL: String = "6e4b0002-8f3c-4d2a-9b61-2c7a5d3e9f10"
    const val DATA_NOTIFY: String = "6e4b0003-8f3c-4d2a-9b61-2c7a5d3e9f10"
    const val DATA_WRITE: String = "6e4b0004-8f3c-4d2a-9b61-2c7a5d3e9f10"
    const val PSM: String = "6e4b0005-8f3c-4d2a-9b61-2c7a5d3e9f10"
    const val CCCD: String = "00002902-0000-1000-8000-00805f9b34fb"
}

enum class MessageType(val code: Int) {
    HELLO(1),
    ECHO(2),
    BULK_REQUEST(3),
    BULK(4),
    BULK_END(5),
    BULK_ACK(6),
    HANDSHAKE(7),
    BYE(8),

    /** Energy prototype: ask the peer to send `count` BULK messages of `size` B every `interval_ms`. */
    RATE(9);

    companion object {
        fun fromCode(code: Int): MessageType =
            entries.firstOrNull { it.code == code } ?: throw ProtocolException("unknown message type $code")
    }
}

class ProtocolException(message: String) : Exception(message)

/**
 * Dummy handshake sizes in bytes (random filler, no crypto). Source: docs/research/2026-10-31-crypto-options.md
 * table (Noise XX 32/96/64, XXhfs + ML-KEM-768 1216/1200/64, IK msg1 96, IKhfs msg1 1296) and ADR 0002
 * (post-quantum first message adds the 1,120 B HPKE X-Wing ciphertext).
 */
object HandshakeSizes {
    val CLASSICAL_XX: List<Int> = listOf(32, 96, 64)
    val PQ_XXHFS: List<Int> = listOf(1216, 1200, 64)
    private const val FIRST_MESSAGE_APP_PAYLOAD = 64
    const val CLASSICAL_FIRST_MESSAGE: Int = 96 + FIRST_MESSAGE_APP_PAYLOAD
    const val PQ_FIRST_MESSAGE: Int = 1296 + 1120 + FIRST_MESSAGE_APP_PAYLOAD
}

/** Control characteristic value: node ID (8) + run tag (4) + UTF-8 "model|os|platform". */
class PeerIdentity(val nodeId: Long, val runTag: Int, val info: String) {
    fun encodeControl(): ByteArray = ByteWriter().long(nodeId).int(runTag).bytes(info.encodeToByteArray()).toByteArray()
    fun encodeHello(): ByteArray = ByteWriter().long(nodeId).bytes(info.encodeToByteArray()).toByteArray()

    val nodeIdHex: String get() = nodeId.toULong().toString(16).padStart(16, '0')

    companion object {
        fun decodeControl(bytes: ByteArray): PeerIdentity {
            if (bytes.size < 12) throw ProtocolException("control value too short: ${bytes.size}")
            val reader = ByteReader(bytes)
            return PeerIdentity(reader.long(), reader.int(), reader.rest().decodeToString())
        }

        fun decodeHello(bytes: ByteArray): PeerIdentity {
            if (bytes.size < 8) throw ProtocolException("hello too short: ${bytes.size}")
            val reader = ByteReader(bytes)
            return PeerIdentity(reader.long(), 0, reader.rest().decodeToString())
        }
    }
}
