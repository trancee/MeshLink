package io.meshlink.crypto

import io.meshlink.util.hexToBytes
import io.meshlink.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256Test {

    // ── SHA-256 NIST test vectors ───────────────────────────────────────

    @Test
    fun sha256EmptyString() {
        val digest = Sha256.hash(byteArrayOf())
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            digest.toHex(),
            "SHA-256 of empty string"
        )
    }

    @Test
    fun sha256Abc() {
        val digest = Sha256.hash("abc".encodeToByteArray())
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digest.toHex(),
            "SHA-256 of 'abc'"
        )
    }

    @Test
    fun sha256TwoBlocks() {
        val digest = Sha256.hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            digest.toHex(),
            "SHA-256 of 448-bit message (two blocks)"
        )
    }

    // ── HMAC-SHA-256 RFC 4231 test vectors ──────────────────────────────

    @Test
    fun hmacSha256TestCase1() {
        val key = ByteArray(20) { 0x0b.toByte() }
        val data = "Hi There".encodeToByteArray()
        val mac = HmacSha256.mac(key, data)
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            mac.toHex(),
            "HMAC-SHA-256 RFC 4231 Test Case 1"
        )
    }

    @Test
    fun hmacSha256TestCase2() {
        val key = "Jefe".encodeToByteArray()
        val data = "what do ya want for nothing?".encodeToByteArray()
        val mac = HmacSha256.mac(key, data)
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            mac.toHex(),
            "HMAC-SHA-256 RFC 4231 Test Case 2"
        )
    }

    // ── HKDF-SHA-256 RFC 5869 test vectors ──────────────────────────────

    @Test
    fun hkdfSha256TestCase1() {
        val ikm = ByteArray(22) { 0x0b.toByte() }
        val salt = hexToBytes("000102030405060708090a0b0c")
        val info = hexToBytes("f0f1f2f3f4f5f6f7f8f9")
        val expectedOkm = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"

        val okm = HkdfSha256.derive(ikm, salt, info, 42)
        assertEquals(expectedOkm, okm.toHex(), "HKDF-SHA-256 RFC 5869 Test Case 1")
    }
}
