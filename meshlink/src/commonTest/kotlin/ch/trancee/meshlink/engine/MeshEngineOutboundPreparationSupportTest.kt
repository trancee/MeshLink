package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.test.InMemorySecureStorage
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class MeshEngineOutboundPreparationSupportTest {
    @Test
    fun `prepareOutboundInlineMessage returns a ready routed message when trust already exists`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("outbound-inline-sender")
            val recipientIdentity = LocalIdentity.fromAppId("outbound-inline-recipient")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            trustStore.write(trustRecordFor(recipientIdentity))
            val callbacks = RecordingOutboundPreparationCallbacks()
            val support = outboundPreparationSupport(localIdentity, trustStore, callbacks)
            val payload = "hello".encodeToByteArray()

            // Act
            val preparation =
                support.prepareOutboundInlineMessage(
                    peerId = recipientIdentity.peerId,
                    payload = payload,
                    priority = DeliveryPriority.HIGH,
                    ttlMillis = 1234,
                )

            // Assert
            val ready = assertIs<MeshEngineOutboundInlineMessagePreparation.Ready>(preparation)
            val message = ready.message
            assertEquals("message-1", message.messageId)
            assertEquals(localIdentity.peerId, message.originPeerId)
            assertEquals(recipientIdentity.peerId, message.destinationPeerId)
            assertEquals(DeliveryPriority.HIGH, message.priority)
            assertEquals(1234, message.ttlMillis)
            val envelope = DirectMessageEnvelope.decode(message.encryptedPayload)
            assertEquals(localIdentity.peerId.value, envelope.senderPeerId.value)
            assertContentEquals(
                localIdentity.identityFingerprintBytes,
                envelope.senderFingerprintBytes,
            )
            assertContentEquals(localIdentity.ed25519PublicKey, envelope.senderEd25519PublicKey)
            assertContentEquals(localIdentity.x25519PublicKey, envelope.senderX25519PublicKey)
            assertTrue(envelope.ciphertext.isNotEmpty())
            assertTrue(callbacks.inlineEncryptFailures.isEmpty())
        }

    @Test
    fun `prepareOutboundInlineMessage reports missing trust when no trust or route exists`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("outbound-inline-missing-trust")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            val callbacks = RecordingOutboundPreparationCallbacks()
            val support = outboundPreparationSupport(localIdentity, trustStore, callbacks)

            // Act
            val preparation =
                support.prepareOutboundInlineMessage(
                    peerId = PeerId("unknown-recipient"),
                    payload = "hello".encodeToByteArray(),
                    priority = DeliveryPriority.NORMAL,
                    ttlMillis = 4321,
                )

            // Assert
            assertEquals(MeshEngineOutboundInlineMessagePreparation.MissingTrust, preparation)
            assertTrue(callbacks.inlineEncryptFailures.isEmpty())
        }

    @Test
    fun `prepareOutboundTransferSession returns an unregistered outbound session when trust already exists`() =
        runBlocking {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("outbound-transfer-sender")
            val recipientIdentity = LocalIdentity.fromAppId("outbound-transfer-recipient")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            trustStore.write(trustRecordFor(recipientIdentity))
            val callbacks = RecordingOutboundPreparationCallbacks()
            val support = outboundPreparationSupport(localIdentity, trustStore, callbacks)
            val payload = ByteArray(2048) { index -> (index % 251).toByte() }

            // Act
            val preparation =
                support.prepareOutboundTransferSession(
                    peerId = recipientIdentity.peerId,
                    payload = payload,
                    hardRunToken = MeshEngineHardRunToken(epoch = 9),
                )

            // Assert
            val ready = assertIs<OutboundTransferPreparation.Ready>(preparation)
            assertEquals("message-1", ready.session.messageId)
            assertEquals("transfer-1", ready.session.transferId)
            assertTrue(ready.session.totalChunks > 1)
            assertTrue(
                callbacks.diagnostics.any { diagnostic ->
                    diagnostic.code == DiagnosticCode.TRANSFER_STARTED &&
                        diagnostic.stage == "transfer.send.start"
                }
            )
            assertTrue(callbacks.transferEncryptFailures.isEmpty())
        }
}

private fun outboundPreparationSupport(
    localIdentity: LocalIdentity,
    trustStore: TofuTrustStore,
    callbacks: RecordingOutboundPreparationCallbacks,
): MeshEngineOutboundPreparationSupport {
    val routeCoordinator = RouteCoordinator(localIdentity.peerId)
    return MeshEngineOutboundPreparationSupport(
        localIdentity = localIdentity,
        recipientTrustSupport =
            MeshEngineOutboundRecipientTrustSupport(
                localIdentity = localIdentity,
                trustStore = trustStore,
                routeCoordinator = routeCoordinator,
                emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                    callbacks.diagnostics +=
                        RecordedPreparationDiagnostic(
                            code = code,
                            severity = severity,
                            stage = stage,
                            peerSuffix = peerSuffix,
                            reason = reason,
                            metadata = metadata,
                        )
                },
            ),
        routingContext =
            MeshEngineOutboundPreparationRoutingContext(
                routingSupport = routingSupport(routeCoordinator, callbacks)
            ),
        callbacks =
            MeshEngineOutboundPreparationCallbacks(
                createMessageId = callbacks::createMessageId,
                createTransferId = callbacks::createTransferId,
                emitInlineEncryptFailure = { peerId, cause ->
                    callbacks.inlineEncryptFailures += peerId to cause
                },
                emitTransferEncryptFailure = { peerId, cause ->
                    callbacks.transferEncryptFailures += peerId to cause
                },
            ),
        emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
            callbacks.diagnostics +=
                RecordedPreparationDiagnostic(
                    code = code,
                    severity = severity,
                    stage = stage,
                    peerSuffix = peerSuffix,
                    reason = reason,
                    metadata = metadata,
                )
        },
    )
}

private fun routingSupport(
    routeCoordinator: RouteCoordinator,
    callbacks: RecordingOutboundPreparationCallbacks,
): MeshEngineRoutingSupport {
    return MeshEngineRoutingSupport(
        routeCoordinator = routeCoordinator,
        runtimeGate = MeshEngineRuntimeSurface().runtimeGate,
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
            callbacks.diagnostics +=
                RecordedPreparationDiagnostic(
                    code = code,
                    severity = severity,
                    stage = stage,
                    peerSuffix = peerSuffix,
                    reason = reason,
                    metadata = metadata,
                )
        },
        sendEncryptedWireFrame = { _, _, _, _ -> true },
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

private data class RecordedPreparationDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)

private class RecordingOutboundPreparationCallbacks {
    val diagnostics: MutableList<RecordedPreparationDiagnostic> = mutableListOf()
    val inlineEncryptFailures: MutableList<Pair<PeerId, String>> = mutableListOf()
    val transferEncryptFailures: MutableList<Pair<PeerId, String>> = mutableListOf()

    private var messageCounter: Int = 0
    private var transferCounter: Int = 0

    fun createMessageId(): String {
        messageCounter += 1
        return "message-$messageCounter"
    }

    fun createTransferId(): String {
        transferCounter += 1
        return "transfer-$transferCounter"
    }
}
