package ch.trancee.meshlink.reference.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import ch.trancee.meshlink.reference.model.referenceAuthorityLabel
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Shared navigation shell for the reference app surfaces. */
@Composable
internal fun ReferenceNavHost(platformServices: PlatformServices) {
    var activeRoute: ReferenceSurface by remember { mutableStateOf(ReferenceSurface.MAIN_GUIDED) }
    var pendingBoundary by remember { mutableStateOf<SessionBoundaryRequest?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val dependencies = rememberReferenceNavHostDependencies(platformServices)
    val snapshot by dependencies.sessionController.snapshot.collectAsState()
    val workflowTitles = rememberReferenceWorkflowTitles()
    val lastRouteBySection = rememberLastRouteBySection()

    fun applySurfaceSelection(surface: ReferenceSurface): Unit {
        activeRoute = surface
        lastRouteBySection[primarySectionFor(surface)] = surface
    }

    fun selectSurface(surface: ReferenceSurface): Unit {
        when (
            val choice =
                chooseSessionSurfaceChoice(
                    activeRoute = activeRoute,
                    currentSnapshot = snapshot,
                    targetSurface = surface,
                )
        ) {
            is SessionSurfaceChoice.SelectSurface -> {
                applySurfaceSelection(choice.surface)
            }

            is SessionSurfaceChoice.RequireBoundaryConfirmation -> {
                pendingBoundary = choice.request
            }

            is SessionSurfaceChoice.StartAlternativeSession -> {
                coroutineScope.launch {
                    dependencies.sessionTransitionService.startAlternativeSession(
                        surface = choice.surface,
                        applySurfaceSelection = ::applySurfaceSelection,
                    )
                }
            }
        }
    }

    fun selectSection(section: ReferencePrimarySection): Unit {
        selectSurface(lastRouteBySection[section] ?: section.defaultSurface)
    }

    val followUpSupportedSessionLabel = followUpSupportedSessionLabel(snapshot)

    LaunchedEffect(pendingBoundary) {
        val request = pendingBoundary ?: return@LaunchedEffect
        dependencies.timelineStore.uiState.collect {
            if (!dependencies.sessionTransitionService.canExecuteBoundary(request)) {
                pendingBoundary = null
            }
        }
    }

    fun startFollowUpSupportedSessionFromEvidenceSurface(): Unit {
        coroutineScope.launch {
            dependencies.sessionTransitionService.startFollowUpSupportedSession(
                currentSnapshot = snapshot,
                applySurfaceSelection = ::applySurfaceSelection,
            )
        }
    }

    fun endSupportedSessionFromEvidenceSurface(
        preEndExportPolicy: ExportPayloadPolicy? = null
    ): Unit {
        coroutineScope.launch {
            dependencies.sessionTransitionService.endSupportedSession(preEndExportPolicy)
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
        onEndSupportedSession = ::endSupportedSessionFromEvidenceSurface,
        pendingBoundary = pendingBoundary,
        onDismissBoundary = { pendingBoundary = null },
        onCompleteBoundary = { request, continuation ->
            coroutineScope.launch {
                dependencies.sessionTransitionService.completeBoundary(
                    request = request,
                    continuation = continuation,
                    applySurfaceSelection = ::applySurfaceSelection,
                )
            }
            pendingBoundary = null
        },
        onSelectSurface = ::selectSurface,
        onSelectSection = ::selectSection,
    )
}
