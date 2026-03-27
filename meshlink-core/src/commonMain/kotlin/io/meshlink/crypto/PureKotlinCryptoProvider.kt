package io.meshlink.crypto

/**
 * Pure Kotlin implementation of [CryptoProvider] with zero platform dependencies.
 * Used as the crypto backend on iOS (all versions) and Android API 26-32
 * where JCA lacks Ed25519/X25519 support.
 *
 * Delegates to:
 * - [Ed25519] for signing/verification
 * - [X25519] for key agreement
 * - [ChaCha20Poly1305] for AEAD encryption
 * - [Sha256] for hashing
 * - [HkdfSha256] for key derivation
 */
internal class PureKotlinCryptoProvider : CryptoProvider {

    override fun generateEd25519KeyPair(): CryptoKeyPair = Ed25519.generateKeyPair()

    override fun sign(privateKey: ByteArray, data: ByteArray): ByteArray =
        Ed25519.sign(privateKey, data)

    override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        Ed25519.verify(publicKey, data, signature)

    override fun generateX25519KeyPair(): CryptoKeyPair = X25519.generateKeyPair()

    override fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        X25519.sharedSecret(privateKey, publicKey)

    override fun aeadEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        ChaCha20Poly1305.encrypt(key, nonce, plaintext, aad)

    override fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        ChaCha20Poly1305.decrypt(key, nonce, ciphertext, aad)

    override fun sha256(data: ByteArray): ByteArray = Sha256.hash(data)

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        HkdfSha256.derive(ikm, salt, info, length)
}
