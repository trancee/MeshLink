package ch.trancee.meshlink.reference.advanced

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class AdvancedControlsStateStore(
    private val platformName: String,
    initialSnapshot: ReferenceControllerSnapshot,
) {
    private val defaultComposerText: String = "hello mesh from $platformName advanced"
    private var latestSnapshot: ReferenceControllerSnapshot = initialSnapshot
    private val uiStateFlow: MutableStateFlow<AdvancedControlsUiState> =
        MutableStateFlow(
            buildAdvancedControlsUiState(
                snapshot = initialSnapshot,
                selectedPeerId = null,
                composerText = defaultComposerText,
                selectedPriority = DeliveryPriority.NORMAL,
            )
        )
    private val lifecycleStateFlow: MutableStateFlow<LifecycleActionState> =
        MutableStateFlow(LifecycleActionState.from(initialSnapshot.session.meshStateLabel))

    val uiState: StateFlow<AdvancedControlsUiState> = uiStateFlow.asStateFlow()
    val lifecycleActions: StateFlow<LifecycleActionState> = lifecycleStateFlow.asStateFlow()

    fun applySnapshot(snapshot: ReferenceControllerSnapshot): Unit {
        latestSnapshot = snapshot
        val current = uiStateFlow.value
        rebuildUiState(
            selectedPeerId = current.selectedPeerId ?: snapshot.peers.firstOrNull()?.peerId,
            composerText = current.composerText,
            selectedPriority = current.selectedPriority,
        )
        lifecycleStateFlow.value = LifecycleActionState.from(snapshot.session.meshStateLabel)
    }

    fun selectPeer(peerId: String): Unit {
        rebuildUiState(
            selectedPeerId = peerId,
            composerText = uiStateFlow.value.composerText,
            selectedPriority = uiStateFlow.value.selectedPriority,
        )
    }

    fun updateComposerText(text: String): Unit {
        rebuildUiState(
            selectedPeerId = uiStateFlow.value.selectedPeerId,
            composerText = text,
            selectedPriority = uiStateFlow.value.selectedPriority,
        )
    }

    fun updatePriority(priority: DeliveryPriority): Unit {
        rebuildUiState(
            selectedPeerId = uiStateFlow.value.selectedPeerId,
            composerText = uiStateFlow.value.composerText,
            selectedPriority = priority,
        )
    }

    private fun rebuildUiState(
        selectedPeerId: String?,
        composerText: String,
        selectedPriority: DeliveryPriority,
    ): Unit {
        uiStateFlow.value =
            buildAdvancedControlsUiState(
                snapshot = latestSnapshot,
                selectedPeerId = selectedPeerId,
                composerText = composerText,
                selectedPriority = selectedPriority,
            )
    }
}
