package ch.trancee.meshlink.reference.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.ReferenceSessionController
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import ch.trancee.meshlink.reference.timeline.transitionAlternativeSessionNow
import ch.trancee.meshlink.reference.timeline.transitionToLabSessionNow
import ch.trancee.meshlink.reference.timeline.transitionToSoloSessionNow

@Composable
internal fun rememberReferenceWorkflowTitles(): Map<ReferenceSurfaceId, String> {
    return remember {
        ReferenceWorkflowCatalog.descriptors().associate { descriptor ->
            descriptor.surfaceId to descriptor.title
        }
    }
}

@Composable
internal fun rememberLastRouteBySection(): MutableMap<ReferencePrimarySection, ReferenceSurfaceId> {
    return remember {
        mutableStateMapOf<ReferencePrimarySection, ReferenceSurfaceId>().apply {
            ReferencePrimarySection.entries.forEach { section ->
                put(section, section.defaultSurface)
            }
        }
    }
}

internal suspend fun startAlternativeSession(
    surface: ReferenceSurfaceId,
    sessionController: ReferenceSessionController,
): Unit {
    when (surface) {
        ReferenceSurfaceId.SOLO_EXPLORATION -> sessionController.startSoloSession()
        ReferenceSurfaceId.LAB -> sessionController.startLabSession()
        else -> Unit
    }
}

internal fun followUpSupportedEntrySurface(
    currentSnapshot: ReferenceControllerSnapshot
): ReferenceSurfaceId {
    return when (currentSnapshot.session.configurationSnapshot["surface"]) {
        ReferenceSurfaceId.ADVANCED_CONTROLS.route -> ReferenceSurfaceId.ADVANCED_CONTROLS
        else -> ReferenceSurfaceId.MAIN_GUIDED
    }
}

internal fun followUpSupportedSessionLabel(currentSnapshot: ReferenceControllerSnapshot): String {
    return when (followUpSupportedEntrySurface(currentSnapshot)) {
        ReferenceSurfaceId.ADVANCED_CONTROLS -> "Start new advanced session"
        else -> "Start new guided session"
    }
}

internal suspend fun startFollowUpSupportedSession(
    currentSnapshot: ReferenceControllerSnapshot,
    applySurfaceSelection: (ReferenceSurfaceId) -> Unit,
    startSupportedSession: suspend (ReferenceSurfaceId) -> Unit,
): Unit {
    val targetSurface = followUpSupportedEntrySurface(currentSnapshot)
    applySurfaceSelection(targetSurface)
    startSupportedSession(targetSurface)
}

internal suspend fun handleBoundaryConfirmation(
    request: SessionBoundaryRequest,
    exportFirst: Boolean,
    timelineStore: TechnicalTimelineStore,
    applySurfaceSelection: (ReferenceSurfaceId) -> Unit,
): Unit {
    when (request) {
        is SessionBoundaryRequest.SupportedTo -> {
            applySurfaceSelection(request.targetSurface)
            when (request.targetSurface) {
                ReferenceSurfaceId.SOLO_EXPLORATION ->
                    timelineStore.transitionToSoloSessionNow(
                        preBoundaryExportPolicy =
                            if (exportFirst) {
                                ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN
                            } else {
                                null
                            }
                    )
                ReferenceSurfaceId.LAB ->
                    timelineStore.transitionToLabSessionNow(
                        preBoundaryExportPolicy =
                            if (exportFirst) {
                                ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN
                            } else {
                                null
                            }
                    )
                else -> Unit
            }
        }

        is SessionBoundaryRequest.AlternativeTo -> {
            applySurfaceSelection(request.targetSurface)
            timelineStore.transitionAlternativeSessionNow(
                targetSurface = request.targetSurface,
                exportBeforeExit = exportFirst,
            )
        }
    }
}
