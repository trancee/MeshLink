package ch.trancee.meshlink.platform.android

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidCryptoProviderTest {
    private val provider = AndroidCryptoProvider()

    @Test
    fun `x25519 shared secret is symmetric and generated keys stay raw 32 byte values`() {
        // Arrange
        val alice = provider.generateX25519KeyPair()
        val bob = provider.generateX25519KeyPair()

        // Act
        val aliceShared = provider.x25519(alice.privateKey, bob.publicKey)
        val bobShared = provider.x25519(bob.privateKey, alice.publicKey)

        // Assert
        assertEquals(32, alice.privateKey.size)
        assertEquals(32, alice.publicKey.size)
        assertEquals(32, bob.privateKey.size)
        assertEquals(32, bob.publicKey.size)
        assertEquals(32, aliceShared.size)
        assertContentEquals(aliceShared, bobShared)
        assertTrue(aliceShared.any { byte -> byte != 0.toByte() })
    }

    @Test
    fun `chacha20 poly1305 round trips`() {
        // Arrange
        val key = provider.randomBytes(32)
        val nonce = provider.randomBytes(12)
        val aad = "meshlink-aad".encodeToByteArray()
        val plaintext = "hello secure mesh".encodeToByteArray()

        // Act
        val ciphertext = provider.chacha20Poly1305Seal(key, nonce, aad, plaintext)
        val decrypted = provider.chacha20Poly1305Open(key, nonce, aad, ciphertext)

        // Assert
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `ed25519 signatures verify and generated keys stay raw 32 byte values`() {
        // Arrange
        val keyPair = provider.generateEd25519KeyPair()
        val message = "trust me".encodeToByteArray()

        // Act
        val signature = provider.ed25519Sign(keyPair.privateKey, message)
        val isValid = provider.ed25519Verify(keyPair.publicKey, message, signature)

        // Assert
        assertEquals(32, keyPair.privateKey.size)
        assertEquals(32, keyPair.publicKey.size)
        assertEquals(64, signature.size)
        assertTrue(isValid)
    }
}
