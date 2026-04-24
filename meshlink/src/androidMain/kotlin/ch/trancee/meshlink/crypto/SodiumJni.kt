package ch.trancee.meshlink.crypto

/**
 * Thin JNI binding to libmeshlink_sodium.so (libsodium 1.0.20).
 *
 * This object is the sole entry point into the native library. It mirrors the 10 functions exported
 * by meshlink_sodium.c. All methods are `@JvmStatic` so the JNI mangling follows the
 * `Java_ch_trancee_meshlink_crypto_SodiumJni_<method>` pattern.
 *
 * Initialisation: `sodium_init()` is called once when the class is first loaded via the companion
 * object's `init` block. Subsequent calls are no-ops in libsodium.
 */
internal object SodiumJni {
    init {
        System.loadLibrary("meshlink_sodium")
        check(sodiumInit() >= 0) { "sodium_init() failed" }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────
    @JvmStatic external fun sodiumInit(): Int

    // ── Ed25519 (RFC 8032) ─────────────────────────────────────────────────
    /** Returns [pubKey (32 bytes), privKey (64 bytes)]. */
    @JvmStatic external fun generateEd25519KeyPair(): Array<ByteArray>

    /** Returns the 64-byte detached Ed25519 signature. */
    @JvmStatic external fun sign(privateKey: ByteArray, message: ByteArray): ByteArray

    /** Returns true if the signature is valid; false otherwise. */
    @JvmStatic
    external fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    // ── X25519 (RFC 7748) ──────────────────────────────────────────────────
    /** Returns [pubKey (32 bytes), privKey (32 bytes)]. */
    @JvmStatic external fun generateX25519KeyPair(): Array<ByteArray>

    /** Returns the 32-byte X25519 shared secret. */
    @JvmStatic
    external fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray

    // ── ChaCha20-Poly1305 IETF AEAD (RFC 8439) ────────────────────────────
    /** Returns ciphertext + 16-byte authentication tag. */
    @JvmStatic
    external fun aeadEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray

    /**
     * Returns the decrypted plaintext.
     *
     * @throws IllegalStateException if the authentication tag verification fails.
     */
    @JvmStatic
    external fun aeadDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray

    // ── SHA-256 (RFC 6234) ─────────────────────────────────────────────────
    /** Returns the 32-byte SHA-256 digest. */
    @JvmStatic external fun sha256(input: ByteArray): ByteArray

    // ── HKDF-SHA-256 (RFC 5869) ────────────────────────────────────────────
    /** Returns derived key material of the requested [length] (max 8160 bytes). */
    @JvmStatic
    external fun hkdfSha256(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray
}
