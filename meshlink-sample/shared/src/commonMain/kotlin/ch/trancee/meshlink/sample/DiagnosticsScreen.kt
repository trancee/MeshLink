package ch.trancee.meshlink.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.api.DiagnosticEvent
import ch.trancee.meshlink.api.DiagnosticLevel
import ch.trancee.meshlink.api.MeshHealthSnapshot

/**
 * Diagnostics screen composable.
 *
 * Displays three sections:
 * 1. **Health card** — current [MeshHealthSnapshot] with connected peers, routing table size,
 *    buffer utilization, power mode, and average route cost.
 * 2. **Severity filter** — [FilterChip] row for ERROR / WARN / INFO / DEBUG. Active chips are
 *    shown; deactivating all chips shows every event.
 * 3. **Event log** — scrollable list of [DiagnosticEvent]s filtered by the active severity
 *    selection. Each row shows the [ch.trancee.meshlink.api.DiagnosticCode] name, a colour-coded
 *    severity badge, a relative timestamp, and a truncated payload summary.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(controller: MeshController, modifier: Modifier = Modifier) {
    val health by controller.healthSnapshot.collectAsState()
    val allEvents by controller.diagnosticEvents.collectAsState()

    // Track which severity levels are enabled; start with all enabled.
    var errorEnabled by remember { mutableStateOf(true) }
    var warnEnabled by remember { mutableStateOf(true) }
    var infoEnabled by remember { mutableStateOf(true) }
    var debugEnabled by remember { mutableStateOf(true) }

    val filteredEvents = remember(allEvents, errorEnabled, warnEnabled, infoEnabled, debugEnabled) {
        allEvents.filter { event ->
            when (event.severity) {
                DiagnosticLevel.ERROR -> errorEnabled
                DiagnosticLevel.WARN -> warnEnabled
                DiagnosticLevel.INFO -> infoEnabled
                DiagnosticLevel.DEBUG -> debugEnabled
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ── Health card ────────────────────────────────────────────────────
        HealthCard(
            snapshot = health,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )

        HorizontalDivider()

        // ── Severity filter row ────────────────────────────────────────────
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SeverityChip(
                label = "ERROR",
                selected = errorEnabled,
                color = diagnosticLevelColor(DiagnosticLevel.ERROR),
                onToggle = { errorEnabled = it },
            )
            SeverityChip(
                label = "WARN",
                selected = warnEnabled,
                color = diagnosticLevelColor(DiagnosticLevel.WARN),
                onToggle = { warnEnabled = it },
            )
            SeverityChip(
                label = "INFO",
                selected = infoEnabled,
                color = diagnosticLevelColor(DiagnosticLevel.INFO),
                onToggle = { infoEnabled = it },
            )
            SeverityChip(
                label = "DEBUG",
                selected = debugEnabled,
                color = diagnosticLevelColor(DiagnosticLevel.DEBUG),
                onToggle = { debugEnabled = it },
            )
        }

        HorizontalDivider()

        // ── Event log ──────────────────────────────────────────────────────
        if (filteredEvents.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (allEvents.isEmpty()) "No diagnostic events" else "All events filtered",
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
                items(filteredEvents, key = { it.monotonicMillis }) { event ->
                    DiagnosticEventItem(event)
                }
            }
        }
    }
}

// ── HealthCard ────────────────────────────────────────────────────────────────

/**
 * Renders a [Card] summarising the most recent [MeshHealthSnapshot].
 *
 * Shows `null` placeholder values when the snapshot has not arrived yet (before engine start
 * or before the first periodic emission).
 */
@Composable
private fun HealthCard(snapshot: MeshHealthSnapshot?, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Health Snapshot", style = MaterialTheme.typography.titleSmall)
            if (snapshot == null) {
                Text(
                    text = "Waiting for first snapshot…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                HealthRow("Connected peers", "${snapshot.connectedPeers}")
                HealthRow("Routing table size", "${snapshot.routingTableSize}")
                HealthRow("Buffer usage", "${snapshot.bufferUsageBytes} B (${snapshot.bufferUtilizationPercent}%)")
                HealthRow("Power mode", snapshot.powerMode.name)
                val costStr = ((snapshot.avgRouteCost * 100).toLong() / 100.0).toString()
                HealthRow("Avg route cost", costStr)
            }
        }
    }
}

@Composable
private fun HealthRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

// ── SeverityChip ──────────────────────────────────────────────────────────────

/**
 * A [FilterChip] that toggles the visibility of events at a given severity level.
 *
 * The chip uses the severity colour as its selected-container colour so each level has a distinct
 * visual identity.
 */
@Composable
private fun SeverityChip(
    label: String,
    selected: Boolean,
    color: Color,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = color.copy(alpha = 0.25f),
            selectedLabelColor = color,
        ),
    )
}

// ── DiagnosticEventItem ───────────────────────────────────────────────────────

/**
 * Renders a single [DiagnosticEvent] row showing:
 * - [ch.trancee.meshlink.api.DiagnosticCode] name
 * - A colour-coded severity label
 * - Relative monotonic timestamp (ms)
 * - Truncated payload summary (via `toString()`, first 80 chars)
 * - Dropped-event count badge when non-zero
 */
@Composable
private fun DiagnosticEventItem(event: DiagnosticEvent, modifier: Modifier = Modifier) {
    val severityColor = diagnosticLevelColor(event.severity)
    val payloadSummary = event.payload.toString().take(80).let { summary ->
        if (event.payload.toString().length > 80) "$summary…" else summary
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = event.code.name,
                style = MaterialTheme.typography.labelMedium,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (event.droppedCount > 0) {
                    Text(
                        text = "+${event.droppedCount} dropped",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = event.severity.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = severityColor,
                )
                Text(
                    text = "+${event.monotonicMillis % 100_000}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = payloadSummary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}

// ── Color helpers ─────────────────────────────────────────────────────────────

/**
 * Maps each [DiagnosticLevel] to a semantic colour:
 * - ERROR → red
 * - WARN  → amber/yellow
 * - INFO  → blue
 * - DEBUG → grey
 */
internal fun diagnosticLevelColor(level: DiagnosticLevel): Color = when (level) {
    DiagnosticLevel.ERROR -> Color(0xFFF44336)   // Material Red 500
    DiagnosticLevel.WARN -> Color(0xFFFFC107)    // Material Amber 500
    DiagnosticLevel.INFO -> Color(0xFF2196F3)    // Material Blue 500
    DiagnosticLevel.DEBUG -> Color(0xFF9E9E9E)   // Material Grey 500
}
