package ch.trancee.meshlink.reference.advanced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.api.DeliveryPriority

@Composable
public fun SendComposer(
    state: AdvancedControlsUiState,
    onTextChanged: (String) -> Unit,
    onPriorityChanged: (DeliveryPriority) -> Unit,
    onSend: () -> Unit,
    onSendLargeTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Send composer", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = state.composerText,
            onValueChange = onTextChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Payload text") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeliveryPriority.entries.forEach { priority ->
                AssistChip(
                    onClick = { onPriorityChanged(priority) },
                    label = { Text(priority.name) },
                    enabled = state.selectedPriority != priority,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSend, enabled = state.canSend) { Text("Send message") }
            Button(onClick = onSendLargeTransfer, enabled = state.canSend) {
                Text("Send large transfer")
            }
        }
    }
}
