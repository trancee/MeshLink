package ch.trancee.meshlink.reference.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore

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
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = "Recent history", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = store::clearHistory) { Text("Clear all") }
                if (uiState.viewingRetained) {
                    Button(onClick = store::returnToLive) { Text("Return to live") }
                }
            }
        }
        items(uiState.retainedSessions) { session ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = session.sessionId, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "State: ${session.meshStateLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "Outcome: ${session.lastOutcomeSummary ?: "none"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { store.openRetainedSession(session.sessionId) }) {
                            Text("Open")
                        }
                        Button(onClick = { store.deleteRetainedSession(session.sessionId) }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}
