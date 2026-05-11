package ch.trancee.meshlink.trust

import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.wire.ReadBuffer
import ch.trancee.meshlink.wire.WriteBuffer

internal class TofuTrustStore internal constructor(private val secureStorage: SecureStorage) {
    internal suspend fun read(peerIdValue: String): TrustRecord? {
        val encoded = secureStorage.read(key(peerIdValue)) ?: return null
        return runCatching {
                val buffer = ReadBuffer(encoded)
                val storedPeerId = buffer.readBytes(buffer.readIntLittleEndian()).decodeToString()
                val fingerprint = buffer.readBytes(buffer.readIntLittleEndian()).decodeToString()
                val ed25519PublicKey = buffer.readBytes(buffer.readIntLittleEndian())
                val x25519PublicKey = buffer.readBytes(buffer.readIntLittleEndian())
                TrustRecord(
                    peerIdValue = storedPeerId,
                    identityFingerprint = fingerprint,
                    ed25519PublicKey = ed25519PublicKey,
                    x25519PublicKey = x25519PublicKey,
                )
            }
            .getOrNull()
    }

    internal suspend fun write(record: TrustRecord): Unit {
        val peerIdBytes = record.peerIdValue.encodeToByteArray()
        val fingerprintBytes = record.identityFingerprint.encodeToByteArray()
        val buffer = WriteBuffer()
        buffer.writeIntLittleEndian(peerIdBytes.size)
        buffer.writeBytes(peerIdBytes)
        buffer.writeIntLittleEndian(fingerprintBytes.size)
        buffer.writeBytes(fingerprintBytes)
        buffer.writeIntLittleEndian(record.ed25519PublicKey.size)
        buffer.writeBytes(record.ed25519PublicKey)
        buffer.writeIntLittleEndian(record.x25519PublicKey.size)
        buffer.writeBytes(record.x25519PublicKey)
        secureStorage.write(key(record.peerIdValue), buffer.toByteArray())
    }

    private fun key(peerIdValue: String): String {
        return "trust:$peerIdValue"
    }
}
