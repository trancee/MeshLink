package ch.trancee.meshlink.reference.advanced

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.platform.PlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shared state holder for the advanced controls surface.
 */
public class AdvancedControlsViewModel(
    private val platformServices: PlatformServices,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val uiStateFlow: MutableStateFlow<AdvancedControlsUiState> =
        MutableStateFlow(
            buildUiState(
                selectedPeerId = null,
                composerText = defaultMessage(),
                selectedPriority = DeliveryPriority.NORMAL,
            )
        )

    public val uiState: StateFlow<AdvancedControlsUiState> = uiStateFlow.asStateFlow()

    public val lifecycleActions: StateFlow<LifecycleActionState> =
        MutableStateFlow(LifecycleActionState.from(uiStateFlow.value.meshStateLabel)).asStateFlow()

    private val lifecycleStateFlow: MutableStateFlow<LifecycleActionState> =
        MutableStateFlow(LifecycleActionState.from(uiStateFlow.value.meshStateLabel))

    init {
        scope.launch {
            platformServices.meshLinkController.snapshot.collectLatest { snapshot ->
                val current = uiStateFlow.value
                val selectedPeerId = current.selectedPeerId ?: snapshot.peers.firstOrNull()?.peerId
                uiStateFlow.value =
                    buildUiState(
                        selectedPeerId = selectedPeerId,
                        composerText = current.composerText,
                        selectedPriority = current.selectedPriority,
                    )
                lifecycleStateFlow.value = LifecycleActionState.from(snapshot.session.meshStateLabel)
            }
        }
    }

    public fun lifecycleActions(): StateFlow<LifecycleActionState> {
        return lifecycleStateFlow.asStateFlow()
    }

    public fun selectPeer(peerId: String): Unit {
        uiStateFlow.value =
            buildUiState(
                selectedPeerId = peerId,
                composerText = uiStateFlow.value.composerText,
                selectedPriority = uiStateFlow.value.selectedPriority,
            )
    }

    public fun updateComposerText(text: String): Unit {
        uiStateFlow.value =
            buildUiState(
                selectedPeerId = uiStateFlow.value.selectedPeerId,
                composerText = text,
                selectedPriority = uiStateFlow.value.selectedPriority,
            )
    }

    public fun updatePriority(priority: DeliveryPriority): Unit {
        uiStateFlow.value = uiStateFlow.value.copy(selectedPriority = priority)
    }

    public fun startMesh(): Unit {
        scope.launch { platformServices.meshLinkController.start() }
    }

    public fun pauseMesh(): Unit {
        scope.launch { platformServices.meshLinkController.pause() }
    }

    public fun resumeMesh(): Unit {
        scope.launch { platformServices.meshLinkController.resume() }
    }

    public fun stopMesh(): Unit {
        scope.launch { platformServices.meshLinkController.stop() }
    }

    public fun sendCurrentMessage(): Unit {
        val state = uiStateFlow.value
        val peerId = state.selectedPeerId ?: return
        scope.launch {
            platformServices.meshLinkController.sendSamplePayload(
                peerId = peerId,
                payloadText = state.composerText,
                priority = state.selectedPriority,
            )
        }
    }

    public fun sendLargeTransferPreview(): Unit {
        val state = uiStateFlow.value
        val peerId = state.selectedPeerId ?: return
        val largePayload = buildString {
            repeat(256) {
                append("MeshLink reference large transfer preview · ")
            }
        }
        scope.launch {
            platformServices.meshLinkController.sendSamplePayload(
                peerId = peerId,
                payloadText = largePayload,
                priority = DeliveryPriority.HIGH,
            )
        }
    }

    public fun forgetSelectedPeer(): Unit {
        val peerId = uiStateFlow.value.selectedPeerId ?: return
        scope.launch {
            platformServices.meshLinkController.forgetPeer(peerId)
        }
    }

    private fun buildUiState(
        selectedPeerId: String?,
        composerText: String,
        selectedPriority: DeliveryPriority,
    ): AdvancedControlsUiState {
        val snapshot = platformServices.meshLinkController.snapshot.value
        val effectivePeerId = selectedPeerId ?: snapshot.peers.firstOrNull()?.peerId
        val configSnapshot = snapshot.session.configurationSnapshot
        return AdvancedControlsUiState(
            config =
                AdvancedConfigState(
                    appId = configSnapshot["appId"] ?: "demo.meshlink.reference",
                    regulatoryRegion = configSnapshot["regulatoryRegion"] ?: "DEFAULT",
                    powerModeLabel = configSnapshot["powerMode"] ?: snapshot.activePowerModeLabel,
                    deliveryRetryDeadlineLabel = configSnapshot["deliveryRetryDeadline"] ?: "15s",
                    authorityModeLabel = snapshot.session.authorityMode.toString(),
                ),
            meshStateLabel = snapshot.session.meshStateLabel,
            activePowerModeLabel = snapshot.activePowerModeLabel,
            selectedPeerId = effectivePeerId,
            composerText = composerText,
            selectedPriority = selectedPriority,
            peerRows =
                snapshot.peers.map { peer ->
                    AdvancedPeerRow(
                        peerId = peer.peerId,
                        peerSuffix = peer.peerSuffix,
                        trustLabel = peer.trustState.name,
                        connectionLabel = peer.connectionState.name,
                        lastDeliveryOutcome = peer.lastDeliveryOutcome,
                    )
                },
            timelineHighlights = snapshot.timeline.takeLast(3).map { entry -> "${entry.title}: ${entry.detail}" },
            lastOutcomeSummary = snapshot.session.lastOutcomeSummary,
        )
    }

    private fun defaultMessage(): String {
        return "hello mesh from ${platformServices.platformName} advanced"
    }
}
