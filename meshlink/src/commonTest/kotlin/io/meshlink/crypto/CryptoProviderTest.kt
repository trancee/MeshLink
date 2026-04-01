package io.meshlink.crypto

import io.meshlink.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CryptoProviderTest {

    private val crypto = CryptoProvider()

    // ── Vertical slice 1: Ed25519 key generation ──

    @Test
    fun ed25519KeyGenProduces32ByteKeys() {
        val keyPair = crypto.generateEd25519KeyPair()
        assertEquals(32, keyPair.publicKey.size, "Ed25519 public key should be 32 bytes")
        assertEquals(32, keyPair.privateKey.size, "Ed25519 private key should be 32 bytes")
    }

    // ── Vertical slice 2: Ed25519 sign/verify round-trip ──

    @Test
    fun ed25519SignVerifyRoundTrip() {
        val keyPair = crypto.generateEd25519KeyPair()
        val message = "hello meshlink".encodeToByteArray()
        val signature = crypto.sign(keyPair.privateKey, message)
        assertEquals(64, signature.size, "Ed25519 signature should be 64 bytes")
        assertTrue(crypto.verify(keyPair.publicKey, message, signature), "Signature should verify")
    }

    // ── Vertical slice 3: tampered signature rejected ──

    @Test
    fun ed25519VerifyRejectsTamperedSignature() {
        val keyPair = crypto.generateEd25519KeyPair()
        val message = "hello meshlink".encodeToByteArray()
        val signature = crypto.sign(keyPair.privateKey, message)

        val tampered = signature.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()
        assertFalse(crypto.verify(keyPair.publicKey, message, tampered), "Tampered signature should not verify")
    }

    @Test
    fun ed25519VerifyRejectsWrongKey() {
        val keyPair1 = crypto.generateEd25519KeyPair()
        val keyPair2 = crypto.generateEd25519KeyPair()
        val message = "hello meshlink".encodeToByteArray()
        val signature = crypto.sign(keyPair1.privateKey, message)
        assertFalse(crypto.verify(keyPair2.publicKey, message, signature), "Wrong key should not verify")
    }

    // ── Vertical slice 4: X25519 key generation ──

    @Test
    fun x25519KeyGenProducesValidKeys() {
        val keyPair = crypto.generateX25519KeyPair()
        assertEquals(32, keyPair.publicKey.size, "X25519 public key should be 32 bytes")
        assertEquals(32, keyPair.privateKey.size, "X25519 private key should be 32 bytes")
    }

    // ── Vertical slice 5: X25519 shared secret is symmetric ──

    @Test
    fun x25519SharedSecretIsSymmetric() {
        val alice = crypto.generateX25519KeyPair()
        val bob = crypto.generateX25519KeyPair()
        val secretAB = crypto.x25519SharedSecret(alice.privateKey, bob.publicKey)
        val secretBA = crypto.x25519SharedSecret(bob.privateKey, alice.publicKey)
        assertEquals(32, secretAB.size, "Shared secret should be 32 bytes")
        assertTrue(secretAB.contentEquals(secretBA), "A(priv)+B(pub) must equal B(priv)+A(pub)")
    }

    // ── Vertical slice 6: ChaCha20-Poly1305 encrypt/decrypt round-trip ──

    @Test
    fun aeadEncryptDecryptRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 100).toByte() }
        val plaintext = "secret mesh data".encodeToByteArray()
        val aad = "associated-data".encodeToByteArray()

        val ciphertext = crypto.aeadEncrypt(key, nonce, plaintext, aad)
        assertTrue(ciphertext.size > plaintext.size, "Ciphertext should be longer (includes auth tag)")

        val decrypted = crypto.aeadDecrypt(key, nonce, ciphertext, aad)
        assertTrue(plaintext.contentEquals(decrypted), "Decrypted should match original")
    }

    // ── Vertical slice 7: ChaCha20-Poly1305 rejects tampered ciphertext ──

    @Test
    fun aeadDecryptRejectsTamperedCiphertext() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 100).toByte() }
        val plaintext = "secret mesh data".encodeToByteArray()

        val ciphertext = crypto.aeadEncrypt(key, nonce, plaintext)
        val tampered = ciphertext.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()

        assertFailsWith<Exception> {
            crypto.aeadDecrypt(key, nonce, tampered)
        }
    }

    // ── Vertical slice 8: SHA-256 golden vector ──

    @Test
    fun sha256GoldenVector() {
        val input = "abc".encodeToByteArray()
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val hash = crypto.sha256(input)
        assertEquals(32, hash.size, "SHA-256 output should be 32 bytes")
        assertEquals(expected, hash.toHex())
    }

    // ── Vertical slice 9: HKDF produces requested length ──

    @Test
    fun hkdfProducesRequestedLength() {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16) { (it + 50).toByte() }
        val info = "meshlink-test".encodeToByteArray()

        val out32 = crypto.hkdfSha256(ikm, salt, info, 32)
        assertEquals(32, out32.size)

        val out64 = crypto.hkdfSha256(ikm, salt, info, 64)
        assertEquals(64, out64.size)

        // First 32 bytes of 64-byte output should match 32-byte output
        assertTrue(out32.contentEquals(out64.copyOf(32)),
            "HKDF shorter output should be prefix of longer output")
    }

    // ── Vertical slice 10: HKDF-SHA256 RFC 5869 test vector ──

    @Test
    fun hkdfSha256Rfc5869TestVector1() {
        // RFC 5869 Appendix A, Test Case 1
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }  // 0x00..0x0c
        val info = ByteArray(10) { (0xf0 + it).toByte() }  // 0xf0..0xf9
        val expectedOkm = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"

        val okm = crypto.hkdfSha256(ikm, salt, info, 42)
        assertEquals(expectedOkm, okm.toHex())
    }
}
