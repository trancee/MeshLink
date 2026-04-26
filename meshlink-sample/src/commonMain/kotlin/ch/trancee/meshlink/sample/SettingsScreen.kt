package ch.trancee.meshlink.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.api.MeshLinkState
import kotlinx.coroutines.launch

/**
 * Settings screen composable.
 *
 * Displays the current [MeshLinkState] with a colour-coded status indicator, lifecycle control
 * buttons (Start / Stop / Pause / Resume), a read-only config summary, and the local public-key
 * hex fingerprint (available after the engine starts).
 *
 * All button click handlers launch coroutines via [rememberCoroutineScope] so they can call the
 * suspend lifecycle wrappers on [MeshController] without blocking the UI thread.
 */
@Composable
fun SettingsScreen(controller: MeshController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val config = controller.config

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Engine status card ─────────────────────────────────────────────
        Text("Engine Status", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Colour indicator dot
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            color = meshLinkStateColor(state),
                            shape = MaterialTheme.shapes.extraSmall,
                        ),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = state.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = meshLinkStateDescription(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Lifecycle controls ────────────────────────────────────────────
        Text("Controls", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = state == MeshLinkState.UNINITIALIZED || state == MeshLinkState.RECOVERABLE,
                onClick = { scope.launch { controller.start() } },
            ) { Text("Start") }
            Button(
                enabled = state == MeshLinkState.RUNNING || state == MeshLinkState.PAUSED,
                onClick = { scope.launch { controller.stop() } },
            ) { Text("Stop") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = state == MeshLinkState.RUNNING,
                onClick = { scope.launch { controller.pause() } },
            ) { Text("Pause") }
            Button(
                enabled = state == MeshLinkState.PAUSED,
                onClick = { scope.launch { controller.resume() } },
            ) { Text("Resume") }
        }

        HorizontalDivider()

        // ── Configuration summary ─────────────────────────────────────────
        Text("Configuration", style = MaterialTheme.typography.titleMedium)
        ConfigRow(label = "App ID", value = config.appId)
        ConfigRow(
            label = "Max Message Size",
            value = "${config.messaging.maxMessageSize / 1024} KB",
        )
        ConfigRow(label = "Trust Mode", value = config.security.trustMode.name)

        HorizontalDivider()

        // ── Identity ──────────────────────────────────────────────────────
        Text("Identity", style = MaterialTheme.typography.titleMedium)
        val pubKeyDisplay = when (state) {
            MeshLinkState.UNINITIALIZED, MeshLinkState.STOPPED -> "(available after start)"
            else -> {
                val hex = controller.meshLink.localPublicKey
                    .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                "${hex.take(32)}…"
            }
        }
        ConfigRow(label = "Public Key", value = pubKeyDisplay)
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun ConfigRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * Maps [MeshLinkState] to a semantic colour for the status indicator dot.
 *
 * - Green  → RUNNING (healthy)
 * - Yellow → PAUSED (intentional suspension)
 * - Red    → TERMINAL or RECOVERABLE (failure; red + orange to distinguish severity)
 * - Grey   → UNINITIALIZED or STOPPED (not active)
 */
private fun meshLinkStateColor(state: MeshLinkState): Color = when (state) {
    MeshLinkState.RUNNING -> Color(0xFF4CAF50)       // green
    MeshLinkState.PAUSED -> Color(0xFFFFC107)         // amber
    MeshLinkState.RECOVERABLE -> Color(0xFFFF9800)    // orange — transient failure
    MeshLinkState.TERMINAL -> Color(0xFFF44336)       // red — permanent failure
    MeshLinkState.UNINITIALIZED,
    MeshLinkState.STOPPED -> Color(0xFF9E9E9E)        // grey
}

/**
 * Returns a one-line human-readable description of each [MeshLinkState] for the settings card.
 */
private fun meshLinkStateDescription(state: MeshLinkState): String = when (state) {
    MeshLinkState.UNINITIALIZED -> "Engine not started — tap Start"
    MeshLinkState.RUNNING -> "Engine active, mesh connected"
    MeshLinkState.PAUSED -> "BLE scanning/advertising paused"
    MeshLinkState.STOPPED -> "Engine stopped permanently"
    MeshLinkState.RECOVERABLE -> "Transient failure — tap Start to retry"
    MeshLinkState.TERMINAL -> "Permanent failure — restart the app"
}
