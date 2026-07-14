package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.platform.android.crypto.JcaCryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CryptoProviderTest {
    private val provider = JcaCryptoProvider()

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
    fun `x25519 rejects all-zero shared secret`() {
        // Arrange
        val privateKey =
            byteArrayOf(
                0x77,
                0x07,
                0x6d,
                0x0a,
                0x73,
                0x18,
                0xa5.toByte(),
                0x7d,
                0x3c,
                0x16,
                0xc1.toByte(),
                0x72,
                0x51,
                0xb2.toByte(),
                0x66,
                0x45,
                0xdf.toByte(),
                0x4c,
                0x2f,
                0x87.toByte(),
                0xeb.toByte(),
                0xc0.toByte(),
                0x99.toByte(),
                0x2a,
                0xb1.toByte(),
                0x77,
                0xfb.toByte(),
                0xa5.toByte(),
                0x1d,
                0xb9.toByte(),
                0x2c,
                0x2a,
            )
        val lowOrderPublicKey = ByteArray(32)

        // Act / Assert
        assertFailsWith<MeshLinkException.CryptoFailure> {
            provider.x25519(privateKey, lowOrderPublicKey)
        }
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
