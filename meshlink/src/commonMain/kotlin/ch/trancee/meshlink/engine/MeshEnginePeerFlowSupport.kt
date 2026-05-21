package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.identity.hexContentEquals
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal data class MeshEnginePeerFlowConfig(val largeInlineTransportBudgetBytes: Int)

internal data class MeshEnginePeerFlowContext(
    val routeCoordinator: RouteCoordinator,
    val coroutineScope: CoroutineScope,
)

internal data class MeshEnginePeerFlowCallbacks(
    val sendEncryptedWireFrame: suspend (PeerId, WireFrame, String) -> Boolean,
    val ensureHopSession: suspend (PeerId) -> SessionEstablishmentOutcome,
    val maximumPayloadBytesPerDelivery: (PeerId) -> Int?,
)

internal class MeshEnginePeerFlowSupport(
    private val localIdentity: LocalIdentity,
    private val context: MeshEnginePeerFlowContext,
    private val config: MeshEnginePeerFlowConfig,
    private val callbacks: MeshEnginePeerFlowCallbacks,
) {
    suspend fun sendTransferTowardsDestination(
        destinationPeerId: PeerId,
        frame: WireFrame,
        action: String,
    ): Boolean {
        val nextHopPeerId =
            context.routeCoordinator.nextHopFor(destinationPeerId) ?: destinationPeerId
        return callbacks.sendEncryptedWireFrame(nextHopPeerId, frame, action)
    }

    fun prewarmHopSession(peerId: PeerId): Unit {
        if (localIdentity.peerId.value >= peerId.value) {
            return
        }
        context.coroutineScope.launch { callbacks.ensureHopSession(peerId) }
    }

    fun forwardMessageToNextHop(frame: WireFrame.Message): Unit {
        val nextHopPeerId = context.routeCoordinator.nextHopFor(frame.destinationPeerId) ?: return
        context.coroutineScope.launch {
            callbacks.sendEncryptedWireFrame(nextHopPeerId, frame, "forward.message")
        }
    }

    fun shouldAttemptLargeInlineSend(peerId: PeerId): Boolean {
        val nextHopPeerId = context.routeCoordinator.nextHopFor(peerId) ?: peerId
        return nextHopPeerId.value == peerId.value &&
            (callbacks.maximumPayloadBytesPerDelivery(nextHopPeerId)?.let { transportBudget ->
                transportBudget >= config.largeInlineTransportBudgetBytes
            } ?: false)
    }

    fun isLocalPeerId(peerId: PeerId): Boolean {
        return peerId.value == localIdentity.peerId.value ||
            peerId.value.hexContentEquals(localIdentity.advertisementKeyHash)
    }
}
