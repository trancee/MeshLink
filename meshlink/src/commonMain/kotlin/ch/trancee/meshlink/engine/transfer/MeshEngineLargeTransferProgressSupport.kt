package ch.trancee.meshlink.engine.transfer

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeGate
import ch.trancee.meshlink.engine.internal.DIAGNOSTIC_PEER_SUFFIX_LENGTH
import ch.trancee.meshlink.transfer.AcknowledgementSettlementResult
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.wire.WireFrame
import kotlin.time.Duration

internal data class MeshEngineLargeTransferProgressConfig(
    val ackSettlementTimeout: Duration,
    val ackIdleWindow: Duration,
)

internal data class MeshEngineLargeTransferProgressDependencies(
    val runtimeGate: MeshEngineRuntimeGate,
    val scheduleRetryDiagnostic: suspend (PeerId, DeliveryPriority) -> Unit,
    val sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
)

internal data class MeshEngineLargeTransferProgressCallbacks(
    val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
    val routeMetadata: suspend (PeerId, Map<String, String>) -> Map<String, String>,
)

internal data class MeshEngineLargeTransferProgressResult(
    val lastRouteAvailable: Boolean,
    val transferProgressObserved: Boolean,
    val sessionIsComplete: Boolean,
)

internal class MeshEngineLargeTransferProgressSupport(
    private val config: MeshEngineLargeTransferProgressConfig,
    private val dependencies: MeshEngineLargeTransferProgressDependencies,
    private val callbacks: MeshEngineLargeTransferProgressCallbacks,
) {
    suspend fun advance(
        session: OutboundTransferSession,
        priority: DeliveryPriority,
        remainingBudget: Duration,
        hardRunToken: MeshEngineHardRunToken,
    ): MeshEngineLargeTransferProgressResult {
        var routeAvailable =
            dependencies.sendTransferTowardsDestination(
                session.destinationPeerId,
                session.asStartFrame(),
                "transfer.start",
                hardRunToken,
            )
        var transferProgressObserved = false

        if (!session.isComplete()) {
            session.forEachMissingChunkIndex { chunkIndex ->
                routeAvailable =
                    dependencies.sendTransferTowardsDestination(
                        session.destinationPeerId,
                        WireFrame.TransferChunk(
                            transferId = session.transferId,
                            chunkIndex = chunkIndex,
                            payload = session.chunks[chunkIndex],
                        ),
                        "transfer.chunk",
                        hardRunToken,
                    ) || routeAvailable
            }
            emitLargeTransferProgressDiagnostic(session)
            if (!routeAvailable) {
                dependencies.scheduleRetryDiagnostic(session.destinationPeerId, priority)
            } else {
                transferProgressObserved =
                    awaitLargeTransferAcknowledgementProgress(
                        session = session,
                        remainingBudget = remainingBudget,
                        hardRunToken = hardRunToken,
                    )
            }
        }

        return MeshEngineLargeTransferProgressResult(
            lastRouteAvailable = routeAvailable,
            transferProgressObserved = transferProgressObserved,
            sessionIsComplete = session.isComplete(),
        )
    }

    private suspend fun emitLargeTransferProgressDiagnostic(
        session: OutboundTransferSession
    ): Unit {
        callbacks.emitDiagnostic(
            DiagnosticCode.TRANSFER_PROGRESS,
            DiagnosticSeverity.DEBUG,
            "transfer.send.progress",
            session.destinationPeerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            null,
            callbacks.routeMetadata(
                session.destinationPeerId,
                mapOf(
                    "ackedChunks" to session.acknowledgedChunkCount().toString(),
                    "totalChunks" to session.totalChunks.toString(),
                ),
            ),
        )
    }

    private suspend fun awaitLargeTransferAcknowledgementProgress(
        session: OutboundTransferSession,
        remainingBudget: Duration,
        hardRunToken: MeshEngineHardRunToken,
    ): Boolean {
        val acknowledgedChunkCountBeforeSettlement = session.acknowledgedChunkCount()
        return when (
            val settlement =
                session.awaitAcknowledgementSettlement(
                    maximumWait = remainingBudget.coerceAtMost(config.ackSettlementTimeout),
                    idleWindow = config.ackIdleWindow,
                    runtimeGate = dependencies.runtimeGate,
                    hardRunToken = hardRunToken,
                )
        ) {
            is AcknowledgementSettlementResult.Completed -> {
                settlement.acknowledgedChunkCount > acknowledgedChunkCountBeforeSettlement
            }
            AcknowledgementSettlementResult.HardRunEnded -> false
        }
    }
}
