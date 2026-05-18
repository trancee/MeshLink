package ch.trancee.meshlink.reference.advanced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun AdvancedControlsScreen(
    viewModel: AdvancedControlsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleActions by viewModel.lifecycleActions().collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(20.dp).testTag("advanced-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(text = "Advanced controls", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = "Configuration", style = MaterialTheme.typography.titleLarge)
                    Text(text = "App ID: ${uiState.config.appId}")
                    Text(text = "Regulatory region: ${uiState.config.regulatoryRegion}")
                    Text(text = "Power mode: ${uiState.config.powerModeLabel}")
                    Text(text = "Retry deadline: ${uiState.config.deliveryRetryDeadlineLabel}")
                    Text(
                        text = "Mesh state: ${uiState.meshStateLabel}",
                        modifier = Modifier.testTag("advanced-mesh-state"),
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "Lifecycle", style = MaterialTheme.typography.titleLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = viewModel::startMesh,
                        enabled = lifecycleActions.startEnabled,
                    ) {
                        Text("Start")
                    }
                    Button(
                        onClick = viewModel::pauseMesh,
                        enabled = lifecycleActions.pauseEnabled,
                    ) {
                        Text("Pause")
                    }
                    Button(
                        onClick = viewModel::resumeMesh,
                        enabled = lifecycleActions.resumeEnabled,
                    ) {
                        Text("Resume")
                    }
                    Button(onClick = viewModel::stopMesh, enabled = lifecycleActions.stopEnabled) {
                        Text("Stop")
                    }
                }
            }
        }
        item {
            Text(
                text = "Peers",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(uiState.peerRows) { peer ->
            PeerDetailCard(
                peer = peer,
                selected = uiState.selectedPeerId == peer.peerId,
                onSelect = { viewModel.selectPeer(peer.peerId) },
            )
        }
        item {
            SendComposer(
                state = uiState,
                onTextChanged = viewModel::updateComposerText,
                onPriorityChanged = viewModel::updatePriority,
                onSend = viewModel::sendCurrentMessage,
                onSendLargeTransfer = viewModel::sendLargeTransferPreview,
            )
        }
        item {
            Button(
                onClick = viewModel::forgetSelectedPeer,
                enabled = uiState.canForgetPeer,
                modifier = Modifier.testTag("advanced-forget-peer"),
            ) {
                Text("Forget selected peer")
            }
        }
        item { Text(text = "Recent highlights", style = MaterialTheme.typography.titleLarge) }
        items(uiState.timelineHighlights) { highlight ->
            Text(text = highlight, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
