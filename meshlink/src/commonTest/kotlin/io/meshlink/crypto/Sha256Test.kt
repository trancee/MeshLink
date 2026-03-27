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

    @Test
    fun sha256RepeatedPattern() {
        val input = "0123456701234567012345670123456701234567012345670123456701234567".repeat(10).encodeToByteArray()
        val digest = Sha256.hash(input)
        assertEquals(
            "594847328451bdfa85056225462cc1d867d877fb388df0ce35f25ab5562bfbb5",
            digest.toHex(),
            "SHA-256 RFC 6234 Test 4 (320-byte repeated pattern)"
        )
    }

    @Test
    fun sha256MillionAs() {
        val input = "a".repeat(1_000_000).encodeToByteArray()
        val digest = Sha256.hash(input)
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            digest.toHex(),
            "SHA-256 RFC 6234 Test 3 (1,000,000 × 'a')"
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

    @Test
    fun hmacSha256TestCase3() {
        val key = ByteArray(20) { 0xaa.toByte() }
        val data = ByteArray(50) { 0xdd.toByte() }
        val mac = HmacSha256.mac(key, data)
        assertEquals(
            "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe",
            mac.toHex(),
            "HMAC-SHA-256 RFC 4231 Test Case 3"
        )
    }

    @Test
    fun hmacSha256TestCase4() {
        val key = ByteArray(25) { (it + 1).toByte() }
        val data = ByteArray(50) { 0xcd.toByte() }
        val mac = HmacSha256.mac(key, data)
        assertEquals(
            "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b",
            mac.toHex(),
            "HMAC-SHA-256 RFC 4231 Test Case 4"
        )
    }

    @Test
    fun hmacSha256TestCase5() {
        // RFC 4231 Test Case 5: truncation to 128 bits (16 bytes)
        val key = ByteArray(20) { 0x0c.toByte() }
        val data = "Test With Truncation".encodeToByteArray()
        val mac = HmacSha256.mac(key, data)
        assertEquals(
            "a3b6167473100ee06e0c796c2955552b",
            mac.copyOf(16).toHex(),
            "HMAC-SHA-256 RFC 4231 Test Case 5 (truncated to 128 bits)"
        )
    }

    @Test
    fun hmacSha256TestCase6() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val data = "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray()
        val mac = HmacSha256.mac(key, data)
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            mac.toHex(),
            "HMAC-SHA-256 RFC 4231 Test Case 6"
        )
    }

    @Test
    fun hmacSha256TestCase7() {
        val key = ByteArray(131) { 0xaa.toByte() }
        val data = ("This is a test using a larger than block-size key and a larger than block-size data. " +
            "The key needs to be hashed before being used by the HMAC algorithm.").encodeToByteArray()
        val mac = HmacSha256.mac(key, data)
        assertEquals(
            "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2",
            mac.toHex(),
            "HMAC-SHA-256 RFC 4231 Test Case 7"
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

    @Test
    fun hkdfSha256TestCase2() {
        val ikm = ByteArray(80) { it.toByte() }               // 0x00..0x4f
        val salt = ByteArray(80) { (it + 0x60).toByte() }     // 0x60..0xaf
        val info = ByteArray(80) { (it + 0xb0).toByte() }     // 0xb0..0xff
        val expectedOkm = "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
            "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
            "cc30c58179ec3e87c14c01d5c1f3434f1d87"

        val okm = HkdfSha256.derive(ikm, salt, info, 82)
        assertEquals(expectedOkm, okm.toHex(), "HKDF-SHA-256 RFC 5869 Test Case 2")
    }

    @Test
    fun hkdfSha256TestCase3() {
        val ikm = ByteArray(22) { 0x0b.toByte() }
        val salt = ByteArray(0)
        val info = ByteArray(0)
        val expectedOkm = "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
            "9d201395faa4b61a96c8"

        val okm = HkdfSha256.derive(ikm, salt, info, 42)
        assertEquals(expectedOkm, okm.toHex(), "HKDF-SHA-256 RFC 5869 Test Case 3")
    }
}
