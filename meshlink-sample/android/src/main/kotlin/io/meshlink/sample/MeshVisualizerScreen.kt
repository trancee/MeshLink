package io.meshlink.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mesh Visualizer screen — renders discovered peers as a circular graph on a Canvas.
 *
 * • Self node is drawn in the center with the primary color.
 * • Peer nodes are arranged in a circle around it.
 * • Links are drawn with color and thickness derived from RSSI signal quality.
 * • Auto-refreshes every 2 seconds.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MeshVisualizerScreen(viewModel: MeshLinkViewModel) {
    val peers by viewModel.discoveredPeers.collectAsState()
    val health by viewModel.health.collectAsState()
    val textMeasurer = rememberTextMeasurer()

    // Tick counter to trigger recomposition every 2 seconds
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }
    // Read tick to force recomposition
    @Suppress("UNUSED_EXPRESSION")
    tick

    Scaffold(
        topBar = { TopAppBar(title = { Text("Mesh Visualizer") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Status chips ──
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("Peers: ${health.connectedPeers}") }
                )
                AssistChip(
                    onClick = {},
                    label = { Text("Mode: ${health.powerMode}") }
                )
            }

            // ── Canvas graph ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                val primaryColor = MaterialTheme.colorScheme.primary
                val onSurfaceColor = MaterialTheme.colorScheme.onSurfaceVariant

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (peers.isEmpty()) {
                        Text(
                            "No peers discovered yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawMeshGraph(
                                peers = peers,
                                primaryColor = primaryColor,
                                labelColor = onSurfaceColor,
                                textMeasurer = textMeasurer,
                            )
                        }
                    }
                }
            }

            // ── Legend ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Legend", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    LegendRow(color = MaterialTheme.colorScheme.primary, label = "Self (this device)")
                    LegendRow(color = Color(0xFF4CAF50), label = "Good signal (RSSI ≥ −60 dBm)")
                    LegendRow(color = Color(0xFFFFC107), label = "Fair signal (RSSI −60…−80 dBm)")
                    LegendRow(color = Color(0xFFF44336), label = "Poor signal (RSSI < −80 dBm)")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Canvas(modifier = Modifier.size(12.dp)) {
            drawCircle(color = color)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

// ── Drawing helpers ──

private fun DrawScope.drawMeshGraph(
    peers: List<PeerInfo>,
    primaryColor: Color,
    labelColor: Color,
    textMeasurer: TextMeasurer,
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = (minOf(size.width, size.height) / 2f) * 0.7f
    val selfNodeRadius = 22f
    val peerNodeRadius = 16f

    val peerPositions = peers.mapIndexed { index, _ ->
        val angle = (2.0 * Math.PI * index / peers.size) - Math.PI / 2
        Offset(
            center.x + (radius * cos(angle)).toFloat(),
            center.y + (radius * sin(angle)).toFloat()
        )
    }

    // Draw links from self to each peer
    peers.forEachIndexed { index, peer ->
        val peerPos = peerPositions[index]
        val linkColor = rssiToColor(peer.rssi)
        val strokeWidth = rssiToStrokeWidth(peer.rssi)
        drawLine(
            color = linkColor,
            start = center,
            end = peerPos,
            strokeWidth = strokeWidth,
        )
    }

    // Draw self node
    drawCircle(color = primaryColor, radius = selfNodeRadius, center = center)
    drawCircle(
        color = primaryColor.copy(alpha = 0.3f),
        radius = selfNodeRadius + 6f,
        center = center,
        style = Stroke(width = 2f)
    )

    val selfLabel = textMeasurer.measure(
        text = "Self",
        style = TextStyle(fontSize = 9.sp, color = labelColor)
    )
    drawText(
        selfLabel,
        topLeft = Offset(
            center.x - selfLabel.size.width / 2f,
            center.y + selfNodeRadius + 4f
        )
    )

    // Draw peer nodes
    peers.forEachIndexed { index, peer ->
        val pos = peerPositions[index]
        val nodeColor = rssiToColor(peer.rssi)

        drawCircle(color = nodeColor, radius = peerNodeRadius, center = pos)

        val idLabel = peer.id.take(4).uppercase()
        val measured = textMeasurer.measure(
            text = idLabel,
            style = TextStyle(fontSize = 9.sp, color = labelColor)
        )
        drawText(
            measured,
            topLeft = Offset(
                pos.x - measured.size.width / 2f,
                pos.y + peerNodeRadius + 4f
            )
        )
    }
}

/** Maps RSSI (dBm, typically −30…−100) to a green→yellow→red color. */
private fun rssiToColor(rssi: Int): Color = when {
    rssi >= -60 -> Color(0xFF4CAF50) // green — good
    rssi >= -80 -> Color(0xFFFFC107) // yellow — fair
    else -> Color(0xFFF44336)         // red — poor
}

/** Maps RSSI to link stroke width (thicker = stronger signal). */
private fun rssiToStrokeWidth(rssi: Int): Float = when {
    rssi >= -60 -> 4f
    rssi >= -80 -> 2.5f
    else -> 1.5f
}
