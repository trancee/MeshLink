package ch.trancee.meshlink.trust

internal class TrustRecord
internal constructor(
    internal val peerIdValue: String,
    identityFingerprint: String? = null,
    identityFingerprintHexBytes: ByteArray? = null,
    internal val firstSeenAtEpochMillis: Long,
    internal val lastVerifiedAtEpochMillis: Long,
    ed25519PublicKey: ByteArray,
    x25519PublicKey: ByteArray,
) {
    private var identityFingerprintText: String? = identityFingerprint
    internal val identityFingerprintHexBytes: ByteArray =
        identityFingerprintHexBytes?.copyOf() ?: identityFingerprint?.encodeToByteArray()
        ?: error("identity fingerprint is required")
    internal val identityFingerprint: String
        get() =
            identityFingerprintText ?: identityFingerprintHexBytes.decodeToString().also {
                identityFingerprintText = it
            }
    internal val ed25519PublicKey: ByteArray = ed25519PublicKey.copyOf()
    internal val x25519PublicKey: ByteArray = x25519PublicKey.copyOf()

    internal fun withLastVerifiedAt(epochMillis: Long): TrustRecord {
        return TrustRecord(
            peerIdValue = peerIdValue,
            identityFingerprintHexBytes = identityFingerprintHexBytes,
            firstSeenAtEpochMillis = firstSeenAtEpochMillis,
            lastVerifiedAtEpochMillis = epochMillis,
            ed25519PublicKey = ed25519PublicKey,
            x25519PublicKey = x25519PublicKey,
        )
    }
}
