package ch.trancee.meshlink.reference.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.resources.Res
import ch.trancee.meshlink.reference.resources.app_title
import ch.trancee.meshlink.reference.resources.mode_label
import ch.trancee.meshlink.reference.resources.platform_label
import ch.trancee.meshlink.reference.session.JsonSessionArtifactSerializer
import ch.trancee.meshlink.reference.session.JsonSessionHistoryRepository
import ch.trancee.meshlink.reference.solo.SoloExplorationScreen
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineScreen
import ch.trancee.meshlink.reference.timeline.TechnicalTimelineStore
import org.jetbrains.compose.resources.stringResource

/** Shared navigation shell for the reference app surfaces. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
public fun ReferenceNavHost(platformServices: PlatformServices) {
    var activeRoute: ReferenceSurfaceId by remember {
        mutableStateOf(ReferenceSurfaceId.MAIN_GUIDED)
    }
    val guidedViewModel =
        remember(platformServices.platformName) { GuidedFirstExchangeViewModel(platformServices) }
    val snapshot by platformServices.meshLinkController.snapshot.collectAsState()
    val advancedViewModel =
        remember(platformServices.platformName) { AdvancedControlsViewModel(platformServices) }
    val timelineStore =
        remember(platformServices.platformName) {
            TechnicalTimelineStore(
                platformServices = platformServices,
                historyRepository = JsonSessionHistoryRepository(platformServices.documentStore),
                artifactSerializer = JsonSessionArtifactSerializer(platformServices.documentStore),
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

    fun selectSurface(surface: ReferenceSurfaceId) {
        activeRoute = surface
        lastRouteBySection[primarySectionFor(surface)] = surface
    }

    val activeSection = primarySectionFor(activeRoute)

    ReferenceLiveProofAutomation(
        platformServices = platformServices,
        guidedViewModel = guidedViewModel,
        timelineStore = timelineStore,
        snapshot = snapshot,
    )

    Scaffold(
        topBar = {
            ReferenceShellHeader(
                activeSection = activeSection,
                activeRoute = activeRoute,
                workflowTitles = workflowTitles,
                platformName = platformServices.platformName,
                authorityModeLabel = snapshot.session.authorityMode.toString(),
                meshStateLabel = snapshot.session.meshStateLabel,
                onSelectSurface = ::selectSurface,
            )
        },
        bottomBar = {
            NavigationBar {
                ReferencePrimarySection.entries.forEach { section ->
                    NavigationBarItem(
                        selected = section == activeSection,
                        onClick = {
                            selectSurface(lastRouteBySection[section] ?: section.defaultSurface)
                        },
                        icon = {
                            Icon(imageVector = section.icon, contentDescription = section.label)
                        },
                        label = { Text(section.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (activeRoute) {
                ReferenceSurfaceId.MAIN_GUIDED ->
                    GuidedFirstExchangeScreen(
                        viewModel = guidedViewModel,
                        onOpenSolo = { selectSurface(ReferenceSurfaceId.SOLO_EXPLORATION) },
                        modifier = Modifier.fillMaxSize(),
                    )

                ReferenceSurfaceId.SOLO_EXPLORATION ->
                    SoloExplorationScreen(modifier = Modifier.fillMaxSize())

                ReferenceSurfaceId.ADVANCED_CONTROLS ->
                    AdvancedControlsScreen(
                        viewModel = advancedViewModel,
                        modifier = Modifier.fillMaxSize(),
                    )

                ReferenceSurfaceId.TECHNICAL_TIMELINE ->
                    TechnicalTimelineScreen(
                        store = timelineStore,
                        modifier = Modifier.fillMaxSize(),
                    )

                ReferenceSurfaceId.LAB -> LabScreen(modifier = Modifier.fillMaxSize())

                ReferenceSurfaceId.RECENT_HISTORY ->
                    RecentSessionHistoryScreen(
                        store = timelineStore,
                        modifier = Modifier.fillMaxSize(),
                    )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReferenceShellHeader(
    activeSection: ReferencePrimarySection,
    activeRoute: ReferenceSurfaceId,
    workflowTitles: Map<ReferenceSurfaceId, String>,
    platformName: String,
    authorityModeLabel: String,
    meshStateLabel: String,
    onSelectSurface: (ReferenceSurfaceId) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
                ReferenceBadge(label = activeSection.label, prominent = true)
                ReferenceBadge(
                    label = "${stringResource(Res.string.platform_label)}: $platformName"
                )
                ReferenceBadge(
                    label = "${stringResource(Res.string.mode_label)}: $authorityModeLabel"
                )
                ReferenceBadge(label = "Mesh: $meshStateLabel")
            }
            if (activeSection.surfaces.size > 1) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    activeSection.surfaces.forEach { surface ->
                        FilterChip(
                            selected = surface == activeRoute,
                            onClick = { onSelectSurface(surface) },
                            label = { Text(workflowTitles.getValue(surface)) },
                        )
                    }
                }
            }
        }
    }
}

private enum class ReferencePrimarySection(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val surfaces: List<ReferenceSurfaceId>,
) {
    EXCHANGE(
        label = "Exchange",
        description =
            "Start with the live guided flow, then switch to solo review when a second device is unavailable.",
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
            "Review live diagnostics, retain completed sessions, and export redacted evidence artifacts.",
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
