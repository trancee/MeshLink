package ch.trancee.meshlink.reference.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy

@Composable
internal fun ReferenceShellScaffold(
    headerState: ReferenceShellHeaderState,
    contentState: ReferenceRouteContentState,
    followUpSupportedSessionLabel: String,
    onStartFollowUpSupportedSession: () -> Unit,
    onEndSupportedSession: (ExportPayloadPolicy?) -> Unit,
    pendingBoundary: SessionBoundaryRequest?,
    onDismissBoundary: () -> Unit,
    onCompleteBoundary: (SessionBoundaryRequest, BoundaryContinuation) -> Unit,
    onSelectSurface: (ReferenceSurface) -> Unit,
    onSelectSection: (ReferencePrimarySection) -> Unit,
): Unit {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ReferenceShellHeader(state = headerState, onSelectSurface = onSelectSurface)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                ReferenceRouteContent(
                    contentState = contentState,
                    followUpSupportedSessionLabel = followUpSupportedSessionLabel,
                    onStartFollowUpSupportedSession = onStartFollowUpSupportedSession,
                    onEndSupportedSession = onEndSupportedSession,
                    onOpenSolo = { onSelectSurface(ReferenceSurface.SOLO_EXPLORATION) },
                )
            }
            ReferenceBottomBar(
                activeSection = headerState.activeSection,
                onSelectSection = onSelectSection,
            )
        }
        pendingBoundary?.let { boundary ->
            val dialogContent = boundary.toDialogContent()
            SessionBoundaryDialog(
                title = dialogContent.title,
                body = dialogContent.body,
                exportLabel = dialogContent.exportLabel,
                continueLabel = dialogContent.continueLabel,
                onExportAndContinue = {
                    onCompleteBoundary(boundary, BoundaryContinuation.EXPORT_AND_CONTINUE)
                },
                onContinueWithoutExport = {
                    onCompleteBoundary(boundary, BoundaryContinuation.CONTINUE_WITHOUT_EXPORT)
                },
                onCancel = onDismissBoundary,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
