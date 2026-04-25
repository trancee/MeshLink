package ch.trancee.meshlink.crypto.noise

import ch.trancee.meshlink.crypto.createCryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CipherStateTest {

    private val crypto = createCryptoProvider()

    // ── hasKey ────────────────────────────────────────────────────────────────

    @Test
    fun hasKeyReturnsFalseWhenNoKeySet() {
        val cs = CipherState(crypto)
        assertFalse(cs.hasKey())
    }

    @Test
    fun hasKeyReturnsTrueAfterInitializeKey() {
        val cs = CipherState(crypto)
        cs.initializeKey(ByteArray(32) { 0x42 })
        assertTrue(cs.hasKey())
    }

    // ── initializeKey ─────────────────────────────────────────────────────────

    @Test
    fun initializeKeyRejectsWrongSize() {
        val cs = CipherState(crypto)
        assertFailsWith<IllegalArgumentException> { cs.initializeKey(ByteArray(16)) }
        assertFailsWith<IllegalArgumentException> { cs.initializeKey(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { cs.initializeKey(ByteArray(33)) }
    }

    @Test
    fun initializeKeyResetsNonceToZero() {
        val cs = CipherState(crypto)
        val key = ByteArray(32) { it.toByte() }
        cs.initializeKey(key)
        val plaintext = "hello".encodeToByteArray()
        val ct1 = cs.encryptWithAd(ByteArray(0), plaintext)

        // Re-initialize with the same key — nonce must reset to 0
        cs.initializeKey(key)
        val ct2 = cs.encryptWithAd(ByteArray(0), plaintext)

        // Same key + same nonce (0) + same plaintext → same ciphertext
        assertContentEquals(ct1, ct2)
    }

    // ── passthrough when no key ────────────────────────────────────────────────

    @Test
    fun encryptWithAdReturnPlaintextWhenNoKey() {
        val cs = CipherState(crypto)
        val data = "plaintext".encodeToByteArray()
        assertContentEquals(data, cs.encryptWithAd(ByteArray(0), data))
    }

    @Test
    fun decryptWithAdReturnCiphertextWhenNoKey() {
        val cs = CipherState(crypto)
        val data = "ciphertext".encodeToByteArray()
        assertContentEquals(data, cs.decryptWithAd(ByteArray(0), data))
    }

    // ── AEAD encrypt / decrypt round-trip ─────────────────────────────────────

    @Test
    fun encryptDecryptRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val enc = CipherState(crypto)
        val dec = CipherState(crypto)
        enc.initializeKey(key)
        dec.initializeKey(key)

        val plaintext = "MeshLink transport".encodeToByteArray()
        val ct = enc.encryptWithAd(ByteArray(0), plaintext)
        val recovered = dec.decryptWithAd(ByteArray(0), ct)
        assertContentEquals(plaintext, recovered)
    }

    // ── Nonce increments ──────────────────────────────────────────────────────

    @Test
    fun nonceIncrementsProduceDifferentCiphertexts() {
        val key = ByteArray(32) { 0x11 }
        val cs = CipherState(crypto)
        cs.initializeKey(key)
        val plaintext = "same".encodeToByteArray()
        val ct1 = cs.encryptWithAd(ByteArray(0), plaintext)
        val ct2 = cs.encryptWithAd(ByteArray(0), plaintext)
        assertFalse(ct1.contentEquals(ct2), "Different nonces must produce different ciphertexts")
    }

    @Test
    fun decryptMatchingNonceOrder() {
        val key = ByteArray(32) { 0x22 }
        val enc = CipherState(crypto)
        val dec = CipherState(crypto)
        enc.initializeKey(key)
        dec.initializeKey(key)

        val messages = listOf("first", "second", "third")
        val ciphertexts = messages.map { enc.encryptWithAd(ByteArray(0), it.encodeToByteArray()) }
        ciphertexts.forEachIndexed { i, ct ->
            val recovered = dec.decryptWithAd(ByteArray(0), ct)
            assertContentEquals(messages[i].encodeToByteArray(), recovered)
        }
    }

    // ── encodeNonce ───────────────────────────────────────────────────────────

    @Test
    fun encodeNonceZeroProducesTwelveZeroBytes() {
        val nonce = CipherState.encodeNonce(0L)
        assertEquals(12, nonce.size)
        assertContentEquals(ByteArray(12), nonce)
    }

    @Test
    fun encodeNonceOnePutsOneInByteFour() {
        val nonce = CipherState.encodeNonce(1L)
        assertEquals(12, nonce.size)
        // bytes 0-3: zero (pad); byte 4: 1; bytes 5-11: zero
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0), nonce)
    }

    @Test
    fun encodeNonceLittleEndianOrdering() {
        // 0x0102030405060708 in little-endian bytes 4..11
        val counter = 0x0102030405060708L
        val nonce = CipherState.encodeNonce(counter)
        assertEquals(12, nonce.size)
        // bytes 0-3: zero (padding)
        assertEquals(0, nonce[0].toInt() and 0xFF)
        assertEquals(0, nonce[1].toInt() and 0xFF)
        assertEquals(0, nonce[2].toInt() and 0xFF)
        assertEquals(0, nonce[3].toInt() and 0xFF)
        // bytes 4-11: LE encoding of counter
        assertEquals(0x08, nonce[4].toInt() and 0xFF)
        assertEquals(0x07, nonce[5].toInt() and 0xFF)
        assertEquals(0x06, nonce[6].toInt() and 0xFF)
        assertEquals(0x05, nonce[7].toInt() and 0xFF)
        assertEquals(0x04, nonce[8].toInt() and 0xFF)
        assertEquals(0x03, nonce[9].toInt() and 0xFF)
        assertEquals(0x02, nonce[10].toInt() and 0xFF)
        assertEquals(0x01, nonce[11].toInt() and 0xFF)
    }

    // ── AD included in authentication ─────────────────────────────────────────

    @Test
    fun wrongAdCausesDecryptionFailure() {
        val key = ByteArray(32) { 0x33 }
        val enc = CipherState(crypto)
        val dec = CipherState(crypto)
        enc.initializeKey(key)
        dec.initializeKey(key)

        val ct = enc.encryptWithAd("good-ad".encodeToByteArray(), "payload".encodeToByteArray())
        assertFailsWith<IllegalStateException> {
            dec.decryptWithAd("bad-ad".encodeToByteArray(), ct)
        }
    }

    // ── rekey stub ────────────────────────────────────────────────────────────

    @Test
    fun rekeyDoesNotThrow() {
        val cs = CipherState(crypto)
        cs.initializeKey(ByteArray(32) { 0x44 })
        cs.rekey() // stub — must not throw
    }
}
