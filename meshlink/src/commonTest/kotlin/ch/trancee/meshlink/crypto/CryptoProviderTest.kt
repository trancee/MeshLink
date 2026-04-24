package ch.trancee.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CryptoProviderTest {

    private val provider: CryptoProvider = createCryptoProvider()

    // ── Factory ──────────────────────────────────────────────────────────────

    @Test
    fun createCryptoProviderIsReachable() {
        // Verifies the factory instantiates without throwing.
        // Return type is non-nullable so a successful call proves correct wiring.
        createCryptoProvider()
    }

    // ── Stub stubs throw NotImplementedError ─────────────────────────────────

    @Test
    fun generateEd25519KeyPairThrows() {
        assertFailsWith<NotImplementedError> { provider.generateEd25519KeyPair() }
    }

    @Test
    fun signThrows() {
        assertFailsWith<NotImplementedError> { provider.sign(byteArrayOf(), byteArrayOf()) }
    }

    @Test
    fun verifyThrows() {
        assertFailsWith<NotImplementedError> {
            provider.verify(byteArrayOf(), byteArrayOf(), byteArrayOf())
        }
    }

    @Test
    fun generateX25519KeyPairThrows() {
        assertFailsWith<NotImplementedError> { provider.generateX25519KeyPair() }
    }

    @Test
    fun x25519SharedSecretThrows() {
        assertFailsWith<NotImplementedError> {
            provider.x25519SharedSecret(byteArrayOf(), byteArrayOf())
        }
    }

    @Test
    fun aeadEncryptThrows() {
        assertFailsWith<NotImplementedError> {
            provider.aeadEncrypt(byteArrayOf(), byteArrayOf(), byteArrayOf(), byteArrayOf())
        }
    }

    @Test
    fun aeadDecryptThrows() {
        assertFailsWith<NotImplementedError> {
            provider.aeadDecrypt(byteArrayOf(), byteArrayOf(), byteArrayOf(), byteArrayOf())
        }
    }

    @Test
    fun sha256Throws() {
        assertFailsWith<NotImplementedError> { provider.sha256(byteArrayOf()) }
    }

    @Test
    fun hkdfSha256Throws() {
        assertFailsWith<NotImplementedError> {
            provider.hkdfSha256(byteArrayOf(), byteArrayOf(), byteArrayOf(), 32)
        }
    }

    // ── KeyPair equals — all branches ────────────────────────────────────────

    @Test
    fun keyPairEqualsSameReference() {
        val kp = KeyPair(byteArrayOf(1, 2), byteArrayOf(3, 4))
        // Branch: this === other → true
        assertTrue(kp.equals(kp))
    }

    @Test
    fun keyPairEqualsNonKeyPairType() {
        val kp = KeyPair(byteArrayOf(1), byteArrayOf(2))
        // Branch: other !is KeyPair → false
        assertFalse(kp.equals("not a keypair"))
    }

    @Test
    fun keyPairEqualsDifferentPublicKey() {
        val kp1 = KeyPair(byteArrayOf(1), byteArrayOf(3))
        val kp2 = KeyPair(byteArrayOf(2), byteArrayOf(3))
        // Branch: publicKey.contentEquals fails → false (short-circuit)
        assertNotEquals(kp1, kp2)
    }

    @Test
    fun keyPairEqualsDifferentPrivateKey() {
        val kp1 = KeyPair(byteArrayOf(1), byteArrayOf(2))
        val kp2 = KeyPair(byteArrayOf(1), byteArrayOf(3))
        // Branch: publicKey matches but privateKey.contentEquals fails → false
        assertNotEquals(kp1, kp2)
    }

    @Test
    fun keyPairEqualsEqualContents() {
        val kp1 = KeyPair(byteArrayOf(1, 2), byteArrayOf(3, 4))
        val kp2 = KeyPair(byteArrayOf(1, 2), byteArrayOf(3, 4))
        // Branch: both contentEquals pass → true
        assertEquals(kp1, kp2)
    }

    // ── KeyPair hashCode ──────────────────────────────────────────────────────

    @Test
    fun keyPairHashCodeConsistency() {
        val kp1 = KeyPair(byteArrayOf(1, 2), byteArrayOf(3, 4))
        val kp2 = KeyPair(byteArrayOf(1, 2), byteArrayOf(3, 4))
        assertEquals(kp1.hashCode(), kp2.hashCode())
    }

    @Test
    fun keyPairHashCodeDiffersForDifferentContents() {
        val kp1 = KeyPair(byteArrayOf(1), byteArrayOf(2))
        val kp2 = KeyPair(byteArrayOf(3), byteArrayOf(4))
        assertNotEquals(kp1.hashCode(), kp2.hashCode())
    }

    // ── KeyPair data class generated methods ──────────────────────────────────

    @Test
    fun keyPairComponentFunctions() {
        val kp = KeyPair(byteArrayOf(10), byteArrayOf(20))
        val (pub, priv) = kp
        assertTrue(pub.contentEquals(byteArrayOf(10)))
        assertTrue(priv.contentEquals(byteArrayOf(20)))
    }

    @Test
    fun keyPairCopyNoArgs() {
        val kp = KeyPair(byteArrayOf(1, 2), byteArrayOf(3, 4))
        val copied = kp.copy()
        assertEquals(kp, copied)
    }

    @Test
    fun keyPairCopyOverridePublicKey() {
        val kp = KeyPair(byteArrayOf(1), byteArrayOf(2))
        val modified = kp.copy(publicKey = byteArrayOf(9))
        assertTrue(modified.publicKey.contentEquals(byteArrayOf(9)))
        assertTrue(modified.privateKey.contentEquals(byteArrayOf(2)))
    }

    @Test
    fun keyPairCopyOverridePrivateKey() {
        val kp = KeyPair(byteArrayOf(1), byteArrayOf(2))
        val modified = kp.copy(privateKey = byteArrayOf(9))
        assertTrue(modified.publicKey.contentEquals(byteArrayOf(1)))
        assertTrue(modified.privateKey.contentEquals(byteArrayOf(9)))
    }

    @Test
    fun keyPairToStringIsNotEmpty() {
        val kp = KeyPair(byteArrayOf(1), byteArrayOf(2))
        assertTrue(kp.toString().isNotEmpty())
    }
}
