@file:Suppress("FunctionNaming")

package ch.trancee.meshlink.reference.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.advanced.AdvancedControlsScreen
import ch.trancee.meshlink.reference.advanced.AdvancedControlsViewModel
import ch.trancee.meshlink.reference.automation.ReferenceLiveProofAutomation
import ch.trancee.meshlink.reference.design.ReferenceBadge
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeScreen
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeViewModel
import ch.trancee.meshlink.reference.history.RecentSessionHistoryScreen
import ch.trancee.meshlink.reference.lab.LabScreen
import ch.trancee.meshlink.reference.model.referenceAuthorityLabel
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.resources.Res
import ch.trancee.meshlink.reference.resources.app_title
import ch.trancee.meshlink.reference.resources.mode_label
import ch.trancee.meshlink.reference.resources.platform_label
import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
import ch.trancee.meshlink.reference.session.ReferenceSessionController
import ch.trancee.meshlink.reference.session.referenceSessionKind
import ch.trancee.meshlink.reference.solo.SoloExplorationScreen
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineScreen
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import ch.trancee.meshlink.reference.timeline.transitionAlternativeSession
import ch.trancee.meshlink.reference.timeline.transitionToLabSession
import ch.trancee.meshlink.reference.timeline.transitionToSoloSession
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Shared navigation shell for the reference app surfaces. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
    val workflowTitles = remember {
        ReferenceWorkflowCatalog.descriptors().associate { descriptor ->
            descriptor.surfaceId to descriptor.title
        }
    }
    val lastRouteBySection = remember {
        mutableStateMapOf<ReferencePrimarySection, ReferenceSurfaceId>().apply {
            ReferencePrimarySection.entries.forEach { section ->
                put(section, section.defaultSurface)
            }
        }
    }

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
            is ReferenceSurfaceSelectionDecision.SelectSurface ->
                applySurfaceSelection(decision.surface)

            is ReferenceSurfaceSelectionDecision.RequireBoundary -> {
                pendingBoundary = decision.request
            }

            is ReferenceSurfaceSelectionDecision.StartNewSession -> {
                coroutineScope.launch {
                    when (decision.surface) {
                        ReferenceSurfaceId.SOLO_EXPLORATION -> sessionController.startSoloSession()
                        ReferenceSurfaceId.LAB -> sessionController.startLabSession()
                        else -> Unit
                    }
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

    ReferenceLiveProofAutomation(
        platformServices = sessionPlatformServices,
        guidedViewModel = guidedViewModel,
        timelineStore = timelineStore,
        snapshot = snapshot,
    )

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
            pendingBoundary = null
        },
        onSelectSurface = ::selectSurface,
        onSelectSection = ::selectSection,
    )
}

@Composable
private fun ReferenceShellScaffold(
    headerState: ReferenceShellHeaderState,
    contentState: ReferenceRouteContentState,
    pendingBoundary: SessionBoundaryRequest?,
    onDismissBoundary: () -> Unit,
    onConfirmBoundary: (SessionBoundaryRequest, Boolean) -> Unit,
    onSelectSurface: (ReferenceSurfaceId) -> Unit,
    onSelectSection: (ReferencePrimarySection) -> Unit,
): Unit {
    Scaffold(
        topBar = { ReferenceShellHeader(state = headerState, onSelectSurface = onSelectSurface) },
        bottomBar = {
            ReferenceBottomBar(
                activeSection = headerState.activeSection,
                onSelectSection = onSelectSection,
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            ReferenceRouteContent(
                contentState = contentState,
                onOpenSolo = { onSelectSurface(ReferenceSurfaceId.SOLO_EXPLORATION) },
            )
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
}

@Composable
private fun ReferenceBottomBar(
    activeSection: ReferencePrimarySection,
    onSelectSection: (ReferencePrimarySection) -> Unit,
): Unit {
    NavigationBar {
        ReferencePrimarySection.entries.forEach { section ->
            NavigationBarItem(
                selected = section == activeSection,
                onClick = { onSelectSection(section) },
                icon = { Icon(imageVector = section.icon, contentDescription = section.label) },
                label = { Text(section.label) },
            )
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
                    .then(
                        if (state.platformName == "Android") Modifier.statusBarsPadding()
                        else Modifier
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
            if (state.activeSection.surfaces.size > 1) {
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

private data class ReferenceRouteContentState(
    val activeRoute: ReferenceSurfaceId,
    val guidedViewModel: GuidedFirstExchangeViewModel,
    val advancedViewModel: AdvancedControlsViewModel,
    val timelineStore: TechnicalTimelineStore,
)

private data class ReferenceShellHeaderState(
    val activeSection: ReferencePrimarySection,
    val activeRoute: ReferenceSurfaceId,
    val workflowTitles: Map<ReferenceSurfaceId, String>,
    val platformName: String,
    val authorityModeLabel: String,
    val meshStateLabel: String,
)

private enum class ReferencePrimarySection(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val surfaces: List<ReferenceSurfaceId>,
) {
    EXCHANGE(
        label = "Exchange",
        description =
            "Start with the live guided flow, then start a solo session when a second device is unavailable.",
        icon = Icons.Filled.Home,
        surfaces = listOf(ReferenceSurfaceId.MAIN_GUIDED, ReferenceSurfaceId.SOLO_EXPLORATION),
    ),
    CONTROLS(
        label = "Controls",
        description =
            "Inspect runtime configuration, peer details, lifecycle actions, and operator-focused send controls.",
        icon = Icons.Filled.Settings,
        surfaces = listOf(ReferenceSurfaceId.ADVANCED_CONTROLS),
    ),
    EVIDENCE(
        label = "Evidence",
        description =
            "Review live diagnostics, end supported sessions, and reopen retained evidence artifacts.",
        icon = Icons.Filled.Info,
        surfaces = listOf(ReferenceSurfaceId.TECHNICAL_TIMELINE, ReferenceSurfaceId.RECENT_HISTORY),
    ),
    LAB(
        label = "Lab",
        description =
            "Keep non-normative proof and benchmark experiments separate from the supported reference workflow.",
        icon = Icons.Filled.Build,
        surfaces = listOf(ReferenceSurfaceId.LAB),
    );

    val defaultSurface: ReferenceSurfaceId = surfaces.first()
}

private fun primarySectionFor(surface: ReferenceSurfaceId): ReferencePrimarySection {
    return ReferencePrimarySection.entries.first { section -> surface in section.surfaces }
}

private class SessionAwarePlatformServices(
    private val delegate: PlatformServices,
    private val sessionController: ReferenceSessionController,
) : PlatformServices by delegate {
    override val meshLinkController:
        ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
        get() = sessionController

    override fun createSupportedMeshLinkController(
        surfaceOfOrigin: String
    ): ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController {
        return delegate.createSupportedMeshLinkController(surfaceOfOrigin)
    }
}
