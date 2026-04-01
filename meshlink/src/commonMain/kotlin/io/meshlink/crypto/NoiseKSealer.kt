package io.meshlink.crypto

/**
 * Noise K E2E sealer for per-message encryption.
 *
 * Sender generates ephemeral X25519 keypair per message, encrypts payload
 * with recipient's static public key using ECDH + HKDF + ChaCha20-Poly1305.
 *
 * When a session secret is provided (from Noise XX handshake), it is mixed
 * into the key derivation to provide recipient forward secrecy. Even if the
 * recipient's static key is later compromised, past messages cannot be
 * decrypted without the ephemeral session secret (held in memory only).
 *
 * Sealed layout: [32B ephemeral_pub | encrypted_payload_with_tag]
 */
class NoiseKSealer(private val crypto: CryptoProvider) {

    /**
     * Seal a message to a recipient's static public key.
     * Returns sealed bytes: [ephemeral_pub(32) | ciphertext+tag].
     *
     * @param sessionSecret Optional session key from Noise XX handshake.
     *   When present, mixed into key derivation for recipient forward secrecy.
     */
    fun seal(
        recipientStaticPub: ByteArray,
        plaintext: ByteArray,
        sessionSecret: ByteArray? = null,
    ): ByteArray {
        val ephemeral = crypto.generateX25519KeyPair()
        val sharedSecret = crypto.x25519SharedSecret(ephemeral.privateKey, recipientStaticPub)
        val ikm = if (sessionSecret != null) sharedSecret + sessionSecret else sharedSecret
        val key = crypto.hkdfSha256(ikm, byteArrayOf(), "NoiseK-seal".encodeToByteArray(), 32)
        val nonce = ByteArray(12) // zero nonce is safe because key is unique per message
        val ciphertext = crypto.aeadEncrypt(key, nonce, plaintext, ephemeral.publicKey)
        return ephemeral.publicKey + ciphertext
    }

    /**
     * Unseal a message using the recipient's static private key.
     * Returns the plaintext.
     *
     * @param sessionSecret Optional session key from Noise XX handshake.
     *   Must match what the sender used during [seal].
     * @throws Exception if authentication fails (tampered or wrong key).
     */
    fun unseal(
        recipientStaticPriv: ByteArray,
        sealed: ByteArray,
        sessionSecret: ByteArray? = null,
    ): ByteArray {
        require(sealed.size >= 48) { "Sealed data too short: ${sealed.size}" } // 32 pub + 16 min tag
        val ephemeralPub = sealed.copyOfRange(0, 32)
        val ciphertext = sealed.copyOfRange(32, sealed.size)
        val sharedSecret = crypto.x25519SharedSecret(recipientStaticPriv, ephemeralPub)
        val ikm = if (sessionSecret != null) sharedSecret + sessionSecret else sharedSecret
        val key = crypto.hkdfSha256(ikm, byteArrayOf(), "NoiseK-seal".encodeToByteArray(), 32)
        val nonce = ByteArray(12)
        return crypto.aeadDecrypt(key, nonce, ciphertext, ephemeralPub)
    }
}
