package ch.trancee.meshlink.crypto

internal class JvmCryptoProvider : CryptoProvider {
    override fun generateEd25519KeyPair(): KeyPair =
        throw NotImplementedError("JVM stub — use Android or iOS for real crypto")

    override fun sign(privateKey: ByteArray, message: ByteArray): ByteArray =
        throw NotImplementedError("JVM stub — use Android or iOS for real crypto")

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        throw NotImplementedError("JVM stub — use Android or iOS for real crypto")

    override fun generateX25519KeyPair(): KeyPair =
        throw NotImplementedError("JVM stub — use Android or iOS for real crypto")

    override fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        throw NotImplementedError("JVM stub — use Android or iOS for real crypto")

    override fun aeadEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray = throw NotImplementedError("JVM stub — use Android or iOS for real crypto")

    override fun aeadDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray = throw NotImplementedError("JVM stub — use Android or iOS for real crypto")

    override fun sha256(input: ByteArray): ByteArray =
        throw NotImplementedError("JVM stub — use Android or iOS for real crypto")

    override fun hkdfSha256(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray = throw NotImplementedError("JVM stub — use Android or iOS for real crypto")
}

actual fun createCryptoProvider(): CryptoProvider = JvmCryptoProvider()
