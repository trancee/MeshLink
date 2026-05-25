@file:Suppress("FunctionNaming")

package ch.trancee.meshlink.reference.advanced

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.model.referencePriorityLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SendComposer(
    state: AdvancedControlsUiState,
    actions: SendComposerActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = "Send composer", style = MaterialTheme.typography.titleLarge)
        Text(
            text = composerTargetText(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.composerText,
            onValueChange = actions.onTextChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Payload text") },
            isError = state.payloadValidationMessage != null,
            supportingText = { Text(text = sendComposerSupportingText(state)) },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DeliveryPriority.entries.forEach { priority ->
                FilterChip(
                    selected = state.selectedPriority == priority,
                    onClick = { actions.onPriorityChanged(priority) },
                    label = { Text(referencePriorityLabel(priority)) },
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = actions.onSend,
                enabled = state.canSendMessage,
                modifier =
                    Modifier.referenceActionAccessibility("Send message", "advanced-send-message"),
            ) {
                Text("Send message")
            }
            Button(
                onClick = actions.onSendLargeTransfer,
                enabled = state.canSendLargeTransfer,
                modifier =
                    Modifier.referenceActionAccessibility(
                        "Send large transfer",
                        "advanced-send-large-transfer",
                    ),
            ) {
                Text("Send large transfer")
            }
        }
    }
}

internal class SendComposerActions(
    public val onTextChanged: (String) -> Unit,
    public val onPriorityChanged: (DeliveryPriority) -> Unit,
    public val onSend: () -> Unit,
    public val onSendLargeTransfer: () -> Unit,
)

private fun composerTargetText(state: AdvancedControlsUiState): String {
    return if (state.selectedPeerId == null) {
        SEND_COMPOSER_IDLE_TEXT
    } else {
        val peerSuffix =
            state.peerRows.firstOrNull { peer -> peer.peerId == state.selectedPeerId }?.peerSuffix
                ?: "selected peer"
        "Targeting $peerSuffix."
    }
}

private fun sendComposerSupportingText(state: AdvancedControlsUiState): String {
    val sizeSummary = "Message size: ${state.payloadSizeBytes} / ${state.payloadLimitBytes} bytes."
    val guidance =
        state.payloadValidationMessage
            ?: "Use the high-priority large transfer button when you want a bigger transport " +
                "preview instead of a short operator message."
    return "$sizeSummary $guidance"
}

private const val SEND_COMPOSER_IDLE_TEXT: String =
    "Select a peer first, then choose whether to send the default operator message " +
        "or a larger transfer preview."
