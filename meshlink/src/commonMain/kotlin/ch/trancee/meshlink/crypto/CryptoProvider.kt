package ch.trancee.meshlink.crypto

internal class X25519KeyPair
internal constructor(internal val privateKey: ByteArray, internal val publicKey: ByteArray)

internal class Ed25519KeyPair
internal constructor(internal val privateKey: ByteArray, internal val publicKey: ByteArray)

internal interface CryptoProvider {
    fun randomBytes(size: Int): ByteArray

    fun sha256(input: ByteArray): ByteArray

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    fun generateX25519KeyPair(): X25519KeyPair

    fun generateEd25519KeyPair(): Ed25519KeyPair

    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray

    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    fun chacha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray

    fun chacha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray
}
