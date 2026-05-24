package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.RelayTransferSession
import ch.trancee.meshlink.wire.TransferAbortReasonCode
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineTransferState(
    val inboundTransfers: MutableMap<String, InboundTransferSession>,
    val relayTransfers: MutableMap<String, RelayTransferSession>,
)

internal data class MeshEngineTransferCallbacks(
    val captureHardRunToken: () -> MeshEngineHardRunToken,
    val isLocalPeerId: (PeerId) -> Boolean,
)

internal class MeshEngineTransferSupport(
    private val state: MeshEngineTransferState,
    private val outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport,
    private val callbacks: MeshEngineTransferCallbacks,
    private val inboundSupport: MeshEngineInboundTransferSupport,
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
            inboundSupport.handleTransferStart(
                peerId = peerId,
                frame = frame,
                hardRunToken = hardRunToken,
            )
            return
        }
        relaySupport.handleTransferStart(
            peerId = peerId,
            frame = frame,
            hardRunToken = hardRunToken,
        )
    }

    suspend fun handleTransferChunk(peerId: PeerId, frame: WireFrame.TransferChunk): Unit {
        if (inboundSupport.handleTransferChunk(peerId = peerId, frame = frame)) {
            return
        }
        relaySupport.handleTransferChunk(frame)
    }

    @Suppress("UnusedParameter")
    suspend fun handleTransferAck(peerId: PeerId, frame: WireFrame.TransferAck): Unit {
        if (outboundTransferLifecycleSupport.markAcknowledged(frame)) {
            return
        }
        relaySupport.handleTransferAck(frame)
    }

    suspend fun handleTransferComplete(peerId: PeerId, frame: WireFrame.TransferComplete): Unit {
        if (inboundSupport.handleTransferComplete(peerId = peerId, frame = frame)) {
            return
        }
        relaySupport.handleTransferComplete(frame)
    }

    suspend fun handleTransferAbort(peerId: PeerId, frame: WireFrame.TransferAbort): Unit {
        if (inboundSupport.handleTransferAbort(frame)) {
            return
        }
        if (relaySupport.handleTransferAbort(frame)) {
            return
        }
        val outboundSession = outboundTransferLifecycleSupport.removeSession(frame.transferId)
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
}

internal fun buildMeshEngineRuntimeTransferSupport(
    captureHardRunToken: () -> MeshEngineHardRunToken,
    isLocalPeerId: (PeerId) -> Boolean,
    inboundTransfers: MutableMap<String, InboundTransferSession>,
    relayTransfers: MutableMap<String, RelayTransferSession>,
    outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport,
    sendEncryptedWireFrame: suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit,
    deliverInnerEnvelope:
        suspend (PeerId, PeerId, ByteArray, DeliveryPriority, MeshEngineHardRunToken) -> Unit,
    routeMetadata: (PeerId, Map<String, String>) -> Map<String, String>,
    emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
): MeshEngineTransferSupport {
    val state =
        MeshEngineTransferState(
            inboundTransfers = inboundTransfers,
            relayTransfers = relayTransfers,
        )
    val inboundSupport =
        buildMeshEngineRuntimeInboundTransferSupport(
            inboundTransfers = state.inboundTransfers,
            sendEncryptedWireFrame = sendEncryptedWireFrame,
            deliverInnerEnvelope = deliverInnerEnvelope,
            routeMetadata = routeMetadata,
            emitDiagnostic = emitDiagnostic,
        )
    val relaySupport =
        buildMeshEngineRuntimeRelayTransferSupport(
            relayTransfers = state.relayTransfers,
            sendEncryptedWireFrame = sendEncryptedWireFrame,
            sendTransferTowardsDestination = sendTransferTowardsDestination,
        )
    val abortSupport =
        buildMeshEngineRuntimeTransferAbortSupport(
            state = state,
            outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
            sendEncryptedWireFrame = sendEncryptedWireFrame,
            sendTransferTowardsDestination = sendTransferTowardsDestination,
            clearQueuedOutboundFrames = clearQueuedOutboundFrames,
            routeMetadata = routeMetadata,
            emitDiagnostic = emitDiagnostic,
        )
    return MeshEngineTransferSupport(
        state = state,
        outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
        callbacks =
            MeshEngineTransferCallbacks(
                captureHardRunToken = captureHardRunToken,
                isLocalPeerId = isLocalPeerId,
            ),
        inboundSupport = inboundSupport,
        relaySupport = relaySupport,
        abortSupport = abortSupport,
        emitDiagnostic = emitDiagnostic,
    )
}
