package ch.trancee.meshlink.reference.guided

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.platform.PlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Shared state holder for the guided first-exchange and solo-exploration surfaces. */
public class GuidedFirstExchangeViewModel(
    private val platformServices: PlatformServices,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val readinessChecker: ReadinessChecker = ReadinessChecker()
    private val uiStateFlow: MutableStateFlow<GuidedFirstExchangeUiState> =
        MutableStateFlow(
            GuidedFirstExchangeUiState(
                readiness =
                    readinessChecker.evaluate(
                        platformName = platformServices.platformName,
                        guidance = platformServices.readinessGuidance,
                        blockers = platformServices.readinessBlockers,
                    ),
                snapshot = platformServices.meshLinkController.snapshot.value,
            )
        )

    public val uiState: StateFlow<GuidedFirstExchangeUiState> = uiStateFlow.asStateFlow()

    init {
        scope.launch {
            platformServices.meshLinkController.snapshot.collectLatest { snapshot ->
                uiStateFlow.value =
                    uiStateFlow.value.copy(
                        readiness =
                            readinessChecker.evaluate(
                                platformName = platformServices.platformName,
                                guidance = platformServices.readinessGuidance,
                                blockers = platformServices.readinessBlockers,
                            ),
                        snapshot = snapshot,
                    )
            }
        }
    }

    public fun startMesh(): Unit {
        scope.launch { platformServices.meshLinkController.start() }
    }

    public fun sendHelloToFirstPeer(): Unit {
        val firstPeer = uiStateFlow.value.snapshot.peers.firstOrNull() ?: return
        scope.launch {
            platformServices.meshLinkController.sendSamplePayload(
                peerId = firstPeer.peerId,
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
                readiness.isBlocked -> "Resolve startup blockers"
                snapshot.session.meshStateLabel.contains("Uninitialized") -> "Start MeshLink"
                snapshot.peers.isEmpty() -> "Wait for a peer or open solo exploration"
                else -> "Send the first guided message"
            }
        }

    public val selectedPeerSuffix: String?
        get() = snapshot.peers.firstOrNull()?.peerSuffix

    public val canSendHello: Boolean
        get() = snapshot.peers.isNotEmpty() && !readiness.isBlocked
}
