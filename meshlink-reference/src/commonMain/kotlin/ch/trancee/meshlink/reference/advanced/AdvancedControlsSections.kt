package ch.trancee.meshlink.reference.advanced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.design.ReferenceBadge
import ch.trancee.meshlink.reference.design.ReferenceSectionCard

@Composable
internal fun AdvancedControlsHeader(): Unit {
    Text(text = "Advanced controls", style = MaterialTheme.typography.headlineSmall)
    Text(
        text = ADVANCED_CONTROLS_INTRO_TEXT,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdvancedLiveSnapshotSection(uiState: AdvancedControlsUiState): Unit {
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
internal fun AdvancedLifecycleSection(
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
                modifier = Modifier.referenceActionAccessibility("Start", "advanced-start"),
            ) {
                Text("Start")
            }
            Button(
                onClick = viewModel::pauseMesh,
                enabled = lifecycleActions.pauseEnabled && !isSessionEnded,
                modifier = Modifier.referenceActionAccessibility("Pause", "advanced-pause"),
            ) {
                Text("Pause")
            }
            Button(
                onClick = viewModel::resumeMesh,
                enabled = lifecycleActions.resumeEnabled && !isSessionEnded,
                modifier = Modifier.referenceActionAccessibility("Resume", "advanced-resume"),
            ) {
                Text("Resume")
            }
            Button(
                onClick = viewModel::stopMesh,
                enabled = lifecycleActions.stopEnabled && !isSessionEnded,
                modifier = Modifier.referenceActionAccessibility("Stop", "advanced-stop"),
            ) {
                Text("Stop")
            }
        }
    }
}

@Composable
internal fun AdvancedConfigurationSection(uiState: AdvancedControlsUiState): Unit {
    ReferenceSectionCard(title = "Configuration", subtitle = ADVANCED_CONFIGURATION_SUBTITLE) {
        Text(text = "App ID: ${uiState.config.appId}")
        Text(text = "Regulatory region: ${uiState.config.regulatoryRegion}")
        Text(text = "Retry deadline: ${uiState.config.deliveryRetryDeadlineLabel}")
    }
}

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

private const val ADVANCED_CONTROLS_INTRO_TEXT: String =
    "Inspect the live runtime, choose a peer deliberately, and send operator-facing previews " +
        "without leaving the reference app."

private const val ADVANCED_LIFECYCLE_SUBTITLE: String =
    "Use these controls when you want to inspect how the live runtime moves between start, " +
        "pause, resume, and stop states."

private const val ADVANCED_CONFIGURATION_SUBTITLE: String =
    "These are the product-facing defaults and live configuration values currently active in " +
        "the reference app."
