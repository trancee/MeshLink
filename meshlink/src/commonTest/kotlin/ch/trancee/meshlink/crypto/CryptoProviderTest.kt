package ch.trancee.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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

    // ── Ed25519 smoke tests ───────────────────────────────────────────────────

    @Test
    fun generateEd25519KeyPairReturnCorrectSizes() {
        val kp = provider.generateEd25519KeyPair()
        assertEquals(32, kp.publicKey.size, "Ed25519 public key must be 32 bytes")
        assertEquals(64, kp.privateKey.size, "Ed25519 private key must be 64 bytes")
    }

    @Test
    fun ed25519SignVerifyRoundTrip() {
        val kp = provider.generateEd25519KeyPair()
        val message = "MeshLink test message".encodeToByteArray()
        val signature = provider.sign(kp.privateKey, message)
        assertEquals(64, signature.size, "Ed25519 signature must be 64 bytes")
        assertTrue(provider.verify(kp.publicKey, message, signature), "Signature must verify")
    }

    @Test
    fun ed25519VerifyRejectsTamperedMessage() {
        val kp = provider.generateEd25519KeyPair()
        val message = "authentic message".encodeToByteArray()
        val signature = provider.sign(kp.privateKey, message)
        val tampered = "tampered message".encodeToByteArray()
        assertFalse(provider.verify(kp.publicKey, tampered, signature), "Wrong message must not verify")
    }

    @Test
    fun ed25519VerifyRejectsWrongKey() {
        val kp1 = provider.generateEd25519KeyPair()
        val kp2 = provider.generateEd25519KeyPair()
        val message = "hello".encodeToByteArray()
        val signature = provider.sign(kp1.privateKey, message)
        assertFalse(provider.verify(kp2.publicKey, message, signature), "Wrong key must not verify")
    }

    // ── X25519 smoke tests ────────────────────────────────────────────────────

    @Test
    fun generateX25519KeyPairReturnsCorrectSizes() {
        val kp = provider.generateX25519KeyPair()
        assertEquals(32, kp.publicKey.size, "X25519 public key must be 32 bytes")
        assertEquals(32, kp.privateKey.size, "X25519 private key must be 32 bytes")
    }

    @Test
    fun x25519DiffieHellmanAgreement() {
        val alice = provider.generateX25519KeyPair()
        val bob = provider.generateX25519KeyPair()
        val sharedA = provider.x25519SharedSecret(alice.privateKey, bob.publicKey)
        val sharedB = provider.x25519SharedSecret(bob.privateKey, alice.publicKey)
        assertEquals(32, sharedA.size, "X25519 shared secret must be 32 bytes")
        assertContentEquals(sharedA, sharedB, "DH shared secrets must agree")
    }

    // ── ChaCha20-Poly1305 AEAD smoke tests ────────────────────────────────────

    @Test
    fun aeadEncryptDecryptRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val plaintext = "Hello, MeshLink!".encodeToByteArray()
        val aad = "additional data".encodeToByteArray()

        val ciphertext = provider.aeadEncrypt(key, nonce, plaintext, aad)
        assertEquals(
            plaintext.size + 16,
            ciphertext.size,
            "Ciphertext must be plaintext.size + 16 (Poly1305 tag)"
        )

        val decrypted = provider.aeadDecrypt(key, nonce, ciphertext, aad)
        assertContentEquals(plaintext, decrypted, "Decrypted must equal original plaintext")
    }

    @Test
    fun aeadEncryptDecryptEmptyAad() {
        val key = ByteArray(32) { 0x42.toByte() }
        val nonce = ByteArray(12) { 0x01.toByte() }
        val plaintext = "no aad".encodeToByteArray()
        val ciphertext = provider.aeadEncrypt(key, nonce, plaintext, byteArrayOf())
        val decrypted = provider.aeadDecrypt(key, nonce, ciphertext, byteArrayOf())
        assertContentEquals(plaintext, decrypted)
    }

    // ── SHA-256 smoke test ────────────────────────────────────────────────────

    @Test
    fun sha256OfEmptyInputMatchesRfcVector() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        val expected = byteArrayOf(
            0xe3.toByte(), 0xb0.toByte(), 0xc4.toByte(), 0x42.toByte(),
            0x98.toByte(), 0xfc.toByte(), 0x1c.toByte(), 0x14.toByte(),
            0x9a.toByte(), 0xfb.toByte(), 0xf4.toByte(), 0xc8.toByte(),
            0x99.toByte(), 0x6f.toByte(), 0xb9.toByte(), 0x24.toByte(),
            0x27.toByte(), 0xae.toByte(), 0x41.toByte(), 0xe4.toByte(),
            0x64.toByte(), 0x9b.toByte(), 0x93.toByte(), 0x4c.toByte(),
            0xa4.toByte(), 0x95.toByte(), 0x99.toByte(), 0x1b.toByte(),
            0x78.toByte(), 0x52.toByte(), 0xb8.toByte(), 0x55.toByte(),
        )
        val actual = provider.sha256(byteArrayOf())
        assertContentEquals(expected, actual, "SHA-256 of empty input must match RFC vector")
    }

    @Test
    fun sha256OutputIs32Bytes() {
        val result = provider.sha256("test".encodeToByteArray())
        assertEquals(32, result.size)
    }

    // ── HKDF-SHA-256 smoke test ───────────────────────────────────────────────

    @Test
    fun hkdfSha256ProducesDeterministicOutput() {
        val salt = "salt".encodeToByteArray()
        val ikm = "input keying material".encodeToByteArray()
        val info = "context info".encodeToByteArray()

        val out1 = provider.hkdfSha256(salt, ikm, info, 32)
        val out2 = provider.hkdfSha256(salt, ikm, info, 32)
        assertEquals(32, out1.size)
        assertContentEquals(out1, out2, "HKDF must be deterministic")
    }

    @Test
    fun hkdfSha256DifferentInfoProducesDifferentOutput() {
        val salt = "salt".encodeToByteArray()
        val ikm = "ikm".encodeToByteArray()
        val out1 = provider.hkdfSha256(salt, ikm, "info-A".encodeToByteArray(), 32)
        val out2 = provider.hkdfSha256(salt, ikm, "info-B".encodeToByteArray(), 32)
        assertFalse(out1.contentEquals(out2), "Different info must produce different output")
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
