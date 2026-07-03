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
                // Records written after the opaque storage key collision fix carry the original
                // peer id alongside the record. Because `key()` reduces the peer id to a 64-bit
                // hash, two different peer ids could theoretically collide on the same storage
                // slot. Verifying the embedded peer id guards against silently returning (and
                // later overwriting) another peer's trust record on collision. Records written
                // before this fix have no embedded peer id and are trusted as-is for backward
                // compatibility.
                val storedPeerIdValue =
                    if (buffer.remaining() > 0) {
                        buffer.readBytes(buffer.readIntLittleEndian()).decodeToString()
                    } else {
                        peerIdValue
                    }
                if (storedPeerIdValue != peerIdValue) {
                    return null
                }
                TrustRecord(
                    peerIdValue = peerIdValue,
                    identityFingerprintBytes = fingerprintBytes,
                    firstSeenAtEpochMillis = firstSeenAtEpochMillis,
                    lastVerifiedAtEpochMillis = lastVerifiedAtEpochMillis,
                    publicKeys =
                        TrustPublicKeys(
                            ed25519PublicKey = ed25519PublicKey,
                            x25519PublicKey = x25519PublicKey,
                        ),
                )
            }
            .getOrNull()
    }

    internal suspend fun write(record: TrustRecord): Unit {
        val fingerprintBytes = record.identityFingerprintBytes
        val peerIdBytes = record.peerIdValue.encodeToByteArray()
        val buffer = WriteBuffer()
        buffer.writeIntLittleEndian(fingerprintBytes.size)
        buffer.writeBytes(fingerprintBytes)
        buffer.writeIntLittleEndian(record.ed25519PublicKey.size)
        buffer.writeBytes(record.ed25519PublicKey)
        buffer.writeIntLittleEndian(record.x25519PublicKey.size)
        buffer.writeBytes(record.x25519PublicKey)
        buffer.writeLongLittleEndian(record.firstSeenAtEpochMillis)
        buffer.writeLongLittleEndian(record.lastVerifiedAtEpochMillis)
        buffer.writeIntLittleEndian(peerIdBytes.size)
        buffer.writeBytes(peerIdBytes)
        secureStorage.write(key(record.peerIdValue), buffer.toByteArray())
    }

    internal suspend fun delete(peerIdValue: String): Unit {
        secureStorage.delete(key(peerIdValue))
    }

    private fun key(peerIdValue: String): String {
        return "trust:${opaquePeerKey(peerIdValue)}"
    }

    /**
     * Exposes the storage key derivation for tests exercising opaque-key collisions. Not for
     * production use outside this module.
     */
    internal fun keyForTest(peerIdValue: String): String {
        return key(peerIdValue)
    }

    private fun opaquePeerKey(peerIdValue: String): String {
        var hash = FNV64_OFFSET_BASIS
        val sourceBytes = peerIdValue.toBytes() ?: peerIdValue.encodeToByteArray()
        sourceBytes.forEach { byte ->
            hash = (hash xor (byte.toLong() and BYTE_MASK.toLong())) * FNV64_PRIME
        }
        return hash.toULong().toString(radix = HEX_RADIX).padStart(FNV64_HEX_LENGTH, '0')
    }

    private companion object {
        private const val BYTE_MASK: Int = 0xFF
        private const val HEX_RADIX: Int = 16
        private const val FNV64_HEX_LENGTH: Int = 16
        private const val TIMESTAMP_SIZE_BYTES: Int = 8
        private const val FNV64_OFFSET_BASIS: Long = -3750763034362895579L
        private const val FNV64_PRIME: Long = 1099511628211L
    }
}
