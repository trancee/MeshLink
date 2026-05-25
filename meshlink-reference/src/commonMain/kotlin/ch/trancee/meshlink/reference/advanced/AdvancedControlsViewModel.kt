package ch.trancee.meshlink.reference.advanced

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.platform.PlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Shared state holder for the advanced controls surface. */
public class AdvancedControlsViewModel(
    private val platformServices: PlatformServices,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val stateStore: AdvancedControlsStateStore =
        AdvancedControlsStateStore(
            platformName = platformServices.platformName,
            initialSnapshot = platformServices.meshLinkController.snapshot.value,
        )

    public val uiState: StateFlow<AdvancedControlsUiState> = stateStore.uiState
    public val lifecycleActions: StateFlow<LifecycleActionState> = stateStore.lifecycleActions

    init {
        scope.launch {
            platformServices.meshLinkController.snapshot.collectLatest(stateStore::applySnapshot)
        }
    }

    public fun selectPeer(peerId: String): Unit {
        stateStore.selectPeer(peerId)
    }

    public fun updateComposerText(text: String): Unit {
        stateStore.updateComposerText(text)
    }

    public fun updatePriority(priority: DeliveryPriority): Unit {
        stateStore.updatePriority(priority)
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
        val state = uiState.value
        val peerId = state.selectedPeerId ?: return
        if (!state.canSendMessage) {
            return
        }
        scope.launch {
            platformServices.meshLinkController.sendPayload(
                peerId = peerId,
                payloadText = state.composerText,
                priority = state.selectedPriority,
            )
        }
    }

    public fun sendLargeTransferPreview(): Unit {
        val state = uiState.value
        val peerId = state.selectedPeerId ?: return
        if (!state.canSendLargeTransfer) {
            return
        }
        scope.launch {
            platformServices.meshLinkController.sendPayload(
                peerId = peerId,
                payloadText = buildAdvancedLargeTransferPreviewPayload(),
                priority = DeliveryPriority.HIGH,
            )
        }
    }

    public fun forgetSelectedPeer(): Unit {
        val peerId = uiState.value.selectedPeerId ?: return
        scope.launch { platformServices.meshLinkController.forgetPeer(peerId) }
    }
}
