package ch.trancee.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Exercises [JcaSymmetricPrimitives] once, shared between the JVM and Android targets via the
 * `jvmAndroidTest` source set (mirroring `jvmAndroidMain`) -- both [JvmCryptoProvider] and
 * Android's `JcaCryptoProvider` delegate to this same class, so one shared test covers both
 * platforms' symmetric-primitive plumbing instead of duplicating the same coverage twice.
 */
class JcaSymmetricPrimitivesTest {
    @Test
    fun randomBytesReturnsRequestedSizeAndVariesAcrossCalls(): Unit {
        // Arrange
        val primitives = JcaSymmetricPrimitives()

        // Act
        val first = primitives.randomBytes(32)
        val second = primitives.randomBytes(32)

        // Assert
        assertEquals(32, first.size)
        assertEquals(32, second.size)
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun sha256MatchesKnownTestVector(): Unit {
        // Arrange
        val primitives = JcaSymmetricPrimitives()
        val input = "abc".encodeToByteArray()
        val expected =
            hexToBytes("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

        // Act
        val actual = primitives.sha256(input)

        // Assert
        assertContentEquals(expected, actual)
    }

    @Test
    fun hmacSha256IsDeterministicForTheSameKeyAndData(): Unit {
        // Arrange
        val primitives = JcaSymmetricPrimitives()
        val key = ByteArray(32) { index -> index.toByte() }
        val data = "message".encodeToByteArray()

        // Act
        val first = primitives.hmacSha256(key, data)
        val second = primitives.hmacSha256(key, data)

        // Assert
        assertContentEquals(first, second)
        assertEquals(32, first.size)
    }

    @Test
    fun chacha20Poly1305SealThenOpenRoundTripsThePlaintext(): Unit {
        // Arrange
        val primitives = JcaSymmetricPrimitives()
        val key = ByteArray(32) { index -> (index * 7).toByte() }
        val nonce = ByteArray(12) { index -> index.toByte() }
        val aad = "associated-data".encodeToByteArray()
        val plaintext = "the quick brown fox".encodeToByteArray()

        // Act
        val ciphertext = primitives.chacha20Poly1305Seal(key, nonce, aad, plaintext)
        val decrypted = primitives.chacha20Poly1305Open(key, nonce, aad, ciphertext)

        // Assert
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun chacha20Poly1305OpenFailsWhenAadDoesNotMatch(): Unit {
        // Arrange
        val primitives = JcaSymmetricPrimitives()
        val key = ByteArray(32) { index -> (index * 3).toByte() }
        val nonce = ByteArray(12) { index -> index.toByte() }
        val plaintext = "payload".encodeToByteArray()
        val ciphertext =
            primitives.chacha20Poly1305Seal(key, nonce, "aad-a".encodeToByteArray(), plaintext)

        // Act / Assert
        assertFailsWithGeneralSecurityException {
            primitives.chacha20Poly1305Open(key, nonce, "aad-b".encodeToByteArray(), ciphertext)
        }
    }

    private fun assertFailsWithGeneralSecurityException(block: () -> Unit): Unit {
        try {
            block()
            throw AssertionError("Expected a decryption failure but none was thrown")
        } catch (expected: java.security.GeneralSecurityException) {
            // Expected: AEAD tag verification correctly rejects mismatched AAD.
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        check(hex.length % 2 == 0) { "hex string must have an even length" }
        return ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
