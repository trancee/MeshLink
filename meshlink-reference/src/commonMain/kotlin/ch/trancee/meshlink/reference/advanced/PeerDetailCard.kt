package ch.trancee.meshlink.reference.advanced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.design.ReferenceBadge

@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun PeerDetailCard(
    peer: AdvancedPeerRow,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = peer.peerSuffix, style = MaterialTheme.typography.titleLarge)
                if (selected) {
                    ReferenceBadge(label = "Selected", prominent = true)
                }
                AssistChip(
                    onClick = onSelect,
                    label = { Text(if (selected) "Selected" else "Select") },
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReferenceBadge(label = "Trust ${peer.trustLabel}")
                ReferenceBadge(label = "Connection ${peer.connectionLabel}")
            }
            Text(text = "Trust: ${peer.trustLabel}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Connection: ${peer.connectionLabel}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Last outcome: ${peer.lastDeliveryOutcome ?: "none yet"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
