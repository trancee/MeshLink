package ch.trancee.meshlink.reference.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import ch.trancee.meshlink.reference.advanced.AdvancedControlsViewModel
import ch.trancee.meshlink.reference.automation.LiveProofAutomationDriver
import ch.trancee.meshlink.reference.automation.TimelineStoreLiveProofAutomationActions
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeViewModel
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
import ch.trancee.meshlink.reference.session.ReferenceSessionController
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import ch.trancee.meshlink.reference.timeline.transitionAlternativeSession
import ch.trancee.meshlink.reference.timeline.transitionToLabSession
import ch.trancee.meshlink.reference.timeline.transitionToSoloSession

internal data class ReferenceNavHostDependencies(
    val sessionController: ReferenceSessionController,
    val sessionPlatformServices: SessionAwarePlatformServices,
    val guidedViewModel: GuidedFirstExchangeViewModel,
    val advancedViewModel: AdvancedControlsViewModel,
    val timelineStore: TechnicalTimelineStore,
    val liveProofAutomationDriver: LiveProofAutomationDriver,
)

@Composable
internal fun rememberReferenceNavHostDependencies(
    platformServices: PlatformServices
): ReferenceNavHostDependencies {
    val historyRepository =
        remember(platformServices.platformName) {
            JsonSessionHistoryRepository(platformServices.documentStore)
        }
    val artifactSerializer =
        remember(platformServices.platformName) {
            JsonSessionArtifactSerializer(platformServices.documentStore)
        }
    val sessionController =
        remember(platformServices.platformName) {
            ReferenceSessionController(
                platformName = platformServices.platformName,
                nowProvider = platformServices::currentTimeMillis,
                supportedControllerFactory = platformServices::createSupportedMeshLinkController,
            )
        }
    val sessionPlatformServices =
        remember(platformServices.platformName) {
            SessionAwarePlatformServices(
                delegate = platformServices,
                sessionController = sessionController,
            )
        }
    val guidedViewModel =
        remember(platformServices.platformName) {
            GuidedFirstExchangeViewModel(sessionPlatformServices)
        }
    val advancedViewModel =
        remember(platformServices.platformName) {
            AdvancedControlsViewModel(sessionPlatformServices)
        }
    val timelineStore =
        remember(platformServices.platformName) {
            TechnicalTimelineStore(
                platformServices = sessionPlatformServices,
                historyRepository = historyRepository,
                artifactSerializer = artifactSerializer,
                sessionController = sessionController,
            )
        }
    val liveProofAutomationDriver =
        remember(platformServices.platformName) {
            LiveProofAutomationDriver(
                automationConfig = sessionPlatformServices.automationConfig,
                timelineUiStateFlow = timelineStore.uiState,
                actions =
                    TimelineStoreLiveProofAutomationActions(
                        platformServices = sessionPlatformServices,
                        timelineStore = timelineStore,
                    ),
            )
        }

    return ReferenceNavHostDependencies(
        sessionController = sessionController,
        sessionPlatformServices = sessionPlatformServices,
        guidedViewModel = guidedViewModel,
        advancedViewModel = advancedViewModel,
        timelineStore = timelineStore,
        liveProofAutomationDriver = liveProofAutomationDriver,
    )
}

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
