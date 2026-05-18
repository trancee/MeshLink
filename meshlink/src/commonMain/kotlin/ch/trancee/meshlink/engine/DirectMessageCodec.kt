package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.ReadBuffer
import ch.trancee.meshlink.wire.WriteBuffer

internal class DirectMessageEnvelope
internal constructor(
    internal val senderPeerId: PeerId,
    senderFingerprintHexBytes: ByteArray,
    senderEd25519PublicKey: ByteArray,
    senderX25519PublicKey: ByteArray,
    ciphertext: ByteArray,
) {
    private val senderPeerIdBytes: ByteArray = senderPeerId.value.encodeToByteArray()
    internal val senderFingerprintHexBytes: ByteArray = senderFingerprintHexBytes.copyOf()
    internal val senderEd25519PublicKey: ByteArray = senderEd25519PublicKey.copyOf()
    internal val senderX25519PublicKey: ByteArray = senderX25519PublicKey.copyOf()
    internal val ciphertext: ByteArray = ciphertext.copyOf()

    internal fun encode(): ByteArray {
        val buffer = WriteBuffer()
        buffer.writeIntLittleEndian(senderPeerIdBytes.size)
        buffer.writeBytes(senderPeerIdBytes)
        buffer.writeIntLittleEndian(senderFingerprintHexBytes.size)
        buffer.writeBytes(senderFingerprintHexBytes)
        buffer.writeIntLittleEndian(senderEd25519PublicKey.size)
        buffer.writeBytes(senderEd25519PublicKey)
        buffer.writeIntLittleEndian(senderX25519PublicKey.size)
        buffer.writeBytes(senderX25519PublicKey)
        buffer.writeIntLittleEndian(ciphertext.size)
        buffer.writeBytes(ciphertext)
        return buffer.toByteArray()
    }

    internal companion object {
        internal fun decode(bytes: ByteArray): DirectMessageEnvelope {
            val buffer = ReadBuffer(bytes)
            val senderBytes = buffer.readBytes(buffer.readIntLittleEndian())
            val fingerprintBytes = buffer.readBytes(buffer.readIntLittleEndian())
            val senderEd25519PublicKey = buffer.readBytes(buffer.readIntLittleEndian())
            val senderX25519PublicKey = buffer.readBytes(buffer.readIntLittleEndian())
            val ciphertext = buffer.readBytes(buffer.readIntLittleEndian())
            return DirectMessageEnvelope(
                senderPeerId = PeerId(senderBytes.decodeToString()),
                senderFingerprintHexBytes = fingerprintBytes,
                senderEd25519PublicKey = senderEd25519PublicKey,
                senderX25519PublicKey = senderX25519PublicKey,
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
    HANDSHAKE_MESSAGE_1(1),
    HANDSHAKE_MESSAGE_2(2),
    HANDSHAKE_MESSAGE_3(3),
    DATA(4);

    internal companion object {
        internal fun fromCode(code: Byte): DirectWireFrameType {
            return entries.firstOrNull { it.code == code }
                ?: throw MeshLinkException.TransportFailure(
                    "Unknown direct wire frame type ${code.toInt() and 0xFF}"
                )
        }
    }
}
