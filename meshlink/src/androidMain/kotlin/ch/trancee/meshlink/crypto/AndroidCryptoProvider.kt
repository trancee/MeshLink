package ch.trancee.meshlink.crypto

internal class AndroidCryptoProvider : CryptoProvider {
    override fun generateEd25519KeyPair(): KeyPair =
        throw NotImplementedError("Android implementation provided in S02 via JNI")

    override fun sign(privateKey: ByteArray, message: ByteArray): ByteArray =
        throw NotImplementedError("Android implementation provided in S02 via JNI")

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        throw NotImplementedError("Android implementation provided in S02 via JNI")

    override fun generateX25519KeyPair(): KeyPair =
        throw NotImplementedError("Android implementation provided in S02 via JNI")

    override fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        throw NotImplementedError("Android implementation provided in S02 via JNI")

    override fun aeadEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray = throw NotImplementedError("Android implementation provided in S02 via JNI")

    override fun aeadDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray = throw NotImplementedError("Android implementation provided in S02 via JNI")

    override fun sha256(input: ByteArray): ByteArray =
        throw NotImplementedError("Android implementation provided in S02 via JNI")

    override fun hkdfSha256(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray = throw NotImplementedError("Android implementation provided in S02 via JNI")
}

actual fun createCryptoProvider(): CryptoProvider = AndroidCryptoProvider()
