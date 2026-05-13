package ch.trancee.meshlink.trust

internal class TrustRecord
internal constructor(
    internal val peerIdValue: String,
    internal val identityFingerprint: String,
    internal val firstSeenAtEpochMillis: Long,
    internal val lastVerifiedAtEpochMillis: Long,
    ed25519PublicKey: ByteArray,
    x25519PublicKey: ByteArray,
) {
    internal val ed25519PublicKey: ByteArray = ed25519PublicKey.copyOf()
    internal val x25519PublicKey: ByteArray = x25519PublicKey.copyOf()

    internal fun withLastVerifiedAt(epochMillis: Long): TrustRecord {
        return TrustRecord(
            peerIdValue = peerIdValue,
            identityFingerprint = identityFingerprint,
            firstSeenAtEpochMillis = firstSeenAtEpochMillis,
            lastVerifiedAtEpochMillis = epochMillis,
            ed25519PublicKey = ed25519PublicKey,
            x25519PublicKey = x25519PublicKey,
        )
    }
}
