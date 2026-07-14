package ch.trancee.meshlink.engine.transport

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.ReadBuffer
import ch.trancee.meshlink.wire.WriteBuffer

/**
 * A sealed direct message addressed to [senderPeerId]'s peer. Only [senderPeerId] and [ciphertext]
 * are carried on the wire: the sender's cryptographic identity is never self-asserted here. The
 * recipient resolves [senderPeerId] to an already-pinned [ch.trancee.meshlink.trust.TrustRecord]
 * (established only through an authenticated Noise handshake) and uses that record's keys to open
 * [ciphertext] via [ch.trancee.meshlink.crypto.MessageSealer]. This deliberately prevents a relay
 * or attacker from seeding trust by asserting arbitrary key material in the envelope itself.
 */
internal class DirectMessageEnvelope
internal constructor(internal val senderPeerId: PeerId, ciphertext: ByteArray) {
    private val senderPeerIdBytes: ByteArray = senderPeerId.value.encodeToByteArray()
    internal val ciphertext: ByteArray = ciphertext.copyOf()

    internal fun encode(): ByteArray {
        val buffer = WriteBuffer()
        buffer.writeIntLittleEndian(senderPeerIdBytes.size)
        buffer.writeBytes(senderPeerIdBytes)
        buffer.writeIntLittleEndian(ciphertext.size)
        buffer.writeBytes(ciphertext)
        return buffer.toByteArray()
    }

    internal companion object {
        internal fun decode(bytes: ByteArray): DirectMessageEnvelope {
            val buffer = ReadBuffer(bytes)
            val senderBytes = buffer.readBytes(buffer.readIntLittleEndian())
            val ciphertext = buffer.readBytes(buffer.readIntLittleEndian())
            return DirectMessageEnvelope(
                senderPeerId = PeerId(senderBytes.decodeToString()),
                ciphertext = ciphertext,
            )
        }
    }
}

internal sealed class DirectWireFrame protected constructor(payload: ByteArray) {
    internal val payload: ByteArray = payload.copyOf()

    internal fun encode(): ByteArray {
        val buffer = WriteBuffer()
        buffer.writeByte(type.code)
        buffer.writeIntLittleEndian(payload.size)
        buffer.writeBytes(payload)
        return buffer.toByteArray()
    }

    internal abstract val type: DirectWireFrameType

    internal class HandshakeMessage1 internal constructor(payload: ByteArray) :
        DirectWireFrame(payload) {
        override val type: DirectWireFrameType = DirectWireFrameType.HANDSHAKE_MESSAGE_1
    }

    internal class HandshakeMessage2 internal constructor(payload: ByteArray) :
        DirectWireFrame(payload) {
        override val type: DirectWireFrameType = DirectWireFrameType.HANDSHAKE_MESSAGE_2
    }

    internal class HandshakeMessage3 internal constructor(payload: ByteArray) :
        DirectWireFrame(payload) {
        override val type: DirectWireFrameType = DirectWireFrameType.HANDSHAKE_MESSAGE_3
    }

    internal class Data internal constructor(payload: ByteArray) : DirectWireFrame(payload) {
        override val type: DirectWireFrameType = DirectWireFrameType.DATA
    }

    internal companion object {
        internal fun decode(bytes: ByteArray): DirectWireFrame {
            val buffer = ReadBuffer(bytes)
            val type = DirectWireFrameType.fromCode(buffer.readByte())
            val payload = buffer.readBytes(buffer.readIntLittleEndian())
            return when (type) {
                DirectWireFrameType.HANDSHAKE_MESSAGE_1 -> HandshakeMessage1(payload)
                DirectWireFrameType.HANDSHAKE_MESSAGE_2 -> HandshakeMessage2(payload)
                DirectWireFrameType.HANDSHAKE_MESSAGE_3 -> HandshakeMessage3(payload)
                DirectWireFrameType.DATA -> Data(payload)
            }
        }
    }
}

internal enum class DirectWireFrameType private constructor(internal val code: Byte) {
    HANDSHAKE_MESSAGE_1(HANDSHAKE_MESSAGE_1_CODE),
    HANDSHAKE_MESSAGE_2(HANDSHAKE_MESSAGE_2_CODE),
    HANDSHAKE_MESSAGE_3(HANDSHAKE_MESSAGE_3_CODE),
    DATA(DATA_FRAME_CODE);

    internal companion object {
        internal fun fromCode(code: Byte): DirectWireFrameType {
            return entries.firstOrNull { it.code == code }
                ?: throw MeshLinkException.TransportFailure(
                    "Unknown direct wire frame type ${code.toInt() and DIRECT_FRAME_CODE_MASK}"
                )
        }
    }
}

private const val DIRECT_FRAME_CODE_MASK: Int = 0xFF
private const val HANDSHAKE_MESSAGE_1_CODE: Byte = 1
private const val HANDSHAKE_MESSAGE_2_CODE: Byte = 2
private const val HANDSHAKE_MESSAGE_3_CODE: Byte = 3
private const val DATA_FRAME_CODE: Byte = 4
