package ch.trancee.meshlink.reference.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.design.ReferenceBadge
import ch.trancee.meshlink.reference.design.ReferenceSectionCard
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore

@OptIn(ExperimentalLayoutApi::class)
@Composable
public fun RecentSessionHistoryScreen(
    store: TechnicalTimelineStore,
    modifier: Modifier = Modifier,
) {
    val uiState by store.uiState.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(20.dp).testTag("history-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(text = "Recent history", style = MaterialTheme.typography.headlineSmall) }
        item {
            Text(
                text =
                    "Retained sessions stay separate from the live run so you can reopen operator evidence without disturbing the current session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
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
                        onClick = {
                            store.openRetainedSession(uiState.retainedSessions.first().sessionId)
                        }
                    ) {
                        Text("Open")
                    }
                }
                Button(onClick = store::clearHistory) { Text("Clear all") }
                if (uiState.viewingRetained) {
                    Button(onClick = store::returnToLive) { Text("Return to live") }
                }
            }
        }
        if (uiState.retainedSessions.isEmpty()) {
            item {
                ReferenceSectionCard(
                    title = "No retained sessions yet",
                    subtitle =
                        "Retain a completed timeline first, then come back here to reopen or delete it.",
                ) {
                    Text(
                        text =
                            "This list stays empty until you retain a session from the technical timeline.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        items(uiState.retainedSessions) { session ->
            ReferenceSectionCard(
                title = "Session ${session.sessionId.takeLast(8)}",
                subtitle = "Scenario ${session.scenarioId} · authority ${session.authorityMode}",
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(onClick = { store.openRetainedSession(session.sessionId) }) {
                        Text("Open")
                    }
                    Button(onClick = { store.deleteRetainedSession(session.sessionId) }) {
                        Text("Delete")
                    }
                }
                Text(
                    text = "State: ${session.meshStateLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Outcome: ${session.lastOutcomeSummary ?: "none"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
