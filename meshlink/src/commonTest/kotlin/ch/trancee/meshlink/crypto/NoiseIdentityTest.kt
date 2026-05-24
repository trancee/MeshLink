package ch.trancee.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class NoiseIdentityTest {
    @Test
    fun `generate delegates to the provider for both key pairs`() {
        // Arrange
        val provider = RecordingCryptoProvider()

        // Act
        val identity = NoiseIdentity.generate(provider)

        // Assert
        assertEquals(1, provider.generatedEd25519Count)
        assertEquals(1, provider.generatedX25519Count)
        assertContentEquals(provider.ed25519KeyPair.privateKey, identity.ed25519KeyPair.privateKey)
        assertContentEquals(provider.ed25519KeyPair.publicKey, identity.ed25519KeyPair.publicKey)
        assertContentEquals(provider.x25519KeyPair.privateKey, identity.x25519KeyPair.privateKey)
        assertContentEquals(provider.x25519KeyPair.publicKey, identity.x25519KeyPair.publicKey)
    }
}

private class RecordingCryptoProvider : CryptoProvider {
    val ed25519KeyPair =
        Ed25519KeyPair(
            privateKey = ByteArray(32) { index -> (index + 1).toByte() },
            publicKey = ByteArray(32) { index -> (index + 33).toByte() },
        )
    val x25519KeyPair =
        X25519KeyPair(
            privateKey = ByteArray(32) { index -> (index + 65).toByte() },
            publicKey = ByteArray(32) { index -> (index + 97).toByte() },
        )
    var generatedEd25519Count: Int = 0
    var generatedX25519Count: Int = 0

    override fun randomBytes(size: Int): ByteArray = error("unused")

    override fun sha256(input: ByteArray): ByteArray = error("unused")

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = error("unused")

    override fun generateX25519KeyPair(): X25519KeyPair {
        generatedX25519Count += 1
        return x25519KeyPair
    }

    override fun generateEd25519KeyPair(): Ed25519KeyPair {
        generatedEd25519Count += 1
        return ed25519KeyPair
    }

    override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray = error("unused")

    override fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray = error("unused")

    override fun ed25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean = error("unused")

    override fun chacha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray,
    ): ByteArray = error("unused")

    override fun chacha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray = error("unused")
}
