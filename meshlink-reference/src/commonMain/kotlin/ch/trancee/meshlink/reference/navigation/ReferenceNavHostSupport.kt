package ch.trancee.meshlink.reference.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.ReferenceSessionController
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import ch.trancee.meshlink.reference.timeline.transitionAlternativeSession
import ch.trancee.meshlink.reference.timeline.transitionToLabSession
import ch.trancee.meshlink.reference.timeline.transitionToSoloSession

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

internal fun handleBoundaryConfirmation(
    request: SessionBoundaryRequest,
    exportFirst: Boolean,
    timelineStore: TechnicalTimelineStore,
    applySurfaceSelection: (ReferenceSurfaceId) -> Unit,
): Unit {
    when (request) {
        is SessionBoundaryRequest.SupportedTo -> {
            when (request.targetSurface) {
                ReferenceSurfaceId.SOLO_EXPLORATION ->
                    timelineStore.transitionToSoloSession(
                        preBoundaryExportPolicy =
                            if (exportFirst) {
                                ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN
                            } else {
                                null
                            }
                    )
                ReferenceSurfaceId.LAB ->
                    timelineStore.transitionToLabSession(
                        preBoundaryExportPolicy =
                            if (exportFirst) {
                                ExportPayloadPolicy.FULL_PAYLOAD_OPT_IN
                            } else {
                                null
                            }
                    )
                else -> Unit
            }
            applySurfaceSelection(request.targetSurface)
        }

        is SessionBoundaryRequest.AlternativeTo -> {
            timelineStore.transitionAlternativeSession(
                targetSurface = request.targetSurface,
                exportBeforeExit = exportFirst,
            )
            applySurfaceSelection(request.targetSurface)
        }
    }
}
