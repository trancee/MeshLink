package ch.trancee.meshlink.reference.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy

@Composable
public fun ExportSessionDialog(
    onExport: (ExportPayloadPolicy) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Export session", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Redacted previews are the default. Full payloads require explicit operator opt-in.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onExport(ExportPayloadPolicy.REDACTED_PREVIEW) }) {
                    Text("Redacted")
                }
                Button(onClick = { onExport(ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN) }) {
                    Text("Full payload")
                }
            }
        }
    }
}
