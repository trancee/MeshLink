package ch.trancee.meshlink.reference.advanced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdvancedControlsScreen(
    viewModel: AdvancedControlsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleActions by viewModel.lifecycleActions.collectAsState()

    AdvancedControlsContent(
        uiState = uiState,
        lifecycleActions = lifecycleActions,
        viewModel = viewModel,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedControlsContent(
    uiState: AdvancedControlsUiState,
    lifecycleActions: LifecycleActionState,
    viewModel: AdvancedControlsViewModel,
    modifier: Modifier,
): Unit {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(20.dp).testTag("advanced-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { AdvancedControlsHeader() }
        item { AdvancedLiveSnapshotSection(uiState = uiState) }
        item {
            AdvancedLifecycleSection(
                meshStateLabel = uiState.meshStateLabel,
                lifecycleActions = lifecycleActions,
                isSessionEnded = uiState.isSessionEnded,
                viewModel = viewModel,
            )
        }
        item { AdvancedConfigurationSection(uiState = uiState) }
        item { AdvancedPeerSectionHeader() }
        items(uiState.peerRows) { peer ->
            PeerDetailCard(
                peer = peer,
                selected = uiState.selectedPeerId == peer.peerId,
                enabled = !uiState.isSessionEnded,
                onSelect = { viewModel.selectPeer(peer.peerId) },
            )
        }
        item { AdvancedSendSection(uiState = uiState, viewModel = viewModel) }
        item { AdvancedRecentHighlightsSection(highlights = uiState.timelineHighlights) }
    }
}
