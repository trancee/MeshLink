package ch.trancee.meshlink.reference.guided

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.platform.PlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Shared state holder for the guided first-exchange surface. */
public class GuidedFirstExchangeViewModel(
    private val platformServices: PlatformServices,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val stateStore: GuidedFirstExchangeStateStore =
        GuidedFirstExchangeStateStore(
            platformName = platformServices.platformName,
            readinessGuidance = platformServices.readinessGuidance,
            readinessBlockers = platformServices.readinessBlockers,
            initialSnapshot = platformServices.meshLinkController.snapshot.value,
        )

    public val uiState: StateFlow<GuidedFirstExchangeUiState> = stateStore.uiState

    init {
        scope.launch {
            platformServices.meshLinkController.snapshot.collectLatest(stateStore::applySnapshot)
        }
    }

    public fun startMesh(): Unit {
        scope.launch { platformServices.meshLinkController.start() }
    }

    public fun sendHelloToFirstPeer(): Unit {
        val firstPeer = uiState.value.snapshot.peers.firstOrNull() ?: return
        sendHelloToPeer(firstPeer.peerId)
    }

    public fun sendHelloToPeer(peerId: String): Unit {
        scope.launch {
            platformServices.meshLinkController.sendSamplePayload(
                peerId = peerId,
                payloadText = "hello mesh from ${platformServices.platformName}",
                priority = DeliveryPriority.NORMAL,
            )
        }
    }
}

public data class GuidedFirstExchangeUiState(
    public val readiness: ReadinessEvaluation,
    public val snapshot: ReferenceControllerSnapshot,
) {
    public val nextActionLabel: String
        get() {
            return when {
                isSessionEnded ->
                    "Open the technical timeline to review or start the next supported session"
                readiness.isBlocked -> "Resolve startup blockers"
                snapshot.session.meshStateLabel.contains("Uninitialized") -> "Start MeshLink"
                snapshot.peers.isEmpty() -> "Wait for a peer or start a solo session"
                else -> "Send the first guided message"
            }
        }

    public val isSessionEnded: Boolean
        get() = snapshot.session.endedAtEpochMillis != null

    public val selectedPeerSuffix: String?
        get() = snapshot.peers.firstOrNull()?.peerSuffix

    public val canSendHello: Boolean
        get() = snapshot.peers.isNotEmpty() && !readiness.isBlocked && !isSessionEnded
}
