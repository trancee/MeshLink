package ch.trancee.meshlink.crypto

internal class IosCryptoProvider : CryptoProvider {
    override fun generateEd25519KeyPair(): KeyPair =
        throw NotImplementedError("iOS implementation provided in S03 via cinterop")

    override fun sign(privateKey: ByteArray, message: ByteArray): ByteArray =
        throw NotImplementedError("iOS implementation provided in S03 via cinterop")

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        throw NotImplementedError("iOS implementation provided in S03 via cinterop")

    override fun generateX25519KeyPair(): KeyPair =
        throw NotImplementedError("iOS implementation provided in S03 via cinterop")

    override fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        throw NotImplementedError("iOS implementation provided in S03 via cinterop")

    override fun aeadEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray = throw NotImplementedError("iOS implementation provided in S03 via cinterop")

    override fun aeadDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray = throw NotImplementedError("iOS implementation provided in S03 via cinterop")

    override fun sha256(input: ByteArray): ByteArray =
        throw NotImplementedError("iOS implementation provided in S03 via cinterop")

    override fun hkdfSha256(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray = throw NotImplementedError("iOS implementation provided in S03 via cinterop")
}

actual fun createCryptoProvider(): CryptoProvider = IosCryptoProvider()
