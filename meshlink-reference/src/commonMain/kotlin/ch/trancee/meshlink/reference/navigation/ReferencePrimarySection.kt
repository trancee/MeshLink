package ch.trancee.meshlink.reference.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class ReferencePrimarySection(
    val label: String,
    val icon: ImageVector,
    val surfaces: List<ReferenceSurfaceId>,
) {
    EXCHANGE(
        label = "Exchange",
        icon = Icons.Filled.Home,
        surfaces = listOf(ReferenceSurfaceId.MAIN_GUIDED, ReferenceSurfaceId.SOLO_EXPLORATION),
    ),
    CONTROLS(
        label = "Controls",
        icon = Icons.Filled.Settings,
        surfaces = listOf(ReferenceSurfaceId.ADVANCED_CONTROLS),
    ),
    EVIDENCE(
        label = "Evidence",
        icon = Icons.Filled.Info,
        surfaces = listOf(ReferenceSurfaceId.TECHNICAL_TIMELINE, ReferenceSurfaceId.RECENT_HISTORY),
    ),
    LAB(label = "Lab", icon = Icons.Filled.Build, surfaces = listOf(ReferenceSurfaceId.LAB));

    val defaultSurface: ReferenceSurfaceId = surfaces.first()

    val supportsSubsurfaceSelection: Boolean = surfaces.size > 1
}

internal fun primarySectionFor(surface: ReferenceSurfaceId): ReferencePrimarySection {
    return ReferencePrimarySection.entries.first { section -> surface in section.surfaces }
}
