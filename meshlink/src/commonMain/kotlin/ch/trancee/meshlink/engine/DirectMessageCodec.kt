package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.ReadBuffer
import ch.trancee.meshlink.wire.WriteBuffer

internal class DirectMessageEnvelope internal constructor(
    internal val senderPeerId: PeerId,
    internal val senderFingerprint: String,
    senderEd25519PublicKey: ByteArray,
    senderX25519PublicKey: ByteArray,
    ciphertext: ByteArray,
) {
    internal val senderEd25519PublicKey: ByteArray = senderEd25519PublicKey.copyOf()
    internal val senderX25519PublicKey: ByteArray = senderX25519PublicKey.copyOf()
    internal val ciphertext: ByteArray = ciphertext.copyOf()

    internal fun encode(): ByteArray {
        val senderBytes = senderPeerId.value.encodeToByteArray()
        val fingerprintBytes = senderFingerprint.encodeToByteArray()
        val buffer = WriteBuffer()
        buffer.writeIntLittleEndian(senderBytes.size)
        buffer.writeBytes(senderBytes)
        buffer.writeIntLittleEndian(fingerprintBytes.size)
        buffer.writeBytes(fingerprintBytes)
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
                senderFingerprint = fingerprintBytes.decodeToString(),
                senderEd25519PublicKey = senderEd25519PublicKey,
                senderX25519PublicKey = senderX25519PublicKey,
                ciphertext = ciphertext,
            )
        }
    }
}
