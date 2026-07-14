package ch.trancee.meshlink.engine.transfer

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.engine.internal.OutboundTransferPreparation
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineOutboundTransferLifecycleState(
    val transferRegistry: MeshEngineTransferRegistry
) {
    constructor(
        outboundTransfers: MutableMap<String, ch.trancee.meshlink.transfer.OutboundTransferSession>
    ) : this(MeshEngineTransferRegistry(outboundTransfers = outboundTransfers))
}

internal data class MeshEngineOutboundTransferLifecycleDependencies(
    val prepareOutboundTransferSession:
        suspend (PeerId, ByteArray, MeshEngineHardRunToken) -> OutboundTransferPreparation,
    val scheduleRetryDiagnostic: suspend (PeerId, DeliveryPriority) -> Unit,
)

internal sealed class MeshEngineOutboundTransferLifecycleResolution {
    internal class Ready
    internal constructor(
        internal val session: ch.trancee.meshlink.transfer.OutboundTransferSession
    ) : MeshEngineOutboundTransferLifecycleResolution()

    internal class Completed internal constructor(internal val result: SendResult) :
        MeshEngineOutboundTransferLifecycleResolution()

    internal data object AwaitRetry : MeshEngineOutboundTransferLifecycleResolution()
}

internal class MeshEngineOutboundTransferLifecycleSupport(
    private val state: MeshEngineOutboundTransferLifecycleState,
    private val dependencies: MeshEngineOutboundTransferLifecycleDependencies,
) {
    suspend fun resolveActiveOrPrepareSession(
        activeSession: ch.trancee.meshlink.transfer.OutboundTransferSession?,
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
                state.transferRegistry.storeOutboundSession(preparation.session)
                MeshEngineOutboundTransferLifecycleResolution.Ready(preparation.session)
            }
            is OutboundTransferPreparation.Failed ->
                MeshEngineOutboundTransferLifecycleResolution.Completed(preparation.result)
        }
    }

    suspend fun markAcknowledged(frame: WireFrame.TransferAck): Boolean {
        val outboundSession =
            state.transferRegistry.outboundSession(frame.transferId) ?: return false
        outboundSession.markAcknowledged(frame)
        return true
    }

    suspend fun removeSession(
        transferId: String
    ): ch.trancee.meshlink.transfer.OutboundTransferSession? {
        return state.transferRegistry.removeOutboundSession(transferId)
    }

    suspend fun takeAllSessions(): List<ch.trancee.meshlink.transfer.OutboundTransferSession> {
        return state.transferRegistry.takeAllOutboundSessions()
    }

    suspend fun clearAll(): Unit {
        state.transferRegistry.clearOutboundSessions()
    }
}

internal fun buildMeshEngineRuntimeOutboundTransferLifecycleSupport(
    transferRegistry: MeshEngineTransferRegistry,
    prepareOutboundTransferSession:
        suspend (PeerId, ByteArray, MeshEngineHardRunToken) -> OutboundTransferPreparation,
    scheduleRetryDiagnostic: suspend (PeerId, DeliveryPriority) -> Unit,
): MeshEngineOutboundTransferLifecycleSupport {
    return MeshEngineOutboundTransferLifecycleSupport(
        state = MeshEngineOutboundTransferLifecycleState(transferRegistry = transferRegistry),
        dependencies =
            MeshEngineOutboundTransferLifecycleDependencies(
                prepareOutboundTransferSession = prepareOutboundTransferSession,
                scheduleRetryDiagnostic = scheduleRetryDiagnostic,
            ),
    )
}
