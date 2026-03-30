package io.meshlink.sample

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.meshlink.diagnostics.DiagnosticEvent
import io.meshlink.diagnostics.Severity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(viewModel: MeshLinkViewModel) {
    val allEvents by viewModel.diagnosticEvents.collectAsState()
    val filter by viewModel.severityFilter.collectAsState()
    val events = if (filter != null) allEvents.filter { it.severity == filter } else allEvents
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new events arrive
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) {
            listState.animateScrollToItem(events.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                actions = {
                    IconButton(onClick = { viewModel.clearDiagnostics() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp)
        ) {
            // Severity filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { viewModel.setSeverityFilter(null) },
                    label = { Text("All (${allEvents.size})") },
                )
                Severity.entries.forEach { severity ->
                    val count = allEvents.count { it.severity == severity }
                    FilterChip(
                        selected = filter == severity,
                        onClick = {
                            viewModel.setSeverityFilter(
                                if (filter == severity) null else severity,
                            )
                        },
                        label = { Text("${severity.name} ($count)") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = severityColor(severity).copy(alpha = 0.2f),
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (events.isEmpty()) {
                Text(
                    text = if (allEvents.isEmpty()) {
                        "No diagnostic events yet.\nStart the mesh to begin."
                    } else {
                        "No events match the selected filter."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(events, key = { index, it -> "${it.monotonicMillis}-${it.code}-$index" }) { _, event ->
                        DiagnosticEventCard(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticEventCard(event: DiagnosticEvent) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = severityColor(event.severity).copy(alpha = 0.08f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SeverityDot(event.severity)
                Text(
                    text = event.code.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = formatTimestamp(event.monotonicMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!event.payload.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = event.payload!!,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SeverityDot(severity: Severity) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
        drawCircle(color = severityColor(severity))
    }
}

private fun severityColor(severity: Severity): Color = when (severity) {
    Severity.INFO -> Color(0xFF4CAF50)
    Severity.WARN -> Color(0xFFFFC107)
    Severity.ERROR -> Color(0xFFF44336)
}

private fun formatTimestamp(monotonicMillis: Long): String {
    val totalSeconds = monotonicMillis / 1000
    val minutes = (totalSeconds / 60) % 60
    val seconds = totalSeconds % 60
    val millis = monotonicMillis % 1000
    return "%02d:%02d.%03d".format(minutes, seconds, millis)
}
