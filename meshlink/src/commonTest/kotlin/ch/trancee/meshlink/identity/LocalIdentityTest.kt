package ch.trancee.meshlink.identity

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.Ed25519KeyPair
import ch.trancee.meshlink.crypto.NoiseIdentity
import ch.trancee.meshlink.crypto.PlaceholderCryptoProvider
import ch.trancee.meshlink.crypto.X25519KeyPair
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

class LocalIdentityTest {
    @Test
    fun `fromAppId is deterministic and uses the app id as the peer id`() {
        // Arrange
        val appId = "demo.meshlink.identity"

        // Act
        val first = LocalIdentity.fromAppId(appId)
        val second = LocalIdentity.fromAppId(appId)

        // Assert
        assertEquals(appId, first.peerId.value)
        assertEquals(first.peerId.value, second.peerId.value)
        assertContentEquals(first.identityFingerprintBytes, second.identityFingerprintBytes)
        assertEquals(first.identityFingerprintBytes.toHexString(), first.identityFingerprint)
        assertContentEquals(
            first.identityFingerprintBytes.copyOfRange(0, first.advertisementKeyHash.size),
            first.advertisementKeyHash,
        )
        assertContentEquals(first.ed25519PublicKey, second.ed25519PublicKey)
        assertContentEquals(first.x25519PublicKey, second.x25519PublicKey)
        assertSame(PlaceholderCryptoProvider, first.cryptoProvider)
    }

    @Test
    fun `fromPeerId preserves the explicit peer id while the identity seed drives the keys`() {
        // Arrange
        val peerId = PeerId("fixed-peer-id")

        // Act
        val first = LocalIdentity.fromPeerId(peerId = peerId, identitySeed = "seed-a")
        val second = LocalIdentity.fromPeerId(peerId = peerId, identitySeed = "seed-b")

        // Assert
        assertEquals(peerId.value, first.peerId.value)
        assertEquals(peerId.value, second.peerId.value)
        assertFalse(first.identityFingerprintBytes.contentEquals(second.identityFingerprintBytes))
        assertFalse(first.ed25519PublicKey.contentEquals(second.ed25519PublicKey))
        assertFalse(first.x25519PublicKey.contentEquals(second.x25519PublicKey))
    }

    @Test
    fun `fromNoiseIdentity derives hashes from public keys and copies source arrays`() {
        // Arrange
        val originalEd25519PublicKey = ByteArray(32) { index -> (index + 1).toByte() }
        val originalX25519PublicKey = ByteArray(32) { index -> (index + 65).toByte() }
        val noiseIdentity =
            NoiseIdentity(
                ed25519KeyPair =
                    Ed25519KeyPair(
                        privateKey = ByteArray(32) { 0x11 },
                        publicKey = originalEd25519PublicKey,
                    ),
                x25519KeyPair =
                    X25519KeyPair(
                        privateKey = ByteArray(32) { 0x22 },
                        publicKey = originalX25519PublicKey,
                    ),
            )
        val expectedFingerprintBytes =
            PlaceholderCryptoProvider.sha256(originalEd25519PublicKey + originalX25519PublicKey)
        val expectedDerivedPeerId = expectedFingerprintBytes.copyOfRange(0, 12).toHexString()
        val explicitPeerId = PeerId("override-peer-id")

        // Act
        val derived =
            LocalIdentity.fromNoiseIdentity(
                noiseIdentity = noiseIdentity,
                provider = PlaceholderCryptoProvider,
            )
        val overridden =
            LocalIdentity.fromNoiseIdentity(
                noiseIdentity = noiseIdentity,
                provider = PlaceholderCryptoProvider,
                peerId = explicitPeerId,
            )
        originalEd25519PublicKey[0] = 0x7F
        originalX25519PublicKey[0] = 0x6F

        // Assert
        assertEquals(expectedDerivedPeerId, derived.peerId.value)
        assertContentEquals(expectedFingerprintBytes, derived.identityFingerprintBytes)
        assertEquals(expectedFingerprintBytes.toHexString(), derived.identityFingerprint)
        assertContentEquals(
            expectedFingerprintBytes.copyOfRange(0, derived.advertisementKeyHash.size),
            derived.advertisementKeyHash,
        )
        assertContentEquals(
            ByteArray(32) { index -> (index + 1).toByte() },
            derived.ed25519PublicKey,
        )
        assertContentEquals(
            ByteArray(32) { index -> (index + 65).toByte() },
            derived.x25519PublicKey,
        )
        assertEquals(explicitPeerId.value, overridden.peerId.value)
        assertSame(PlaceholderCryptoProvider, derived.cryptoProvider)
    }

    @Test
    fun `meshDomainHash defaults to an empty prologue when not specified`() {
        // Arrange & Act
        val identity = LocalIdentity.fromAppId("demo.meshlink.mesh-domain-default")

        // Assert
        assertContentEquals(ByteArray(0), identity.meshDomainHash)
    }

    @Test
    fun `computeMeshDomainHash is deterministic for the same app id and diverges across app ids`() {
        // Arrange
        val appId = "mesh-a"
        val otherAppId = "mesh-b"

        // Act
        val first = LocalIdentity.computeMeshDomainHash(appId, PlaceholderCryptoProvider)
        val second = LocalIdentity.computeMeshDomainHash(appId, PlaceholderCryptoProvider)
        val other = LocalIdentity.computeMeshDomainHash(otherAppId, PlaceholderCryptoProvider)

        // Assert
        assertContentEquals(first, second)
        assertFalse(first.contentEquals(other))
    }

    @Test
    fun `factories thread an explicit meshDomainHash through to the identity`() {
        // Arrange
        val meshDomainHash =
            LocalIdentity.computeMeshDomainHash("demo.mesh", PlaceholderCryptoProvider)

        // Act
        val fromAppId =
            LocalIdentity.fromAppId(appId = "demo.mesh", meshDomainHash = meshDomainHash)
        val fromPeerId =
            LocalIdentity.fromPeerId(
                peerId = PeerId("demo-peer"),
                identitySeed = "demo-seed",
                meshDomainHash = meshDomainHash,
            )
        val fromNoiseIdentity =
            LocalIdentity.fromNoiseIdentity(
                noiseIdentity = NoiseIdentity.generate(PlaceholderCryptoProvider),
                provider = PlaceholderCryptoProvider,
                meshDomainHash = meshDomainHash,
            )

        // Assert
        assertContentEquals(meshDomainHash, fromAppId.meshDomainHash)
        assertContentEquals(meshDomainHash, fromPeerId.meshDomainHash)
        assertContentEquals(meshDomainHash, fromNoiseIdentity.meshDomainHash)
    }
}
