package ch.trancee.meshlink.internal

import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlin.random.Random

/**
 * The real provider with every fresh random value drawn from [seed], so one seed replays one mesh
 * byte for byte. The primitives stay real; only their randomness is fixed (ADR 0017).
 */
internal class SeededCrypto(
    seed: Long,
) : CryptoProvider by SodiumCryptoProvider {
    private val random = Random(seed)
    private val sodium = SodiumCryptoProvider

    override fun randomBytes(count: Int): ByteArray = random.nextBytes(count)

    override fun ed25519KeyPair(): Ed25519KeyPair {
        val seed = randomBytes(CryptoProvider.ED25519_SEED_LENGTH)
        return sodium.ed25519KeyPair(seed)
    }

    override fun x25519KeyPair(): KeyPair = sodium.x25519KeyPair(randomBytes(CryptoProvider.X25519_KEY_LENGTH))

    override fun mlKem768KeyPair(): KeyPair = sodium.mlKem768KeyPair(randomBytes(CryptoProvider.ML_KEM_768_SEED_LENGTH))

    override fun mlKem768Encapsulate(publicKey: ByteArray): CryptoResult<KemEncapsulation> =
        sodium.mlKem768Encapsulate(
            publicKey,
            randomBytes(CryptoProvider.ML_KEM_768_ENCAPSULATION_SEED_LENGTH),
        )

    override fun xWingKeyPair(): KeyPair = sodium.xWingKeyPair(randomBytes(CryptoProvider.X_WING_SEED_LENGTH))

    override fun xWingEncapsulate(publicKey: ByteArray): CryptoResult<KemEncapsulation> =
        sodium.xWingEncapsulate(
            publicKey,
            randomBytes(CryptoProvider.X_WING_ENCAPSULATION_SEED_LENGTH),
        )
}
