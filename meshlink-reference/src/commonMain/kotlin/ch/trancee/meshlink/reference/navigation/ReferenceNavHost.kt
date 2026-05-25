package ch.trancee.meshlink.reference.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ch.trancee.meshlink.reference.advanced.AdvancedControlsViewModel
import ch.trancee.meshlink.reference.automation.LiveProofAutomationDriver
import ch.trancee.meshlink.reference.automation.TimelineStoreLiveProofAutomationActions
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeViewModel
import ch.trancee.meshlink.reference.model.referenceAuthorityLabel
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
import ch.trancee.meshlink.reference.session.ReferenceSessionController
import ch.trancee.meshlink.reference.session.referenceSessionKind
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import kotlinx.coroutines.launch

/** Shared navigation shell for the reference app surfaces. */
@Composable
public fun ReferenceNavHost(platformServices: PlatformServices) {
    var activeRoute: ReferenceSurfaceId by remember {
        mutableStateOf(ReferenceSurfaceId.MAIN_GUIDED)
    }
    var pendingBoundary by remember { mutableStateOf<SessionBoundaryRequest?>(null) }
    val coroutineScope = rememberCoroutineScope()
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
    val snapshot by sessionController.snapshot.collectAsState()
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
    val workflowTitles = rememberReferenceWorkflowTitles()
    val lastRouteBySection = rememberLastRouteBySection()

    fun applySurfaceSelection(surface: ReferenceSurfaceId): Unit {
        activeRoute = surface
        lastRouteBySection[primarySectionFor(surface)] = surface
    }

    fun selectSurface(surface: ReferenceSurfaceId): Unit {
        when (
            val decision =
                resolveSurfaceSelection(
                    ReferenceSurfaceSelectionRequest(
                        currentKind = snapshot.referenceSessionKind(),
                        activeRoute = activeRoute,
                        targetSurface = surface,
                    )
                )
        ) {
            is ReferenceSurfaceSelectionDecision.SelectSurface -> {
                applySurfaceSelection(decision.surface)
            }

            is ReferenceSurfaceSelectionDecision.RequireBoundary -> {
                pendingBoundary = decision.request
            }

            is ReferenceSurfaceSelectionDecision.StartNewSession -> {
                coroutineScope.launch {
                    startAlternativeSession(
                        surface = decision.surface,
                        sessionController = sessionController,
                    )
                    applySurfaceSelection(decision.surface)
                }
            }
        }
    }

    fun selectSection(section: ReferencePrimarySection): Unit {
        selectSurface(lastRouteBySection[section] ?: section.defaultSurface)
    }

    val shellHeaderState =
        ReferenceShellHeaderState(
            activeSection = primarySectionFor(activeRoute),
            activeRoute = activeRoute,
            workflowTitles = workflowTitles,
            platformName = platformServices.platformName,
            authorityModeLabel = referenceAuthorityLabel(snapshot.session.authorityMode),
            meshStateLabel = snapshot.session.meshStateLabel,
        )

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

    DisposableEffect(liveProofAutomationDriver) {
        liveProofAutomationDriver.start()
        onDispose { liveProofAutomationDriver.close() }
    }

    ReferenceShellScaffold(
        headerState = shellHeaderState,
        contentState =
            ReferenceRouteContentState(
                activeRoute = activeRoute,
                guidedViewModel = guidedViewModel,
                advancedViewModel = advancedViewModel,
                timelineStore = timelineStore,
            ),
        pendingBoundary = pendingBoundary,
        onDismissBoundary = { pendingBoundary = null },
        onConfirmBoundary = { request, exportFirst ->
            handleBoundaryConfirmation(
                request = request,
                exportFirst = exportFirst,
                timelineStore = timelineStore,
                applySurfaceSelection = ::applySurfaceSelection,
            )
            pendingBoundary = null
        },
        onSelectSurface = ::selectSurface,
        onSelectSection = ::selectSection,
    )
}
