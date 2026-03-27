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

    // ---- RFC 8439 §2.4.2  ChaCha20 Encryption (Sunscreen) ----

    @Test
    fun testChaCha20EncryptSunscreen() {
        val key = hexToBytes(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        )
        val nonce = hexToBytes("000000000000004a00000000")
        val plaintext =
            "Ladies and Gentlemen of the class of '99: If I could offer you " +
                "only one tip for the future, sunscreen would be it."
        val expectedCiphertext = hexToBytes(
            "6e2e359a2568f98041ba0728dd0d6981" +
                "e97e7aec1d4360c20a27afccfd9fae0b" +
                "f91b65c5524733ab8f593dabcd62b357" +
                "1639d624e65152ab8f530c359f0861d8" +
                "07ca0dbf500d6a6156a38e088a22b65e" +
                "52bc514d16ccf806818ce91ab7793736" +
                "5af90bbf74a35be6b40b8eedf2785e42" +
                "874d"
        )

        val result = ChaCha20Poly1305.chacha20Encrypt(
            key, 1, nonce, plaintext.encodeToByteArray()
        )
        assertEquals(expectedCiphertext.toHex(), result.toHex())
    }

    // ---- RFC 8439 §2.6.2  Poly1305 Key Generation ----

    @Test
    fun testPoly1305KeyGeneration() {
        val key = hexToBytes(
            "808182838485868788898a8b8c8d8e8f" +
                "909192939495969798999a9b9c9d9e9f"
        )
        val nonce = hexToBytes("000000000001020304050607")
        val expectedPolyKey = hexToBytes(
            "8ad5a08b905f81cc815040274ab29471" +
                "a833b637e3fd0da508dbb8e2fdd1a646"
        )

        val block0 = ChaCha20Poly1305.chacha20Block(key, 0, nonce)
        val polyKey = block0.copyOf(32)
        assertEquals(expectedPolyKey.toHex(), polyKey.toHex())
    }

    // ---- RFC 8439 Appendix A.3  Poly1305 Edge Case Vectors ----

    @Test
    fun testPoly1305EdgeCaseVector5() {
        val key = hexToBytes(
            "0200000000000000000000000000000000000000000000000000000000000000"
        )
        val data = hexToBytes("ffffffffffffffffffffffffffffffff")
        val expectedTag = hexToBytes("03000000000000000000000000000000")

        val tag = ChaCha20Poly1305.poly1305Mac(key, data)
        assertEquals(expectedTag.toHex(), tag.toHex())
    }

    @Test
    fun testPoly1305EdgeCaseVector6() {
        val key = hexToBytes(
            "02000000000000000000000000000000ffffffffffffffffffffffffffffffff"
        )
        val data = hexToBytes("02000000000000000000000000000000")
        val expectedTag = hexToBytes("03000000000000000000000000000000")

        val tag = ChaCha20Poly1305.poly1305Mac(key, data)
        assertEquals(expectedTag.toHex(), tag.toHex())
    }

    @Test
    fun testPoly1305EdgeCaseVector7() {
        val key = hexToBytes(
            "0100000000000000000000000000000000000000000000000000000000000000"
        )
        val data = hexToBytes(
            "ffffffffffffffffffffffffffffffff" +
                "f0ffffffffffffffffffffffffffffff" +
                "11000000000000000000000000000000"
        )
        val expectedTag = hexToBytes("05000000000000000000000000000000")

        val tag = ChaCha20Poly1305.poly1305Mac(key, data)
        assertEquals(expectedTag.toHex(), tag.toHex())
    }

    @Test
    fun testPoly1305EdgeCaseVector8() {
        val key = hexToBytes(
            "0100000000000000000000000000000000000000000000000000000000000000"
        )
        val data = hexToBytes(
            "ffffffffffffffffffffffffffffffff" +
                "fbfefefefefefefefefefefefefefefe" +
                "01010101010101010101010101010101"
        )
        val expectedTag = hexToBytes("00000000000000000000000000000000")

        val tag = ChaCha20Poly1305.poly1305Mac(key, data)
        assertEquals(expectedTag.toHex(), tag.toHex())
    }

    @Test
    fun testPoly1305EdgeCaseVector9() {
        val key = hexToBytes(
            "0200000000000000000000000000000000000000000000000000000000000000"
        )
        val data = hexToBytes("fdffffffffffffffffffffffffffffff")
        val expectedTag = hexToBytes("faffffffffffffffffffffffffffffff")

        val tag = ChaCha20Poly1305.poly1305Mac(key, data)
        assertEquals(expectedTag.toHex(), tag.toHex())
    }

    @Test
    fun testPoly1305EdgeCaseVector10() {
        val key = hexToBytes(
            "0100000000000000040000000000000000000000000000000000000000000000"
        )
        val data = hexToBytes(
            "e33594d7505e43b900000000000000003394d7505e4379cd01000000000000000000000000000000000000000000000001000000000000000000000000000000"
        )
        val expectedTag = hexToBytes("14000000000000005500000000000000")

        val tag = ChaCha20Poly1305.poly1305Mac(key, data)
        assertEquals(expectedTag.toHex(), tag.toHex())
    }

    @Test
    fun testPoly1305EdgeCaseVector11() {
        val key = hexToBytes(
            "0100000000000000040000000000000000000000000000000000000000000000"
        )
        val data = hexToBytes(
            "e33594d7505e43b900000000000000003394d7505e4379cd010000000000000000000000000000000000000000000000"
        )
        val expectedTag = hexToBytes("13000000000000000000000000000000")

        val tag = ChaCha20Poly1305.poly1305Mac(key, data)
        assertEquals(expectedTag.toHex(), tag.toHex())
    }

    // ---- RFC 8439 Appendix A.5  AEAD Decryption ----

    @Test
    fun testAeadDecryptAppendixA5PolyKey() {
        val key = hexToBytes(
            "1c9240a5eb55d38af333888604f6b5f0473917c1402b80099dca5cbc207075c0"
        )
        val nonce = hexToBytes("000000000102030405060708")
        val block0 = ChaCha20Poly1305.chacha20Block(key, 0, nonce)
        val polyKey = block0.copyOf(32)
        val expectedPolyKey = hexToBytes(
            "bdf04aa95ce4de8995b14bb6a18fecaf26478f50c054f563dbc0a21e261572aa"
        )
        assertEquals(expectedPolyKey.toHex(), polyKey.toHex())
    }

    @Test
    fun testAeadDecryptAppendixA5() {
        val key = hexToBytes(
            "1c9240a5eb55d38af333888604f6b5f0473917c1402b80099dca5cbc207075c0"
        )
        val nonce = hexToBytes("000000000102030405060708")
        val aad = hexToBytes("f33388860000000000004e91")
        val ciphertextWithTag = hexToBytes(
            "64a0861575861af460f062c79be643bd" +
                "5e805cfd345cf389f108670ac76c8cb2" +
                "4c6cfc18755d43eea09ee94e382d26b0" +
                "bdb7b73c321b0100d4f03b7f355894cf" +
                "332f830e710b97ce98c8a84abd0b9481" +
                "14ad176e008d33bd60f982b1ff37c855" +
                "9797a06ef4f0ef61c186324e2b350638" +
                "3606907b6a7c02b0f9f6157b53c867e4" +
                "b9166c767b804d46a59b5216cde7a4e9" +
                "9040c5a40433225ee282a1b0a06c523e" +
                "af4534d7f83fa1155b0047718cbc546a" +
                "0d072b04b3564eea1b422273f548271a" +
                "0bb2316053fa76991955ebd63159434e" +
                "cebb4e466dae5a1073a6727627097a10" +
                "49e617d91d361094fa68f0ff77987130" +
                "305beaba2eda04df997b714d6c6f2c29" +
                "a6ad5cb4022b02709b" +
                "eead9d67890cbb22392336fea1851f38"
        )
        val expectedPlaintext =
            "Internet-Drafts are draft documents valid for a maximum of six months" +
                " and may be updated, replaced, or obsoleted by other documents at any time." +
                " It is inappropriate to use Internet-Drafts as reference material or to cite" +
                " them other than as /\u201Cwork in progress./\u201D"

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
