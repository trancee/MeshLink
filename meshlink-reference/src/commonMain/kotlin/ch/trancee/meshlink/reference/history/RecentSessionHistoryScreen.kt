@file:Suppress("FunctionNaming")

package ch.trancee.meshlink.reference.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecentSessionHistoryScreen(
    store: TechnicalTimelineStore,
    modifier: Modifier = Modifier,
) {
    val uiState by store.uiState.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(20.dp).testTag("history-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(text = "Recent history", style = MaterialTheme.typography.headlineSmall) }
        item { historyIntroText() }
        item { historyActionRow(store = store, uiState = uiState) }

        if (uiState.retainedSessions.isEmpty()) {
            item { emptyHistoryCard() }
        }

        items(items = uiState.retainedSessions, key = { session -> session.sessionId }) { session ->
            retainedSessionCard(store = store, session = session)
        }
    }
}
