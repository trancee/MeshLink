package ch.trancee.meshlink.trust

internal class TrustRecord
internal constructor(
    internal val peerIdValue: String,
    internal val identityFingerprint: String,
    ed25519PublicKey: ByteArray,
    x25519PublicKey: ByteArray,
) {
    internal val ed25519PublicKey: ByteArray = ed25519PublicKey.copyOf()
    internal val x25519PublicKey: ByteArray = x25519PublicKey.copyOf()
}
