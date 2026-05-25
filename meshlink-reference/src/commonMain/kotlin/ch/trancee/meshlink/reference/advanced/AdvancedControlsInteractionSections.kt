package ch.trancee.meshlink.reference.advanced

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import ch.trancee.meshlink.reference.design.ReferenceSectionCard

@Composable
internal fun AdvancedPeerSectionHeader(): Unit {
    Text(
        text = "Peers",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun AdvancedSendSection(
    uiState: AdvancedControlsUiState,
    viewModel: AdvancedControlsViewModel,
): Unit {
    ReferenceSectionCard(
        title = "Send",
        subtitle = "Use this area for deliberate outbound operator traffic once a peer is selected.",
    ) {
        SendComposer(
            state = uiState,
            actions =
                SendComposerActions(
                    onTextChanged = viewModel::updateComposerText,
                    onPriorityChanged = viewModel::updatePriority,
                    onSend = viewModel::sendCurrentMessage,
                    onSendLargeTransfer = viewModel::sendLargeTransferPreview,
                ),
        )
        Button(
            onClick = viewModel::forgetSelectedPeer,
            enabled = uiState.canForgetPeer,
            modifier =
                Modifier.referenceActionAccessibility(
                    "Reset trust for selected peer",
                    "advanced-forget-peer",
                ),
        ) {
            Text("Reset trust for selected peer")
        }
    }
}

@Composable
internal fun AdvancedRecentHighlightsSection(highlights: List<String>): Unit {
    ReferenceSectionCard(
        title = "Recent highlights",
        subtitle = "Keep one eye on the last timeline changes without leaving the controls surface.",
    ) {
        if (highlights.isEmpty()) {
            Text(
                text = "No highlights yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            highlights.forEach { highlight ->
                Text(text = highlight, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

internal fun AdvancedControlsUiState.selectedPeerSuffix(): String {
    return peerRows.firstOrNull { peer -> peer.peerId == selectedPeerId }?.peerSuffix ?: "none"
}
