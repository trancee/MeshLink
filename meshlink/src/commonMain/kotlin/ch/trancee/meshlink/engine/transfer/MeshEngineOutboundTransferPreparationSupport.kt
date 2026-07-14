package ch.trancee.meshlink.engine.transfer

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.engine.internal.DIAGNOSTIC_PEER_SUFFIX_LENGTH
import ch.trancee.meshlink.engine.internal.OutboundTransferPreparation
import ch.trancee.meshlink.engine.internal.chunkTransferPayload
import ch.trancee.meshlink.engine.routing.MeshEngineRoutingSupport
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.TransferChunkPlan
import ch.trancee.meshlink.transfer.TransferSessionRoute

internal data class MeshEngineOutboundTransferPreparationRoutingContext(
    val routingSupport: MeshEngineRoutingSupport
)

internal data class MeshEngineOutboundTransferPreparationCallbacks(
    val createMessageId: suspend () -> String,
    val createTransferId: suspend () -> String,
    val emitEncryptFailure: suspend (PeerId, String) -> Unit,
    val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
)

internal class MeshEngineOutboundTransferPreparationSupport(
    private val localIdentity: LocalIdentity,
    private val directEnvelopeSupport: MeshEngineOutboundDirectEnvelopeSupport,
    private val routingContext: MeshEngineOutboundTransferPreparationRoutingContext,
    private val callbacks: MeshEngineOutboundTransferPreparationCallbacks,
) {
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
                    emitEncryptFailure = callbacks.emitEncryptFailure,
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

    private suspend fun createOutboundTransferSession(
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

    private suspend fun emitTransferStartedDiagnostic(peerId: PeerId): Unit {
        callbacks.emitDiagnostic(
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

internal fun buildMeshEngineRuntimeOutboundTransferPreparationSupport(
    localIdentity: LocalIdentity,
    directEnvelopeSupport: MeshEngineOutboundDirectEnvelopeSupport,
    routingSupport: MeshEngineRoutingSupport,
    createMessageId: suspend () -> String,
    createTransferId: suspend () -> String,
    emitEncryptFailure: suspend (PeerId, String) -> Unit,
    emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
): MeshEngineOutboundTransferPreparationSupport {
    return MeshEngineOutboundTransferPreparationSupport(
        localIdentity = localIdentity,
        directEnvelopeSupport = directEnvelopeSupport,
        routingContext =
            MeshEngineOutboundTransferPreparationRoutingContext(routingSupport = routingSupport),
        callbacks =
            MeshEngineOutboundTransferPreparationCallbacks(
                createMessageId = createMessageId,
                createTransferId = createTransferId,
                emitEncryptFailure = emitEncryptFailure,
                emitDiagnostic = emitDiagnostic,
            ),
    )
}
