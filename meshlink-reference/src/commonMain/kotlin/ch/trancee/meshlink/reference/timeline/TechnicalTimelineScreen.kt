package ch.trancee.meshlink.reference.timeline

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy

@Composable
public fun TechnicalTimelineScreen(store: TechnicalTimelineStore, modifier: Modifier = Modifier) {
    val uiState by store.uiState.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(20.dp).testTag("timeline-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(text = "Technical timeline", style = MaterialTheme.typography.headlineSmall) }
        item {
            OutlinedTextField(
                value = uiState.filters.searchText,
                onValueChange = store::updateSearch,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search events") },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = store::retainCurrentSession) { Text("Retain session") }
                Button(
                    onClick = { store.exportCurrentSession(ExportPayloadPolicy.REDACTED_PREVIEW) }
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
        }
        item {
            if (uiState.lastExportPath != null) {
                Text(
                    text = "Last export: ${uiState.lastExportPath}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(uiState.visibleEntries) { entry ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = entry.title, style = MaterialTheme.typography.titleLarge)
                    Text(text = entry.detail, style = MaterialTheme.typography.bodyMedium)
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
}
