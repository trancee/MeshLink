package io.meshlink.crypto

import io.meshlink.util.hexToBytes
import io.meshlink.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChaCha20Poly1305Test {

    // ---- RFC 8439 §2.1.1  Quarter Round ----

    @Test
    fun testQuarterRound() {
        val state = intArrayOf(
            0x11111111, 0x01020304, 0x9b8d6f43.toInt(), 0x01234567
        )
        ChaCha20Poly1305.quarterRound(state, 0, 1, 2, 3)
        assertEquals(0xea2a92f4.toInt(), state[0])
        assertEquals(0xcb1cf8ce.toInt(), state[1])
        assertEquals(0x4581472e, state[2])
        assertEquals(0x5881c4bb, state[3])
    }

    // ---- RFC 8439 §2.3.2  ChaCha20 Block Function ----

    @Test
    fun testChaCha20Block() {
        val key = hexToBytes(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        )
        val nonce = hexToBytes("000000090000004a00000000")
        val expected = hexToBytes(
            "10f1e7e4d13b5915500fdd1fa32071c4" +
                "c7d1f4c733c068030422aa9ac3d46c4e" +
                "d2826446079faa0914c2d705d98b02a2" +
                "b5129cd1de164eb9cbd083e8a2503c4e"
        )
        val block = ChaCha20Poly1305.chacha20Block(key, 1, nonce)
        assertContentEquals(expected, block)
    }

    // ---- RFC 8439 §2.5.2  Poly1305 MAC ----

    @Test
    fun testPoly1305Mac() {
        val key = hexToBytes(
            "85d6be7857556d337f4452fe42d506a8" +
                "0103808afb0db2fd4abff6af4149f51b"
        )
        val message = "Cryptographic Forum Research Group".encodeToByteArray()
        val expectedTag = hexToBytes("a8061dc1305136c6c22b8baf0c0127a9")

        val tag = ChaCha20Poly1305.poly1305Mac(key, message)
        assertEquals(expectedTag.toHex(), tag.toHex())
    }

    // ---- RFC 8439 §2.8.2  AEAD Encrypt / Decrypt ----

    @Test
    fun testAeadEncrypt() {
        val key = hexToBytes(
            "808182838485868788898a8b8c8d8e8f" +
                "909192939495969798999a9b9c9d9e9f"
        )
        val nonce = hexToBytes("070000004041424344454647")
        val aad = hexToBytes("50515253c0c1c2c3c4c5c6c7")
        val plaintext =
            "Ladies and Gentlemen of the class of '99: If I could offer you " +
                "only one tip for the future, sunscreen would be it."
        val expectedCiphertextWithTag = hexToBytes(
            "d31a8d34648e60db7b86afbc53ef7ec2" +
                "a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b" +
                "1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58" +
                "fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b" +
                "6116" +
                "1ae10b594f09e26a7e902ecbd0600691"
        )

        val result = ChaCha20Poly1305.encrypt(
            key, nonce, plaintext.encodeToByteArray(), aad
        )
        assertEquals(expectedCiphertextWithTag.toHex(), result.toHex())
    }

    @Test
    fun testAeadDecrypt() {
        val key = hexToBytes(
            "808182838485868788898a8b8c8d8e8f" +
                "909192939495969798999a9b9c9d9e9f"
        )
        val nonce = hexToBytes("070000004041424344454647")
        val aad = hexToBytes("50515253c0c1c2c3c4c5c6c7")
        val ciphertextWithTag = hexToBytes(
            "d31a8d34648e60db7b86afbc53ef7ec2" +
                "a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b" +
                "1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58" +
                "fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b" +
                "6116" +
                "1ae10b594f09e26a7e902ecbd0600691"
        )
        val expectedPlaintext =
            "Ladies and Gentlemen of the class of '99: If I could offer you " +
                "only one tip for the future, sunscreen would be it."

        val result = ChaCha20Poly1305.decrypt(key, nonce, ciphertextWithTag, aad)
        assertEquals(expectedPlaintext, result.decodeToString())
    }

    // ---- Round-trip ----

    @Test
    fun testRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 100).toByte() }
        val aad = "extra-data".encodeToByteArray()
        val plaintext = "Hello, MeshLink!".encodeToByteArray()

        val sealed = ChaCha20Poly1305.encrypt(key, nonce, plaintext, aad)
        val opened = ChaCha20Poly1305.decrypt(key, nonce, sealed, aad)
        assertContentEquals(plaintext, opened)
    }

    @Test
    fun testRoundTripEmptyPlaintext() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val nonce = ByteArray(12) { (it + 50).toByte() }
        val aad = "auth-only".encodeToByteArray()
        val plaintext = ByteArray(0)

        val sealed = ChaCha20Poly1305.encrypt(key, nonce, plaintext, aad)
        assertEquals(16, sealed.size) // tag only
        val opened = ChaCha20Poly1305.decrypt(key, nonce, sealed, aad)
        assertContentEquals(plaintext, opened)
    }

    // ---- Authentication failure ----

    @Test
    fun testAuthFailureOnTamperedCiphertext() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val aad = "aad".encodeToByteArray()
        val plaintext = "secret".encodeToByteArray()

        val sealed = ChaCha20Poly1305.encrypt(key, nonce, plaintext, aad)
        // Flip a bit in the ciphertext (not the tag)
        sealed[0] = (sealed[0].toInt() xor 1).toByte()

        assertFailsWith<IllegalArgumentException> {
            ChaCha20Poly1305.decrypt(key, nonce, sealed, aad)
        }
    }

    @Test
    fun testAuthFailureOnTamperedTag() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 1).toByte() }
        val aad = ByteArray(0)
        val plaintext = "data".encodeToByteArray()

        val sealed = ChaCha20Poly1305.encrypt(key, nonce, plaintext, aad)
        // Flip a bit in the tag (last byte)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()

        assertFailsWith<IllegalArgumentException> {
            ChaCha20Poly1305.decrypt(key, nonce, sealed, aad)
        }
    }
}
