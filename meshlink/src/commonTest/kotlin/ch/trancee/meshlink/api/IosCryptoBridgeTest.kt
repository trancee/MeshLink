package ch.trancee.meshlink.api

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IosCryptoBridgeTest {
    @AfterTest
    fun tearDown() {
        // Arrange / Act
        IosCryptoBridgeRegistry.clear()

        // Assert
        Unit
    }

    @Test
    fun `ios crypto raw key pair rejects private keys with the wrong size`() {
        // Arrange / Act
        val failure =
            assertFailsWith<IllegalArgumentException> {
                IosCryptoRawKeyPair(privateKey = ByteArray(31), publicKey = ByteArray(32))
            }

        // Assert
        assertEquals("privateKey must be 32 bytes", failure.message)
    }

    @Test
    fun `ios crypto raw key pair rejects public keys with the wrong size`() {
        // Arrange / Act
        val failure =
            assertFailsWith<IllegalArgumentException> {
                IosCryptoRawKeyPair(privateKey = ByteArray(32), publicKey = ByteArray(31))
            }

        // Assert
        assertEquals("publicKey must be 32 bytes", failure.message)
    }

    @Test
    fun `registry requireCallbacks fails when the bridge is not installed`() {
        // Arrange
        IosCryptoBridgeRegistry.clear()

        // Act
        val failure =
            assertFailsWith<MeshLinkException.PlatformFailure> {
                IosCryptoBridgeRegistry.requireCallbacks()
            }

        // Assert
        assertTrue(failure.message.orEmpty().contains("IosCryptoBridge.install"))
    }

    @Test
    fun `install registers callbacks that the registry exposes`() {
        // Arrange
        val randomBytesResult = byteArrayOf(1, 2, 3, 4)
        val sha256Result = byteArrayOf(5, 6, 7, 8)
        val hmacResult = byteArrayOf(9, 10, 11, 12)
        val x25519KeyPair =
            IosCryptoRawKeyPair(
                privateKey = ByteArray(32) { 0x11 },
                publicKey = ByteArray(32) { 0x22 },
            )
        val ed25519KeyPair =
            IosCryptoRawKeyPair(
                privateKey = ByteArray(32) { 0x33 },
                publicKey = ByteArray(32) { 0x44.toByte() },
            )
        val x25519Result = byteArrayOf(13, 14, 15, 16)
        val signatureResult = ByteArray(64) { index -> index.toByte() }
        val sealResult = byteArrayOf(17, 18, 19, 20)
        val openResult = byteArrayOf(21, 22, 23, 24)
        IosCryptoBridge.install(
            randomBytes = { randomBytesResult.copyOf() },
            sha256 = { sha256Result.copyOf() },
            hmacSha256 = { _, _ -> hmacResult.copyOf() },
            generateX25519KeyPair = { x25519KeyPair },
            generateEd25519KeyPair = { ed25519KeyPair },
            x25519 = { _, _ -> x25519Result.copyOf() },
            ed25519Sign = { _, _ -> signatureResult.copyOf() },
            ed25519Verify = { _, _, _ -> true },
            chacha20Poly1305Seal = { _, _, _, _ -> sealResult.copyOf() },
            chacha20Poly1305Open = { _, _, _, _ -> openResult.copyOf() },
        )

        // Act
        val callbacks = IosCryptoBridgeRegistry.requireCallbacks()

        // Assert
        assertContentEquals(randomBytesResult, callbacks.randomBytes(4))
        assertContentEquals(sha256Result, callbacks.sha256(byteArrayOf(1)))
        assertContentEquals(hmacResult, callbacks.hmacSha256(byteArrayOf(1), byteArrayOf(2)))
        assertContentEquals(x25519KeyPair.privateKey, callbacks.generateX25519KeyPair().privateKey)
        assertContentEquals(ed25519KeyPair.publicKey, callbacks.generateEd25519KeyPair().publicKey)
        assertContentEquals(x25519Result, callbacks.x25519(byteArrayOf(1), byteArrayOf(2)))
        assertContentEquals(signatureResult, callbacks.ed25519Sign(byteArrayOf(1), byteArrayOf(2)))
        assertEquals(true, callbacks.ed25519Verify(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)))
        assertContentEquals(
            sealResult,
            callbacks.chacha20Poly1305Seal(
                byteArrayOf(1),
                byteArrayOf(2),
                byteArrayOf(3),
                byteArrayOf(4),
            ),
        )
        assertContentEquals(
            openResult,
            callbacks.chacha20Poly1305Open(
                byteArrayOf(1),
                byteArrayOf(2),
                byteArrayOf(3),
                byteArrayOf(4),
            ),
        )
    }

    @Test
    fun `clear removes previously installed callbacks`() {
        // Arrange
        IosCryptoBridge.install(
            randomBytes = { ByteArray(it) },
            sha256 = { it },
            hmacSha256 = { _, data -> data },
            generateX25519KeyPair = { IosCryptoRawKeyPair(ByteArray(32), ByteArray(32)) },
            generateEd25519KeyPair = { IosCryptoRawKeyPair(ByteArray(32), ByteArray(32)) },
            x25519 = { _, publicKey -> publicKey },
            ed25519Sign = { _, message -> message },
            ed25519Verify = { _, _, _ -> true },
            chacha20Poly1305Seal = { _, _, _, plaintext -> plaintext },
            chacha20Poly1305Open = { _, _, _, ciphertext -> ciphertext },
        )
        IosCryptoBridgeRegistry.clear()

        // Act
        val failure =
            assertFailsWith<MeshLinkException.PlatformFailure> {
                IosCryptoBridgeRegistry.requireCallbacks()
            }

        // Assert
        assertTrue(failure.message.orEmpty().contains("IosCryptoBridge.install"))
    }
}
