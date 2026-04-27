package ch.trancee.meshlink.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.RoutingEntry
import ch.trancee.meshlink.api.RoutingSnapshot

/**
 * Mesh Visualizer screen composable.
 *
 * Displays two sections:
 * 1. **Peer list** — a live log of [PeerEvent.Found] and [PeerEvent.Lost] events collected
 *    by [MeshController]. Found peers show their hex fingerprint, connection status, and trust
 *    mode; Lost peers are rendered in a muted style.
 * 2. **Routing snapshot** — a point-in-time view of [RoutingSnapshot] showing destination,
 *    next-hop, cost, and sequence number for each [RoutingEntry]. A **Refresh** button
 *    re-reads the snapshot from the engine since [ch.trancee.meshlink.api.MeshLinkApi.routingSnapshot]
 *    is not a live [kotlinx.coroutines.flow.Flow].
 */
@Composable
fun MeshVisualizerScreen(controller: MeshController, modifier: Modifier = Modifier) {
    val peers by controller.peers.collectAsState()

    // routingSnapshot() is a point-in-time read — store in local state and refresh on demand.
    var routingSnapshot by remember {
        mutableStateOf(controller.meshLink.routingSnapshot())
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ── Peer list section ──────────────────────────────────────────────
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Peers (${peers.size} events)",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }

        if (peers.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No peers discovered",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(peers, key = { event ->
                    // Use content hashCode of the id bytes + event class name for stable keys
                    when (event) {
                        is PeerEvent.Found -> "found-${event.id.contentHashCode()}"
                        is PeerEvent.Lost -> "lost-${event.id.contentHashCode()}"
                    }
                }) { event ->
                    PeerEventItem(event)
                }
            }
        }

        HorizontalDivider()

        // ── Routing snapshot section ───────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Routing Table (${routingSnapshot.routes.size} routes)",
                style = MaterialTheme.typography.titleSmall,
            )
            Button(
                onClick = { routingSnapshot = controller.meshLink.routingSnapshot() },
            ) {
                Text("Refresh")
            }
        }

        if (routingSnapshot.routes.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No routes in table",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(routingSnapshot.routes, key = { entry ->
                    "${entry.destination.contentHashCode()}-${entry.nextHop.contentHashCode()}"
                }) { entry ->
                    RoutingEntryItem(entry)
                }
            }
        }
    }
}

// ── PeerEventItem ─────────────────────────────────────────────────────────────

/**
 * Renders a single [PeerEvent] row.
 *
 * - [PeerEvent.Found]: Shows the peer fingerprint, connection status, and trust mode in normal text.
 * - [PeerEvent.Lost]: Shows the truncated hex ID in a muted/greyed style.
 */
@Composable
private fun PeerEventItem(event: PeerEvent, modifier: Modifier = Modifier) {
    when (event) {
        is PeerEvent.Found -> {
            Card(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = event.detail.fingerprint.take(16) + "…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = if (event.detail.isConnected) "● Connected" else "○ Seen",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (event.detail.isConnected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "Trust: ${event.detail.trustMode.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        is PeerEvent.Lost -> {
            val idHex = event.id.joinToString("") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0')
            }.take(16)
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$idHex… (lost)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

// ── RoutingEntryItem ──────────────────────────────────────────────────────────

/**
 * Renders a single [RoutingEntry] row showing destination, next-hop, cost, and sequence number.
 * Both [RoutingEntry.destination] and [RoutingEntry.nextHop] are shown as truncated hex strings
 * (first 8 bytes = 16 hex chars) for readability.
 */
@Composable
private fun RoutingEntryItem(entry: RoutingEntry, modifier: Modifier = Modifier) {
    fun ByteArray.shortHex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.take(16) + "…"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "→ ${entry.destination.shortHex()}",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = "cost ${entry.cost}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "via ${entry.nextHop.shortHex()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "seq ${entry.seqNo}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}
