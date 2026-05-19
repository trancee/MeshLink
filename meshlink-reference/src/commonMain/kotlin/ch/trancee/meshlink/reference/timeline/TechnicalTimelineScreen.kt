package ch.trancee.meshlink.reference.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.design.ReferenceBadge
import ch.trancee.meshlink.reference.design.ReferenceSectionCard
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy

@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun TechnicalTimelineScreen(store: TechnicalTimelineStore, modifier: Modifier = Modifier) {
    val uiState by store.uiState.collectAsState()
    val availableFamilies =
        uiState.currentSnapshot.timeline.map { entry -> entry.family }.distinct()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(20.dp).testTag("timeline-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(text = "Technical timeline", style = MaterialTheme.typography.headlineSmall) }
        item {
            Text(
                text =
                    "Read the live diagnostics as operator evidence, then retain or export the current session when the run is complete.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ReferenceSectionCard(
                title = "Retain or export",
                subtitle =
                    "Keep the most common evidence actions at the top of the page so they stay available during a live operator run.",
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReferenceBadge(
                        label =
                            if (uiState.viewingRetained) "Viewing retained session"
                            else "Viewing live session",
                        prominent = !uiState.viewingRetained,
                    )
                    ReferenceBadge(label = "Visible ${uiState.visibleEntries.size}")
                    ReferenceBadge(label = "Retained ${uiState.retainedSessions.size}")
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = store::retainCurrentSession) { Text("Retain session") }
                    Button(
                        onClick = {
                            store.exportCurrentSession(ExportPayloadPolicy.REDACTED_PREVIEW)
                        }
                    ) {
                        Text("Export redacted")
                    }
                    Button(
                        onClick = {
                            store.exportCurrentSession(ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN)
                        }
                    ) {
                        Text("Export full payload")
                    }
                }
                if (uiState.lastExportPath != null) {
                    Text(
                        text = "Last export: ${uiState.lastExportPath}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            ReferenceSectionCard(
                title = "Filter events",
                subtitle =
                    "Search the current session and narrow the timeline to one family when you want a smaller operator view.",
            ) {
                OutlinedTextField(
                    value = uiState.filters.searchText,
                    onValueChange = store::updateSearch,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Search events") },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = uiState.filters.family == null,
                        onClick = store::clearFilters,
                        label = { Text("All") },
                    )
                    availableFamilies.forEach { family ->
                        FilterChip(
                            selected = uiState.filters.family == family,
                            onClick = { store.updateFamily(family) },
                            label = { Text(family.label()) },
                        )
                    }
                }
            }
        }
        if (uiState.visibleEntries.isEmpty()) {
            item {
                ReferenceSectionCard(
                    title = "No matching events",
                    subtitle =
                        "Clear the current filters or keep running the session until new diagnostics arrive.",
                ) {
                    Text(
                        text = "Nothing matches the current search and family filters.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        items(uiState.visibleEntries) { entry ->
            ReferenceSectionCard(title = entry.title, subtitle = entry.detail) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReferenceBadge(label = entry.family.label())
                    ReferenceBadge(label = entry.severity.name)
                    if (entry.peerSuffix != null) {
                        ReferenceBadge(label = "Peer ${entry.peerSuffix}")
                    }
                }
                if (entry.payloadPreview != null) {
                    Text(
                        text = "Preview: ${entry.payloadPreview}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun TimelineFamily.label(): String {
    return name.lowercase().replace('_', ' ').replaceFirstChar { character ->
        character.titlecase()
    }
}
