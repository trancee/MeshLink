package ch.trancee.meshlink.engine.transfer

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.engine.internal.MeshEngineEmitDiagnostic
import ch.trancee.meshlink.engine.internal.MeshEngineSendEncryptedWireFrame
import ch.trancee.meshlink.engine.internal.diagnosticSuffix
import ch.trancee.meshlink.engine.internal.inboundTransferMetadata
import ch.trancee.meshlink.wire.TransferAbortReasonCode
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineTransferAbortCallbacks(
    val sendEncryptedWireFrame: MeshEngineSendEncryptedWireFrame,
    val sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    val clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit,
    val routeMetadata: suspend (PeerId, Map<String, String>) -> Map<String, String>,
)

internal class MeshEngineTransferAbortSupport(
    private val transferRegistry: MeshEngineTransferRegistry,
    private val outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport,
    private val callbacks: MeshEngineTransferAbortCallbacks,
    private val emitDiagnostic: MeshEngineEmitDiagnostic,
) {
    constructor(
        state: MeshEngineTransferState,
        outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport,
        callbacks: MeshEngineTransferAbortCallbacks,
        emitDiagnostic: MeshEngineEmitDiagnostic,
    ) : this(
        transferRegistry = state.transferRegistry,
        outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
        callbacks = callbacks,
        emitDiagnostic = emitDiagnostic,
    )

    suspend fun abortLocalTransfers(reasonCode: TransferAbortReasonCode): Unit {
        val outboundSessions = outboundTransferLifecycleSupport.takeAllSessions()
        val inboundSessions = transferRegistry.takeAllInboundSessions()
        val relaySessions = transferRegistry.takeAllRelaySessions()

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
            peerId.diagnosticSuffix(),
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

internal fun buildMeshEngineRuntimeTransferAbortSupport(
    transferRegistry: MeshEngineTransferRegistry,
    outboundTransferLifecycleSupport: MeshEngineOutboundTransferLifecycleSupport,
    sendEncryptedWireFrame: MeshEngineSendEncryptedWireFrame,
    sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    clearQueuedOutboundFrames: suspend (PeerId, String) -> Unit,
    routeMetadata: suspend (PeerId, Map<String, String>) -> Map<String, String>,
    emitDiagnostic: MeshEngineEmitDiagnostic,
): MeshEngineTransferAbortSupport {
    return MeshEngineTransferAbortSupport(
        transferRegistry = transferRegistry,
        outboundTransferLifecycleSupport = outboundTransferLifecycleSupport,
        callbacks =
            MeshEngineTransferAbortCallbacks(
                sendEncryptedWireFrame = sendEncryptedWireFrame,
                sendTransferTowardsDestination = sendTransferTowardsDestination,
                clearQueuedOutboundFrames = clearQueuedOutboundFrames,
                routeMetadata = routeMetadata,
            ),
        emitDiagnostic = emitDiagnostic,
    )
}
