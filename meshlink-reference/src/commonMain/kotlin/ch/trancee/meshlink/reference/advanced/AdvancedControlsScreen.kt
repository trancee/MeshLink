package ch.trancee.meshlink.reference.advanced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.design.ReferenceBadge
import ch.trancee.meshlink.reference.design.ReferenceSectionCard

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
            Text(
                text =
                    "Inspect the live runtime, choose a peer deliberately, and send operator-facing previews without leaving the reference app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ReferenceSectionCard(
                title = "Live snapshot",
                subtitle =
                    "Use this summary to see the current runtime state before you move into lifecycle, peer, or send actions.",
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReferenceBadge(label = uiState.config.authorityModeLabel)
                    ReferenceBadge(label = uiState.activePowerModeLabel, prominent = true)
                    ReferenceBadge(
                        label =
                            "Peer ${uiState.peerRows.firstOrNull { peer -> peer.peerId == uiState.selectedPeerId }?.peerSuffix ?: "none"}"
                    )
                }
                Text(
                    text = "Mesh state: ${uiState.meshStateLabel}",
                    modifier = Modifier.testTag("advanced-mesh-state"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Power mode: ${uiState.config.powerModeLabel}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Last outcome: ${uiState.lastOutcomeSummary ?: "none yet"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            ReferenceSectionCard(
                title = "Lifecycle",
                subtitle =
                    "Use these controls when you want to inspect how the live runtime moves between start, pause, resume, and stop states.",
            ) {
                Text(
                    text = "Mesh state: ${uiState.meshStateLabel}",
                    style = MaterialTheme.typography.bodyLarge,
                )
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
            ReferenceSectionCard(
                title = "Configuration",
                subtitle =
                    "These are the product-facing defaults and live configuration values currently active in the reference app.",
            ) {
                Text(text = "App ID: ${uiState.config.appId}")
                Text(text = "Regulatory region: ${uiState.config.regulatoryRegion}")
                Text(text = "Retry deadline: ${uiState.config.deliveryRetryDeadlineLabel}")
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
            ReferenceSectionCard(
                title = "Send",
                subtitle =
                    "Use this area for deliberate outbound operator traffic once a peer is selected.",
            ) {
                SendComposer(
                    state = uiState,
                    onTextChanged = viewModel::updateComposerText,
                    onPriorityChanged = viewModel::updatePriority,
                    onSend = viewModel::sendCurrentMessage,
                    onSendLargeTransfer = viewModel::sendLargeTransferPreview,
                )
                Button(
                    onClick = viewModel::forgetSelectedPeer,
                    enabled = uiState.canForgetPeer,
                    modifier = Modifier.testTag("advanced-forget-peer"),
                ) {
                    Text("Forget selected peer")
                }
            }
        }
        item {
            ReferenceSectionCard(
                title = "Recent highlights",
                subtitle =
                    "Keep one eye on the last timeline changes without leaving the controls surface.",
            ) {
                if (uiState.timelineHighlights.isEmpty()) {
                    Text(
                        text = "No highlights yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    uiState.timelineHighlights.forEach { highlight ->
                        Text(text = highlight, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
