package ch.trancee.meshlink.crypto.noise

import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.crypto.KeyPair

private const val DH_LEN = 32
private const val KEY_LEN = 32
private const val NONCE_LEN = 12
private const val HKDF_OUTPUT_LEN = 44

private val NOISE_K_INFO = "MeshLink-v1-NoiseK-seal".encodeToByteArray()

/**
 * Noise K seal: encrypts [plaintext] from [senderStaticKeyPair] to [recipientStaticPublicKey].
 *
 * Implements the full Noise K seal per spec §4 (05-security-encryption.md):
 * 1. Fresh ephemeral X25519 keypair — per-message forward secrecy.
 * 2. DH(e, rs) — ephemeral sender × static recipient.
 * 3. DH(s, rs) — static sender × static recipient (cacheable via [dhCache]).
 * 4. IKM = DH(e,rs) ‖ DH(s,rs) ‖ [sessionSecret].
 * 5. HKDF-SHA-256(salt=∅, ikm=IKM, info=`"MeshLink-v1-NoiseK-seal"`) → 44 bytes.
 * 6. ChaCha20-Poly1305 encryption; AAD = ephemeral public key.
 * 7. Output: `ephemeralPubkey(32B) ‖ ciphertext(N + 16B AEAD tag)`.
 *
 * @param crypto Platform crypto provider.
 * @param senderStaticKeyPair The sender's static X25519 key pair.
 * @param recipientStaticPublicKey The recipient's static X25519 public key (32 bytes).
 * @param plaintext The payload to encrypt.
 * @param sessionSecret Optional session secret from Noise XX for additional forward secrecy.
 * @param dhCache Optional cache for the DH(s, rs) result; recomputes on every call when null.
 * @return Sealed bytes: `ephemeralPubkey(32B) ‖ ciphertext(N + 16B AEAD tag)`.
 */
internal fun noiseKSeal(
    crypto: CryptoProvider,
    senderStaticKeyPair: KeyPair,
    recipientStaticPublicKey: ByteArray,
    plaintext: ByteArray,
    sessionSecret: ByteArray = ByteArray(0),
    dhCache: DhCache? = null,
): ByteArray {
    val ephemeral = crypto.generateX25519KeyPair()
    val dhER = crypto.x25519SharedSecret(ephemeral.privateKey, recipientStaticPublicKey)
    val dhSR =
        dhCache?.getOrCompute(crypto, senderStaticKeyPair.privateKey, recipientStaticPublicKey)
            ?: crypto.x25519SharedSecret(senderStaticKeyPair.privateKey, recipientStaticPublicKey)
    val ikm = dhER + dhSR + sessionSecret
    val hkdfOut = crypto.hkdfSha256(ByteArray(0), ikm, NOISE_K_INFO, HKDF_OUTPUT_LEN)
    val key = hkdfOut.copyOfRange(0, KEY_LEN)
    val nonce = hkdfOut.copyOfRange(KEY_LEN, KEY_LEN + NONCE_LEN)
    val ciphertext = crypto.aeadEncrypt(key, nonce, plaintext, ephemeral.publicKey)
    return ephemeral.publicKey + ciphertext
}
