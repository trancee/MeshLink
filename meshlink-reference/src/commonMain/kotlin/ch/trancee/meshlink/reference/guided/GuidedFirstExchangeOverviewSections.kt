package ch.trancee.meshlink.reference.guided

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.design.ReferenceBadge
import ch.trancee.meshlink.reference.design.ReferenceSectionCard

@Composable
internal fun GuidedFirstExchangeHeader(): Unit {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Library harness", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = GUIDED_FIRST_EXCHANGE_INTRO_TEXT,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuidedLiveFirstMessageSection(
    uiState: GuidedFirstExchangeUiState,
    powerMitigationLabel: String?,
    onStartMesh: () -> Unit,
    onSendHello: () -> Unit,
): Unit {
    ReferenceSectionCard(
        title = "Deterministic startup flow",
        modifier = Modifier.testTag("guided-state-card"),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            powerMitigationLabel?.let { label -> ReferenceBadge(label = label, prominent = true) }
            ReferenceBadge(
                label =
                    when {
                        uiState.isSessionEnded -> "Session complete"
                        uiState.readiness.isBlocked -> "Startup blocked"
                        else -> "Ready"
                    },
                prominent = !uiState.readiness.isBlocked && !uiState.isSessionEnded,
            )
            ReferenceBadge(label = "Peer: ${uiState.selectedPeerSuffix ?: "none"}")
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = onStartMesh,
                enabled = !uiState.readiness.isBlocked && !uiState.isSessionEnded,
                modifier = Modifier.testTag("guided-start"),
            ) {
                Text("Start MeshLink")
            }
            Button(
                onClick = onSendHello,
                enabled = uiState.canSendHello,
                modifier = Modifier.testTag("guided-send"),
            ) {
                Text("Send Hello")
            }
        }
        Text(
            text = "Mesh state: ${uiState.snapshot.session.meshStateLabel}",
            modifier = Modifier.testTag("guided-state"),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = "Next action: ${uiState.nextActionLabel}",
            modifier = Modifier.testTag("guided-next-action"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (uiState.isSessionEnded) {
            Text(
                text =
                    "This session is closed. Export the captured artifact or rerun the harness with a fresh scenario.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val GUIDED_FIRST_EXCHANGE_INTRO_TEXT: String =
    "Use this screen to start MeshLink, inspect peers, and deterministically verify the first " +
        "message flow."
