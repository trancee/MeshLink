package ch.trancee.meshlink.reference.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.trancee.meshlink.reference.advanced.AdvancedControlsScreen
import ch.trancee.meshlink.reference.advanced.AdvancedControlsViewModel
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeScreen
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeViewModel
import ch.trancee.meshlink.reference.history.RecentSessionHistoryScreen
import ch.trancee.meshlink.reference.lab.LabScreen
import ch.trancee.meshlink.reference.solo.SoloExplorationScreen
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineScreen
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore

@Composable
internal fun ReferenceRouteContent(
    contentState: ReferenceRouteContentState,
    followUpSupportedSessionLabel: String,
    onStartFollowUpSupportedSession: () -> Unit,
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
                followUpSupportedSessionLabel = followUpSupportedSessionLabel,
                onStartFollowUpSupportedSession = onStartFollowUpSupportedSession,
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

internal data class ReferenceRouteContentState(
    val activeRoute: ReferenceSurfaceId,
    val guidedViewModel: GuidedFirstExchangeViewModel,
    val advancedViewModel: AdvancedControlsViewModel,
    val timelineStore: TechnicalTimelineStore,
)
