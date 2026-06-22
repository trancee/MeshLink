package ch.trancee.meshlink.reference.advanced

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Shared state holder for the advanced controls surface. */
internal class AdvancedControlsViewModel(
    private val platformName: String,
    private val meshLinkController: ReferenceMeshLinkController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val stateStore: AdvancedControlsStateStore =
        AdvancedControlsStateStore(
            platformName = platformName,
            initialSnapshot = meshLinkController.snapshot.value,
        )

    public val uiState: StateFlow<AdvancedControlsUiState> = stateStore.uiState
    public val lifecycleActions: StateFlow<LifecycleActionState> = stateStore.lifecycleActions

    init {
        scope.launch { meshLinkController.snapshot.collectLatest(stateStore::applySnapshot) }
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
        scope.launch { meshLinkController.start() }
    }

    public fun pauseMesh(): Unit {
        scope.launch { meshLinkController.pause() }
    }

    public fun resumeMesh(): Unit {
        scope.launch { meshLinkController.resume() }
    }

    public fun stopMesh(): Unit {
        scope.launch { meshLinkController.stop() }
    }

    public fun sendCurrentMessage(): Unit {
        val state = uiState.value
        val peerId = state.selectedPeerId ?: return
        if (!state.canSendMessage) {
            return
        }
        scope.launch {
            meshLinkController.sendPayload(
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
            meshLinkController.sendPayload(
                peerId = peerId,
                payloadText = buildAdvancedLargeTransferPreviewPayload(),
                priority = DeliveryPriority.HIGH,
            )
        }
    }

    public fun forgetSelectedPeer(): Unit {
        val peerId = uiState.value.selectedPeerId ?: return
        scope.launch { meshLinkController.forgetPeer(peerId) }
    }
}
