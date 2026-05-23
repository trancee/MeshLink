package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
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
    val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
    val peerRouteMetadata: (PeerId, Map<String, String>) -> Map<String, String>,
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
        val destinationPeerId = frame.destinationPeerId
        val originPeerId = frame.originPeerId
        val peerSuffix = destinationPeerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH)
        val diagnosticMetadata =
            mapOf("originPeerId" to originPeerId.value, "forwardAction" to "relay")
        val nextHopPeerId = context.routeCoordinator.nextHopFor(destinationPeerId)
        if (nextHopPeerId == null) {
            callbacks.emitDiagnostic(
                DiagnosticCode.DELIVERY_UNREACHABLE,
                DiagnosticSeverity.ERROR,
                "forward.message.noRoute",
                peerSuffix,
                DiagnosticReason.DELIVERY_FAILURE,
                callbacks.peerRouteMetadata(destinationPeerId, diagnosticMetadata),
            )
            return
        }

        callbacks.emitDiagnostic(
            DiagnosticCode.DELIVERY_QUEUED,
            DiagnosticSeverity.INFO,
            "forward.message.queued",
            peerSuffix,
            null,
            callbacks.peerRouteMetadata(destinationPeerId, diagnosticMetadata),
        )
        context.coroutineScope.launch {
            val forwarded =
                callbacks.sendEncryptedWireFrame(nextHopPeerId, frame, "forward.message")
            callbacks.emitDiagnostic(
                if (forwarded) DiagnosticCode.DELIVERY_SUCCEEDED
                else DiagnosticCode.DELIVERY_UNREACHABLE,
                if (forwarded) DiagnosticSeverity.INFO else DiagnosticSeverity.ERROR,
                if (forwarded) "forward.message.delivered" else "forward.message.failed",
                peerSuffix,
                if (forwarded) null else DiagnosticReason.DELIVERY_FAILURE,
                callbacks.peerRouteMetadata(destinationPeerId, diagnosticMetadata),
            )
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
