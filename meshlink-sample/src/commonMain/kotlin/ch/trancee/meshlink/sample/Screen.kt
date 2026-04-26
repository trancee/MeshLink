package ch.trancee.meshlink.sample

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.serialization.Serializable

// ── Route objects ─────────────────────────────────────────────────────────────

/** Navigation route for the Chat screen. */
@Serializable
object ChatRoute

/** Navigation route for the Mesh Visualizer screen. */
@Serializable
object MeshVisualizerRoute

/** Navigation route for the Diagnostics screen. */
@Serializable
object DiagnosticsRoute

/** Navigation route for the Settings screen. */
@Serializable
object SettingsRoute

// ── Screen descriptors ────────────────────────────────────────────────────────

/**
 * Describes each bottom-navigation destination — its human-readable label, icon, and typed route.
 *
 * Used by [App] to build the [NavigationBar] and the [NavHost].
 */
sealed class Screen(
    val label: String,
    val icon: ImageVector,
    val route: Any,
) {
    object Chat : Screen("Chat", Icons.Default.Chat, ChatRoute)
    object MeshVisualizer : Screen("Mesh", Icons.Default.Hub, MeshVisualizerRoute)
    object Diagnostics : Screen("Diagnostics", Icons.Default.MonitorHeart, DiagnosticsRoute)
    object Settings : Screen("Settings", Icons.Default.Settings, SettingsRoute)

    companion object {
        /** Ordered list of all bottom-navigation screens. */
        val all: List<Screen> = listOf(Chat, MeshVisualizer, Diagnostics, Settings)
    }
}
