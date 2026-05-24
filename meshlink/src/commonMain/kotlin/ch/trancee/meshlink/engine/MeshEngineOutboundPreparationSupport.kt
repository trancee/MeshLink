package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.TransferChunkPlan
import ch.trancee.meshlink.transfer.TransferSessionRoute
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineOutboundPreparationRoutingContext(
    val routingSupport: MeshEngineRoutingSupport
)

internal data class MeshEngineOutboundPreparationCallbacks(
    val createMessageId: () -> String,
    val createTransferId: () -> String,
    val emitInlineEncryptFailure: (PeerId, String) -> Unit,
    val emitTransferEncryptFailure: (PeerId, String) -> Unit,
)

internal sealed class MeshEngineOutboundInlineMessagePreparation {
    internal class Ready internal constructor(internal val message: WireFrame.Message) :
        MeshEngineOutboundInlineMessagePreparation()

    internal data object MissingTrust : MeshEngineOutboundInlineMessagePreparation()

    internal data object EncryptFailure : MeshEngineOutboundInlineMessagePreparation()
}

internal class MeshEngineOutboundPreparationSupport(
    private val localIdentity: LocalIdentity,
    private val directEnvelopeSupport: MeshEngineOutboundDirectEnvelopeSupport,
    private val routingContext: MeshEngineOutboundPreparationRoutingContext,
    private val callbacks: MeshEngineOutboundPreparationCallbacks,
    private val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
) {

    suspend fun prepareOutboundInlineMessage(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        ttlMillis: Int,
    ): MeshEngineOutboundInlineMessagePreparation {
        return when (
            val envelopePreparation =
                directEnvelopeSupport.prepare(
                    peerId = peerId,
                    payload = payload,
                    emitEncryptFailure = callbacks.emitInlineEncryptFailure,
                )
        ) {
            MeshEngineOutboundDirectEnvelopePreparation.MissingTrust ->
                MeshEngineOutboundInlineMessagePreparation.MissingTrust
            MeshEngineOutboundDirectEnvelopePreparation.EncryptFailure ->
                MeshEngineOutboundInlineMessagePreparation.EncryptFailure
            is MeshEngineOutboundDirectEnvelopePreparation.Ready ->
                MeshEngineOutboundInlineMessagePreparation.Ready(
                    createInlineRoutedMessage(
                        peerId = peerId,
                        priority = priority,
                        ttlMillis = ttlMillis,
                        envelopeBytes = envelopePreparation.envelopeBytes,
                    )
                )
        }
    }

    suspend fun prepareOutboundTransferSession(
        peerId: PeerId,
        payload: ByteArray,
        hardRunToken: MeshEngineHardRunToken,
    ): OutboundTransferPreparation {
        return when (
            val envelopePreparation =
                directEnvelopeSupport.prepare(
                    peerId = peerId,
                    payload = payload,
                    emitEncryptFailure = callbacks.emitTransferEncryptFailure,
                )
        ) {
            MeshEngineOutboundDirectEnvelopePreparation.MissingTrust ->
                OutboundTransferPreparation.PendingRoute
            MeshEngineOutboundDirectEnvelopePreparation.EncryptFailure ->
                OutboundTransferPreparation.Failed(
                    SendResult.NotSent(SendFailureReason.TRUST_FAILURE)
                )
            is MeshEngineOutboundDirectEnvelopePreparation.Ready -> {
                val session =
                    createOutboundTransferSession(
                        peerId = peerId,
                        envelopeBytes = envelopePreparation.envelopeBytes,
                        hardRunToken = hardRunToken,
                    )
                emitTransferStartedDiagnostic(peerId)
                OutboundTransferPreparation.Ready(session)
            }
        }
    }

    private fun createInlineRoutedMessage(
        peerId: PeerId,
        priority: DeliveryPriority,
        ttlMillis: Int,
        envelopeBytes: ByteArray,
    ): WireFrame.Message {
        return WireFrame.Message(
            messageId = callbacks.createMessageId(),
            originPeerId = localIdentity.peerId,
            destinationPeerId = peerId,
            priority = priority,
            ttlMillis = ttlMillis,
            encryptedPayload = envelopeBytes,
        )
    }

    private fun createOutboundTransferSession(
        peerId: PeerId,
        envelopeBytes: ByteArray,
        @Suppress("UNUSED_PARAMETER") hardRunToken: MeshEngineHardRunToken,
    ): OutboundTransferSession {
        return OutboundTransferSession.fromOwnedPlan(
            route =
                TransferSessionRoute(
                    transferId = callbacks.createTransferId(),
                    messageId = callbacks.createMessageId(),
                    originPeerId = localIdentity.peerId,
                    destinationPeerId = peerId,
                ),
            chunkPlan =
                TransferChunkPlan(
                    chunks = chunkTransferPayload(envelopeBytes, TRANSFER_CHUNK_PAYLOAD_BYTES),
                    totalBytes = envelopeBytes.size,
                    maxChunkPayloadBytes = TRANSFER_CHUNK_PAYLOAD_BYTES,
                ),
        )
    }

    private fun emitTransferStartedDiagnostic(peerId: PeerId): Unit {
        emitDiagnostic(
            DiagnosticCode.TRANSFER_STARTED,
            DiagnosticSeverity.INFO,
            "transfer.send.start",
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            null,
            routingContext.routingSupport.peerRouteMetadata(peerId),
        )
    }

    private companion object {
        private const val TRANSFER_CHUNK_PAYLOAD_BYTES: Int = 392
    }
}

internal fun buildMeshEngineRuntimeOutboundPreparationSupport(
    localIdentity: LocalIdentity,
    trustStore: TofuTrustStore,
    routeCoordinator: RouteCoordinator,
    routingSupport: MeshEngineRoutingSupport,
    createMessageId: () -> String,
    createTransferId: () -> String,
    emitInlineEncryptFailure: (PeerId, String) -> Unit,
    emitTransferEncryptFailure: (PeerId, String) -> Unit,
    emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
): MeshEngineOutboundPreparationSupport {
    val recipientTrustSupport =
        buildMeshEngineRuntimeOutboundRecipientTrustSupport(
            localIdentity = localIdentity,
            trustStore = trustStore,
            routeCoordinator = routeCoordinator,
            emitDiagnostic = emitDiagnostic,
        )
    return MeshEngineOutboundPreparationSupport(
        localIdentity = localIdentity,
        directEnvelopeSupport =
            buildMeshEngineRuntimeOutboundDirectEnvelopeSupport(
                localIdentity = localIdentity,
                recipientTrustSupport = recipientTrustSupport,
            ),
        routingContext =
            MeshEngineOutboundPreparationRoutingContext(routingSupport = routingSupport),
        callbacks =
            MeshEngineOutboundPreparationCallbacks(
                createMessageId = createMessageId,
                createTransferId = createTransferId,
                emitInlineEncryptFailure = emitInlineEncryptFailure,
                emitTransferEncryptFailure = emitTransferEncryptFailure,
            ),
        emitDiagnostic = emitDiagnostic,
    )
}
