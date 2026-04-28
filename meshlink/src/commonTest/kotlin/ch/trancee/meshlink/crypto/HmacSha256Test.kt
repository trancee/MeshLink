package ch.trancee.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * HMAC-SHA-256 test vectors from RFC 4231 (§4.2–§4.5, §4.6–§4.7).
 *
 * Each test case computes `crypto.hmacSha256(key, data)` and asserts the result matches the
 * expected HMAC-SHA-256 tag from the RFC.
 */
class HmacSha256Test {

    private val crypto: CryptoProvider = createCryptoProvider()

    // ── RFC 4231 §4.2 — Test Case 1 ─────────────────────────────────────────
    // Key: 20 bytes of 0x0b (key shorter than hash block size)
    @Test
    fun rfc4231TestCase1() {
        val key = ByteArray(20) { 0x0b }
        val data = "Hi There".encodeToByteArray()
        val expected =
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7".hexToByteArray()

        val result = crypto.hmacSha256(key, data)
        assertEquals(32, result.size, "HMAC-SHA-256 output must be 32 bytes")
        assertContentEquals(expected, result, "RFC 4231 Test Case 1 (§4.2)")
    }

    // ── RFC 4231 §4.3 — Test Case 2 ─────────────────────────────────────────
    // Key: "Jefe" (4 bytes — short key)
    @Test
    fun rfc4231TestCase2() {
        val key = "Jefe".encodeToByteArray()
        val data = "what do ya want for nothing?".encodeToByteArray()
        val expected =
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843".hexToByteArray()

        val result = crypto.hmacSha256(key, data)
        assertContentEquals(expected, result, "RFC 4231 Test Case 2 (§4.3)")
    }

    // ── RFC 4231 §4.4 — Test Case 3 ─────────────────────────────────────────
    // Key: 20 bytes of 0xaa, Data: 50 bytes of 0xdd
    @Test
    fun rfc4231TestCase3() {
        val key = ByteArray(20) { 0xaa.toByte() }
        val data = ByteArray(50) { 0xdd.toByte() }
        val expected =
            "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe".hexToByteArray()

        val result = crypto.hmacSha256(key, data)
        assertContentEquals(expected, result, "RFC 4231 Test Case 3 (§4.4)")
    }

    // ── RFC 4231 §4.5 — Test Case 4 ─────────────────────────────────────────
    // Key: 25 bytes (0x01..0x19), Data: 50 bytes of 0xcd
    @Test
    fun rfc4231TestCase4() {
        val key = "0102030405060708090a0b0c0d0e0f10111213141516171819".hexToByteArray()
        val data = ByteArray(50) { 0xcd.toByte() }
        val expected =
            "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b".hexToByteArray()

        val result = crypto.hmacSha256(key, data)
        assertContentEquals(expected, result, "RFC 4231 Test Case 4 (§4.5)")
    }

    // ── RFC 4231 §4.7 — Test Case 6 ─────────────────────────────────────────
    // Key: 131 bytes of 0xaa (key longer than hash block size of 64 bytes)
    @Test
    fun rfc4231TestCase6KeyLongerThanBlockSize() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val data = "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()
        val expected =
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54".hexToByteArray()

        val result = crypto.hmacSha256(key, data)
        assertContentEquals(expected, result, "RFC 4231 Test Case 6 (§4.7) — 131-byte key")
    }

    // ── RFC 4231 §4.8 — Test Case 7 ─────────────────────────────────────────
    // Key: 131 bytes of 0xaa, Data: longer message (key + data both > block size)
    @Test
    fun rfc4231TestCase7KeyAndDataLargerThanBlockSize() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val data =
            ("This is a test using a larger than block-size key and a " +
                    "larger than block-size data. The key needs to be hashed " +
                    "before being used by the HMAC algorithm.")
                .encodeToByteArray()
        val expected =
            "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2".hexToByteArray()

        val result = crypto.hmacSha256(key, data)
        assertContentEquals(
            expected,
            result,
            "RFC 4231 Test Case 7 (§4.8) — 131-byte key + long data",
        )
    }

    // ── Boundary: empty data ─────────────────────────────────────────────────
    // HMAC with empty message should produce a valid 32-byte tag.
    @Test
    fun hmacSha256EmptyData() {
        val key = ByteArray(32) { 0x01 }
        val data = ByteArray(0)

        val result = crypto.hmacSha256(key, data)
        assertEquals(32, result.size, "HMAC of empty data must still be 32 bytes")
    }

    // ── Boundary: key exactly 64 bytes (SHA-256 block size) ──────────────────
    // Exercises the boundary where HMAC key is exactly the hash block size.
    @Test
    fun hmacSha256KeyExactly64Bytes() {
        val key = ByteArray(64) { (it and 0xFF).toByte() }
        val data = "test data for 64-byte key".encodeToByteArray()

        val result = crypto.hmacSha256(key, data)
        assertEquals(32, result.size, "HMAC output must be 32 bytes with 64-byte key")
        // Verify determinism: same inputs → same output.
        val result2 = crypto.hmacSha256(key, data)
        assertContentEquals(result, result2, "HMAC must be deterministic")
    }
}
