package ch.trancee.meshlink.crypto.noise

import ch.trancee.meshlink.crypto.createCryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SymmetricStateTest {

    private val crypto = createCryptoProvider()

    // ── initializeSymmetric ───────────────────────────────────────────────────

    @Test
    fun initializeSymmetricWithShortNamePadsToHashLen() {
        // Protocol names ≤ 32 bytes are zero-padded; h and ck should equal the padded name bytes.
        val ss = SymmetricState(crypto)
        ss.initializeSymmetric("short")
        // Verify it does not throw and state is initialized — confirmed by being able to call
        // split.
        // split() produces two independent cipher states with freshly-derived keys.
        val (c1, c2) = ss.split()
        assertTrue(c1.hasKey())
        assertTrue(c2.hasKey())
    }

    @Test
    fun initializeSymmetricWithLongNameHashesIt() {
        // Names > 32 bytes: h/ck = SHA-256(name). Must not throw.
        val ss = SymmetricState(crypto)
        ss.initializeSymmetric("Noise_XX_25519_ChaChaPoly_SHA256") // exactly 32 bytes — boundary
        ss.initializeSymmetric("Noise_XX_25519_ChaChaPoly_SHA256_extra") // > 32 bytes — hashed
    }

    @Test
    fun twoInstancesWithSameProtocolNameProduceConsistentState() {
        // Both should derive the same hash output from split after no mixKey/mixHash calls.
        val ss1 = SymmetricState(crypto)
        val ss2 = SymmetricState(crypto)
        ss1.initializeSymmetric("TestProtocol")
        ss2.initializeSymmetric("TestProtocol")

        val key1 = ByteArray(32) { it.toByte() }
        ss1.mixKey(key1)
        ss2.mixKey(key1)

        val data = "handshake-msg".encodeToByteArray()
        val ct1 = ss1.encryptAndHash(data)
        val ct2 = ss2.encryptAndHash(data)
        assertContentEquals(ct1, ct2)
    }

    // ── mixKey ────────────────────────────────────────────────────────────────

    @Test
    fun mixKeyActivatesCipherState() {
        val ss = SymmetricState(crypto)
        ss.initializeSymmetric("Noise_XX_25519_ChaChaPoly_SHA256")
        assertFalse(ss.hasKey(), "No key before mixKey")
        ss.mixKey(ByteArray(32) { 0x11 })
        assertTrue(ss.hasKey(), "Key active after mixKey")
    }

    @Test
    fun mixKeyChangesChainingKey() {
        // Two symmetric states with the same init but different mixKey inputs produce different
        // handshake hash after encryptAndHash.
        val ss1 = SymmetricState(crypto)
        val ss2 = SymmetricState(crypto)
        ss1.initializeSymmetric("Proto")
        ss2.initializeSymmetric("Proto")
        ss1.mixKey(ByteArray(32) { 0xAA.toByte() })
        ss2.mixKey(ByteArray(32) { 0xBB.toByte() })

        val ct1 = ss1.encryptAndHash(ByteArray(0))
        val ct2 = ss2.encryptAndHash(ByteArray(0))
        assertFalse(ct1.contentEquals(ct2))
    }

    // ── mixHash ───────────────────────────────────────────────────────────────

    @Test
    fun mixHashChangeHandshakeHash() {
        val ss = SymmetricState(crypto)
        ss.initializeSymmetric("Proto")
        val h1 = ss.getHandshakeHash()
        ss.mixHash("some data".encodeToByteArray())
        val h2 = ss.getHandshakeHash()
        assertFalse(h1.contentEquals(h2))
    }

    @Test
    fun mixHashWithEmptyDataStillChangesHash() {
        val ss = SymmetricState(crypto)
        ss.initializeSymmetric("Proto")
        val h1 = ss.getHandshakeHash()
        ss.mixHash(ByteArray(0))
        val h2 = ss.getHandshakeHash()
        // SHA-256("Proto_padded" ‖ "") != SHA-256("Proto_padded"), since h2 = SHA-256(h1 ‖ "")
        assertFalse(h1.contentEquals(h2))
    }

    // ── encryptAndHash / decryptAndHash ───────────────────────────────────────

    @Test
    fun encryptAndHashPassthroughWhenNoKey() {
        val ss = SymmetricState(crypto)
        ss.initializeSymmetric("Proto")
        val data = "cleartext".encodeToByteArray()
        val result = ss.encryptAndHash(data)
        assertContentEquals(data, result)
    }

    @Test
    fun encryptAndHashDecryptAndHashRoundTrip() {
        val ss1 = SymmetricState(crypto)
        val ss2 = SymmetricState(crypto)
        ss1.initializeSymmetric("Noise_XX_25519_ChaChaPoly_SHA256")
        ss2.initializeSymmetric("Noise_XX_25519_ChaChaPoly_SHA256")

        val sharedDh = ByteArray(32) { 0x55 }
        ss1.mixKey(sharedDh)
        ss2.mixKey(sharedDh)

        val plaintext = "secret payload".encodeToByteArray()
        val ciphertext = ss1.encryptAndHash(plaintext)
        val recovered = ss2.decryptAndHash(ciphertext)
        assertContentEquals(plaintext, recovered)
    }

    @Test
    fun encryptAndHashProducesAuthenticatedCiphertext() {
        val ss1 = SymmetricState(crypto)
        val ss2 = SymmetricState(crypto)
        ss1.initializeSymmetric("Noise_XX_25519_ChaChaPoly_SHA256")
        ss2.initializeSymmetric("Noise_XX_25519_ChaChaPoly_SHA256")
        val dh = ByteArray(32) { 0x77 }
        ss1.mixKey(dh)
        ss2.mixKey(dh)

        val ct = ss1.encryptAndHash("data".encodeToByteArray())
        // Tamper the ciphertext — decryptAndHash must throw
        ct[0] = (ct[0].toInt() xor 0xFF).toByte()
        assertFailsWith<IllegalStateException> { ss2.decryptAndHash(ct) }
    }

    // ── split ─────────────────────────────────────────────────────────────────

    @Test
    fun splitProducesTwoCipherStatesWithKeys() {
        val ss = SymmetricState(crypto)
        ss.initializeSymmetric("Noise_XX_25519_ChaChaPoly_SHA256")
        ss.mixKey(ByteArray(32) { it.toByte() })
        val (c1, c2) = ss.split()
        assertTrue(c1.hasKey())
        assertTrue(c2.hasKey())
    }

    @Test
    fun splitProducesTwoDistinctKeys() {
        val ss = SymmetricState(crypto)
        ss.initializeSymmetric("Noise_XX_25519_ChaChaPoly_SHA256")
        ss.mixKey(ByteArray(32) { it.toByte() })
        val (c1, c2) = ss.split()

        val plaintext = "test".encodeToByteArray()
        val ct1 = c1.encryptWithAd(ByteArray(0), plaintext)
        val ct2 = c2.encryptWithAd(ByteArray(0), plaintext)
        // Different keys → different ciphertexts
        assertFalse(ct1.contentEquals(ct2))
    }

    @Test
    fun splitAfterNoMixKeyProducesValidCipherStates() {
        // split() is valid even without any mixKey (no handshake messages); h=ck=initial hash.
        val ss = SymmetricState(crypto)
        ss.initializeSymmetric("TestProto")
        val (c1, c2) = ss.split()
        // Keys are derived from HKDF(ck, "", "", 64) even when ck=initial hash
        assertTrue(c1.hasKey())
        assertTrue(c2.hasKey())
    }

    // ── getHandshakeHash ──────────────────────────────────────────────────────

    @Test
    fun getHandshakeHashReturnsCopy() {
        val ss = SymmetricState(crypto)
        ss.initializeSymmetric("Proto")
        val h1 = ss.getHandshakeHash()
        h1[0] = (h1[0].toInt() xor 0xFF).toByte()
        val h2 = ss.getHandshakeHash()
        assertNotEquals(h1[0], h2[0], "Modifying returned hash must not affect internal state")
    }

    // ── mixKeyAndHash ─────────────────────────────────────────────────────────

    @Test
    fun mixKeyAndHashActivatesCipherState() {
        val ss = SymmetricState(crypto)
        ss.initializeSymmetric("Proto")
        assertFalse(ss.hasKey())
        ss.mixKeyAndHash(ByteArray(32) { 0x99.toByte() })
        assertTrue(ss.hasKey())
    }
}
