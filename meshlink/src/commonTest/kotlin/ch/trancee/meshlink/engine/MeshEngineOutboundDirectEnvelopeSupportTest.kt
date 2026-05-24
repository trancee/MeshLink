package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeshEngineOutboundDirectEnvelopeSupportTest {
    @Test
    fun `prepare returns ready when recipient trust already exists`() = runBlocking {
        // Arrange
        val localIdentity = LocalIdentity.fromAppId("direct-envelope-local")
        val recipientIdentity = LocalIdentity.fromAppId("direct-envelope-recipient")
        val trustStore = TofuTrustStore(InMemorySecureStorage())
        trustStore.write(trustRecordFor(recipientIdentity))
        val recipientTrustSupport =
            MeshEngineOutboundRecipientTrustSupport(
                localIdentity = localIdentity,
                trustStore = trustStore,
                routeCoordinator =
                    ch.trancee.meshlink.routing.RouteCoordinator(localIdentity.peerId),
                emitDiagnostic = { _, _, _, _, _, _ -> },
            )
        val support =
            MeshEngineOutboundDirectEnvelopeSupport(
                localIdentity = localIdentity,
                recipientTrustSupport = recipientTrustSupport,
            )
        val encryptFailures = mutableListOf<Pair<PeerId, String>>()

        // Act
        val preparation =
            support.prepare(
                peerId = recipientIdentity.peerId,
                payload = "hello".encodeToByteArray(),
                emitEncryptFailure = { peerId, cause -> encryptFailures += peerId to cause },
            )

        // Assert
        val ready = assertIs<MeshEngineOutboundDirectEnvelopePreparation.Ready>(preparation)
        val envelope = DirectMessageEnvelope.decode(ready.envelopeBytes)
        assertEquals(localIdentity.peerId.value, envelope.senderPeerId.value)
        assertContentEquals(localIdentity.identityFingerprintBytes, envelope.senderFingerprintBytes)
        assertContentEquals(localIdentity.ed25519PublicKey, envelope.senderEd25519PublicKey)
        assertContentEquals(localIdentity.x25519PublicKey, envelope.senderX25519PublicKey)
        assertTrue(envelope.ciphertext.isNotEmpty())
        assertTrue(encryptFailures.isEmpty())
    }

    @Test
    fun `prepare returns missing trust when no trust or route exists`() = runBlocking {
        // Arrange
        val localIdentity = LocalIdentity.fromAppId("direct-envelope-missing-local")
        val trustStore = TofuTrustStore(InMemorySecureStorage())
        val recipientTrustSupport =
            MeshEngineOutboundRecipientTrustSupport(
                localIdentity = localIdentity,
                trustStore = trustStore,
                routeCoordinator =
                    ch.trancee.meshlink.routing.RouteCoordinator(localIdentity.peerId),
                emitDiagnostic = { _, _, _, _, _, _ -> },
            )
        val support =
            MeshEngineOutboundDirectEnvelopeSupport(
                localIdentity = localIdentity,
                recipientTrustSupport = recipientTrustSupport,
            )
        val encryptFailures = mutableListOf<Pair<PeerId, String>>()

        // Act
        val preparation =
            support.prepare(
                peerId = PeerId("unknown-recipient"),
                payload = "hello".encodeToByteArray(),
                emitEncryptFailure = { peerId, cause -> encryptFailures += peerId to cause },
            )

        // Assert
        assertEquals(MeshEngineOutboundDirectEnvelopePreparation.MissingTrust, preparation)
        assertTrue(encryptFailures.isEmpty())
    }
}

private fun trustRecordFor(identity: LocalIdentity): TrustRecord {
    return TrustRecord(
        peerIdValue = identity.peerId.value,
        identityFingerprintBytes = identity.identityFingerprintBytes,
        firstSeenAtEpochMillis = 1L,
        lastVerifiedAtEpochMillis = 1L,
        publicKeys =
            TrustPublicKeys(
                ed25519PublicKey = identity.ed25519PublicKey,
                x25519PublicKey = identity.x25519PublicKey,
            ),
    )
}
