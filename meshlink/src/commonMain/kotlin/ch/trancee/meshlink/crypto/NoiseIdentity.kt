package ch.trancee.meshlink.crypto

internal class NoiseIdentity internal constructor(
    internal val ed25519KeyPair: Ed25519KeyPair,
    internal val x25519KeyPair: X25519KeyPair,
) {
    internal companion object {
        internal fun generate(provider: CryptoProvider): NoiseIdentity {
            return NoiseIdentity(
                ed25519KeyPair = provider.generateEd25519KeyPair(),
                x25519KeyPair = provider.generateX25519KeyPair(),
            )
        }
    }
}
