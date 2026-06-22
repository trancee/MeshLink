package ch.trancee.meshlink.reference.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ch.trancee.meshlink.reference.advanced.AdvancedControlsViewModel
import ch.trancee.meshlink.reference.automation.LiveProofAutomationDriver
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfigView
import ch.trancee.meshlink.reference.automation.TimelineStoreLiveProofAutomationActions
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeViewModel
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceSessionController
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore

internal data class ReferenceNavHostDependencies(
    val sessionController: ReferenceSessionController,
    val guidedViewModel: GuidedFirstExchangeViewModel,
    val advancedViewModel: AdvancedControlsViewModel,
    val timelineStore: TechnicalTimelineStore,
    val sessionTransitionService: SessionTransitionService,
    val liveProofAutomationDriver: LiveProofAutomationDriver,
)

@Composable
internal fun rememberReferenceNavHostDependencies(
    platformName: String,
    readinessGuidance: List<String>,
    readinessBlockers: List<String>,
    powerMitigationStatus: String?,
    documentStore: Any?,
    meshLinkController: ReferenceMeshLinkController,
    stopPowerMitigation: () -> Unit,
    currentTimeMillis: () -> Long,
    emitAutomationLog: (String) -> Unit,
    createSupportedMeshLinkController: (String) -> ReferenceMeshLinkController = {
        meshLinkController
    },
    automationConfig: ReferenceAutomationConfigView? = null,
): ReferenceNavHostDependencies {
    val retainedDocumentStore =
        remember(platformName) {
            (documentStore as? ReferenceDocumentStore) ?: InMemoryReferenceDocumentStore()
        }
    val historyRepository =
        remember(platformName) { JsonSessionHistoryRepository(retainedDocumentStore) }
    val artifactSerializer =
        remember(platformName) { JsonSessionArtifactSerializer(retainedDocumentStore) }
    val sessionController =
        remember(platformName) {
            ReferenceSessionController(
                platformName = platformName,
                nowProvider = currentTimeMillis,
                supportedControllerFactory = createSupportedMeshLinkController,
                emitAutomationLog = emitAutomationLog,
            )
        }
    val guidedViewModel =
        remember(platformName) {
            GuidedFirstExchangeViewModel(
                platformName = platformName,
                readinessGuidance = readinessGuidance,
                readinessBlockers = readinessBlockers,
                powerMitigationStatus = powerMitigationStatus,
                meshLinkController = meshLinkController,
                automationMode = automationConfig?.mode,
            )
        }
    val advancedViewModel =
        remember(platformName) {
            AdvancedControlsViewModel(
                platformName = platformName,
                meshLinkController = meshLinkController,
            )
        }
    val timelineStore =
        remember(platformName) {
            TechnicalTimelineStore(
                platformName = platformName,
                readinessBlockers = readinessBlockers,
                meshLinkController = meshLinkController,
                currentTimeMillis = currentTimeMillis,
                historyRepository = historyRepository,
                artifactSerializer = artifactSerializer,
            )
        }
    val sessionTransitionService =
        remember(platformName) {
            SessionTransitionService(
                timelineStore = timelineStore,
                sessionController = sessionController,
                stopPowerMitigation = stopPowerMitigation,
                currentTimeMillis = currentTimeMillis,
            )
        }
    val liveProofAutomationDriver =
        remember(platformName) {
            LiveProofAutomationDriver(
                automationConfig = automationConfig,
                timelineUiStateFlow = timelineStore.uiState,
                actions =
                    TimelineStoreLiveProofAutomationActions(
                        platformNameValue = platformName,
                        readinessBlockersValue = readinessBlockers,
                        emitAutomationLogAction = emitAutomationLog,
                        meshLinkController = meshLinkController,
                        timelineStore = timelineStore,
                        sessionTransitionService = sessionTransitionService,
                    ),
            )
        }

    return ReferenceNavHostDependencies(
        sessionController = sessionController,
        guidedViewModel = guidedViewModel,
        advancedViewModel = advancedViewModel,
        timelineStore = timelineStore,
        sessionTransitionService = sessionTransitionService,
        liveProofAutomationDriver = liveProofAutomationDriver,
    )
}
