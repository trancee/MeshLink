package ch.trancee.meshlink.crypto

import ch.trancee.meshlink.api.MeshLinkException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PureX25519Test {
    @Test
    fun `public key generation matches the base point shared secret for a clamped scalar`() {
        // Arrange
        val privateKey = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val basePoint = ByteArray(32).also { it[0] = 9 }

        // Act
        val publicKey = PureX25519.publicKeyFromPrivate(privateKey)
        val sharedSecret = PureX25519.sharedSecret(privateKey, basePoint)

        // Assert
        assertContentEquals(sharedSecret, publicKey)
    }

    @Test
    fun `x25519 ignores the high bit of a noncanonical public key`() {
        // Arrange
        val privateKey = hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d")
        val canonicalPublicKey = hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")
        val nonCanonicalPublicKey =
            canonicalPublicKey.copyOf().also {
                it[it.lastIndex] = (it[it.lastIndex].toInt() or 0x80).toByte()
            }

        // Act
        val canonicalSharedSecret = PureX25519.sharedSecret(privateKey, canonicalPublicKey)
        val nonCanonicalSharedSecret = PureX25519.sharedSecret(privateKey, nonCanonicalPublicKey)

        // Assert
        assertContentEquals(canonicalSharedSecret, nonCanonicalSharedSecret)
    }

    @Test
    fun `shared secret matches rfc 7748 vector 2`() {
        // Arrange
        val privateKey = hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d")
        val publicKey = hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")
        val expected = hex("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957")

        // Act
        val actual = PureX25519.sharedSecret(privateKey = privateKey, publicKey = publicKey)

        // Assert
        assertContentEquals(expected, actual)
    }

    @Test
    fun `shared secret rejects low order inputs at the validation layer`() {
        // Arrange
        val privateKey = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val lowOrderPublicKey = ByteArray(32)

        // Act
        val sharedSecret = PureX25519.sharedSecret(privateKey, lowOrderPublicKey)

        // Assert
        assertTrue(sharedSecret.all { it == 0.toByte() })
        assertFailsWith<MeshLinkException.CryptoFailure> {
            requireValidX25519SharedSecret(sharedSecret)
        }
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0) { "hex input must have an even length" }
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
