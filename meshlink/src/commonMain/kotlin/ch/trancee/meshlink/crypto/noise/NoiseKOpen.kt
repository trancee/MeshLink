package ch.trancee.meshlink.crypto.noise

import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.KeyPair

private const val DH_LEN = 32
private const val TAG_LEN = 16
private const val KEY_LEN = 32
private const val NONCE_LEN = 12
private const val HKDF_OUTPUT_LEN = 44

private val NOISE_K_INFO = "MeshLink-v1-NoiseK-seal".encodeToByteArray()

/**
 * Noise K open: decrypts a sealed message produced by [noiseKSeal].
 *
 * Reverses the Noise K seal per spec §4 (05-security-encryption.md):
 * 1. Parse first 32 bytes as the sender's ephemeral public key.
 * 2. DH(rs, e) — static recipient × ephemeral sender.
 * 3. DH(rs, s) — static recipient × static sender (cacheable via [dhCache]).
 * 4. IKM = DH(rs,e) ‖ DH(rs,s) ‖ [sessionSecret].
 * 5. HKDF-SHA-256(salt=∅, ikm=IKM, info=`"MeshLink-v1-NoiseK-seal"`) → 44 bytes.
 * 6. ChaCha20-Poly1305 decryption; AAD = ephemeral public key.
 *
 * @param crypto Platform crypto provider.
 * @param recipientStaticKeyPair The recipient's static X25519 key pair.
 * @param senderStaticPublicKey The sender's static X25519 public key (32 bytes).
 * @param sealed The sealed bytes produced by [noiseKSeal].
 * @param sessionSecret Optional session secret from Noise XX; must match what was used to seal.
 * @param dhCache Optional cache for the DH(rs, s) result; recomputes on every call when null.
 * @return The decrypted plaintext.
 * @throws IllegalArgumentException if [sealed] is shorter than 48 bytes (32B ephemeral + 16B tag).
 * @throws IllegalStateException if the AEAD authentication tag verification fails.
 */
internal fun noiseKOpen(
    crypto: CryptoProvider,
    recipientStaticKeyPair: KeyPair,
    senderStaticPublicKey: ByteArray,
    sealed: ByteArray,
    sessionSecret: ByteArray = ByteArray(0),
    dhCache: DhCache? = null,
): ByteArray {
    if (sealed.size < DH_LEN + TAG_LEN) {
        throw IllegalArgumentException("sealed size ${sealed.size} < minimum ${DH_LEN + TAG_LEN}")
    }
    val ephemeralPubKey = sealed.copyOfRange(0, DH_LEN)
    val ciphertext = sealed.copyOfRange(DH_LEN, sealed.size)
    val dhER = crypto.x25519SharedSecret(recipientStaticKeyPair.privateKey, ephemeralPubKey)
    val dhSS =
        if (dhCache != null)
            dhCache.getOrCompute(crypto, recipientStaticKeyPair.privateKey, senderStaticPublicKey)
        else crypto.x25519SharedSecret(recipientStaticKeyPair.privateKey, senderStaticPublicKey)
    val ikm = dhER + dhSS + sessionSecret
    val hkdfOut = crypto.hkdfSha256(ByteArray(0), ikm, NOISE_K_INFO, HKDF_OUTPUT_LEN)
    val key = hkdfOut.copyOfRange(0, KEY_LEN)
    val nonce = hkdfOut.copyOfRange(KEY_LEN, KEY_LEN + NONCE_LEN)
    return crypto.aeadDecrypt(key, nonce, ciphertext, ephemeralPubKey)
}
