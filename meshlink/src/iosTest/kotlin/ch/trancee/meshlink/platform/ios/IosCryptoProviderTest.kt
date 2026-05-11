package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.IosCryptoBridge
import ch.trancee.meshlink.api.IosCryptoBridgeRegistry
import ch.trancee.meshlink.api.IosCryptoRawKeyPair
import ch.trancee.meshlink.api.MeshLinkException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IosCryptoProviderTest {
    @AfterTest
    fun tearDown(): Unit {
        IosCryptoBridgeRegistry.clear()
    }

    @Test
    fun randomBytesFailsWhenBridgeIsMissing(): Unit {
        // Arrange
        IosCryptoBridgeRegistry.clear()
        val provider = IosCryptoProvider()

        // Act
        val exception = assertFailsWith<MeshLinkException.PlatformFailure> {
            provider.randomBytes(size = 4)
        }

        // Assert
        assertTrue(
            actual = exception.message.orEmpty().contains("IosCryptoBridge.install"),
            message = "Missing bridge failures should direct iOS callers to install native callbacks",
        )
    }

    @Test
    fun randomBytesDelegatesToInstalledBridge(): Unit {
        // Arrange
        val provider = IosCryptoProvider()
        val expected = byteArrayOf(1, 2, 3, 4)
        installTestBridge(
            randomBytes = { size ->
                assertEquals(expected.size, size, "The provider should forward the requested random byte count")
                expected.copyOf()
            },
        )

        // Act
        val actual = provider.randomBytes(size = expected.size)

        // Assert
        assertContentEquals(expected, actual, "The provider should return the bytes produced by the installed bridge")
    }

    @Test
    fun x25519KeyPairDelegatesToInstalledBridge(): Unit {
        // Arrange
        val provider = IosCryptoProvider()
        val expectedPrivateKey = ByteArray(32) { 0x11.toByte() }
        val expectedPublicKey = ByteArray(32) { 0x22.toByte() }
        installTestBridge(
            generateX25519KeyPair = {
                IosCryptoRawKeyPair(
                    privateKey = expectedPrivateKey.copyOf(),
                    publicKey = expectedPublicKey.copyOf(),
                )
            },
        )

        // Act
        val actual = provider.generateX25519KeyPair()

        // Assert
        assertContentEquals(expectedPrivateKey, actual.privateKey, "The provider should expose the bridged X25519 private key")
        assertContentEquals(expectedPublicKey, actual.publicKey, "The provider should expose the bridged X25519 public key")
    }

    private fun installTestBridge(
        randomBytes: (Int) -> ByteArray = { size -> ByteArray(size) },
        sha256: (ByteArray) -> ByteArray = { input -> input },
        hmacSha256: (ByteArray, ByteArray) -> ByteArray = { _, data -> data },
        generateX25519KeyPair: () -> IosCryptoRawKeyPair = {
            IosCryptoRawKeyPair(privateKey = ByteArray(32), publicKey = ByteArray(32))
        },
        generateEd25519KeyPair: () -> IosCryptoRawKeyPair = {
            IosCryptoRawKeyPair(privateKey = ByteArray(32), publicKey = ByteArray(32))
        },
        x25519: (ByteArray, ByteArray) -> ByteArray = { _, publicKey -> publicKey },
        ed25519Sign: (ByteArray, ByteArray) -> ByteArray = { _, message -> message },
        ed25519Verify: (ByteArray, ByteArray, ByteArray) -> Boolean = { _, _, _ -> true },
        chacha20Poly1305Seal: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray = { _, _, _, plaintext -> plaintext },
        chacha20Poly1305Open: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray = { _, _, _, ciphertext -> ciphertext },
    ): Unit {
        IosCryptoBridge.install(
            randomBytes = randomBytes,
            sha256 = sha256,
            hmacSha256 = hmacSha256,
            generateX25519KeyPair = generateX25519KeyPair,
            generateEd25519KeyPair = generateEd25519KeyPair,
            x25519 = x25519,
            ed25519Sign = ed25519Sign,
            ed25519Verify = ed25519Verify,
            chacha20Poly1305Seal = chacha20Poly1305Seal,
            chacha20Poly1305Open = chacha20Poly1305Open,
        )
    }
}
