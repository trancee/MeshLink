package io.meshlink.crypto

/**
 * Noise K E2E sealer for per-message encryption.
 *
 * Sender generates ephemeral X25519 keypair per message, encrypts payload
 * with recipient's static public key using ECDH + HKDF + AES-GCM.
 *
 * Sealed layout: [32B ephemeral_pub | encrypted_payload_with_tag]
 *
 * This provides forward secrecy per message (ephemeral keys are discarded).
 */
class NoiseKSealer(private val crypto: CryptoProvider) {

    /**
     * Seal a message to a recipient's static public key.
     * Returns sealed bytes: [ephemeral_pub(32) | ciphertext+tag].
     */
    fun seal(recipientStaticPub: ByteArray, plaintext: ByteArray): ByteArray {
        val ephemeral = crypto.generateX25519KeyPair()
        val sharedSecret = crypto.x25519SharedSecret(ephemeral.privateKey, recipientStaticPub)
        val key = crypto.hkdfSha256(sharedSecret, byteArrayOf(), "NoiseK-seal".encodeToByteArray(), 32)
        val nonce = ByteArray(12) // zero nonce is safe because key is unique per message
        val ciphertext = crypto.aesgcmEncrypt(key, nonce, plaintext, ephemeral.publicKey)
        return ephemeral.publicKey + ciphertext
    }

    /**
     * Unseal a message using the recipient's static private key.
     * Returns the plaintext.
     * @throws Exception if authentication fails (tampered or wrong key).
     */
    fun unseal(recipientStaticPriv: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size >= 48) { "Sealed data too short: ${sealed.size}" } // 32 pub + 16 min tag
        val ephemeralPub = sealed.copyOfRange(0, 32)
        val ciphertext = sealed.copyOfRange(32, sealed.size)
        val sharedSecret = crypto.x25519SharedSecret(recipientStaticPriv, ephemeralPub)
        val key = crypto.hkdfSha256(sharedSecret, byteArrayOf(), "NoiseK-seal".encodeToByteArray(), 32)
        val nonce = ByteArray(12)
        return crypto.aesgcmDecrypt(key, nonce, ciphertext, ephemeralPub)
    }
}
