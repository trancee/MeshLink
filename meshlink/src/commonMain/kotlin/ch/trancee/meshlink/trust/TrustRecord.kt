package ch.trancee.meshlink.trust

import ch.trancee.meshlink.identity.toBytes
import ch.trancee.meshlink.identity.toHexString

internal class TrustRecord
internal constructor(
    internal val peerIdValue: String,
    identityFingerprint: String? = null,
    identityFingerprintBytes: ByteArray? = null,
    internal val firstSeenAtEpochMillis: Long,
    internal val lastVerifiedAtEpochMillis: Long,
    ed25519PublicKey: ByteArray,
    x25519PublicKey: ByteArray,
) {
    private var identityFingerprintText: String? = identityFingerprint
    internal val identityFingerprintBytes: ByteArray =
        identityFingerprintBytes?.copyOf() ?: identityFingerprint?.toBytes()
        ?: error("identity fingerprint is required")
    internal val identityFingerprint: String
        get() =
            identityFingerprintText ?: identityFingerprintBytes.toHexString().also {
                identityFingerprintText = it
            }
    internal val ed25519PublicKey: ByteArray = ed25519PublicKey.copyOf()
    internal val x25519PublicKey: ByteArray = x25519PublicKey.copyOf()

    internal fun withLastVerifiedAt(epochMillis: Long): TrustRecord {
        return TrustRecord(
            peerIdValue = peerIdValue,
            identityFingerprintBytes = identityFingerprintBytes,
            firstSeenAtEpochMillis = firstSeenAtEpochMillis,
            lastVerifiedAtEpochMillis = epochMillis,
            ed25519PublicKey = ed25519PublicKey,
            x25519PublicKey = x25519PublicKey,
        )
    }
}
