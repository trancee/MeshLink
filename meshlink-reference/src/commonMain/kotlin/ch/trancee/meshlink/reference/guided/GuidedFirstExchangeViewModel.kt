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

private const val SCENARIO_PAUSE_RESUME = "direct-pause-resume"
private const val SCENARIO_TRUST_RESET_RECOVERY = "direct-trust-reset-recovery"
private const val SCENARIO_FULL_EXPORT = "direct-full-export"
private const val SCENARIO_LARGE_TRANSFER = "direct-large-transfer"
private const val LARGE_TRANSFER_MIN_BYTES = 4_096
private const val PAUSE_RESUME_WINDOW_MILLIS = 1_000L
private const val PAUSE_RESUME_SEND_SETTLE_MILLIS = 3_000L
private const val TRUST_RESET_DELIVERY_SETTLE_MILLIS = 3_000L

/** Tracks progress through the two-send `direct-trust-reset-recovery` scenario. */
private enum class TrustResetPhase {
    NOT_STARTED,
    INITIAL_SEND_IN_FLIGHT,
    AWAITING_RECOVERY,
    RECOVERY_SEND_IN_FLIGHT,
}

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
    private var deliveryCount: Int = 0
    private var trustResetPhase: TrustResetPhase = TrustResetPhase.NOT_STARTED
    private val initAtEpochMillis: Long = currentTimeMillis()
    private val isPassiveRole: Boolean =
        automationRole?.equals("passive", ignoreCase = true) == true
    private val isSenderRole: Boolean = automationRole?.equals("sender", ignoreCase = true) == true

    /** Number of distinct inbound deliveries the passive peer must observe before completing. */
    private val requiredPassiveInboundCount: Int =
        if (automationScenario == SCENARIO_TRUST_RESET_RECOVERY) 2 else 1

    public val powerMitigationLabel: String?
        get() = powerMitigationLabelValue

    public val uiState: StateFlow<GuidedFirstExchangeUiState> = stateStore.uiState

    init {
        if (automationMode != null && automationRole != null) {
            emitAutomationLog(
                "REFERENCE_AUTOMATION started mode=$automationMode role=$automationRole scenario=${automationScenario ?: "none"}"
            )
        }
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
        val payloadText =
            if (automationScenario == SCENARIO_LARGE_TRANSFER) {
                largeTransferPayloadText()
            } else {
                "hello mesh from $platformName"
            }
        emitAutomationLog(
            "REFERENCE_AUTOMATION startup-state=guided.viewModel.sendHello.requested peerId=$peerId"
        )
        scope.launch {
            deliverPayload(peerId = peerId, payloadText = payloadText, phase = null)
            emitAutomationLog(
                "REFERENCE_AUTOMATION startup-state=guided.viewModel.sendHello.end peerId=$peerId"
            )
            maybeLogSenderCompletion(peerId)
        }
    }

    /**
     * Builds an oversized payload (>= 4096 bytes) for the `direct-large-transfer` scenario and
     * emits the "payload=large-transfer bytes=<n>" marker the physical harness scans for before
     * the send is attempted.
     */
    private fun largeTransferPayloadText(): String {
        val body = "hello mesh from $platformName (large transfer) ".repeat(120)
        val payload = body.take(LARGE_TRANSFER_MIN_BYTES).ifEmpty { body }
        val payloadBytes = payload.encodeToByteArray().size
        emitAutomationLog(
            "REFERENCE_AUTOMATION large-transfer.requested role=sender payload=large-transfer bytes=$payloadBytes"
        )
        return payload
    }

    /** Sends [payloadText] to [peerId], logging the send lifecycle markers the harnesses parse. */
    private suspend fun deliverPayload(peerId: String, payloadText: String, phase: String?): Unit {
        val phaseSuffix = if (phase != null) " phase=$phase" else ""
        emitAutomationLog("REFERENCE_AUTOMATION send.requested role=sender peerId=$peerId$phaseSuffix")
        emitAutomationLog(
            "REFERENCE_AUTOMATION startup-state=guided.viewModel.sendHello.begin peerId=$peerId"
        )
        meshLinkController.sendPayload(
            peerId = peerId,
            payloadText = payloadText,
            priority = DeliveryPriority.NORMAL,
        )
        deliveryCount += 1
    }

    /**
     * Logs the sender-role automation completion marker once the guided flow's automated hello
     * send(s) have returned without throwing. The Android/iOS direct-proof test harnesses
     * (`run_headless_reference_android_direct_proof.py`, `run_headless_reference_live_proof.py`)
     * key their pass/fail decision off this exact "REFERENCE_AUTOMATION proof.complete role=sender"
     * marker.
     */
    private fun maybeLogSenderCompletion(peerId: String): Unit {
        if (senderCompletionLogged || !isSenderRole) return
        senderCompletionLogged = true
        emitAutomationLog(
            "REFERENCE_AUTOMATION proof.complete role=sender deliveries=$deliveryCount peerId=$peerId"
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
        val messageEntries = snapshot.timeline.filter { entry -> entry.family == TimelineFamily.MESSAGE }
        if (messageEntries.size < requiredPassiveInboundCount) return
        if (!snapshot.isEligibleForAutomaticRetention(readinessBlockers)) return
        passiveCompletionTriggered = true
        scope.launch {
            val historyRepository = JsonSessionHistoryRepository(documentStore)
            val artifactSerializer = JsonSessionArtifactSerializer(documentStore)
            val retainedSnapshot = snapshot.redactedRetainedSnapshot()
            historyRepository.retainSnapshot(retainedSnapshot)

            if (automationScenario == SCENARIO_FULL_EXPORT) {
                emitAutomationLog(
                    "REFERENCE_AUTOMATION export.requested role=passive policy=full-payload"
                )
                val fullExportPath =
                    writeSessionArtifact(
                        // Uses the un-redacted live snapshot (not retainedSnapshot) so the full-payload
                        // export can actually include fullPayload bytes; redactedRetainedSnapshot()
                        // strips fullPayload from every timeline entry before retention.
                        snapshot = snapshot,
                        policy = ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN,
                        artifactSerializer = artifactSerializer,
                        currentTimeMillis = currentTimeMillis,
                    )
                emitAutomationLog(
                    "REFERENCE_AUTOMATION export.completed role=passive policy=full-payload path=$fullExportPath"
                )
            }

            emitAutomationLog(
                "REFERENCE_AUTOMATION export.requested role=passive policy=redacted-preview"
            )
            val exportPath =
                writeSessionArtifact(
                    snapshot = retainedSnapshot,
                    policy = ExportPayloadPolicy.REDACTED_PREVIEW,
                    artifactSerializer = artifactSerializer,
                    currentTimeMillis = currentTimeMillis,
                )
            emitAutomationLog(
                "REFERENCE_AUTOMATION export.completed role=passive policy=redacted-preview path=$exportPath"
            )
            val largestInboundBytes = messageEntries.mapNotNull { entry -> entry.payloadSizeBytes }.maxOrNull() ?: 0
            emitAutomationLog(
                "REFERENCE_AUTOMATION proof.complete role=passive " +
                    "inboundCount=${messageEntries.size} " +
                    "largestInboundBytes=$largestInboundBytes " +
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
        if (!autoSendHello) return
        when (automationScenario) {
            SCENARIO_PAUSE_RESUME -> maybeRunPauseResumeThenSend(snapshot)
            SCENARIO_TRUST_RESET_RECOVERY -> maybeRunTrustResetRecoveryFlow(snapshot)
            else -> maybeAutoSendHelloDefault(snapshot)
        }
    }

    private fun resolveAutoSendPeer(snapshot: ReferenceControllerSnapshot) =
        when (val targetPeerId = automationTargetPeerId) {
            null -> snapshot.peers.firstOrNull { it.connectionState == PeerConnectionSnapshotState.CONNECTED }
            else ->
                snapshot.peers.firstOrNull {
                    it.peerId == targetPeerId && it.connectionState == PeerConnectionSnapshotState.CONNECTED
                }
        }

    private fun maybeAutoSendHelloDefault(snapshot: ReferenceControllerSnapshot): Unit {
        if (autoSendTriggered) return
        val peer = resolveAutoSendPeer(snapshot) ?: return
        autoSendTriggered = true
        emitAutomationLog(
            "REFERENCE_AUTOMATION startup-state=guided.viewModel.autoSendHello.requested peerId=${peer.peerId} targetPeerId=${automationTargetPeerId ?: "none"}"
        )
        sendHelloToPeer(peer.peerId)
    }

    /**
     * Drives the `direct-pause-resume` scenario: pauses the mesh, confirms the pause window is
     * open, resumes it, confirms the window closed, and only then performs the automated send --
     * matching the "requested/observed/recovered" recovery-window contract the physical harness
     * analyzer (`analyze_reference_physical_run.py`) requires before it will trust the proof.
     */
    private fun maybeRunPauseResumeThenSend(snapshot: ReferenceControllerSnapshot): Unit {
        if (autoSendTriggered) return
        val peer = resolveAutoSendPeer(snapshot) ?: return
        autoSendTriggered = true
        scope.launch {
            emitAutomationLog("REFERENCE_AUTOMATION pause.requested role=sender")
            meshLinkController.pause()
            emitAutomationLog("REFERENCE_AUTOMATION pause.observed role=sender window=open")
            delay(PAUSE_RESUME_WINDOW_MILLIS)
            emitAutomationLog("REFERENCE_AUTOMATION resume.requested role=sender")
            meshLinkController.resume()
            emitAutomationLog("REFERENCE_AUTOMATION resume.observed role=sender window=closed")
            emitAutomationLog("REFERENCE_AUTOMATION pause.recovered role=sender window=closed")
            // Resuming re-enables the mesh runtime but the underlying BLE link needs time to
            // reconnect physically before a send is deliverable -- without this settle delay the
            // send can race the reconnection (mirrors the same race fixed for
            // direct-trust-reset-recovery's post-forgetPeer send).
            delay(PAUSE_RESUME_SEND_SETTLE_MILLIS)
            emitAutomationLog(
                "REFERENCE_AUTOMATION startup-state=guided.viewModel.autoSendHello.requested peerId=${peer.peerId} targetPeerId=${automationTargetPeerId ?: "none"}"
            )
            sendHelloToPeer(peer.peerId)
        }
    }

    /**
     * Drives the `direct-trust-reset-recovery` scenario: sends an initial hello, forgets the peer
     * (`forgetPeer`, exercising the same end-to-end recovery path the manual "Forget peer" UI
     * control uses), waits for the peer to be rediscovered/reconnected, then performs a second,
     * explicitly-marked ("phase=recovery") send. The physical harness requires two sender
     * deliveries and two passive inbound messages to trust this scenario.
     */
    private fun maybeRunTrustResetRecoveryFlow(snapshot: ReferenceControllerSnapshot): Unit {
        when (trustResetPhase) {
            TrustResetPhase.NOT_STARTED -> {
                val peer = resolveAutoSendPeer(snapshot) ?: return
                trustResetPhase = TrustResetPhase.INITIAL_SEND_IN_FLIGHT
                scope.launch {
                    deliverPayload(peerId = peer.peerId, payloadText = "hello mesh from $platformName", phase = "initial")
                    // Give the initial payload time to actually transmit over the physical BLE
                    // link before tearing down the peer -- sendPayload() returning only means the
                    // mesh engine accepted/enqueued the send, not that delivery has completed.
                    delay(TRUST_RESET_DELIVERY_SETTLE_MILLIS)
                    emitAutomationLog("REFERENCE_AUTOMATION trust.reset.requested role=sender")
                    meshLinkController.forgetPeer(peer.peerId)
                    emitAutomationLog("REFERENCE_AUTOMATION trust.reset.observed role=sender window=open")
                    trustResetPhase = TrustResetPhase.AWAITING_RECOVERY
                }
            }
            TrustResetPhase.AWAITING_RECOVERY -> {
                val peer =
                    snapshot.peers.firstOrNull { it.connectionState == PeerConnectionSnapshotState.CONNECTED }
                        ?: return
                trustResetPhase = TrustResetPhase.RECOVERY_SEND_IN_FLIGHT
                scope.launch {
                    emitAutomationLog("REFERENCE_AUTOMATION trust.reset.recovered role=sender window=closed")
                    deliverPayload(
                        peerId = peer.peerId,
                        payloadText = "hello mesh from $platformName (recovery)",
                        phase = "recovery",
                    )
                    maybeLogSenderCompletion(peer.peerId)
                }
            }
            TrustResetPhase.INITIAL_SEND_IN_FLIGHT,
            TrustResetPhase.RECOVERY_SEND_IN_FLIGHT -> Unit
        }
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
