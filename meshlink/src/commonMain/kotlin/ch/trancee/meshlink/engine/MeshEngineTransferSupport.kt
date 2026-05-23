package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.transfer.InboundChunkAcceptance
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.RelayTransferSession
import ch.trancee.meshlink.transfer.toTransferStartDescriptor
import ch.trancee.meshlink.wire.TransferAbortReasonCode
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineTransferState(
    val outboundTransfers: MutableMap<String, OutboundTransferSession>,
    val inboundTransfers: MutableMap<String, InboundTransferSession>,
    val relayTransfers: MutableMap<String, RelayTransferSession>,
)

internal data class MeshEngineTransferCallbacks(
    val captureHardRunToken: () -> MeshEngineHardRunToken,
    val isLocalPeerId: (PeerId) -> Boolean,
    val sendEncryptedWireFrame:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    val deliverInnerEnvelope:
        suspend (PeerId, PeerId, ByteArray, DeliveryPriority, MeshEngineHardRunToken) -> Unit,
)

internal class MeshEngineTransferSupport(
    private val state: MeshEngineTransferState,
    private val routingSupport: MeshEngineRoutingSupport,
    private val callbacks: MeshEngineTransferCallbacks,
    private val relaySupport: MeshEngineRelayTransferSupport,
    private val abortSupport: MeshEngineTransferAbortSupport,
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
    suspend fun handleTransferStart(peerId: PeerId, frame: WireFrame.TransferStart): Unit {
        val hardRunToken = callbacks.captureHardRunToken()
        if (callbacks.isLocalPeerId(frame.destinationPeerId)) {
            handleInboundTransferStart(peerId, frame, hardRunToken)
            return
        }
        relaySupport.handleTransferStart(
            peerId = peerId,
            frame = frame,
            hardRunToken = hardRunToken,
        )
    }

    private suspend fun handleInboundTransferStart(
        peerId: PeerId,
        frame: WireFrame.TransferStart,
        hardRunToken: MeshEngineHardRunToken,
    ): Unit {
        val existingSession = state.inboundTransfers[frame.transferId]
        val inboundSession =
            if (existingSession != null) {
                existingSession.upstreamPeerId = peerId
                existingSession
            } else {
                InboundTransferSession(
                        startDescriptor = frame.toTransferStartDescriptor(),
                        upstreamPeerId = peerId,
                        hardRunToken = hardRunToken,
                    )
                    .also { session -> state.inboundTransfers[frame.transferId] = session }
            }
        val preparedAck = inboundSession.prepareAck()
        emitInboundTransferProgress(
            stage = "transfer.receive.start",
            peerId = peerId,
            session = inboundSession,
            metadata =
                mapOf(
                    "existingSession" to (existingSession != null).toString(),
                    "receivedChunks" to preparedAck.receivedChunkCount.toString(),
                    "ackHighestContiguous" to preparedAck.highestContiguousAck.toString(),
                ),
        )
        val ackSent =
            callbacks.sendEncryptedWireFrame(
                peerId,
                preparedAck.frame,
                "transfer.ack.start",
                inboundSession.hardRunToken,
            )
        emitInboundTransferProgress(
            stage = "transfer.ack.start",
            peerId = peerId,
            session = inboundSession,
            metadata =
                inboundAckMetadata(preparedAck, ackSent) +
                    mapOf("existingSession" to (existingSession != null).toString()),
        )
    }

    suspend fun handleTransferChunk(peerId: PeerId, frame: WireFrame.TransferChunk): Unit {
        val inboundSession = state.inboundTransfers[frame.transferId]
        if (inboundSession != null) {
            handleInboundTransferChunk(peerId = peerId, frame = frame, session = inboundSession)
            return
        }
        relaySupport.handleTransferChunk(frame)
    }

    @Suppress("UnusedParameter")
    suspend fun handleTransferAck(peerId: PeerId, frame: WireFrame.TransferAck): Unit {
        val outboundSession = state.outboundTransfers[frame.transferId]
        if (outboundSession != null) {
            outboundSession.markAcknowledged(frame)
            return
        }
        relaySupport.handleTransferAck(frame)
    }

    suspend fun handleTransferComplete(peerId: PeerId, frame: WireFrame.TransferComplete): Unit {
        val inboundSession = state.inboundTransfers.remove(frame.transferId)
        if (inboundSession != null) {
            emitInboundTransferProgress(
                stage = "transfer.receive.complete",
                peerId = peerId,
                session = inboundSession,
                metadata =
                    mapOf(
                        "complete" to inboundSession.isComplete().toString(),
                        "receivedChunks" to inboundSession.receivedChunkCount().toString(),
                    ),
            )
            if (inboundSession.isComplete()) {
                callbacks.deliverInnerEnvelope(
                    peerId,
                    inboundSession.originPeerId,
                    inboundSession.assembledPayload(),
                    DeliveryPriority.NORMAL,
                    inboundSession.hardRunToken,
                )
            }
            return
        }
        relaySupport.handleTransferComplete(frame)
    }

    suspend fun handleTransferAbort(peerId: PeerId, frame: WireFrame.TransferAbort): Unit {
        state.inboundTransfers.remove(frame.transferId)
        if (relaySupport.handleTransferAbort(frame)) {
            return
        }
        val outboundSession = state.outboundTransfers.remove(frame.transferId)
        if (outboundSession != null) {
            emitDiagnostic(
                DiagnosticCode.TRANSFER_FAILED,
                DiagnosticSeverity.ERROR,
                "transfer.abort",
                peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                DiagnosticReason.TRANSFER_FAILURE,
                mapOf("reasonCode" to frame.reasonCode.toString()),
            )
        }
    }

    suspend fun abortLocalTransfers(reasonCode: TransferAbortReasonCode): Unit {
        abortSupport.abortLocalTransfers(reasonCode)
    }

    private suspend fun handleInboundTransferChunk(
        peerId: PeerId,
        frame: WireFrame.TransferChunk,
        session: InboundTransferSession,
    ): Unit {
        val acceptance = session.acceptChunk(frame)
        emitInboundTransferProgress(
            stage = inboundTransferChunkStage(acceptance),
            peerId = peerId,
            session = session,
            metadata = inboundTransferChunkMetadata(acceptance),
        )
        acknowledgeInboundTransferChunkIfNeeded(
            peerId = peerId,
            frame = frame,
            session = session,
            acceptance = acceptance,
        )
        completeInboundTransferChunkIfNeeded(
            peerId = peerId,
            frame = frame,
            session = session,
            acceptance = acceptance,
        )
    }

    private suspend fun acknowledgeInboundTransferChunkIfNeeded(
        peerId: PeerId,
        frame: WireFrame.TransferChunk,
        session: InboundTransferSession,
        acceptance: InboundChunkAcceptance,
    ): Unit {
        if (!acceptance.shouldAcknowledge) {
            return
        }
        val preparedAck = session.prepareAck()
        val ackSent =
            callbacks.sendEncryptedWireFrame(
                session.upstreamPeerId,
                preparedAck.frame,
                "transfer.ack.chunk",
                session.hardRunToken,
            )
        emitInboundTransferProgress(
            stage = "transfer.ack.chunk",
            peerId = peerId,
            session = session,
            metadata =
                inboundAckMetadata(preparedAck, ackSent) +
                    mapOf(
                        "triggerChunkIndex" to frame.chunkIndex.toString(),
                        "triggerDuplicateChunk" to acceptance.duplicateChunk.toString(),
                    ),
        )
    }

    private suspend fun completeInboundTransferChunkIfNeeded(
        peerId: PeerId,
        frame: WireFrame.TransferChunk,
        session: InboundTransferSession,
        acceptance: InboundChunkAcceptance,
    ): Unit {
        if (!acceptance.complete) {
            return
        }
        state.inboundTransfers.remove(frame.transferId)
        emitInboundTransferProgress(
            stage = "transfer.receive.complete",
            peerId = peerId,
            session = session,
            metadata =
                mapOf(
                    "complete" to "true",
                    "receivedChunks" to session.receivedChunkCount().toString(),
                    "triggerChunkIndex" to frame.chunkIndex.toString(),
                ),
        )
        callbacks.deliverInnerEnvelope(
            peerId,
            session.originPeerId,
            session.assembledPayload(),
            DeliveryPriority.NORMAL,
            session.hardRunToken,
        )
    }

    private fun emitInboundTransferProgress(
        stage: String,
        peerId: PeerId,
        session: InboundTransferSession,
        metadata: Map<String, String> = emptyMap(),
    ): Unit {
        emitDiagnostic(
            DiagnosticCode.TRANSFER_PROGRESS,
            DiagnosticSeverity.DEBUG,
            stage,
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            null,
            routingSupport.peerRouteMetadata(
                peerId = peerId,
                metadata = inboundTransferMetadata(session) + metadata,
            ),
        )
    }
}
