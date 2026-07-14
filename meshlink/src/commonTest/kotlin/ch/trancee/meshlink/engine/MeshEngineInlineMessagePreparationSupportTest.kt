package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.handshake.EndToEndSessionEstablishmentOutcome
import ch.trancee.meshlink.engine.transfer.MeshEngineInlineMessagePreparationCallbacks
import ch.trancee.meshlink.engine.transfer.MeshEngineInlineMessagePreparationSupport
import ch.trancee.meshlink.engine.transfer.MeshEngineOutboundDirectEnvelopeSupport
import ch.trancee.meshlink.engine.transfer.MeshEngineOutboundInlineMessagePreparation
import ch.trancee.meshlink.engine.transfer.MeshEngineOutboundRecipientTrustSupport
import ch.trancee.meshlink.engine.transport.DirectMessageEnvelope
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MeshEngineInlineMessagePreparationSupportTest {
    @Test
    fun `prepare returns a ready routed message when trust already exists`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("outbound-inline-sender")
            val recipientIdentity = LocalIdentity.fromAppId("outbound-inline-recipient")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            trustStore.write(trustRecordFor(identity = recipientIdentity))
            val encryptFailures = mutableListOf<Pair<PeerId, String>>()
            val support =
                inlineMessagePreparationSupport(
                    localIdentity = localIdentity,
                    trustStore = trustStore,
                    emitEncryptFailure = { peerId, cause -> encryptFailures += peerId to cause },
                )
            val payload = "hello".encodeToByteArray()

            // Act
            val preparation =
                support.prepare(
                    peerId = recipientIdentity.peerId,
                    payload = payload,
                    priority = DeliveryPriority.HIGH,
                    ttlMillis = 1234,
                )

            // Assert
            val ready = assertIs<MeshEngineOutboundInlineMessagePreparation.Ready>(preparation)
            val message = ready.message
            assertEquals("message-1", message.messageId)
            assertEquals(localIdentity.peerId.value, message.originPeerId.value)
            assertEquals(recipientIdentity.peerId.value, message.destinationPeerId.value)
            assertEquals(DeliveryPriority.HIGH, message.priority)
            assertEquals(1234, message.ttlMillis)
            val envelope = DirectMessageEnvelope.decode(message.encryptedPayload)
            assertEquals(localIdentity.peerId.value, envelope.senderPeerId.value)
            assertTrue(envelope.ciphertext.isNotEmpty())
            assertTrue(encryptFailures.isEmpty())
        }

    @Test
    fun `prepare reports missing trust when no trust or route exists`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("outbound-inline-missing-trust")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            val encryptFailures = mutableListOf<Pair<PeerId, String>>()
            val support =
                inlineMessagePreparationSupport(
                    localIdentity = localIdentity,
                    trustStore = trustStore,
                    emitEncryptFailure = { peerId, cause -> encryptFailures += peerId to cause },
                )

            // Act
            val preparation =
                support.prepare(
                    peerId = PeerId("unknown-recipient"),
                    payload = "hello".encodeToByteArray(),
                    priority = DeliveryPriority.NORMAL,
                    ttlMillis = 4321,
                )

            // Assert
            assertEquals(MeshEngineOutboundInlineMessagePreparation.MissingTrust, preparation)
            assertTrue(encryptFailures.isEmpty())
        }
}

private fun inlineMessagePreparationSupport(
    localIdentity: LocalIdentity,
    trustStore: TofuTrustStore,
    emitEncryptFailure: (PeerId, String) -> Unit,
): MeshEngineInlineMessagePreparationSupport {
    val recipientTrustSupport =
        MeshEngineOutboundRecipientTrustSupport(
            trustStore = trustStore,
            ensureEndToEndSession = { EndToEndSessionEstablishmentOutcome.Unreachable },
        )
    return MeshEngineInlineMessagePreparationSupport(
        localIdentity = localIdentity,
        directEnvelopeSupport =
            MeshEngineOutboundDirectEnvelopeSupport(
                localIdentity = localIdentity,
                recipientTrustSupport = recipientTrustSupport,
            ),
        callbacks =
            MeshEngineInlineMessagePreparationCallbacks(
                createMessageId = { "message-1" },
                emitEncryptFailure = emitEncryptFailure,
            ),
    )
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
