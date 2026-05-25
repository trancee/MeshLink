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
    val surfaces: List<ReferenceSurface>,
) {
    EXCHANGE(
        label = "Exchange",
        icon = Icons.Filled.Home,
        surfaces = listOf(ReferenceSurface.MAIN_GUIDED, ReferenceSurface.SOLO_EXPLORATION),
    ),
    CONTROLS(
        label = "Controls",
        icon = Icons.Filled.Settings,
        surfaces = listOf(ReferenceSurface.ADVANCED_CONTROLS),
    ),
    EVIDENCE(
        label = "Evidence",
        icon = Icons.Filled.Info,
        surfaces = listOf(ReferenceSurface.TECHNICAL_TIMELINE, ReferenceSurface.RECENT_HISTORY),
    ),
    LAB(label = "Lab", icon = Icons.Filled.Build, surfaces = listOf(ReferenceSurface.LAB));

    val defaultSurface: ReferenceSurface = surfaces.first()

    val supportsSubsurfaceSelection: Boolean = surfaces.size > 1
}

internal fun primarySectionFor(surface: ReferenceSurface): ReferencePrimarySection {
    return ReferencePrimarySection.entries.first { section -> surface in section.surfaces }
}
