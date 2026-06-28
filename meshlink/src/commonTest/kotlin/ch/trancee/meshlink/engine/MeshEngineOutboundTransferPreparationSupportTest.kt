package ch.trancee.meshlink.engine

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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

class MeshEngineOutboundTransferPreparationSupportTest {
    @Test
    fun `prepareOutboundTransferSession returns an unregistered outbound session when trust already exists`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("outbound-transfer-sender")
            val recipientIdentity = LocalIdentity.fromAppId("outbound-transfer-recipient")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            trustStore.write(trustRecordFor(identity = recipientIdentity))
            val callbacks = RecordingTransferPreparationCallbacks()
            val support = outboundTransferPreparationSupport(localIdentity, trustStore, callbacks)
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
            assertTrue(callbacks.encryptFailures.isEmpty())
        }

    @Test
    fun `prepareOutboundTransferSession returns pending route when no trust or route exists`() =
        runBlocking<Unit> {
            // Arrange
            val localIdentity = LocalIdentity.fromAppId("outbound-transfer-missing-trust")
            val trustStore = TofuTrustStore(InMemorySecureStorage())
            val callbacks = RecordingTransferPreparationCallbacks()
            val support = outboundTransferPreparationSupport(localIdentity, trustStore, callbacks)

            // Act
            val preparation =
                support.prepareOutboundTransferSession(
                    peerId = PeerId("unknown-recipient"),
                    payload = "hello".encodeToByteArray(),
                    hardRunToken = MeshEngineHardRunToken(epoch = 9),
                )

            // Assert
            assertEquals(OutboundTransferPreparation.PendingRoute, preparation)
            assertTrue(callbacks.encryptFailures.isEmpty())
            assertTrue(callbacks.diagnostics.isEmpty())
        }
}

private fun outboundTransferPreparationSupport(
    localIdentity: LocalIdentity,
    trustStore: TofuTrustStore,
    callbacks: RecordingTransferPreparationCallbacks,
): MeshEngineOutboundTransferPreparationSupport {
    val routeCoordinator = RouteCoordinator(localIdentity.peerId)
    return MeshEngineOutboundTransferPreparationSupport(
        localIdentity = localIdentity,
        directEnvelopeSupport =
            MeshEngineOutboundDirectEnvelopeSupport(
                localIdentity = localIdentity,
                recipientTrustSupport =
                    MeshEngineOutboundRecipientTrustSupport(
                        localIdentity = localIdentity,
                        trustStore = trustStore,
                        routeCoordinator = routeCoordinator,
                        emitDiagnostic = { _, _, _, _, _, _ -> },
                    ),
            ),
        routingContext =
            MeshEngineOutboundTransferPreparationRoutingContext(
                routingSupport = transferPreparationRoutingSupport(routeCoordinator, callbacks)
            ),
        callbacks =
            MeshEngineOutboundTransferPreparationCallbacks(
                createMessageId = callbacks::createMessageId,
                createTransferId = callbacks::createTransferId,
                emitEncryptFailure = { peerId, cause ->
                    callbacks.encryptFailures += peerId to cause
                },
                emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                    callbacks.diagnostics +=
                        RecordedTransferPreparationDiagnostic(
                            code = code,
                            severity = severity,
                            stage = stage,
                            peerSuffix = peerSuffix,
                            reason = reason,
                            metadata = metadata,
                        )
                },
            ),
    )
}

private fun transferPreparationRoutingSupport(
    routeCoordinator: RouteCoordinator,
    callbacks: RecordingTransferPreparationCallbacks,
): MeshEngineRoutingSupport {
    return MeshEngineRoutingSupport(
        routeCoordinator = routeCoordinator,
        runtimeGate = MeshEngineRuntimeSurface().runtimeGate,
        coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
            callbacks.diagnostics +=
                RecordedTransferPreparationDiagnostic(
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

private data class RecordedTransferPreparationDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)

private class RecordingTransferPreparationCallbacks {
    val diagnostics: MutableList<RecordedTransferPreparationDiagnostic> = mutableListOf()
    val encryptFailures: MutableList<Pair<PeerId, String>> = mutableListOf()

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
