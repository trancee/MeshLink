package ch.trancee.meshlink.reference.guided

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shared guided first-exchange surface. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuidedFirstExchangeScreen(
    viewModel: GuidedFirstExchangeViewModel,
    onOpenSolo: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    GuidedFirstExchangeContent(
        uiState = uiState,
        powerMitigationLabel = viewModel.powerMitigationLabel,
        onStartMesh = viewModel::startMesh,
        onSendHello = viewModel::sendHelloToFirstPeer,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuidedFirstExchangeContent(
    uiState: GuidedFirstExchangeUiState,
    powerMitigationLabel: String?,
    onStartMesh: () -> Unit,
    onSendHello: () -> Unit,
    modifier: Modifier,
): Unit {
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { GuidedFirstExchangeHeader() }
        item {
            GuidedLiveFirstMessageSection(
                uiState = uiState,
                powerMitigationLabel = powerMitigationLabel,
                onStartMesh = onStartMesh,
                onSendHello = onSendHello,
            )
        }
        if (uiState.readiness.isBlocked) {
            item { GuidedStartupBlockerCard(blockers = uiState.readiness.blockers) }
        }
        item { GuidedReadinessChecklist(items = uiState.readiness.items) }
        item { GuidedRecentTimelineHeader() }
        items(uiState.snapshot.timeline.takeLast(RECENT_GUIDED_TIMELINE_ENTRY_COUNT)) { entry ->
            GuidedTimelineEntryCard(entry = entry)
        }
    }
}
