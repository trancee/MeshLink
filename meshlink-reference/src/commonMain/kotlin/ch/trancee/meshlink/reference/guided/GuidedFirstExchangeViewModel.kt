package ch.trancee.meshlink.reference.guided

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import ch.trancee.meshlink.reference.timeline.isEligibleForAutomaticRetention
import ch.trancee.meshlink.reference.timeline.redactedRetainedSnapshot
import ch.trancee.meshlink.reference.timeline.writeSessionArtifact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val automationDocumentStore: ReferenceDocumentStore? = null,
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
    private var senderCompletionLogged: Boolean = false
    private var passiveCompletionTriggered: Boolean = false
    private val initAtEpochMillis: Long = currentTimeMillis()
    private val isPassiveRole: Boolean =
        automationRole?.equals("passive", ignoreCase = true) == true
    private val isSenderRole: Boolean = automationRole?.equals("sender", ignoreCase = true) == true

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
                maybeCompletePassiveExchange(snapshot)
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
            maybeLogSenderCompletion(peerId)
        }
    }

    /**
     * Logs the sender-role automation completion marker once the guided flow's single automated
     * hello send has returned without throwing. The Android/iOS direct-proof test harnesses
     * (`run_headless_reference_android_direct_proof.py`, `run_headless_reference_live_proof.py`)
     * key their pass/fail decision off this exact "REFERENCE_AUTOMATION proof.complete role=sender"
     * marker.
     */
    private fun maybeLogSenderCompletion(peerId: String): Unit {
        if (senderCompletionLogged || !isSenderRole) return
        senderCompletionLogged = true
        emitAutomationLog(
            "REFERENCE_AUTOMATION proof.complete role=sender deliveries=1 peerId=$peerId"
        )
    }

    /**
     * On the passive role, once an inbound message has been recorded in the live timeline, persists
     * the session to retained history and writes a redacted export artifact -- mirroring exactly
     * what the manual "Export session" UI action does (see [writeSessionArtifact]) -- then logs the
     * "REFERENCE_AUTOMATION proof.complete role=passive ... export=<path>" marker the direct-proof
     * test harnesses poll for. Without this, the automated "direct-guided" scenario never produced
     * the completion evidence the harnesses were already checking for, even when the underlying
     * mesh exchange succeeded end-to-end.
     */
    private fun maybeCompletePassiveExchange(snapshot: ReferenceControllerSnapshot): Unit {
        if (passiveCompletionTriggered || !isPassiveRole) return
        val documentStore = automationDocumentStore ?: return
        if (!snapshot.timeline.any { entry -> entry.family == TimelineFamily.MESSAGE }) return
        if (!snapshot.isEligibleForAutomaticRetention(readinessBlockers)) return
        passiveCompletionTriggered = true
        scope.launch {
            val historyRepository = JsonSessionHistoryRepository(documentStore)
            val artifactSerializer = JsonSessionArtifactSerializer(documentStore)
            val retainedSnapshot = snapshot.redactedRetainedSnapshot()
            historyRepository.retainSnapshot(retainedSnapshot)
            val exportPath =
                writeSessionArtifact(
                    snapshot = retainedSnapshot,
                    policy = ExportPayloadPolicy.REDACTED_PREVIEW,
                    artifactSerializer = artifactSerializer,
                    currentTimeMillis = currentTimeMillis,
                )
            emitAutomationLog(
                "REFERENCE_AUTOMATION proof.complete role=passive " +
                    "inboundCount=${snapshot.timeline.count { entry -> entry.family == TimelineFamily.MESSAGE }} " +
                    "export=$exportPath"
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

    /**
     * Tears down this view model's underlying MeshLink session and cancels its coroutine scope.
     * Must be called when the view model is discarded (see the `DisposableEffect` in
     * [ch.trancee.meshlink.reference.app.ReferenceApp]) so a re-launch of the hosting Activity --
     * for example the direct-proof test harnesses re-launching with a newly-resolved target peer id
     * -- cannot leave a previous instance's MeshLink still scanning/advertising and racing a fresh
     * instance under the same app identity. Runs the shutdown on an independent scope because
     * [scope] itself is cancelled as part of the shutdown.
     */
    internal fun close(): Unit {
        val shutdownScope = CoroutineScope(Dispatchers.Default)
        shutdownScope.launch {
            runCatching { meshLinkController.close() }
            scope.cancel()
            shutdownScope.cancel()
        }
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
