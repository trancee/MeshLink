package ch.trancee.meshlink.reference.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.advanced.AdvancedControlsScreen
import ch.trancee.meshlink.reference.advanced.AdvancedControlsViewModel
import ch.trancee.meshlink.reference.design.ReferenceBadge
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeScreen
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeViewModel
import ch.trancee.meshlink.reference.history.RecentSessionHistoryScreen
import ch.trancee.meshlink.reference.lab.LabScreen
import ch.trancee.meshlink.reference.resources.Res
import ch.trancee.meshlink.reference.resources.app_title
import ch.trancee.meshlink.reference.resources.mode_label
import ch.trancee.meshlink.reference.resources.platform_label
import ch.trancee.meshlink.reference.solo.SoloExplorationScreen
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineScreen
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ReferenceShellScaffold(
    headerState: ReferenceShellHeaderState,
    contentState: ReferenceRouteContentState,
    pendingBoundary: SessionBoundaryRequest?,
    onDismissBoundary: () -> Unit,
    onConfirmBoundary: (SessionBoundaryRequest, Boolean) -> Unit,
    onSelectSurface: (ReferenceSurfaceId) -> Unit,
    onSelectSection: (ReferencePrimarySection) -> Unit,
): Unit {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ReferenceShellHeader(state = headerState, onSelectSurface = onSelectSurface)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                ReferenceRouteContent(
                    contentState = contentState,
                    onOpenSolo = { onSelectSurface(ReferenceSurfaceId.SOLO_EXPLORATION) },
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
                onExportAndContinue = { onConfirmBoundary(boundary, true) },
                onContinueWithoutExport = { onConfirmBoundary(boundary, false) },
                onCancel = onDismissBoundary,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun ReferenceBottomBar(
    activeSection: ReferencePrimarySection,
    onSelectSection: (ReferencePrimarySection) -> Unit,
): Unit {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            NavigationBar {
                ReferencePrimarySection.entries.forEach { section ->
                    NavigationBarItem(
                        selected = section == activeSection,
                        onClick = { onSelectSection(section) },
                        icon = {
                            Icon(imageVector = section.icon, contentDescription = section.label)
                        },
                        label = { Text(section.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReferenceRouteContent(
    contentState: ReferenceRouteContentState,
    onOpenSolo: () -> Unit,
): Unit {
    when (contentState.activeRoute) {
        ReferenceSurfaceId.MAIN_GUIDED ->
            GuidedFirstExchangeScreen(
                viewModel = contentState.guidedViewModel,
                onOpenSolo = onOpenSolo,
                modifier = Modifier.fillMaxSize(),
            )

        ReferenceSurfaceId.SOLO_EXPLORATION ->
            SoloExplorationScreen(modifier = Modifier.fillMaxSize())

        ReferenceSurfaceId.ADVANCED_CONTROLS ->
            AdvancedControlsScreen(
                viewModel = contentState.advancedViewModel,
                modifier = Modifier.fillMaxSize(),
            )

        ReferenceSurfaceId.TECHNICAL_TIMELINE ->
            TechnicalTimelineScreen(
                store = contentState.timelineStore,
                modifier = Modifier.fillMaxSize(),
            )

        ReferenceSurfaceId.LAB -> LabScreen(modifier = Modifier.fillMaxSize())

        ReferenceSurfaceId.RECENT_HISTORY ->
            RecentSessionHistoryScreen(
                store = contentState.timelineStore,
                modifier = Modifier.fillMaxSize(),
            )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReferenceShellHeader(
    state: ReferenceShellHeaderState,
    onSelectSurface: (ReferenceSurfaceId) -> Unit,
): Unit {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.app_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ReferenceBadge(label = state.activeSection.label, prominent = true)
                ReferenceBadge(
                    label = "${stringResource(Res.string.platform_label)}: ${state.platformName}"
                )
                ReferenceBadge(
                    label = "${stringResource(Res.string.mode_label)}: ${state.authorityModeLabel}"
                )
                ReferenceBadge(label = "Mesh: ${state.meshStateLabel}")
            }
            if (state.activeSection.supportsSubsurfaceSelection) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    state.activeSection.surfaces.forEach { surface ->
                        FilterChip(
                            selected = surface == state.activeRoute,
                            onClick = { onSelectSurface(surface) },
                            label = { Text(state.workflowTitles.getValue(surface)) },
                        )
                    }
                }
            }
        }
    }
}

internal data class ReferenceRouteContentState(
    val activeRoute: ReferenceSurfaceId,
    val guidedViewModel: GuidedFirstExchangeViewModel,
    val advancedViewModel: AdvancedControlsViewModel,
    val timelineStore: TechnicalTimelineStore,
)

internal data class ReferenceShellHeaderState(
    val activeSection: ReferencePrimarySection,
    val activeRoute: ReferenceSurfaceId,
    val workflowTitles: Map<ReferenceSurfaceId, String>,
    val platformName: String,
    val authorityModeLabel: String,
    val meshStateLabel: String,
)
