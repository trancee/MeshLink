package ch.trancee.meshlink.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

/**
 * Root composable for the MeshLink reference app.
 *
 * Builds the app shell: [Scaffold] with a [NavigationBar] containing four destinations (Chat,
 * Mesh Visualizer, Diagnostics, Settings) and a [NavHost] routing to each screen. All screens
 * receive the shared [MeshController] so they can observe engine state without needing their own
 * MeshLink instances.
 *
 * [MeshController] is created once via [remember] and lives as long as the composition scope. The
 * engine is started via [LaunchedEffect] immediately after first composition so that state Flows
 * emit live data by the time any screen renders.
 */
@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { MeshController(scope) }

    // Start the MeshLink engine on first composition; the scope cancels it on disposal.
    LaunchedEffect(controller) {
        controller.start()
    }

    MaterialTheme {
        val navController = rememberNavController()
        val currentEntry by navController.currentBackStackEntryAsState()
        val currentDestination = currentEntry?.destination

        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentDestination?.route?.startsWith(
                            ChatRoute::class.qualifiedName ?: ""
                        ) == true,
                        onClick = {
                            navController.navigate(ChatRoute) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Screen.Chat.icon, Screen.Chat.label) },
                        label = { Text(Screen.Chat.label) },
                    )
                    NavigationBarItem(
                        selected = currentDestination?.route?.startsWith(
                            MeshVisualizerRoute::class.qualifiedName ?: ""
                        ) == true,
                        onClick = {
                            navController.navigate(MeshVisualizerRoute) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Screen.MeshVisualizer.icon, Screen.MeshVisualizer.label) },
                        label = { Text(Screen.MeshVisualizer.label) },
                    )
                    NavigationBarItem(
                        selected = currentDestination?.route?.startsWith(
                            DiagnosticsRoute::class.qualifiedName ?: ""
                        ) == true,
                        onClick = {
                            navController.navigate(DiagnosticsRoute) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Screen.Diagnostics.icon, Screen.Diagnostics.label) },
                        label = { Text(Screen.Diagnostics.label) },
                    )
                    NavigationBarItem(
                        selected = currentDestination?.route?.startsWith(
                            SettingsRoute::class.qualifiedName ?: ""
                        ) == true,
                        onClick = {
                            navController.navigate(SettingsRoute) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Screen.Settings.icon, Screen.Settings.label) },
                        label = { Text(Screen.Settings.label) },
                    )
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = ChatRoute,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable<ChatRoute> {
                    PlaceholderScreen(label = "Chat", controller = controller)
                }
                composable<MeshVisualizerRoute> {
                    PlaceholderScreen(label = "Mesh Visualizer", controller = controller)
                }
                composable<DiagnosticsRoute> {
                    PlaceholderScreen(label = "Diagnostics", controller = controller)
                }
                composable<SettingsRoute> {
                    PlaceholderScreen(label = "Settings", controller = controller)
                }
            }
        }
    }
}

/**
 * Temporary placeholder used by each screen destination until T03–T05 implement the real content.
 *
 * Accepts [controller] so that wiring to [MeshController] state is established in this commit
 * and downstream tasks only need to replace the [Box] content with real screen composables.
 */
@Composable
private fun PlaceholderScreen(
    label: String,
    controller: MeshController,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label)
    }
}
