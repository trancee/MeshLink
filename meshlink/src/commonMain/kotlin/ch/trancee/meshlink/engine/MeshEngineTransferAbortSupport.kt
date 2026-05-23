package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.wire.TransferAbortReasonCode
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineTransferAbortCallbacks(
    val sendEncryptedWireFrame:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    val sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    val clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit,
    val routeMetadata: (PeerId, Map<String, String>) -> Map<String, String>,
)

internal class MeshEngineTransferAbortSupport(
    private val state: MeshEngineTransferState,
    private val callbacks: MeshEngineTransferAbortCallbacks,
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
    suspend fun abortLocalTransfers(reasonCode: TransferAbortReasonCode): Unit {
        val outboundSessions = state.outboundTransfers.values.toList()
        val inboundSessions = state.inboundTransfers.values.toList()
        val relaySessions = state.relayTransfers.values.toList()
        state.outboundTransfers.clear()
        state.inboundTransfers.clear()
        state.relayTransfers.clear()

        outboundSessions.forEach { session ->
            val abortFrame = abortFrameFor(session.transferId, reasonCode)
            callbacks.clearQueuedOutboundFrames(
                session.destinationPeerId,
                "transfer.clearQueuedFramesOnAbort",
            )
            callbacks.sendTransferTowardsDestination(
                session.destinationPeerId,
                abortFrame,
                "transfer.abort.runtimeStop",
                null,
            )
            emitTransferAbortDiagnostic(
                peerId = session.destinationPeerId,
                metadata =
                    callbacks.routeMetadata(
                        session.destinationPeerId,
                        mapOf(
                            "reasonCode" to reasonCode.code.toString(),
                            "transferAbortReason" to reasonCode.name,
                            "transferAbortScope" to "outbound",
                        ),
                    ),
            )
        }

        inboundSessions.forEach { session ->
            val abortFrame = abortFrameFor(session.transferId, reasonCode)
            callbacks.sendEncryptedWireFrame(
                session.upstreamPeerId,
                abortFrame,
                "transfer.abort.runtimeStop",
                null,
            )
            emitTransferAbortDiagnostic(
                peerId = session.upstreamPeerId,
                metadata =
                    callbacks.routeMetadata(
                        session.upstreamPeerId,
                        inboundTransferMetadata(session) +
                            mapOf(
                                "reasonCode" to reasonCode.code.toString(),
                                "transferAbortReason" to reasonCode.name,
                                "transferAbortScope" to "inbound",
                            ),
                    ),
            )
        }

        relaySessions.forEach { session ->
            val abortFrame = abortFrameFor(session.transferId, reasonCode)
            callbacks.sendEncryptedWireFrame(
                session.upstreamPeerId,
                abortFrame,
                "transfer.abort.runtimeStop.upstream",
                null,
            )
            callbacks.sendTransferTowardsDestination(
                session.destinationPeerId,
                abortFrame,
                "transfer.abort.runtimeStop.downstream",
                null,
            )
            emitTransferAbortDiagnostic(
                peerId = session.destinationPeerId,
                metadata =
                    callbacks.routeMetadata(
                        session.destinationPeerId,
                        mapOf(
                            "originPeerId" to session.originPeerId.value,
                            "reasonCode" to reasonCode.code.toString(),
                            "transferAbortReason" to reasonCode.name,
                            "transferAbortScope" to "relay",
                            "transferId" to session.transferId,
                            "upstreamPeerId" to session.upstreamPeerId.value,
                        ),
                    ),
            )
        }
    }

    private fun emitTransferAbortDiagnostic(peerId: PeerId, metadata: Map<String, String>): Unit {
        emitDiagnostic(
            DiagnosticCode.TRANSFER_FAILED,
            DiagnosticSeverity.ERROR,
            "transfer.abort.runtimeStop",
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.TRANSFER_FAILURE,
            metadata,
        )
    }

    private fun abortFrameFor(
        transferId: String,
        reasonCode: TransferAbortReasonCode,
    ): WireFrame.TransferAbort {
        return WireFrame.TransferAbort(transferId = transferId, reasonCode = reasonCode.code)
    }
}
