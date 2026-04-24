package ch.trancee.meshlink.crypto

/**
 * Android implementation of [CryptoProvider] backed by libsodium 1.0.20 via JNI.
 *
 * All cryptographic operations are thin delegations to [SodiumJni], which is the sole entry point
 * into the native `libmeshlink_sodium.so` library. The library is loaded and initialised once when
 * [SodiumJni] is first referenced (its `init` block calls `sodium_init()`).
 *
 * Thread safety: libsodium is fully thread-safe once `sodium_init()` has returned successfully.
 */
internal class AndroidCryptoProvider : CryptoProvider {
    override fun generateEd25519KeyPair(): KeyPair {
        val parts = SodiumJni.generateEd25519KeyPair()
        return KeyPair(publicKey = parts[0], privateKey = parts[1])
    }

    override fun sign(privateKey: ByteArray, message: ByteArray): ByteArray =
        SodiumJni.sign(privateKey, message)

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        SodiumJni.verify(publicKey, message, signature)

    override fun generateX25519KeyPair(): KeyPair {
        val parts = SodiumJni.generateX25519KeyPair()
        return KeyPair(publicKey = parts[0], privateKey = parts[1])
    }

    override fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        SodiumJni.x25519SharedSecret(privateKey, publicKey)

    override fun aeadEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray = SodiumJni.aeadEncrypt(key, nonce, plaintext, aad)

    override fun aeadDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray = SodiumJni.aeadDecrypt(key, nonce, ciphertext, aad)

    override fun sha256(input: ByteArray): ByteArray = SodiumJni.sha256(input)

    override fun hkdfSha256(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray = SodiumJni.hkdfSha256(salt, ikm, info, length)
}

actual fun createCryptoProvider(): CryptoProvider = AndroidCryptoProvider()
