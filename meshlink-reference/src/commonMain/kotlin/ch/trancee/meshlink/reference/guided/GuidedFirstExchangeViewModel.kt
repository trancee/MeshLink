package ch.trancee.meshlink.reference.guided

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    public val powerMitigationLabel: String?
        get() = powerMitigationLabelValue

    public val uiState: StateFlow<GuidedFirstExchangeUiState> = stateStore.uiState

    init {
        scope.launch {
            meshLinkController.snapshot.collectLatest { snapshot ->
                stateStore.applySnapshot(snapshot)
            }
        }
    }

    public fun startMesh(): Unit {
        scope.launch { meshLinkController.start() }
    }

    public fun sendHelloToFirstPeer(): Unit {
        val firstPeer = uiState.value.snapshot.peers.firstOrNull() ?: return
        sendHelloToPeer(firstPeer.peerId)
    }

    public fun sendHelloToPeer(peerId: String): Unit {
        scope.launch {
            meshLinkController.sendPayload(
                peerId = peerId,
                payloadText = "hello mesh from $platformName",
                priority = DeliveryPriority.NORMAL,
            )
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
