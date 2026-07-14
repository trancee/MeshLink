package ch.trancee.meshlink.engine.transfer

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.engine.internal.MeshEngineEmitDiagnostic
import ch.trancee.meshlink.engine.internal.diagnosticSuffix
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineLargeTransferTerminalDependencies(
    val clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit,
    val sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
)

internal data class MeshEngineLargeTransferTerminalCallbacks(
    val routeMetadata: suspend (PeerId) -> Map<String, String>,
    val emitDiagnostic: MeshEngineEmitDiagnostic,
)

internal class MeshEngineLargeTransferTerminalSupport(
    private val outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport,
    private val dependencies: MeshEngineLargeTransferTerminalDependencies,
    private val callbacks: MeshEngineLargeTransferTerminalCallbacks,
) {
    suspend fun fail(
        activeSession: OutboundTransferSession?,
        peerId: PeerId,
        lastRouteAvailable: Boolean,
    ): SendResult {
        activeSession?.let { session ->
            outboundTransferLifecycleSupport.removeSession(session.transferId)
            dependencies.clearQueuedOutboundFrames(
                session.destinationPeerId,
                "transfer.clearQueuedFramesOnFailure",
            )
        }
        return if (!lastRouteAvailable) {
            callbacks.emitDiagnostic(
                DiagnosticCode.DELIVERY_UNREACHABLE,
                DiagnosticSeverity.ERROR,
                "transfer.retryExpired",
                peerId.diagnosticSuffix(),
                DiagnosticReason.DELIVERY_FAILURE,
                callbacks.routeMetadata(peerId),
            )
            SendResult.NotSent(SendFailureReason.UNREACHABLE)
        } else {
            callbacks.emitDiagnostic(
                DiagnosticCode.TRANSFER_FAILED,
                DiagnosticSeverity.ERROR,
                "transfer.send.timeout",
                peerId.diagnosticSuffix(),
                DiagnosticReason.TRANSFER_FAILURE,
                callbacks.routeMetadata(peerId),
            )
            SendResult.NotSent(SendFailureReason.TRANSFER_TIMED_OUT)
        }
    }

    suspend fun complete(
        session: OutboundTransferSession,
        hardRunToken: MeshEngineHardRunToken,
    ): SendResult {
        dependencies.clearQueuedOutboundFrames(
            session.destinationPeerId,
            "transfer.clearQueuedFrames",
        )
        dependencies.sendTransferTowardsDestination(
            session.destinationPeerId,
            WireFrame.TransferComplete(session.transferId),
            "transfer.complete",
            hardRunToken,
        )
        outboundTransferLifecycleSupport.removeSession(session.transferId)
        callbacks.emitDiagnostic(
            DiagnosticCode.TRANSFER_COMPLETED,
            DiagnosticSeverity.INFO,
            "transfer.send.complete",
            session.destinationPeerId.diagnosticSuffix(),
            null,
            callbacks.routeMetadata(session.destinationPeerId),
        )
        return SendResult.Sent
    }

    suspend fun abort(activeSession: OutboundTransferSession?): SendResult {
        activeSession?.let { session ->
            outboundTransferLifecycleSupport.removeSession(session.transferId)
            dependencies.clearQueuedOutboundFrames(
                session.destinationPeerId,
                "transfer.clearQueuedFramesOnAbort",
            )
        }
        return SendResult.NotSent(SendFailureReason.TRANSFER_ABORTED)
    }
}
