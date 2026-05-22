@file:Suppress("FunctionNaming")

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.design.ReferenceBadge
import ch.trancee.meshlink.reference.design.ReferenceSectionCard
import ch.trancee.meshlink.reference.export.ExportSessionDialog
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.model.referenceTimelineFamilyLabel
import ch.trancee.meshlink.reference.model.referenceTimelineSeverityLabel
import ch.trancee.meshlink.reference.navigation.SessionBoundaryDialog
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy

@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun TechnicalTimelineScreen(store: TechnicalTimelineStore, modifier: Modifier = Modifier) {
    val uiState by store.uiState.collectAsState()
    val availableFamilies =
        uiState.currentSnapshot.timeline.map { entry -> entry.family }.distinct()
    val availablePeerSuffixes =
        uiState.currentSnapshot.timeline.mapNotNull { entry -> entry.peerSuffix }.distinct()
    val availableSeverities =
        uiState.currentSnapshot.timeline.map { entry -> entry.severity }.distinct()

    TechnicalTimelineContent(
        uiState = uiState,
        availableFamilies = availableFamilies,
        availablePeerSuffixes = availablePeerSuffixes,
        availableSeverities = availableSeverities,
        store = store,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TechnicalTimelineContent(
    uiState: TechnicalTimelineUiState,
    availableFamilies: List<TimelineFamily>,
    availablePeerSuffixes: List<String>,
    availableSeverities: List<TimelineSeverity>,
    store: TechnicalTimelineStore,
    modifier: Modifier,
): Unit {
    var showExportDialog by remember { mutableStateOf(false) }
    var showEndSessionDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp).testTag("timeline-screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TechnicalTimelineHeader() }
        item {
            TimelineRetentionSection(
                uiState = uiState,
                store = store,
                showExportDialog = showExportDialog,
                showEndSessionDialog = showEndSessionDialog,
                onOpenExportDialog = { showExportDialog = true },
                onDismissExportDialog = { showExportDialog = false },
                onOpenEndSessionDialog = { showEndSessionDialog = true },
                onDismissEndSessionDialog = { showEndSessionDialog = false },
            )
        }
        item {
            TimelineFilterSection(
                uiState = uiState,
                availableFamilies = availableFamilies,
                availablePeerSuffixes = availablePeerSuffixes,
                availableSeverities = availableSeverities,
                store = store,
            )
        }
        if (uiState.visibleEntries.isEmpty()) {
            item { EmptyTimelineSection() }
        }
        items(uiState.visibleEntries) { entry -> TimelineEntryCard(entry = entry) }
    }
}

@Composable
private fun TechnicalTimelineHeader(): Unit {
    Text(text = "Technical timeline", style = MaterialTheme.typography.headlineSmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineRetentionSection(
    uiState: TechnicalTimelineUiState,
    store: TechnicalTimelineStore,
    showExportDialog: Boolean,
    showEndSessionDialog: Boolean,
    onOpenExportDialog: () -> Unit,
    onDismissExportDialog: () -> Unit,
    onOpenEndSessionDialog: () -> Unit,
    onDismissEndSessionDialog: () -> Unit,
): Unit {
    ReferenceSectionCard(title = "End or export") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReferenceBadge(
                label =
                    when {
                        uiState.viewingRetained -> "Viewing retained session"
                        uiState.isCurrentSessionEnded -> "Viewing ended session"
                        else -> "Viewing live session"
                    },
                prominent = !uiState.viewingRetained,
            )
            ReferenceBadge(label = "Visible ${uiState.visibleEntries.size}")
            ReferenceBadge(label = "Retained ${uiState.retainedSessions.size}")
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.isSupportedLiveSession && !uiState.viewingRetained) {
                Button(onClick = onOpenEndSessionDialog) { Text("End session") }
            }
            if (uiState.showStartNewSession && !uiState.viewingRetained) {
                Button(onClick = { store.startNewSupportedSession() }) { Text("Start new session") }
            }
            if (uiState.viewingRetained) {
                Button(onClick = store::returnToLive) { Text("Return to live") }
            }
            Button(onClick = onOpenExportDialog) { Text("Export session") }
        }
        if (uiState.viewingRetained) {
            Text(
                text =
                    "Return to the live session before starting another supported session or using full-payload export.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (uiState.isCurrentSessionEnded) {
            Text(
                text =
                    "This supported session is closed. Review the timeline, export a redacted artifact, or start a new session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showEndSessionDialog) {
            SessionBoundaryDialog(
                title = "End supported session",
                body =
                    "Ending the session closes the current evidence window and immediately retains eligible evidence in recent history.",
                exportLabel = "Export full and end",
                continueLabel = "End without full export",
                onExportAndContinue = {
                    store.endCurrentSession(
                        preEndExportPolicy = ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN
                    )
                    onDismissEndSessionDialog()
                },
                onContinueWithoutExport = {
                    store.endCurrentSession()
                    onDismissEndSessionDialog()
                },
                onCancel = onDismissEndSessionDialog,
            )
        }
        if (showExportDialog) {
            ExportSessionDialog(
                onExport = { policy ->
                    store.exportCurrentSession(policy)
                    onDismissExportDialog()
                },
                onDismiss = onDismissExportDialog,
                allowFullPayload = uiState.allowFullPayloadExport,
            )
        }
        if (uiState.lastExportPath != null) {
            Text(
                text = "Last export: ${uiState.lastExportPath}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineFilterSection(
    uiState: TechnicalTimelineUiState,
    availableFamilies: List<TimelineFamily>,
    availablePeerSuffixes: List<String>,
    availableSeverities: List<TimelineSeverity>,
    store: TechnicalTimelineStore,
): Unit {
    ReferenceSectionCard(
        title = "Filter events",
        subtitle =
            "Search the current session and narrow the timeline by family, severity, or peer when you want a smaller operator view.",
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
                selected =
                    uiState.filters.searchText.isBlank() &&
                        uiState.filters.family == null &&
                        uiState.filters.severity == null &&
                        uiState.filters.peerSuffix == null,
                onClick = store::clearFilters,
                label = { Text("All") },
            )
            availableFamilies.forEach { family ->
                FilterChip(
                    selected = uiState.filters.family == family,
                    onClick = {
                        store.updateFamily(if (uiState.filters.family == family) null else family)
                    },
                    label = { Text(referenceTimelineFamilyLabel(family)) },
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            availableSeverities.forEach { severity ->
                FilterChip(
                    selected = uiState.filters.severity == severity,
                    onClick = {
                        store.updateSeverity(
                            if (uiState.filters.severity == severity) null else severity
                        )
                    },
                    label = { Text(referenceTimelineSeverityLabel(severity)) },
                )
            }
        }
        if (availablePeerSuffixes.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                availablePeerSuffixes.forEach { peerSuffix ->
                    FilterChip(
                        selected = uiState.filters.peerSuffix == peerSuffix,
                        onClick = {
                            store.updatePeer(
                                if (uiState.filters.peerSuffix == peerSuffix) null else peerSuffix
                            )
                        },
                        label = { Text("Peer $peerSuffix") },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTimelineSection(): Unit {
    ReferenceSectionCard(
        title = "No matching events",
        subtitle =
            "Clear the current filters or keep running the session until new diagnostics arrive.",
    ) {
        Text(
            text = "Nothing matches the current search, family, severity, and peer filters.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineEntryCard(entry: TimelineEntry): Unit {
    ReferenceSectionCard(title = entry.title, subtitle = entry.detail) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReferenceBadge(label = referenceTimelineFamilyLabel(entry.family))
            ReferenceBadge(label = referenceTimelineSeverityLabel(entry.severity))
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
