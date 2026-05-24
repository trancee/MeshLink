package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.power.PowerPolicyController
import ch.trancee.meshlink.presence.PeerPresenceTracker
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.transfer.InboundTransferSession
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.RelayTransferSession
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.wire.TransferAbortReasonCode
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.CoroutineScope

internal data class MeshEngineRuntimeAssemblyEnvironment(
    val config: MeshLinkConfig,
    val localIdentity: LocalIdentity,
    val trustStore: TofuTrustStore,
    val coroutineScope: CoroutineScope,
    val platformBridge: MeshEnginePlatformBridge,
    val publishedSurface: MeshEnginePublishedRuntimeSurface,
    val compatibilitySurface: MeshEngineCompatibilityRuntimeSurface,
)

internal data class MeshEngineRuntimeAssemblySupport(
    val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
    val sendDirectWireFrame:
        suspend (PeerId, DirectWireFrame, String, TransportMode?) -> TransportSendResult,
)

internal class MeshEngineRuntimeLateBindingContext {
    private var routingAdvertisementSender:
        (suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean)? =
        null

    fun registerRoutingAdvertisementSender(
        sender: suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean
    ): Unit {
        check(routingAdvertisementSender == null) {
            "routingAdvertisementSender is already registered"
        }
        routingAdvertisementSender = sender
    }

    fun routingAdvertisementSender():
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean {
        return routingAdvertisementSender ?: error("routingAdvertisementSender is not registered")
    }
}

internal data class MeshEngineRuntimeFoundationAssembly(
    val sharedState: MeshEngineRuntimeSharedState,
    val routingAndTrust: MeshEngineRuntimeRoutingAndTrustPhase,
)

internal data class MeshEngineRuntimeSharedState(
    val presenceTracker: PeerPresenceTracker,
    val routeCoordinator: RouteCoordinator,
    val deliveryRetryScheduler: DeliveryRetryScheduler,
    val powerPolicyController: PowerPolicyController,
    val sessionRegistry: MeshEngineSessionRegistry,
    val outboundTransfers: MutableMap<String, OutboundTransferSession>,
    val inboundTransfers: MutableMap<String, InboundTransferSession>,
    val relayTransfers: MutableMap<String, RelayTransferSession>,
    val sequenceGenerator: MeshEngineSequenceGenerator,
    val powerPolicyNowMillis: () -> Long,
    val ttlMillisFor: (DeliveryPriority) -> Int,
)

internal data class MeshEngineRuntimeRoutingAndTrustPhase(
    val routingSupport: MeshEngineRoutingSupport,
    val trustSupport: MeshEngineTrustSupport,
    val scheduleRetryDiagnostic: (PeerId, DeliveryPriority) -> Unit,
)

internal data class MeshEngineRuntimeSessionAssembly(
    val ensureHopSession: suspend (PeerId, MeshEngineHardRunToken?) -> SessionEstablishmentOutcome,
    val sendEncryptedWireFrame:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    val sendEncryptedDirectWireFrame:
        suspend (PeerId, HopSession, WireFrame, String) -> TransportSendResult,
    val decryptHopPayload: (HopSession, ByteArray) -> ByteArray,
    val emitHopSessionFailed: (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
    val prewarmHopSession: (PeerId) -> Unit,
    val forwardMessageToNextHop: (WireFrame.Message, MeshEngineHardRunToken) -> Unit,
    val shouldAttemptLargeInlineSend: (PeerId) -> Boolean,
    val isLocalPeerId: (PeerId) -> Boolean,
    val sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    val handleHandshakeMessage1: suspend (PeerId, ByteArray) -> Unit,
    val handleHandshakeMessage2: suspend (PeerId, ByteArray) -> Unit,
    val handleHandshakeMessage3: suspend (PeerId, ByteArray) -> Unit,
)

internal data class MeshEngineRuntimeTransferAndInboundPhase(
    val sendPayload:
        suspend (
            MeshEngineOutboundDeliveryMode,
            PeerId,
            ByteArray,
            DeliveryPriority,
            MeshEngineHardRunToken,
        ) -> SendResult,
    val handleEncryptedDataFrame: suspend (PeerId, ByteArray) -> Unit,
    val abortLocalTransfers: suspend (TransferAbortReasonCode) -> Unit,
    val clearOutboundTransfers: () -> Unit,
)

internal interface MeshEngineRuntimeFacadeOperations {
    suspend fun start(): ch.trancee.meshlink.api.StartResult

    suspend fun pause(): ch.trancee.meshlink.api.PauseResult

    suspend fun resume(): ch.trancee.meshlink.api.ResumeResult

    suspend fun stop(): ch.trancee.meshlink.api.StopResult

    suspend fun send(peerId: PeerId, payload: ByteArray, priority: DeliveryPriority): SendResult

    suspend fun forgetPeer(peerId: PeerId): ch.trancee.meshlink.api.ForgetPeerResult

    fun updateBattery(level: Float, isCharging: Boolean): Unit
}
