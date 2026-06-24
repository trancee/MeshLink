package ch.trancee.meshlink.reference.guided

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Shared state holder for the guided first-exchange surface. */
internal class GuidedFirstExchangeViewModel(
    private val platformName: String,
    private val readinessGuidance: List<String>,
    private val readinessBlockers: List<String>,
    private val powerMitigationStatus: String?,
    private val meshLinkController: ReferenceMeshLinkController,
    private val automationMode: String? = null,
    private val automationRole: String? = null,
    private val automationScenario: String? = null,
    private val automationTargetPeerId: String? = null,
    private val autoStartMesh: Boolean = false,
    private val autoSendHello: Boolean = false,
    private val emitAutomationLog: (String) -> Unit = {},
    private val currentTimeMillis: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val stateStore: GuidedFirstExchangeStateStore =
        GuidedFirstExchangeStateStore(
            platformName = platformName,
            readinessGuidance = readinessGuidance,
            readinessBlockers = readinessBlockers,
            initialSnapshot = meshLinkController.snapshot.value,
        )
    private val powerMitigationLabelValue: String? = powerMitigationStatus
    private var autoSendTriggered: Boolean = false
    private var peerDiscoveryLogged: Boolean = false
    private var discoveryPendingLogged: Boolean = false
    private var discoveryStalledLogged: Boolean = false
    private var routeReadyLogged: Boolean = false
    private var routePendingLogged: Boolean = false
    private val initAtEpochMillis: Long = currentTimeMillis()

    public val powerMitigationLabel: String?
        get() = powerMitigationLabelValue

    public val uiState: StateFlow<GuidedFirstExchangeUiState> = stateStore.uiState

    init {
        emitAutomationLog(
            "REFERENCE_AUTOMATION startup-state=guided.viewModel.init mode=${automationMode ?: "none"} role=${automationRole ?: "none"} scenario=${automationScenario ?: "none"} autoStartMesh=$autoStartMesh autoSendHello=$autoSendHello targetPeerId=${automationTargetPeerId ?: "none"}"
        )
        scope.launch {
            emitAutomationLog(
                "REFERENCE_AUTOMATION startup-state=guided.viewModel.snapshot.collect.begin"
            )
            meshLinkController.snapshot.collectLatest { snapshot ->
                stateStore.applySnapshot(snapshot)
                maybeLogDiscoveryPending(snapshot)
                maybeLogPeerDiscovery(snapshot)
                maybeLogRouteReadiness(snapshot)
                maybeAutoSendHello(snapshot)
            }
        }
        scope.launch {
            delay(3_000L)
            maybeLogDiscoveryStalled()
        }
        if (autoStartMesh) {
            emitAutomationLog(
                "REFERENCE_AUTOMATION startup-state=guided.viewModel.autoStartMesh.requested"
            )
            startMesh()
        }
    }

    public fun startMesh(): Unit {
        emitAutomationLog("REFERENCE_AUTOMATION startup-state=guided.viewModel.startMesh.requested")
        scope.launch {
            emitAutomationLog("REFERENCE_AUTOMATION startup-state=guided.viewModel.startMesh.begin")
            meshLinkController.start()
            emitAutomationLog("REFERENCE_AUTOMATION startup-state=guided.viewModel.startMesh.end")
        }
    }

    public fun sendHelloToFirstPeer(): Unit {
        val firstPeer = uiState.value.snapshot.peers.firstOrNull() ?: return
        sendHelloToPeer(firstPeer.peerId)
    }

    public fun sendHelloToPeer(peerId: String): Unit {
        emitAutomationLog("REFERENCE_AUTOMATION send.requested role=sender peerId=$peerId")
        emitAutomationLog(
            "REFERENCE_AUTOMATION startup-state=guided.viewModel.sendHello.requested peerId=$peerId"
        )
        scope.launch {
            emitAutomationLog(
                "REFERENCE_AUTOMATION startup-state=guided.viewModel.sendHello.begin peerId=$peerId"
            )
            meshLinkController.sendPayload(
                peerId = peerId,
                payloadText = "hello mesh from $platformName",
                priority = DeliveryPriority.NORMAL,
            )
            emitAutomationLog(
                "REFERENCE_AUTOMATION startup-state=guided.viewModel.sendHello.end peerId=$peerId"
            )
        }
    }

    private fun maybeLogDiscoveryPending(snapshot: ReferenceControllerSnapshot): Unit {
        if (discoveryPendingLogged || snapshot.peers.isNotEmpty()) return
        discoveryPendingLogged = true
        val role = automationRole ?: "unknown"
        emitAutomationLog(
            "REFERENCE_AUTOMATION discovery.pending role=$role count=0 selectedPeerId=none elapsedSeconds=0.0"
        )
        emitAutomationLog(
            "REFERENCE_AUTOMATION startup-state=guided.viewModel.discovery.pending role=$role count=0 selectedPeerId=none"
        )
        if (role.equals("sender", ignoreCase = true) || role.equals("SENDER", ignoreCase = true)) {
            emitAutomationLog(
                "REFERENCE_AUTOMATION sender.discovery.pending role=$role count=0 selectedPeerId=none elapsedSeconds=0.0"
            )
        }
    }

    private fun maybeLogDiscoveryStalled(): Unit {
        if (discoveryStalledLogged) return
        val snapshot = uiState.value.snapshot
        if (snapshot.peers.isNotEmpty()) return
        discoveryStalledLogged = true
        val role = automationRole ?: "unknown"
        emitAutomationLog(
            "REFERENCE_AUTOMATION discovery.stalled role=$role count=0 selectedPeerId=none elapsedSeconds=3.0"
        )
        emitAutomationLog(
            "REFERENCE_AUTOMATION startup-state=guided.viewModel.discovery.stalled role=$role count=0 selectedPeerId=none elapsedSeconds=3.0 initAt=$initAtEpochMillis"
        )
        if (role.equals("sender", ignoreCase = true) || role.equals("SENDER", ignoreCase = true)) {
            emitAutomationLog(
                "REFERENCE_AUTOMATION sender.discovery.stalled role=$role count=0 selectedPeerId=none elapsedSeconds=3.0"
            )
        }
    }

    private fun maybeLogPeerDiscovery(snapshot: ReferenceControllerSnapshot): Unit {
        if (peerDiscoveryLogged) return
        val peer = snapshot.peers.firstOrNull() ?: return
        peerDiscoveryLogged = true
        emitAutomationLog(
            "REFERENCE_AUTOMATION peer.discovered role=${automationRole ?: "unknown"} peerId=${peer.peerId} peerSuffix=${peer.peerSuffix}"
        )
        emitAutomationLog(
            "REFERENCE_AUTOMATION startup-state=guided.viewModel.peer.discovered peerId=${peer.peerId} peerSuffix=${peer.peerSuffix}"
        )
    }

    private fun maybeLogRouteReadiness(snapshot: ReferenceControllerSnapshot): Unit {
        val peer = snapshot.peers.firstOrNull()
        if (peer == null) {
            if (routePendingLogged) return
            routePendingLogged = true
            emitAutomationLog(
                "REFERENCE_AUTOMATION route.pending role=${automationRole ?: "unknown"} count=0 selectedPeerId=none"
            )
            return
        }
        if (peer.connectionState == PeerConnectionSnapshotState.CONNECTED) {
            if (routeReadyLogged) return
            routeReadyLogged = true
            emitAutomationLog(
                "REFERENCE_AUTOMATION route.ready role=${automationRole ?: "unknown"} peerId=${peer.peerId} peerSuffix=${peer.peerSuffix} trustState=${peer.trustState} connectionState=${peer.connectionState}"
            )
            emitAutomationLog(
                "REFERENCE_AUTOMATION startup-state=guided.viewModel.route.ready peerId=${peer.peerId} peerSuffix=${peer.peerSuffix} trustState=${peer.trustState} connectionState=${peer.connectionState}"
            )
            return
        }
        if (routePendingLogged) return
        routePendingLogged = true
        emitAutomationLog(
            "REFERENCE_AUTOMATION route.pending role=${automationRole ?: "unknown"} peerId=${peer.peerId} peerSuffix=${peer.peerSuffix} trustState=${peer.trustState} connectionState=${peer.connectionState}"
        )
    }

    private fun maybeAutoSendHello(snapshot: ReferenceControllerSnapshot): Unit {
        if (!autoSendHello || autoSendTriggered) return
        val targetPeerId = automationTargetPeerId
        val peer =
            when {
                targetPeerId != null -> snapshot.peers.firstOrNull { it.peerId == targetPeerId }
                else -> snapshot.peers.firstOrNull()
            } ?: return
        if (peer.connectionState != PeerConnectionSnapshotState.CONNECTED) return
        autoSendTriggered = true
        emitAutomationLog(
            "REFERENCE_AUTOMATION startup-state=guided.viewModel.autoSendHello.requested peerId=${peer.peerId} targetPeerId=${targetPeerId ?: "none"}"
        )
        sendHelloToPeer(peer.peerId)
    }
}

internal data class GuidedFirstExchangeUiState(
    public val readiness: ReadinessEvaluation,
    public val snapshot: ReferenceControllerSnapshot,
) {
    public val nextActionLabel: String
        get() {
            return when {
                isSessionEnded -> "Review the captured session state"
                readiness.isBlocked -> "Resolve startup blockers"
                snapshot.session.meshStateLabel.contains("Uninitialized") -> "Start MeshLink"
                snapshot.peers.isEmpty() -> "Wait for a peer or rerun the harness"
                else -> "Send the first test message"
            }
        }

    public val isSessionEnded: Boolean
        get() = snapshot.session.endedAtEpochMillis != null

    public val selectedPeerSuffix: String?
        get() = snapshot.peers.firstOrNull()?.peerSuffix

    public val canSendHello: Boolean
        get() = snapshot.peers.isNotEmpty() && !readiness.isBlocked && !isSessionEnded
}
