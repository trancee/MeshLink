package ch.trancee.meshlink.crypto

/**
 * Platform-agnostic cryptographic operations backed by libsodium.
 *
 * All platform implementations (Android JNI via S02, iOS cinterop via S03) must implement every
 * operation defined here. Obtain an instance via [createCryptoProvider].
 *
 * All key and nonce parameters are raw byte arrays. See each function for expected sizes.
 */
internal interface CryptoProvider {
    /**
     * Generates a new Ed25519 signing key pair (RFC 8032).
     *
     * @return [KeyPair] with a 32-byte compressed public key and a 64-byte private key.
     */
    fun generateEd25519KeyPair(): KeyPair

    /**
     * Signs [message] with [privateKey] using Ed25519 (RFC 8032).
     *
     * @param privateKey The 64-byte Ed25519 private key.
     * @param message The message bytes to sign.
     * @return A 64-byte Ed25519 signature.
     */
    fun sign(privateKey: ByteArray, message: ByteArray): ByteArray

    /**
     * Verifies an Ed25519 [signature] over [message] using [publicKey] (RFC 8032).
     *
     * @param publicKey The 32-byte Ed25519 public key.
     * @param message The message bytes that were signed.
     * @param signature The 64-byte signature to verify.
     * @return `true` if the signature is valid, `false` otherwise.
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    /**
     * Generates a new X25519 Diffie-Hellman key pair (RFC 7748).
     *
     * @return [KeyPair] with a 32-byte public key and a 32-byte private scalar.
     */
    fun generateX25519KeyPair(): KeyPair

    /**
     * Computes an X25519 Diffie-Hellman shared secret (RFC 7748).
     *
     * @param privateKey The 32-byte local X25519 private scalar.
     * @param publicKey The 32-byte remote X25519 public key.
     * @return The 32-byte shared secret.
     */
    fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray

    /**
     * Encrypts [plaintext] using ChaCha20-Poly1305 IETF AEAD (RFC 8439).
     *
     * @param key The 32-byte symmetric key.
     * @param nonce The 12-byte nonce (must be unique per key).
     * @param plaintext The data to encrypt.
     * @param aad Additional authenticated data (authenticated but not encrypted).
     * @return The ciphertext concatenated with the 16-byte Poly1305 authentication tag.
     */
    fun aeadEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray

    /**
     * Decrypts [ciphertext] using ChaCha20-Poly1305 IETF AEAD (RFC 8439).
     *
     * @param key The 32-byte symmetric key.
     * @param nonce The 12-byte nonce used during encryption.
     * @param ciphertext The ciphertext concatenated with the 16-byte authentication tag.
     * @param aad Additional authenticated data that must match what was used during encryption.
     * @return The decrypted plaintext.
     * @throws IllegalStateException if the authentication tag verification fails.
     */
    fun aeadDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray

    /**
     * Computes the SHA-256 hash of [input] (RFC 6234).
     *
     * @param input The data to hash.
     * @return The 32-byte SHA-256 digest.
     */
    fun sha256(input: ByteArray): ByteArray

    /**
     * Computes HMAC-SHA-256 (RFC 2104) of [data] under [key].
     *
     * @param key The HMAC key (arbitrary length; keys shorter than 32 bytes are valid per RFC
     *   2104).
     * @param data The message to authenticate.
     * @return A 32-byte HMAC-SHA-256 authentication tag.
     */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /**
     * Derives key material using HKDF-SHA-256 (RFC 5869).
     *
     * @param salt Optional salt; pass an empty array if no salt is available.
     * @param ikm Input keying material.
     * @param info Context and application-specific information (domain separator).
     * @param length Desired output length in bytes (max 255 × 32 = 8160 bytes).
     * @return Derived key material of the requested [length].
     */
    fun hkdfSha256(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray
}
