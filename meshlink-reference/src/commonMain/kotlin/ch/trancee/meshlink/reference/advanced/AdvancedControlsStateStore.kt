package ch.trancee.meshlink.reference.advanced

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class AdvancedControlsStateStore(
    private val platformName: String,
    initialSnapshot: ReferenceControllerSnapshot,
) {
    private val defaultComposerText: String = "hello mesh from $platformName advanced"
    private val snapshotFlow: MutableStateFlow<ReferenceControllerSnapshot> =
        MutableStateFlow(initialSnapshot)
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
        snapshotFlow.value = snapshot
        uiStateFlow.update { current ->
            buildAdvancedControlsUiState(
                snapshot = snapshot,
                selectedPeerId = current.selectedPeerId ?: snapshot.peers.firstOrNull()?.peerId,
                composerText = current.composerText,
                selectedPriority = current.selectedPriority,
            )
        }
        lifecycleStateFlow.value = LifecycleActionState.from(snapshot.session.meshStateLabel)
    }

    fun selectPeer(peerId: String): Unit {
        uiStateFlow.update { current ->
            buildAdvancedControlsUiState(
                snapshot = snapshotFlow.value,
                selectedPeerId = peerId,
                composerText = current.composerText,
                selectedPriority = current.selectedPriority,
            )
        }
    }

    fun updateComposerText(text: String): Unit {
        uiStateFlow.update { current ->
            buildAdvancedControlsUiState(
                snapshot = snapshotFlow.value,
                selectedPeerId = current.selectedPeerId,
                composerText = text,
                selectedPriority = current.selectedPriority,
            )
        }
    }

    fun updatePriority(priority: DeliveryPriority): Unit {
        uiStateFlow.update { current ->
            buildAdvancedControlsUiState(
                snapshot = snapshotFlow.value,
                selectedPeerId = current.selectedPeerId,
                composerText = current.composerText,
                selectedPriority = priority,
            )
        }
    }
}
