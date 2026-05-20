package ch.trancee.meshlink.trust

import ch.trancee.meshlink.identity.toBytes
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.wire.ReadBuffer
import ch.trancee.meshlink.wire.WriteBuffer

internal class TofuTrustStore internal constructor(private val secureStorage: SecureStorage) {
    internal suspend fun read(peerIdValue: String): TrustRecord? {
        val encoded = secureStorage.read(key(peerIdValue)) ?: return null
        return runCatching {
                val buffer = ReadBuffer(encoded)
                val fingerprintBytes = buffer.readBytes(buffer.readIntLittleEndian())
                val ed25519PublicKey = buffer.readBytes(buffer.readIntLittleEndian())
                val x25519PublicKey = buffer.readBytes(buffer.readIntLittleEndian())
                val firstSeenAtEpochMillis =
                    if (buffer.remaining() >= TIMESTAMP_SIZE_BYTES) {
                        buffer.readLongLittleEndian()
                    } else {
                        0L
                    }
                val lastVerifiedAtEpochMillis =
                    if (buffer.remaining() >= TIMESTAMP_SIZE_BYTES) {
                        buffer.readLongLittleEndian()
                    } else {
                        firstSeenAtEpochMillis
                    }
                TrustRecord(
                    peerIdValue = peerIdValue,
                    identityFingerprintBytes = fingerprintBytes,
                    firstSeenAtEpochMillis = firstSeenAtEpochMillis,
                    lastVerifiedAtEpochMillis = lastVerifiedAtEpochMillis,
                    ed25519PublicKey = ed25519PublicKey,
                    x25519PublicKey = x25519PublicKey,
                )
            }
            .getOrNull()
    }

    internal suspend fun write(record: TrustRecord): Unit {
        val fingerprintBytes = record.identityFingerprintBytes
        val buffer = WriteBuffer()
        buffer.writeIntLittleEndian(fingerprintBytes.size)
        buffer.writeBytes(fingerprintBytes)
        buffer.writeIntLittleEndian(record.ed25519PublicKey.size)
        buffer.writeBytes(record.ed25519PublicKey)
        buffer.writeIntLittleEndian(record.x25519PublicKey.size)
        buffer.writeBytes(record.x25519PublicKey)
        buffer.writeLongLittleEndian(record.firstSeenAtEpochMillis)
        buffer.writeLongLittleEndian(record.lastVerifiedAtEpochMillis)
        secureStorage.write(key(record.peerIdValue), buffer.toByteArray())
    }

    internal suspend fun delete(peerIdValue: String): Unit {
        secureStorage.delete(key(peerIdValue))
    }

    private fun key(peerIdValue: String): String {
        return "trust:${opaquePeerKey(peerIdValue)}"
    }

    private fun opaquePeerKey(peerIdValue: String): String {
        var hash = FNV64_OFFSET_BASIS
        val sourceBytes = peerIdValue.toBytes() ?: peerIdValue.encodeToByteArray()
        sourceBytes.forEach { byte -> hash = (hash xor (byte.toLong() and 0xFF)) * FNV64_PRIME }
        return hash.toULong().toString(radix = 16).padStart(16, '0')
    }

    private companion object {
        private const val TIMESTAMP_SIZE_BYTES: Int = 8
        private const val FNV64_OFFSET_BASIS: Long = -3750763034362895579L
        private const val FNV64_PRIME: Long = 1099511628211L
    }
}
