@file:Suppress("FunctionNaming")

package ch.trancee.meshlink.reference.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.design.ReferenceBadge
import ch.trancee.meshlink.reference.design.ReferenceSectionCard
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.referenceAuthorityLabel
import ch.trancee.meshlink.reference.model.referenceOutcomeLabel
import ch.trancee.meshlink.reference.model.referenceScenarioTitle
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineUiState

@Composable
internal fun historyIntroText(): Unit {
    Text(
        text =
            "Retained sessions stay separate from the live run so you can reopen " +
                "operator evidence without disturbing the current session.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun historyActionRow(
    store: TechnicalTimelineStore,
    uiState: TechnicalTimelineUiState,
): Unit {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ReferenceBadge(label = "Sessions ${uiState.retainedSessions.size}")
        if (uiState.viewingRetained) {
            ReferenceBadge(label = "Retained session open", prominent = true)
        }
        if (uiState.retainedSessions.isNotEmpty() && !uiState.viewingRetained) {
            Button(
                onClick = { store.openRetainedSession(uiState.retainedSessions.first().sessionId) }
            ) {
                Text("Open")
            }
        }
        Button(onClick = store::clearRetainedSessions) { Text("Clear all") }
        if (uiState.viewingRetained) {
            Button(onClick = store::openLiveSession) { Text("Return to live") }
        }
    }
}

@Composable
internal fun emptyHistoryCard(): Unit {
    ReferenceSectionCard(
        title = "No retained sessions yet",
        subtitle =
            "Eligible sessions appear here automatically after they end, then you can reopen or delete them later.",
    ) {
        Text(
            text =
                "This list stays empty until a supported ended session produces reviewable evidence and is retained automatically.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun retainedSessionCard(store: TechnicalTimelineStore, session: ReferenceSession): Unit {
    ReferenceSectionCard(
        title = "Session ${session.sessionId.takeLast(SESSION_ID_SUFFIX_LENGTH)}",
        subtitle =
            "${referenceScenarioTitle(session.scenarioId)} · ${referenceAuthorityLabel(session.authorityMode)}",
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = { store.openRetainedSession(session.sessionId) }) { Text("Open") }
            Button(onClick = { store.deleteRetainedSession(session.sessionId) }) { Text("Delete") }
        }
        Text(text = "State: ${session.meshStateLabel}", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "Outcome: ${referenceOutcomeLabel(session.lastOutcomeSummary) ?: "none yet"}",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private const val SESSION_ID_SUFFIX_LENGTH: Int = 8
