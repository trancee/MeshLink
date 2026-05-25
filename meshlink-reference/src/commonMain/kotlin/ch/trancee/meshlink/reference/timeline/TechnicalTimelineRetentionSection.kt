package ch.trancee.meshlink.reference.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.design.ReferenceBadge
import ch.trancee.meshlink.reference.design.ReferenceSectionCard
import ch.trancee.meshlink.reference.export.ExportSessionDialog
import ch.trancee.meshlink.reference.navigation.SessionBoundaryDialog
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimelineRetentionSection(
    uiState: TechnicalTimelineUiState,
    store: TechnicalTimelineStore,
    followUpSupportedSessionLabel: String,
    onStartFollowUpSupportedSession: () -> Unit,
    showExportDialog: Boolean,
    showEndSessionDialog: Boolean,
    onOpenExportDialog: () -> Unit,
    onDismissExportDialog: () -> Unit,
    onOpenEndSessionDialog: () -> Unit,
    onDismissEndSessionDialog: () -> Unit,
): Unit {
    ReferenceSectionCard(title = "End or export") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReferenceBadge(
                label =
                    when {
                        uiState.viewingRetained -> "Viewing retained session"
                        uiState.isCurrentSessionEnded -> "Viewing ended session"
                        else -> "Viewing live session"
                    },
                prominent = !uiState.viewingRetained,
            )
            ReferenceBadge(label = "Visible ${uiState.visibleEntries.size}")
            ReferenceBadge(label = "Retained ${uiState.retainedSessions.size}")
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.isSupportedLiveSession && !uiState.viewingRetained) {
                Button(onClick = onOpenEndSessionDialog) { Text("End session") }
            }
            if (uiState.showStartNewSession && !uiState.viewingRetained) {
                Button(onClick = onStartFollowUpSupportedSession) {
                    Text(followUpSupportedSessionLabel)
                }
            }
            if (uiState.viewingRetained) {
                Button(onClick = store::returnToLive) { Text("Return to live") }
            }
            Button(onClick = onOpenExportDialog) { Text("Export session") }
        }
        if (uiState.viewingRetained) {
            Text(
                text =
                    "Return to the live session before starting another supported session or using full-payload export.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (uiState.isCurrentSessionEnded) {
            Text(
                text =
                    "This supported session is closed. Review the timeline, export a redacted artifact, or start the next supported session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showEndSessionDialog) {
            SessionBoundaryDialog(
                title = "End supported session",
                body =
                    "Ending the session closes the current evidence window and immediately retains eligible evidence in recent history.",
                exportLabel = "Export full and end",
                continueLabel = "End without full export",
                onExportAndContinue = {
                    store.endCurrentSession(
                        preEndExportPolicy = ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN
                    )
                    onDismissEndSessionDialog()
                },
                onContinueWithoutExport = {
                    store.endCurrentSession()
                    onDismissEndSessionDialog()
                },
                onCancel = onDismissEndSessionDialog,
            )
        }
        if (showExportDialog) {
            ExportSessionDialog(
                onExport = { policy ->
                    store.exportCurrentSession(policy)
                    onDismissExportDialog()
                },
                onDismiss = onDismissExportDialog,
                allowFullPayload = uiState.allowFullPayloadExport,
            )
        }
        if (uiState.lastExportPath != null) {
            Text(
                text = "Last export: ${uiState.lastExportPath}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
