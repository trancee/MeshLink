@file:Suppress("FunctionNaming")

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
) {
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

@Composable
private fun AdvancedControlsHeader(): Unit {
    Text(text = "Advanced controls", style = MaterialTheme.typography.headlineSmall)
    Text(
        text = ADVANCED_CONTROLS_INTRO_TEXT,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedLiveSnapshotSection(uiState: AdvancedControlsUiState): Unit {
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
            ReferenceBadge(label = "Peer ${uiState.selectedPeerSuffix()}")
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
            text = "Last outcome: ${uiState.lastOutcomeDisplayText ?: "none yet"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (uiState.isSessionEnded) {
            Text(
                text =
                    "This session is closed. Open the technical timeline to export a redacted artifact or start a new session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedLifecycleSection(
    meshStateLabel: String,
    lifecycleActions: LifecycleActionState,
    isSessionEnded: Boolean,
    viewModel: AdvancedControlsViewModel,
): Unit {
    ReferenceSectionCard(title = "Lifecycle", subtitle = ADVANCED_LIFECYCLE_SUBTITLE) {
        Text(text = "Mesh state: $meshStateLabel", style = MaterialTheme.typography.bodyLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = viewModel::startMesh,
                enabled = lifecycleActions.startEnabled && !isSessionEnded,
            ) {
                Text("Start")
            }
            Button(
                onClick = viewModel::pauseMesh,
                enabled = lifecycleActions.pauseEnabled && !isSessionEnded,
            ) {
                Text("Pause")
            }
            Button(
                onClick = viewModel::resumeMesh,
                enabled = lifecycleActions.resumeEnabled && !isSessionEnded,
            ) {
                Text("Resume")
            }
            Button(
                onClick = viewModel::stopMesh,
                enabled = lifecycleActions.stopEnabled && !isSessionEnded,
            ) {
                Text("Stop")
            }
        }
    }
}

@Composable
private fun AdvancedConfigurationSection(uiState: AdvancedControlsUiState): Unit {
    ReferenceSectionCard(title = "Configuration", subtitle = ADVANCED_CONFIGURATION_SUBTITLE) {
        Text(text = "App ID: ${uiState.config.appId}")
        Text(text = "Regulatory region: ${uiState.config.regulatoryRegion}")
        Text(text = "Retry deadline: ${uiState.config.deliveryRetryDeadlineLabel}")
    }
}

@Composable
private fun AdvancedPeerSectionHeader(): Unit {
    Text(
        text = "Peers",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun AdvancedSendSection(
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
            modifier = Modifier.testTag("advanced-forget-peer"),
        ) {
            Text("Reset trust for selected peer")
        }
    }
}

@Composable
private fun AdvancedRecentHighlightsSection(highlights: List<String>): Unit {
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

private fun AdvancedControlsUiState.selectedPeerSuffix(): String {
    return peerRows.firstOrNull { peer -> peer.peerId == selectedPeerId }?.peerSuffix ?: "none"
}

private const val ADVANCED_CONTROLS_INTRO_TEXT: String =
    "Inspect the live runtime, choose a peer deliberately, and send operator-facing previews " +
        "without leaving the reference app."

private const val ADVANCED_LIFECYCLE_SUBTITLE: String =
    "Use these controls when you want to inspect how the live runtime moves between start, " +
        "pause, resume, and stop states."

private const val ADVANCED_CONFIGURATION_SUBTITLE: String =
    "These are the product-facing defaults and live configuration values currently active in " +
        "the reference app."
