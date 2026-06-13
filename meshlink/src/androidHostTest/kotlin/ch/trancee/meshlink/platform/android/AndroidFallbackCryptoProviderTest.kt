package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.MeshLinkException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidFallbackCryptoProviderTest {
    private val provider = AndroidFallbackCryptoProvider()

    @Test
    fun `x25519 matches rfc 7748 test vector 1`() {
        // Arrange
        val scalar = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val uCoordinate = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        val expected = hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552")

        // Act
        val actual = provider.x25519(scalar, uCoordinate)

        // Assert
        assertContentEquals(expected, actual)
    }

    @Test
    fun `x25519 shared secret is symmetric`() {
        // Arrange
        val alice = provider.generateX25519KeyPair()
        val bob = provider.generateX25519KeyPair()

        // Act
        val aliceShared = provider.x25519(alice.privateKey, bob.publicKey)
        val bobShared = provider.x25519(bob.privateKey, alice.publicKey)

        // Assert
        assertEquals(32, aliceShared.size)
        assertContentEquals(aliceShared, bobShared)
        assertTrue(aliceShared.any { byte -> byte != 0.toByte() })
    }

    @Test
    fun `chacha20 poly1305 matches rfc 8439 aead vector`() {
        // Arrange
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plaintext =
            hex(
                "4c616469657320616e642047656e746c656d656e206f662074686520636c617373206f66202739393a204966204920636f756c64206f6666657220796f75206f6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73637265656e20776f756c642062652069742e"
            )
        val expected =
            hex(
                "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691"
            )

        // Act
        val actual = provider.chacha20Poly1305Seal(key, nonce, aad, plaintext)

        // Assert
        assertContentEquals(expected, actual)
    }

    @Test
    fun `sha256 matches standard vector for abc`() {
        // Arrange
        val input = "abc".encodeToByteArray()
        val expected = hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

        // Act
        val actual = provider.sha256(input)

        // Assert
        assertContentEquals(expected, actual)
    }

    @Test
    fun `hmac sha256 matches standard vector`() {
        // Arrange
        val key = "key".encodeToByteArray()
        val data = "The quick brown fox jumps over the lazy dog".encodeToByteArray()
        val expected = hex("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8")

        // Act
        val actual = provider.hmacSha256(key, data)

        // Assert
        assertContentEquals(expected, actual)
    }

    @Test
    fun `ed25519 matches rfc 8032 test vector 1`() {
        // Arrange
        val privateKey = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val publicKey = hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val message = byteArrayOf()
        val expectedSignature =
            hex(
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
            )

        // Act
        val actualSignature = provider.ed25519Sign(privateKey, message)
        val isValid = provider.ed25519Verify(publicKey, message, actualSignature)

        // Assert
        assertContentEquals(expectedSignature, actualSignature)
        assertTrue(isValid)
    }

    @Test
    fun `chacha20 poly1305 round trips`() {
        // Arrange
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val nonce = ByteArray(12) { index -> (index + 2).toByte() }
        val aad = byteArrayOf(0x09, 0x08)
        val plaintext = byteArrayOf(0x01, 0x02, 0x03, 0x04)

        // Act
        val ciphertext = provider.chacha20Poly1305Seal(key, nonce, aad, plaintext)
        val decrypted = provider.chacha20Poly1305Open(key, nonce, aad, ciphertext)

        // Assert
        assertTrue(ciphertext.size > plaintext.size)
        assertContentEquals(plaintext, decrypted)
    }

    @Test
    fun `chacha20 poly1305 rejects tampered tag`() {
        // Arrange
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val nonce = ByteArray(12) { index -> (index + 2).toByte() }
        val aad = byteArrayOf(0x09, 0x08)
        val plaintext = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val ciphertext = provider.chacha20Poly1305Seal(key, nonce, aad, plaintext)
        val tampered =
            ciphertext.copyOf().also {
                it[it.lastIndex] = (it[it.lastIndex].toInt() xor 0x01).toByte()
            }

        // Act / Assert
        assertFailsWith<MeshLinkException.CryptoFailure> {
            provider.chacha20Poly1305Open(key, nonce, aad, tampered)
        }
    }

    private fun hex(value: String): ByteArray {
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
