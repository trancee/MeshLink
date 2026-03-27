package io.meshlink.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Settings screen — displays current MeshLink configuration and allows
 * changing config presets and MTU.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MeshLinkViewModel) {
    val config by viewModel.currentConfig.collectAsState()
    val currentPreset by viewModel.currentPreset.collectAsState()
    val health by viewModel.health.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val startTime by viewModel.startTime.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Mesh Status ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Mesh Status", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusRow("Status", if (isRunning) "Running" else "Stopped")
                    StatusRow("Connected Peers", "${health.connectedPeers}")
                    StatusRow("Uptime", formatUptime(startTime))
                }
            }

            // ── Config Preset Picker ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .selectableGroup()
                ) {
                    Text("Config Preset", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    ConfigPreset.entries.forEach { preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = currentPreset == preset,
                                    onClick = { viewModel.applyPreset(preset.key) },
                                    role = Role.RadioButton
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentPreset == preset,
                                onClick = null // handled by selectable
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(preset.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    preset.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ── MTU Slider ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("MTU", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Current: ${config.mtu} bytes",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Slider(
                        value = config.mtu.toFloat(),
                        onValueChange = { viewModel.updateMtu(it.roundToInt()) },
                        valueRange = 23f..512f,
                        steps = 0,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("23", style = MaterialTheme.typography.labelSmall)
                        Text("512", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // ── Configuration Details ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Configuration Details", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    ListItem(
                        headlineContent = { Text("Power Mode") },
                        supportingContent = { Text("Auto-detected from battery level") },
                        trailingContent = { Text(health.powerMode) }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Max Message Size") },
                        trailingContent = { Text(formatBytes(config.maxMessageSize)) }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Buffer Capacity") },
                        trailingContent = { Text(formatBytes(config.bufferCapacity)) }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Buffer Usage") },
                        trailingContent = { Text("${health.bufferUtilizationPercent}%") }
                    )
                }
            }

            // ── Reset Button ──
            Button(
                onClick = { viewModel.applyPreset(ConfigPreset.CHAT.key) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text("Reset to Defaults")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** Human-readable byte formatting. */
private fun formatBytes(bytes: Int): String = when {
    bytes >= 1_048_576 -> "${"%.1f".format(bytes / 1_048_576.0)} MB"
    bytes >= 1_024 -> "${"%.1f".format(bytes / 1_024.0)} KB"
    else -> "$bytes B"
}

/** Human-readable uptime from a start timestamp (0 = not started). */
private fun formatUptime(startTimeMs: Long): String {
    if (startTimeMs == 0L) return "—"
    val elapsed = System.currentTimeMillis() - startTimeMs
    val seconds = (elapsed / 1_000) % 60
    val minutes = (elapsed / 60_000) % 60
    val hours = elapsed / 3_600_000
    return buildString {
        if (hours > 0) append("${hours}h ")
        if (minutes > 0 || hours > 0) append("${minutes}m ")
        append("${seconds}s")
    }
}

/** Available config presets with display metadata. */
enum class ConfigPreset(val key: String, val label: String, val description: String) {
    CHAT("chatOptimized", "Chat Optimized", "Low latency, moderate buffer"),
    FILE_TRANSFER("fileTransferOptimized", "File Transfer", "Large messages, big buffer"),
    POWER("powerOptimized", "Power Optimized", "Reduced resource usage"),
    SENSOR("sensorOptimized", "Sensor / IoT", "Tiny messages, long intervals"),
}
