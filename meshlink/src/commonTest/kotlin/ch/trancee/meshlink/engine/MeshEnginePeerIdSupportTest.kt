package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.handshake.canonicalPeerIdForTemporaryTransportPeer
import ch.trancee.meshlink.engine.handshake.isTemporaryTransportPeerId
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.identity.fromAppId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeshEnginePeerIdSupportTest {
    @Test
    fun `temporary transport peer ids are detected across platform prefixes`() {
        // Arrange
        val androidTemporaryPeerId = PeerId("bt-001122334455")
        val iosTemporaryPeerId = PeerId("cb-001122334455")
        val canonicalPeerId = PeerId("00112233445566778899aabb")

        // Act
        val androidDetected = androidTemporaryPeerId.isTemporaryTransportPeerId()
        val iosDetected = iosTemporaryPeerId.isTemporaryTransportPeerId()
        val canonicalDetected = canonicalPeerId.isTemporaryTransportPeerId()

        // Assert
        assertTrue(androidDetected)
        assertTrue(iosDetected)
        assertFalse(canonicalDetected)
    }

    @Test
    fun `canonical peer id for temporary peers uses the advertisement hash prefix`() {
        // Arrange
        val localIdentity = LocalIdentity.fromAppId("temporary-peer-support-test")
        val remoteIdentity = LocalIdentity.fromAppId("temporary-peer-support-remote")
        val temporaryPeerId = PeerId("bt-aabbccddeeff")
        val expectedPeerId =
            PeerId(
                localIdentity.cryptoProvider
                    .sha256(remoteIdentity.ed25519PublicKey + remoteIdentity.x25519PublicKey)
                    .copyOfRange(0, 12)
                    .joinToString(separator = "") { byte ->
                        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
                    }
            )

        // Act
        val canonicalPeerId =
            canonicalPeerIdForTemporaryTransportPeer(
                peerId = temporaryPeerId,
                remoteEd25519PublicKey = remoteIdentity.ed25519PublicKey,
                remoteX25519PublicKey = remoteIdentity.x25519PublicKey,
                cryptoProvider = localIdentity.cryptoProvider,
            )

        // Assert
        assertEquals(expectedPeerId.value, canonicalPeerId.value)
    }

    @Test
    fun `canonical peer id helper leaves non temporary peers unchanged`() {
        // Arrange
        val localIdentity = LocalIdentity.fromAppId("canonical-peer-support-test")
        val remoteIdentity = LocalIdentity.fromAppId("canonical-peer-support-remote")
        val canonicalPeerId = PeerId("00112233445566778899aabb")

        // Act
        val resolvedPeerId =
            canonicalPeerIdForTemporaryTransportPeer(
                peerId = canonicalPeerId,
                remoteEd25519PublicKey = remoteIdentity.ed25519PublicKey,
                remoteX25519PublicKey = remoteIdentity.x25519PublicKey,
                cryptoProvider = localIdentity.cryptoProvider,
            )

        // Assert
        assertEquals(canonicalPeerId.value, resolvedPeerId.value)
    }
}
