package ch.trancee.meshlink.reference.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TechnicalTimelineScreen(
    store: TechnicalTimelineStore,
    followUpSupportedSessionLabel: String,
    onStartFollowUpSupportedSession: () -> Unit,
    onEndSupportedSession: (ExportPayloadPolicy?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by store.uiState.collectAsState()
    val availableFamilies = rememberAvailableFamilies(uiState)
    val availablePeerSuffixes = rememberAvailablePeerSuffixes(uiState)
    val availableSeverities = rememberAvailableSeverities(uiState)

    TechnicalTimelineContent(
        uiState = uiState,
        availableFamilies = availableFamilies,
        availablePeerSuffixes = availablePeerSuffixes,
        availableSeverities = availableSeverities,
        store = store,
        followUpSupportedSessionLabel = followUpSupportedSessionLabel,
        onStartFollowUpSupportedSession = onStartFollowUpSupportedSession,
        onEndSupportedSession = onEndSupportedSession,
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
    followUpSupportedSessionLabel: String,
    onStartFollowUpSupportedSession: () -> Unit,
    onEndSupportedSession: (ExportPayloadPolicy?) -> Unit,
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
                followUpSupportedSessionLabel = followUpSupportedSessionLabel,
                onStartFollowUpSupportedSession = onStartFollowUpSupportedSession,
                onEndSupportedSession = onEndSupportedSession,
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
private fun rememberAvailableFamilies(uiState: TechnicalTimelineUiState): List<TimelineFamily> {
    return remember(uiState.currentSnapshot.timeline) {
        uiState.currentSnapshot.timeline.map { entry -> entry.family }.distinct()
    }
}

@Composable
private fun rememberAvailablePeerSuffixes(uiState: TechnicalTimelineUiState): List<String> {
    return remember(uiState.currentSnapshot.timeline) {
        uiState.currentSnapshot.timeline.mapNotNull { entry -> entry.peerSuffix }.distinct()
    }
}

@Composable
private fun rememberAvailableSeverities(uiState: TechnicalTimelineUiState): List<TimelineSeverity> {
    return remember(uiState.currentSnapshot.timeline) {
        uiState.currentSnapshot.timeline.map { entry -> entry.severity }.distinct()
    }
}
