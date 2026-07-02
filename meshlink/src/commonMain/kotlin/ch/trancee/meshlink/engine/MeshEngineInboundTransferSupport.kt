package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.transfer.InboundChunkAcceptance
import ch.trancee.meshlink.transfer.toTransferStartDescriptor
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineInboundTransferSupportCallbacks(
    val sendEncryptedWireFrame:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    val deliverInnerEnvelope:
        suspend (PeerId, PeerId, ByteArray, DeliveryPriority, MeshEngineHardRunToken) -> Unit,
    val routeMetadata: suspend (PeerId, Map<String, String>) -> Map<String, String>,
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

internal class MeshEngineInboundTransferSupport(
    private val transferRegistry: MeshEngineTransferRegistry,
    private val callbacks: MeshEngineInboundTransferSupportCallbacks,
) {
    constructor(
        inboundTransfers: MutableMap<String, ch.trancee.meshlink.transfer.InboundTransferSession>,
        callbacks: MeshEngineInboundTransferSupportCallbacks,
    ) : this(MeshEngineTransferRegistry(inboundTransfers = inboundTransfers), callbacks)

    suspend fun handleTransferStart(
        peerId: PeerId,
        frame: WireFrame.TransferStart,
        hardRunToken: MeshEngineHardRunToken,
    ): Unit {
        val existingSession = transferRegistry.inboundSession(frame.transferId)
        if (existingSession == null && !frame.isWithinInboundTransferSizeLimits()) {
            callbacks.emitDiagnostic(
                DiagnosticCode.SIZE_LIMIT_REJECTED,
                DiagnosticSeverity.WARN,
                "transfer.receive.start",
                peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                DiagnosticReason.SIZE_LIMIT,
                mapOf(
                    "transferId" to frame.transferId,
                    "totalBytes" to frame.totalBytes.toString(),
                    "totalChunks" to frame.totalChunks.toString(),
                    "maxChunkPayloadBytes" to frame.maxChunkPayloadBytes.toString(),
                ),
            )
            return
        }
        val inboundSession =
            if (existingSession != null) {
                existingSession.upstreamPeerId = peerId
                existingSession
            } else {
                ch.trancee.meshlink.transfer
                    .InboundTransferSession(
                        startDescriptor = frame.toTransferStartDescriptor(),
                        upstreamPeerId = peerId,
                        hardRunToken = hardRunToken,
                    )
                    .also { session -> transferRegistry.storeInboundSession(session) }
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

    suspend fun handleTransferChunk(peerId: PeerId, frame: WireFrame.TransferChunk): Boolean {
        val session = transferRegistry.inboundSession(frame.transferId) ?: return false
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
        return true
    }

    suspend fun handleTransferComplete(peerId: PeerId, frame: WireFrame.TransferComplete): Boolean {
        val inboundSession = transferRegistry.removeInboundSession(frame.transferId) ?: return false
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
        return true
    }

    suspend fun handleTransferAbort(frame: WireFrame.TransferAbort): Boolean {
        return transferRegistry.removeInboundSession(frame.transferId) != null
    }

    private suspend fun acknowledgeInboundTransferChunkIfNeeded(
        peerId: PeerId,
        frame: WireFrame.TransferChunk,
        session: ch.trancee.meshlink.transfer.InboundTransferSession,
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
        session: ch.trancee.meshlink.transfer.InboundTransferSession,
        acceptance: InboundChunkAcceptance,
    ): Unit {
        if (!acceptance.complete) {
            return
        }
        transferRegistry.removeInboundSession(frame.transferId)
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

    private suspend fun emitInboundTransferProgress(
        stage: String,
        peerId: PeerId,
        session: ch.trancee.meshlink.transfer.InboundTransferSession,
        metadata: Map<String, String> = emptyMap(),
    ): Unit {
        callbacks.emitDiagnostic(
            DiagnosticCode.TRANSFER_PROGRESS,
            DiagnosticSeverity.DEBUG,
            stage,
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            null,
            callbacks.routeMetadata(peerId, inboundTransferMetadata(session) + metadata),
        )
    }
}

internal fun buildMeshEngineRuntimeInboundTransferSupport(
    transferRegistry: MeshEngineTransferRegistry,
    sendEncryptedWireFrame: suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    deliverInnerEnvelope:
        suspend (PeerId, PeerId, ByteArray, DeliveryPriority, MeshEngineHardRunToken) -> Unit,
    routeMetadata: suspend (PeerId, Map<String, String>) -> Map<String, String>,
    emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
): MeshEngineInboundTransferSupport {
    return MeshEngineInboundTransferSupport(
        transferRegistry = transferRegistry,
        callbacks =
            MeshEngineInboundTransferSupportCallbacks(
                sendEncryptedWireFrame = sendEncryptedWireFrame,
                deliverInnerEnvelope = deliverInnerEnvelope,
                routeMetadata = routeMetadata,
                emitDiagnostic = emitDiagnostic,
            ),
    )
}
