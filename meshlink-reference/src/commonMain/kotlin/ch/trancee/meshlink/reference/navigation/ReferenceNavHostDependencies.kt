package ch.trancee.meshlink.reference.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ch.trancee.meshlink.reference.advanced.AdvancedControlsViewModel
import ch.trancee.meshlink.reference.automation.LiveProofAutomationDriver
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfigView
import ch.trancee.meshlink.reference.automation.TimelineStoreLiveProofAutomationActions
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeViewModel
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceSessionController
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore

internal data class ReferenceNavHostDependencies(
    val sessionController: ReferenceSessionController,
    val sessionPlatformServices: SessionAwarePlatformServices,
    val guidedViewModel: GuidedFirstExchangeViewModel,
    val advancedViewModel: AdvancedControlsViewModel,
    val timelineStore: TechnicalTimelineStore,
    val sessionTransitionService: SessionTransitionService,
    val liveProofAutomationDriver: LiveProofAutomationDriver,
)

@Composable
internal fun rememberReferenceNavHostDependencies(
    platformServices: PlatformServices,
    automationConfig: ReferenceAutomationConfigView? = null,
): ReferenceNavHostDependencies {
    val retainedDocumentStore =
        remember(platformServices.platformName) {
            (platformServices.documentStore as? ReferenceDocumentStore)
                ?: InMemoryReferenceDocumentStore()
        }
    val historyRepository =
        remember(platformServices.platformName) {
            JsonSessionHistoryRepository(retainedDocumentStore)
        }
    val artifactSerializer =
        remember(platformServices.platformName) {
            JsonSessionArtifactSerializer(retainedDocumentStore)
        }
    val sessionController =
        remember(platformServices.platformName) {
            ReferenceSessionController(
                platformName = platformServices.platformName,
                nowProvider = platformServices::currentTimeMillis,
                supportedControllerFactory = platformServices::createSupportedMeshLinkController,
                emitAutomationLog = platformServices::emitAutomationLog,
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
            GuidedFirstExchangeViewModel(
                platformServices = sessionPlatformServices,
                automationMode = automationConfig?.mode,
            )
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
            )
        }
    val sessionTransitionService =
        remember(platformServices.platformName) {
            SessionTransitionService(
                timelineStore = timelineStore,
                sessionController = sessionController,
                platformServices = sessionPlatformServices,
                currentTimeMillis = sessionPlatformServices::currentTimeMillis,
            )
        }
    val liveProofAutomationDriver =
        remember(platformServices.platformName) {
            LiveProofAutomationDriver(
                automationConfig = automationConfig,
                timelineUiStateFlow = timelineStore.uiState,
                actions =
                    TimelineStoreLiveProofAutomationActions(
                        platformServices = sessionPlatformServices,
                        timelineStore = timelineStore,
                        sessionTransitionService = sessionTransitionService,
                    ),
            )
        }

    return ReferenceNavHostDependencies(
        sessionController = sessionController,
        sessionPlatformServices = sessionPlatformServices,
        guidedViewModel = guidedViewModel,
        advancedViewModel = advancedViewModel,
        timelineStore = timelineStore,
        sessionTransitionService = sessionTransitionService,
        liveProofAutomationDriver = liveProofAutomationDriver,
    )
}
