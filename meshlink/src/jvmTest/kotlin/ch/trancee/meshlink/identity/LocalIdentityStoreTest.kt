package ch.trancee.meshlink.identity

import ch.trancee.meshlink.api.MeshLinkException
import ch.trancee.meshlink.crypto.JvmCryptoProvider
import ch.trancee.meshlink.storage.InMemorySecureStorage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LocalIdentityStoreTest {
    private val provider = JvmCryptoProvider()

    @Test
    fun `loadOrCreate keeps app identities isolated inside shared secure storage`() = runBlocking {
        // Arrange
        val storage = InMemorySecureStorage()
        val firstAppId = "demo.meshlink.alpha"
        val secondAppId = "demo.meshlink.beta"

        // Act
        val firstIdentity =
            LocalIdentityStore.loadOrCreate(
                appId = firstAppId,
                secureStorage = storage,
                provider = provider,
            )
        val secondIdentity =
            LocalIdentityStore.loadOrCreate(
                appId = secondAppId,
                secureStorage = storage,
                provider = provider,
            )
        val firstEd25519PrivateKey = storage.read("identity:$firstAppId:ed25519-private")
        val secondEd25519PrivateKey = storage.read("identity:$secondAppId:ed25519-private")

        // Assert
        assertTrue(firstIdentity.peerId.value.isNotBlank())
        assertTrue(secondIdentity.peerId.value.isNotBlank())
        assertTrue(firstIdentity.peerId.value != secondIdentity.peerId.value)
        assertTrue(firstIdentity.identityFingerprint != secondIdentity.identityFingerprint)
        assertContentEquals(
            firstIdentity.noiseIdentity.ed25519KeyPair.privateKey,
            firstEd25519PrivateKey,
        )
        assertContentEquals(
            secondIdentity.noiseIdentity.ed25519KeyPair.privateKey,
            secondEd25519PrivateKey,
        )
    }

    @Test
    fun `loadOrCreate persists and reloads the same local identity`() = runBlocking {
        // Arrange
        val storage = InMemorySecureStorage()
        val appId = "demo.meshlink"

        // Act
        val firstIdentity =
            LocalIdentityStore.loadOrCreate(
                appId = appId,
                secureStorage = storage,
                provider = provider,
            )
        val secondIdentity =
            LocalIdentityStore.loadOrCreate(
                appId = appId,
                secureStorage = storage,
                provider = provider,
            )

        // Assert
        assertEquals(firstIdentity.peerId.value, secondIdentity.peerId.value)
        assertEquals(firstIdentity.identityFingerprint, secondIdentity.identityFingerprint)
        assertContentEquals(firstIdentity.ed25519PublicKey, secondIdentity.ed25519PublicKey)
        assertContentEquals(firstIdentity.x25519PublicKey, secondIdentity.x25519PublicKey)
        assertEquals(12, firstIdentity.advertisementKeyHash.size)
        assertTrue(
            firstIdentity.peerId.value.isNotBlank(),
            "Provider-backed identities should derive a stable peer id",
        )
    }

    @Test
    fun `loadOrCreate fails closed when stored key material is incomplete`() {
        // Arrange
        val storage = InMemorySecureStorage()
        val appId = "broken.meshlink"
        runBlocking {
            storage.write("identity:$appId:ed25519-private", ByteArray(32) { 0x01.toByte() })
        }

        // Act
        val exception =
            assertFailsWith<MeshLinkException.StorageFailure> {
                runBlocking {
                    LocalIdentityStore.loadOrCreate(
                        appId = appId,
                        secureStorage = storage,
                        provider = provider,
                    )
                }
            }

        // Assert
        assertTrue(
            actual = exception.message.orEmpty().contains("incomplete"),
            message =
                "Incomplete stored identities must fail closed instead of silently rotating keys",
        )
    }

    @Test
    fun `loadOrCreate fails closed when stored key material has the wrong size`() {
        // Arrange
        val storage = InMemorySecureStorage()
        val appId = "invalid-size.meshlink"
        runBlocking {
            storage.write("identity:$appId:ed25519-private", ByteArray(31) { 0x01.toByte() })
            storage.write("identity:$appId:ed25519-public", ByteArray(32) { 0x02.toByte() })
            storage.write("identity:$appId:x25519-private", ByteArray(32) { 0x03.toByte() })
            storage.write("identity:$appId:x25519-public", ByteArray(32) { 0x04.toByte() })
        }

        // Act
        val exception =
            assertFailsWith<MeshLinkException.StorageFailure> {
                runBlocking {
                    LocalIdentityStore.loadOrCreate(
                        appId = appId,
                        secureStorage = storage,
                        provider = provider,
                    )
                }
            }

        // Assert
        assertTrue(
            actual = exception.message.orEmpty().contains("invalid size"),
            message = "Stored identities with malformed key sizes must fail closed",
        )
    }

    @Test
    fun `loadOrCreate reports which stored key has the wrong size`() {
        // Arrange
        val storage = InMemorySecureStorage()
        val appId = "invalid-x25519-public.meshlink"
        runBlocking {
            storage.write("identity:$appId:ed25519-private", ByteArray(32) { 0x01.toByte() })
            storage.write("identity:$appId:ed25519-public", ByteArray(32) { 0x02.toByte() })
            storage.write("identity:$appId:x25519-private", ByteArray(32) { 0x03.toByte() })
            storage.write("identity:$appId:x25519-public", ByteArray(31) { 0x04.toByte() })
        }

        // Act
        val exception =
            assertFailsWith<MeshLinkException.StorageFailure> {
                runBlocking {
                    LocalIdentityStore.loadOrCreate(
                        appId = appId,
                        secureStorage = storage,
                        provider = provider,
                    )
                }
            }

        // Assert
        assertTrue(exception.message.orEmpty().contains("x25519 public"))
        assertTrue(exception.message.orEmpty().contains("31"))
    }
}
