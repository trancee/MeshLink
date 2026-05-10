package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.wire.ReadBuffer
import ch.trancee.meshlink.wire.WriteBuffer

internal class DirectMessageEnvelope internal constructor(
    internal val senderPeerId: PeerId,
    internal val senderFingerprint: String,
    ciphertext: ByteArray,
) {
    internal val ciphertext: ByteArray = ciphertext.copyOf()

    internal fun encode(): ByteArray {
        val senderBytes = senderPeerId.value.encodeToByteArray()
        val fingerprintBytes = senderFingerprint.encodeToByteArray()
        val buffer = WriteBuffer()
        buffer.writeIntLittleEndian(senderBytes.size)
        buffer.writeBytes(senderBytes)
        buffer.writeIntLittleEndian(fingerprintBytes.size)
        buffer.writeBytes(fingerprintBytes)
        buffer.writeIntLittleEndian(ciphertext.size)
        buffer.writeBytes(ciphertext)
        return buffer.toByteArray()
    }

    internal companion object {
        internal fun decode(bytes: ByteArray): DirectMessageEnvelope {
            val buffer = ReadBuffer(bytes)
            val senderBytes = buffer.readBytes(buffer.readIntLittleEndian())
            val fingerprintBytes = buffer.readBytes(buffer.readIntLittleEndian())
            val ciphertext = buffer.readBytes(buffer.readIntLittleEndian())
            return DirectMessageEnvelope(
                senderPeerId = PeerId(senderBytes.decodeToString()),
                senderFingerprint = fingerprintBytes.decodeToString(),
                ciphertext = ciphertext,
            )
        }
    }
}
