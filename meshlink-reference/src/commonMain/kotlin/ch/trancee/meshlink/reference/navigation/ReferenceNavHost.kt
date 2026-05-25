package ch.trancee.meshlink.reference.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ch.trancee.meshlink.reference.model.referenceAuthorityLabel
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.referenceSessionKind
import ch.trancee.meshlink.reference.timeline.startNewSupportedSessionNow
import kotlinx.coroutines.launch

/** Shared navigation shell for the reference app surfaces. */
@Composable
public fun ReferenceNavHost(platformServices: PlatformServices) {
    var activeRoute: ReferenceSurfaceId by remember {
        mutableStateOf(ReferenceSurfaceId.MAIN_GUIDED)
    }
    var pendingBoundary by remember { mutableStateOf<SessionBoundaryRequest?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val dependencies = rememberReferenceNavHostDependencies(platformServices)
    val snapshot by dependencies.sessionController.snapshot.collectAsState()
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
                        sessionController = dependencies.sessionController,
                    )
                    applySurfaceSelection(decision.surface)
                }
            }
        }
    }

    fun selectSection(section: ReferencePrimarySection): Unit {
        selectSurface(lastRouteBySection[section] ?: section.defaultSurface)
    }

    val followUpSupportedSessionLabel = followUpSupportedSessionLabel(snapshot)

    fun startFollowUpSupportedSessionFromEvidenceSurface(): Unit {
        coroutineScope.launch {
            startFollowUpSupportedSession(
                currentSnapshot = snapshot,
                applySurfaceSelection = ::applySurfaceSelection,
                startSupportedSession = { targetSurface ->
                    dependencies.timelineStore.startNewSupportedSessionNow(
                        surfaceOfOrigin = targetSurface.route
                    )
                },
            )
        }
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

    DisposableEffect(dependencies.liveProofAutomationDriver) {
        dependencies.liveProofAutomationDriver.start()
        onDispose { dependencies.liveProofAutomationDriver.close() }
    }

    ReferenceShellScaffold(
        headerState = shellHeaderState,
        contentState =
            ReferenceRouteContentState(
                activeRoute = activeRoute,
                guidedViewModel = dependencies.guidedViewModel,
                advancedViewModel = dependencies.advancedViewModel,
                timelineStore = dependencies.timelineStore,
            ),
        followUpSupportedSessionLabel = followUpSupportedSessionLabel,
        onStartFollowUpSupportedSession = ::startFollowUpSupportedSessionFromEvidenceSurface,
        pendingBoundary = pendingBoundary,
        onDismissBoundary = { pendingBoundary = null },
        onConfirmBoundary = { request, exportFirst ->
            coroutineScope.launch {
                handleBoundaryConfirmation(
                    request = request,
                    exportFirst = exportFirst,
                    timelineStore = dependencies.timelineStore,
                    applySurfaceSelection = ::applySurfaceSelection,
                )
            }
            pendingBoundary = null
        },
        onSelectSurface = ::selectSurface,
        onSelectSection = ::selectSection,
    )
}
