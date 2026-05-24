package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineInlineDispatchRoutingContext(
    val routeCoordinator: ch.trancee.meshlink.routing.RouteCoordinator,
    val routingSupport: MeshEngineRoutingSupport,
)

internal data class MeshEngineInlineDispatchDependencies(
    val ensureHopSession: suspend (PeerId, MeshEngineHardRunToken) -> SessionEstablishmentOutcome,
    val sendEncryptedDirectWireFrame:
        suspend (PeerId, HopSession, WireFrame, String) -> TransportSendResult,
    val scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit,
    val emitHopSessionFailed: (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
)

internal data class MeshEngineInlineDispatchCallbacks(
    val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit
)

internal sealed class MeshEngineInlineDispatchResolution {
    internal class Ready
    internal constructor(internal val nextHopPeerId: PeerId, internal val session: HopSession) :
        MeshEngineInlineDispatchResolution()

    internal data object AwaitRetry : MeshEngineInlineDispatchResolution()

    internal data object TrustFailure : MeshEngineInlineDispatchResolution()
}

internal sealed class MeshEngineInlineDispatchResult {
    internal data object Delivered : MeshEngineInlineDispatchResult()

    internal data object AwaitRetry : MeshEngineInlineDispatchResult()
}

internal class MeshEngineInlineDispatchSupport(
    private val routingContext: MeshEngineInlineDispatchRoutingContext,
    private val dependencies: MeshEngineInlineDispatchDependencies,
    private val callbacks: MeshEngineInlineDispatchCallbacks,
) {
    fun currentTopologyVersion(): Long {
        return routingContext.routeCoordinator.topologyVersion.value
    }

    fun routeMetadata(peerId: PeerId): Map<String, String> {
        return routingContext.routingSupport.peerRouteMetadata(peerId)
    }

    suspend fun resolveDispatch(
        peerId: PeerId,
        priority: DeliveryPriority,
        hardRunToken: MeshEngineHardRunToken,
    ): MeshEngineInlineDispatchResolution {
        val nextHopPeerId = routingContext.routeCoordinator.nextHopFor(peerId) ?: peerId
        return when (
            val sessionOutcome = dependencies.ensureHopSession(nextHopPeerId, hardRunToken)
        ) {
            is SessionEstablishmentOutcome.Established ->
                MeshEngineInlineDispatchResolution.Ready(
                    nextHopPeerId = nextHopPeerId,
                    session = sessionOutcome.session,
                )
            SessionEstablishmentOutcome.TrustFailure ->
                MeshEngineInlineDispatchResolution.TrustFailure
            SessionEstablishmentOutcome.Unreachable -> {
                dependencies.scheduleRetryDiagnostic(peerId, priority)
                MeshEngineInlineDispatchResolution.AwaitRetry
            }
        }
    }

    suspend fun dispatchPreparedMessage(
        peerId: PeerId,
        priority: DeliveryPriority,
        routedMessage: WireFrame.Message,
        resolution: MeshEngineInlineDispatchResolution.Ready,
    ): MeshEngineInlineDispatchResult {
        return when (
            runCatching {
                    dependencies.sendEncryptedDirectWireFrame(
                        resolution.nextHopPeerId,
                        resolution.session,
                        routedMessage,
                        "send.data",
                    )
                }
                .getOrElse { exception ->
                    dependencies.emitHopSessionFailed(
                        resolution.nextHopPeerId,
                        "delivery.send.transportEncrypt",
                        DiagnosticReason.DELIVERY_FAILURE,
                        mapOf("cause" to exception::class.simpleName.orEmpty()),
                    )
                    return MeshEngineInlineDispatchResult.AwaitRetry
                }
        ) {
            TransportSendResult.Delivered -> {
                callbacks.emitDiagnostic(
                    DiagnosticCode.DELIVERY_SUCCEEDED,
                    DiagnosticSeverity.INFO,
                    "delivery.send",
                    peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                    null,
                    routeMetadata(peerId),
                )
                MeshEngineInlineDispatchResult.Delivered
            }
            is TransportSendResult.Dropped -> {
                dependencies.scheduleRetryDiagnostic(peerId, priority)
                MeshEngineInlineDispatchResult.AwaitRetry
            }
        }
    }
}
