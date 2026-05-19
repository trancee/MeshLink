package ch.trancee.meshlink.reference.guided

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import ch.trancee.meshlink.reference.design.ReferenceBadge
import ch.trancee.meshlink.reference.design.ReferenceSectionCard

/** Shared guided first-exchange surface. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun GuidedFirstExchangeScreen(
    viewModel: GuidedFirstExchangeViewModel,
    onOpenSolo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Guided first exchange", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text =
                        "Use the fastest product-like path to start MeshLink, discover one peer, and prove the first message flow.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            ReferenceSectionCard(
                title = "Live first-message flow",
                modifier = Modifier.testTag("guided-state-card"),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ReferenceBadge(
                        label =
                            if (uiState.readiness.isBlocked) "Startup blocked"
                            else "Ready to start",
                        prominent = !uiState.readiness.isBlocked,
                    )
                    ReferenceBadge(label = "Peer: ${uiState.selectedPeerSuffix ?: "none"}")
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = viewModel::startMesh,
                        enabled = !uiState.readiness.isBlocked,
                        modifier = Modifier.testTag("guided-start"),
                    ) {
                        Text("Start MeshLink")
                    }
                    Button(
                        onClick = viewModel::sendHelloToFirstPeer,
                        enabled = uiState.canSendHello,
                        modifier = Modifier.testTag("guided-send"),
                    ) {
                        Text("Send Hello")
                    }
                    Button(onClick = onOpenSolo, modifier = Modifier.testTag("guided-open-solo")) {
                        Text("Solo mode")
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
            }
        }
        if (uiState.readiness.isBlocked) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("guided-blocker-card"),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Startup blocked",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        uiState.readiness.blockers.forEach { blocker ->
                            Text(
                                text = "${blocker.title}: ${blocker.detail}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
        item {
            ReferenceSectionCard(
                title = "Readiness checklist",
                subtitle =
                    "Clear these local prerequisites before you treat the guided flow as live proof.",
            ) {
                uiState.readiness.items.forEach { item ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = item.title, style = MaterialTheme.typography.labelLarge)
                        Text(text = item.detail, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            Text(
                text = "Recent timeline",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(uiState.snapshot.timeline.takeLast(4)) { entry ->
            ReferenceSectionCard(title = entry.title, subtitle = entry.detail) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReferenceBadge(label = entry.family.name)
                    ReferenceBadge(label = entry.severity.name)
                    if (entry.peerSuffix != null) {
                        ReferenceBadge(label = "Peer ${entry.peerSuffix}")
                    }
                }
            }
        }
    }
}
