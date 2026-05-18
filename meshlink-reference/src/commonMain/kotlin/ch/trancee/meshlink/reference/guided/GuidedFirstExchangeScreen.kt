package ch.trancee.meshlink.reference.guided

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

/** Shared guided first-exchange surface. */
@Composable
public fun GuidedFirstExchangeScreen(
    viewModel: GuidedFirstExchangeViewModel,
    onOpenSolo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(text = "Guided first exchange", style = MaterialTheme.typography.headlineSmall)
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("guided-state-card"),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = uiState.readiness.summary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
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
                    Text(
                        text = "Selected peer: ${uiState.selectedPeerSuffix ?: "none"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Button(
                            onClick = viewModel::startMesh,
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
                        Button(
                            onClick = onOpenSolo,
                            modifier = Modifier.testTag("guided-open-solo"),
                        ) {
                            Text("Solo mode")
                        }
                    }
                }
            }
        }
        item {
            Text(
                text = "Readiness checklist",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(uiState.readiness.items) { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = item.title, style = MaterialTheme.typography.labelLarge)
                    Text(text = item.detail, style = MaterialTheme.typography.bodyMedium)
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
        items(uiState.snapshot.timeline.takeLast(5)) { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(text = entry.title, style = MaterialTheme.typography.bodyLarge)
                    Text(text = entry.detail, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
