package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineOutboundTransferLifecycleState(
    val outboundTransfers: MutableMap<String, OutboundTransferSession>
)

internal data class MeshEngineOutboundTransferLifecycleDependencies(
    val prepareOutboundTransferSession:
        suspend (PeerId, ByteArray, MeshEngineHardRunToken) -> OutboundTransferPreparation,
    val scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit,
)

internal sealed class MeshEngineOutboundTransferLifecycleResolution {
    internal class Ready internal constructor(internal val session: OutboundTransferSession) :
        MeshEngineOutboundTransferLifecycleResolution()

    internal class Completed internal constructor(internal val result: SendResult) :
        MeshEngineOutboundTransferLifecycleResolution()

    internal data object AwaitRetry : MeshEngineOutboundTransferLifecycleResolution()
}

internal class MeshEngineOutboundTransferLifecycleSupport(
    private val state: MeshEngineOutboundTransferLifecycleState,
    private val dependencies: MeshEngineOutboundTransferLifecycleDependencies,
) {
    suspend fun resolveActiveOrPrepareSession(
        activeSession: OutboundTransferSession?,
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        hardRunToken: MeshEngineHardRunToken,
    ): MeshEngineOutboundTransferLifecycleResolution {
        if (activeSession != null) {
            return MeshEngineOutboundTransferLifecycleResolution.Ready(activeSession)
        }

        return when (
            val preparation =
                dependencies.prepareOutboundTransferSession(peerId, payload, hardRunToken)
        ) {
            OutboundTransferPreparation.PendingRoute -> {
                dependencies.scheduleRetryDiagnostic(peerId, priority)
                MeshEngineOutboundTransferLifecycleResolution.AwaitRetry
            }
            is OutboundTransferPreparation.Ready -> {
                state.outboundTransfers[preparation.session.transferId] = preparation.session
                MeshEngineOutboundTransferLifecycleResolution.Ready(preparation.session)
            }
            is OutboundTransferPreparation.Failed ->
                MeshEngineOutboundTransferLifecycleResolution.Completed(preparation.result)
        }
    }

    fun markAcknowledged(frame: WireFrame.TransferAck): Boolean {
        val outboundSession = state.outboundTransfers[frame.transferId] ?: return false
        outboundSession.markAcknowledged(frame)
        return true
    }

    fun removeSession(transferId: String): OutboundTransferSession? {
        return state.outboundTransfers.remove(transferId)
    }

    fun takeAllSessions(): List<OutboundTransferSession> {
        val outboundSessions = state.outboundTransfers.values.toList()
        state.outboundTransfers.clear()
        return outboundSessions
    }

    fun clearAll(): Unit {
        state.outboundTransfers.clear()
    }
}
