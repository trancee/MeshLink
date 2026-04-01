package io.meshlink.crypto

import io.meshlink.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that [PureKotlinCryptoProvider] produces correct results
 * and is interoperable with the JVM crypto provider.
 */
class PureKotlinCryptoProviderTest {

    private val pure = PureKotlinCryptoProvider()

    @Test
    fun ed25519SignVerifyRoundTrip() {
        val kp = pure.generateEd25519KeyPair()
        assertEquals(32, kp.publicKey.size)
        assertEquals(32, kp.privateKey.size)

        val message = "Hello MeshLink".encodeToByteArray()
        val sig = pure.sign(kp.privateKey, message)
        assertEquals(64, sig.size)
        assertTrue(pure.verify(kp.publicKey, message, sig))
    }

    @Test
    fun x25519SharedSecretCommutativity() {
        val alice = pure.generateX25519KeyPair()
        val bob = pure.generateX25519KeyPair()

        val sharedAB = pure.x25519SharedSecret(alice.privateKey, bob.publicKey)
        val sharedBA = pure.x25519SharedSecret(bob.privateKey, alice.publicKey)

        assertContentEquals(sharedAB, sharedBA)
    }

    @Test
    fun aeadEncryptDecryptRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 0x10).toByte() }
        val plaintext = "secret message for mesh".encodeToByteArray()
        val aad = "additional data".encodeToByteArray()

        val ciphertext = pure.aeadEncrypt(key, nonce, plaintext, aad)
        val decrypted = pure.aeadDecrypt(key, nonce, ciphertext, aad)

        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun sha256MatchesKnownVector() {
        val hash = pure.sha256("abc".encodeToByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hash.toHex()
        )
    }

    @Test
    fun hkdfProducesCorrectLength() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }

        val okm = pure.hkdfSha256(ikm, salt, info, 42)
        assertEquals(42, okm.size)
    }

    @Test
    fun crossProviderAeadInterop() {
        // Verify pure Kotlin and JVM providers produce interoperable AEAD
        val jvm = CryptoProvider() // JvmCryptoProvider on JVM, platform provider on other targets
        val key = ByteArray(32) { (it * 3).toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val plaintext = "cross-provider test".encodeToByteArray()
        val aad = "auth".encodeToByteArray()

        // JVM encrypts, pure Kotlin decrypts
        val ciphertext = jvm.aeadEncrypt(key, nonce, plaintext, aad)
        val decrypted = pure.aeadDecrypt(key, nonce, ciphertext, aad)
        assertContentEquals(plaintext, decrypted)

        // Pure Kotlin encrypts, JVM decrypts
        val ciphertext2 = pure.aeadEncrypt(key, nonce, plaintext, aad)
        val decrypted2 = jvm.aeadDecrypt(key, nonce, ciphertext2, aad)
        assertContentEquals(plaintext, decrypted2)
    }

    @Test
    fun crossProviderSha256Interop() {
        val jvm = CryptoProvider()
        val data = "interop hash test".encodeToByteArray()

        assertContentEquals(jvm.sha256(data), pure.sha256(data))
    }

    @Test
    fun crossProviderHkdfInterop() {
        val jvm = CryptoProvider()
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16) { (0x80 + it).toByte() }
        val info = "MeshLink-test".encodeToByteArray()

        assertContentEquals(
            jvm.hkdfSha256(ikm, salt, info, 64),
            pure.hkdfSha256(ikm, salt, info, 64)
        )
    }
}
