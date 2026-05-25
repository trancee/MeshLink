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

/** Shared state holder for the advanced controls surface. */
public class AdvancedControlsViewModel(
    private val platformServices: PlatformServices,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val defaultComposerText: String =
        "hello mesh from ${platformServices.platformName} advanced"
    private val uiStateFlow: MutableStateFlow<AdvancedControlsUiState> =
        MutableStateFlow(
            buildAdvancedControlsUiState(
                platformServices = platformServices,
                selectedPeerId = null,
                composerText = defaultComposerText,
                selectedPriority = DeliveryPriority.NORMAL,
            )
        )
    private val lifecycleStateFlow: MutableStateFlow<LifecycleActionState> =
        MutableStateFlow(LifecycleActionState.from(uiStateFlow.value.meshStateLabel))

    public val uiState: StateFlow<AdvancedControlsUiState> = uiStateFlow.asStateFlow()
    public val lifecycleActions: StateFlow<LifecycleActionState> = lifecycleStateFlow.asStateFlow()

    init {
        scope.launch {
            platformServices.meshLinkController.snapshot.collectLatest { snapshot ->
                val current = uiStateFlow.value
                rebuildUiState(
                    selectedPeerId = current.selectedPeerId ?: snapshot.peers.firstOrNull()?.peerId,
                    composerText = current.composerText,
                    selectedPriority = current.selectedPriority,
                )
                lifecycleStateFlow.value =
                    LifecycleActionState.from(snapshot.session.meshStateLabel)
            }
        }
    }

    public fun selectPeer(peerId: String): Unit {
        rebuildUiState(
            selectedPeerId = peerId,
            composerText = uiStateFlow.value.composerText,
            selectedPriority = uiStateFlow.value.selectedPriority,
        )
    }

    public fun updateComposerText(text: String): Unit {
        rebuildUiState(
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
        if (!state.canSendMessage) {
            return
        }
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
        if (!state.canSendLargeTransfer) {
            return
        }
        scope.launch {
            platformServices.meshLinkController.sendSamplePayload(
                peerId = peerId,
                payloadText = buildAdvancedLargeTransferPreviewPayload(),
                priority = DeliveryPriority.HIGH,
            )
        }
    }

    public fun forgetSelectedPeer(): Unit {
        val peerId = uiStateFlow.value.selectedPeerId ?: return
        scope.launch { platformServices.meshLinkController.forgetPeer(peerId) }
    }

    private fun rebuildUiState(
        selectedPeerId: String?,
        composerText: String,
        selectedPriority: DeliveryPriority,
    ): Unit {
        uiStateFlow.value =
            buildAdvancedControlsUiState(
                platformServices = platformServices,
                selectedPeerId = selectedPeerId,
                composerText = composerText,
                selectedPriority = selectedPriority,
            )
    }
}
