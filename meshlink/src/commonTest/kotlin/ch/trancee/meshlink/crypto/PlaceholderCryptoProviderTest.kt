package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.api.MeshLinkException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceholderCryptoProviderTest {
    private val provider = PlaceholderCryptoProvider

    @Test
    fun `x25519 returns the same shared secret regardless of key order`() {
        // Arrange
        val leftKey = ByteArray(32) { index -> (index + 1).toByte() }
        val rightKey = ByteArray(32) { index -> (32 - index).toByte() }

        // Act
        val leftToRight = provider.x25519(privateKey = leftKey, publicKey = rightKey)
        val rightToLeft = provider.x25519(privateKey = rightKey, publicKey = leftKey)

        // Assert
        assertEquals(32, leftToRight.size)
        assertContentEquals(leftToRight, rightToLeft)
    }

    @Test
    fun `ed25519 verify accepts matching signatures and rejects mismatched public keys`() {
        // Arrange
        val key = ByteArray(32) { index -> (index * 3).toByte() }
        val otherKey = ByteArray(32) { index -> (index * 5).toByte() }
        val message = "meshlink".encodeToByteArray()

        // Act
        val signature = provider.ed25519Sign(privateKey = key, message = message)
        val verified =
            provider.ed25519Verify(publicKey = key, message = message, signature = signature)
        val verifiedWithOtherKey =
            provider.ed25519Verify(publicKey = otherKey, message = message, signature = signature)

        // Assert
        assertEquals(64, signature.size)
        assertTrue(verified)
        assertFalse(verifiedWithOtherKey)
    }

    @Test
    fun `chacha20 poly1305 seal and open round-trip the plaintext`() {
        // Arrange
        val key = ByteArray(32) { index -> (index + 10).toByte() }
        val nonce = ByteArray(12) { index -> (index + 20).toByte() }
        val aad = byteArrayOf(1, 3, 5, 7)
        val plaintext = "hello meshlink".encodeToByteArray()

        // Act
        val ciphertext = provider.chacha20Poly1305Seal(key, nonce, aad, plaintext)
        val decrypted = provider.chacha20Poly1305Open(key, nonce, aad, ciphertext)

        // Assert
        assertEquals(plaintext.size + 16, ciphertext.size)
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `chacha20 poly1305 open rejects tampered authentication tags`() {
        // Arrange
        val key = ByteArray(32) { index -> (index + 30).toByte() }
        val nonce = ByteArray(12) { index -> (index + 40).toByte() }
        val aad = byteArrayOf(2, 4, 6, 8)
        val plaintext = "hello meshlink".encodeToByteArray()
        val ciphertext = provider.chacha20Poly1305Seal(key, nonce, aad, plaintext)
        val tampered =
            ciphertext.copyOf().also { copy -> copy[copy.lastIndex] = (copy.last() + 1).toByte() }

        // Act
        val failure =
            assertFailsWith<MeshLinkException.CryptoFailure> {
                provider.chacha20Poly1305Open(key, nonce, aad, tampered)
            }

        // Assert
        assertEquals("Ciphertext authentication tag verification failed", failure.message)
    }
}
