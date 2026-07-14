package ch.trancee.meshlink.engine.lifecycle

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeGate
import ch.trancee.meshlink.engine.handshake.shouldInitiateHandshakeTowards
import ch.trancee.meshlink.engine.internal.DIAGNOSTIC_PEER_SUFFIX_LENGTH
import ch.trancee.meshlink.engine.internal.SessionEstablishmentOutcome
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
    val runtimeGate: MeshEngineRuntimeGate,
    val captureHardRunToken: () -> MeshEngineHardRunToken,
    val sendEncryptedWireFrame:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
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
    val peerRouteMetadata: suspend (PeerId, Map<String, String>) -> Map<String, String>,
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
        hardRunToken: MeshEngineHardRunToken,
    ): Boolean {
        val nextHopPeerId =
            context.routeCoordinator.nextHopFor(destinationPeerId) ?: destinationPeerId
        return callbacks.sendEncryptedWireFrame(nextHopPeerId, frame, action, hardRunToken)
    }

    fun prewarmHopSession(peerId: PeerId): Unit {
        if (!shouldInitiateHandshakeTowards(localIdentity.peerId, peerId)) {
            return
        }
        val hardRunToken = callbacks.captureHardRunToken()
        context.coroutineScope.launch {
            if (!callbacks.runtimeGate.isHardRunActive(hardRunToken)) {
                return@launch
            }
            callbacks.ensureHopSession(peerId)
        }
    }

    suspend fun forwardMessageToNextHop(
        frame: WireFrame.Message,
        hardRunToken: MeshEngineHardRunToken,
    ): Unit {
        forwardFrameToNextHop(
            destinationPeerId = frame.destinationPeerId,
            originPeerId = frame.originPeerId,
            frame = frame,
            action = "forward.message",
            hardRunToken = hardRunToken,
        )
    }

    /**
     * Relays an end-to-end (multi-hop) Noise XX handshake frame one hop closer to
     * [WireFrame.EndToEndHandshakeFrame.destinationPeerId]. Intermediate relays never decode
     * [WireFrame.EndToEndHandshakeFrame.payload]; they only look at routing metadata, so the
     * handshake stays authenticated end-to-end between
     * [WireFrame.EndToEndHandshakeFrame.originPeerId] and the destination.
     */
    suspend fun forwardEndToEndHandshakeFrame(
        frame: WireFrame.EndToEndHandshakeFrame,
        hardRunToken: MeshEngineHardRunToken,
    ): Unit {
        forwardFrameToNextHop(
            destinationPeerId = frame.destinationPeerId,
            originPeerId = frame.originPeerId,
            frame = frame as WireFrame,
            action = "forward.e2eHandshake",
            hardRunToken = hardRunToken,
        )
    }

    private suspend fun forwardFrameToNextHop(
        destinationPeerId: PeerId,
        originPeerId: PeerId,
        frame: WireFrame,
        action: String,
        hardRunToken: MeshEngineHardRunToken,
    ): Unit {
        val peerSuffix = destinationPeerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH)
        val diagnosticMetadata =
            mapOf("originPeerId" to originPeerId.value, "forwardAction" to "relay")
        val nextHopPeerId = context.routeCoordinator.nextHopFor(destinationPeerId)
        if (nextHopPeerId == null) {
            callbacks.emitDiagnostic(
                DiagnosticCode.DELIVERY_UNREACHABLE,
                DiagnosticSeverity.ERROR,
                "$action.noRoute",
                peerSuffix,
                DiagnosticReason.DELIVERY_FAILURE,
                callbacks.peerRouteMetadata(destinationPeerId, diagnosticMetadata),
            )
            return
        }

        callbacks.emitDiagnostic(
            DiagnosticCode.DELIVERY_QUEUED,
            DiagnosticSeverity.INFO,
            "$action.queued",
            peerSuffix,
            null,
            callbacks.peerRouteMetadata(destinationPeerId, diagnosticMetadata),
        )
        context.coroutineScope.launch {
            val forwarded =
                callbacks.sendEncryptedWireFrame(nextHopPeerId, frame, action, hardRunToken)
            if (!callbacks.runtimeGate.isHardRunActive(hardRunToken) && !forwarded) {
                return@launch
            }
            callbacks.emitDiagnostic(
                if (forwarded) DiagnosticCode.DELIVERY_SUCCEEDED
                else DiagnosticCode.DELIVERY_UNREACHABLE,
                if (forwarded) DiagnosticSeverity.INFO else DiagnosticSeverity.ERROR,
                if (forwarded) "$action.delivered" else "$action.failed",
                peerSuffix,
                if (forwarded) null else DiagnosticReason.DELIVERY_FAILURE,
                callbacks.peerRouteMetadata(destinationPeerId, diagnosticMetadata),
            )
        }
    }

    suspend fun shouldAttemptLargeInlineSend(peerId: PeerId): Boolean {
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

internal fun buildMeshEngineRuntimePeerFlowSupport(
    localIdentity: LocalIdentity,
    routeCoordinator: RouteCoordinator,
    coroutineScope: CoroutineScope,
    runtimeGate: MeshEngineRuntimeGate,
    captureHardRunToken: () -> MeshEngineHardRunToken,
    sendEncryptedWireFrame: suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    ensureHopSession: suspend (PeerId) -> SessionEstablishmentOutcome,
    maximumPayloadBytesPerDelivery: (PeerId) -> Int?,
    emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
    peerRouteMetadata: suspend (PeerId, Map<String, String>) -> Map<String, String>,
): MeshEnginePeerFlowSupport {
    return MeshEnginePeerFlowSupport(
        localIdentity = localIdentity,
        context =
            MeshEnginePeerFlowContext(
                routeCoordinator = routeCoordinator,
                coroutineScope = coroutineScope,
            ),
        config =
            MeshEnginePeerFlowConfig(
                largeInlineTransportBudgetBytes = LARGE_INLINE_SEND_TRANSPORT_BUDGET_BYTES
            ),
        callbacks =
            MeshEnginePeerFlowCallbacks(
                runtimeGate = runtimeGate,
                captureHardRunToken = captureHardRunToken,
                sendEncryptedWireFrame = sendEncryptedWireFrame,
                ensureHopSession = ensureHopSession,
                maximumPayloadBytesPerDelivery = maximumPayloadBytesPerDelivery,
                emitDiagnostic = emitDiagnostic,
                peerRouteMetadata = peerRouteMetadata,
            ),
    )
}

private const val LARGE_INLINE_SEND_TRANSPORT_BUDGET_BYTES: Int = 16 * 1024
