package ch.trancee.meshlink.api

import ch.trancee.meshlink.api.apple.ChaCha20Poly1305Callbacks
import ch.trancee.meshlink.api.apple.CryptoBridge
import ch.trancee.meshlink.api.apple.CryptoBridgeRegistry
import ch.trancee.meshlink.api.apple.CryptoCallbacks
import ch.trancee.meshlink.api.apple.CryptoRawKeyPair
import ch.trancee.meshlink.api.apple.Ed25519Callbacks
import ch.trancee.meshlink.api.apple.HashCallbacks
import ch.trancee.meshlink.api.apple.KeyGenerationCallbacks
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CryptoBridgeTest {
    @AfterTest
    fun tearDown() {
        // Arrange / Act
        CryptoBridgeRegistry.clear()

        // Assert
        Unit
    }

    @Test
    fun `ios crypto raw key pair rejects private keys with the wrong size`() {
        // Arrange / Act
        val failure =
            assertFailsWith<IllegalArgumentException> {
                CryptoRawKeyPair(privateKey = ByteArray(31), publicKey = ByteArray(32))
            }

        // Assert
        assertEquals("privateKey must be 32 bytes", failure.message)
    }

    @Test
    fun `ios crypto raw key pair rejects public keys with the wrong size`() {
        // Arrange / Act
        val failure =
            assertFailsWith<IllegalArgumentException> {
                CryptoRawKeyPair(privateKey = ByteArray(32), publicKey = ByteArray(31))
            }

        // Assert
        assertEquals("publicKey must be 32 bytes", failure.message)
    }

    @Test
    fun `ios crypto raw key pair stores defensive copies`() {
        // Arrange
        val originalPrivateKey = ByteArray(32) { 0x11 }
        val originalPublicKey = ByteArray(32) { 0x22 }
        val keyPair =
            CryptoRawKeyPair(privateKey = originalPrivateKey, publicKey = originalPublicKey)

        // Act
        originalPrivateKey[0] = 0x7F
        originalPublicKey[0] = 0x6E
        val privateKeySnapshot = keyPair.privateKey
        val publicKeySnapshot = keyPair.publicKey
        privateKeySnapshot[1] = 0x5D
        publicKeySnapshot[1] = 0x4C

        // Assert
        assertEquals(0x11, keyPair.privateKey[0].toInt() and 0xFF)
        assertEquals(0x22, keyPair.publicKey[0].toInt() and 0xFF)
        assertEquals(0x11, keyPair.privateKey[1].toInt() and 0xFF)
        assertEquals(0x22, keyPair.publicKey[1].toInt() and 0xFF)
    }

    @Test
    fun `registry requireCallbacks fails when the bridge is not installed`() {
        // Arrange
        CryptoBridgeRegistry.clear()

        // Act
        val failure =
            assertFailsWith<MeshLinkException.PlatformFailure> {
                CryptoBridgeRegistry.requireCallbacks()
            }

        // Assert
        assertTrue(failure.message.orEmpty().contains("CryptoBridge.install"))
    }

    @Test
    fun `install registers callbacks that the registry exposes`() {
        // Arrange
        val randomBytesResult = byteArrayOf(1, 2, 3, 4)
        val sha256Result = byteArrayOf(5, 6, 7, 8)
        val hmacResult = byteArrayOf(9, 10, 11, 12)
        val x25519KeyPair =
            CryptoRawKeyPair(
                privateKey = ByteArray(32) { 0x11 },
                publicKey = ByteArray(32) { 0x22 },
            )
        val ed25519KeyPair =
            CryptoRawKeyPair(
                privateKey = ByteArray(32) { 0x33 },
                publicKey = ByteArray(32) { 0x44.toByte() },
            )
        val x25519Result = byteArrayOf(13, 14, 15, 16)
        val signatureResult = ByteArray(64) { index -> index.toByte() }
        val sealResult = byteArrayOf(17, 18, 19, 20)
        val openResult = byteArrayOf(21, 22, 23, 24)
        CryptoBridge.install(
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
        val callbacks = CryptoBridgeRegistry.requireCallbacks()

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
    fun `install accepts grouped callback objects`() {
        // Arrange
        val randomBytesResult = byteArrayOf(31, 32, 33, 34)
        val sha256Result = byteArrayOf(35, 36, 37, 38)
        val hmacResult = byteArrayOf(39, 40, 41, 42)
        val x25519KeyPair =
            CryptoRawKeyPair(
                privateKey = ByteArray(32) { 0x55 },
                publicKey = ByteArray(32) { 0x66 },
            )
        val ed25519KeyPair =
            CryptoRawKeyPair(
                privateKey = ByteArray(32) { 0x77 },
                publicKey = ByteArray(32) { 0x88.toByte() },
            )
        val x25519Result = byteArrayOf(43, 44, 45, 46)
        val signatureResult = ByteArray(64) { index -> (index + 10).toByte() }
        val sealResult = byteArrayOf(47, 48, 49, 50)
        val openResult = byteArrayOf(51, 52, 53, 54)
        val callbacks =
            CryptoCallbacks(
                randomBytes = { randomBytesResult.copyOf() },
                hashes =
                    HashCallbacks(
                        sha256 = { sha256Result.copyOf() },
                        hmacSha256 = { _, _ -> hmacResult.copyOf() },
                    ),
                keyGeneration =
                    KeyGenerationCallbacks(
                        generateX25519KeyPair = { x25519KeyPair },
                        generateEd25519KeyPair = { ed25519KeyPair },
                    ),
                x25519 = { _, _ -> x25519Result.copyOf() },
                ed25519 =
                    Ed25519Callbacks(
                        sign = { _, _ -> signatureResult.copyOf() },
                        verify = { _, _, _ -> true },
                    ),
                chacha20Poly1305 =
                    ChaCha20Poly1305Callbacks(
                        seal = { _, _, _, _ -> sealResult.copyOf() },
                        open = { _, _, _, _ -> openResult.copyOf() },
                    ),
            )
        CryptoBridge.install(callbacks = callbacks)

        // Act
        val installedCallbacks = CryptoBridgeRegistry.requireCallbacks()

        // Assert
        assertContentEquals(randomBytesResult, installedCallbacks.randomBytes(4))
        assertContentEquals(sha256Result, installedCallbacks.sha256(byteArrayOf(1)))
        assertContentEquals(
            hmacResult,
            installedCallbacks.hmacSha256(byteArrayOf(1), byteArrayOf(2)),
        )
        assertContentEquals(
            x25519KeyPair.privateKey,
            installedCallbacks.generateX25519KeyPair().privateKey,
        )
        assertContentEquals(
            ed25519KeyPair.publicKey,
            installedCallbacks.generateEd25519KeyPair().publicKey,
        )
        assertContentEquals(x25519Result, installedCallbacks.x25519(byteArrayOf(1), byteArrayOf(2)))
        assertContentEquals(
            signatureResult,
            installedCallbacks.ed25519Sign(byteArrayOf(1), byteArrayOf(2)),
        )
        assertEquals(
            true,
            installedCallbacks.ed25519Verify(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)),
        )
        assertContentEquals(
            sealResult,
            installedCallbacks.chacha20Poly1305Seal(
                byteArrayOf(1),
                byteArrayOf(2),
                byteArrayOf(3),
                byteArrayOf(4),
            ),
        )
        assertContentEquals(
            openResult,
            installedCallbacks.chacha20Poly1305Open(
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
        CryptoBridge.install(
            randomBytes = { ByteArray(it) },
            sha256 = { it },
            hmacSha256 = { _, data -> data },
            generateX25519KeyPair = { CryptoRawKeyPair(ByteArray(32), ByteArray(32)) },
            generateEd25519KeyPair = { CryptoRawKeyPair(ByteArray(32), ByteArray(32)) },
            x25519 = { _, publicKey -> publicKey },
            ed25519Sign = { _, message -> message },
            ed25519Verify = { _, _, _ -> true },
            chacha20Poly1305Seal = { _, _, _, plaintext -> plaintext },
            chacha20Poly1305Open = { _, _, _, ciphertext -> ciphertext },
        )
        CryptoBridgeRegistry.clear()

        // Act
        val failure =
            assertFailsWith<MeshLinkException.PlatformFailure> {
                CryptoBridgeRegistry.requireCallbacks()
            }

        // Assert
        assertTrue(failure.message.orEmpty().contains("CryptoBridge.install"))
    }
}
