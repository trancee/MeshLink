package ch.trancee.meshlink.reference.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.advanced.AdvancedControlsScreen
import ch.trancee.meshlink.reference.advanced.AdvancedControlsViewModel
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeScreen
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeViewModel
import ch.trancee.meshlink.reference.lab.LabScreen
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.resources.Res
import ch.trancee.meshlink.reference.resources.advanced_summary
import ch.trancee.meshlink.reference.resources.advanced_title
import ch.trancee.meshlink.reference.resources.app_title
import ch.trancee.meshlink.reference.resources.guided_title
import ch.trancee.meshlink.reference.resources.history_summary
import ch.trancee.meshlink.reference.resources.history_title
import ch.trancee.meshlink.reference.resources.lab_summary
import ch.trancee.meshlink.reference.resources.lab_title
import ch.trancee.meshlink.reference.resources.mode_label
import ch.trancee.meshlink.reference.resources.platform_label
import ch.trancee.meshlink.reference.resources.solo_summary
import ch.trancee.meshlink.reference.resources.solo_title
import ch.trancee.meshlink.reference.resources.timeline_summary
import ch.trancee.meshlink.reference.resources.timeline_title
import ch.trancee.meshlink.reference.solo.SoloExplorationScreen
import org.jetbrains.compose.resources.stringResource

/**
 * Shared navigation shell for the reference app surfaces.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun ReferenceNavHost(platformServices: PlatformServices) {
    var activeRoute: ReferenceSurfaceRoute by remember { mutableStateOf(ReferenceSurfaceRoute.GUIDED) }
    val guidedViewModel = remember(platformServices.platformName) { GuidedFirstExchangeViewModel(platformServices) }
    val snapshot by platformServices.meshLinkController.snapshot.collectAsState()
    val advancedViewModel = remember(platformServices.platformName) { AdvancedControlsViewModel(platformServices) }
    val routes =
        listOf(
            ReferenceSurfaceRoute.GUIDED to stringResource(Res.string.guided_title),
            ReferenceSurfaceRoute.SOLO to stringResource(Res.string.solo_title),
            ReferenceSurfaceRoute.ADVANCED to stringResource(Res.string.advanced_title),
            ReferenceSurfaceRoute.TIMELINE to stringResource(Res.string.timeline_title),
            ReferenceSurfaceRoute.LAB to stringResource(Res.string.lab_title),
            ReferenceSurfaceRoute.HISTORY to stringResource(Res.string.history_title),
        )

    Scaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    text = stringResource(Res.string.app_title),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = "${stringResource(Res.string.platform_label)}: ${platformServices.platformName} · ${stringResource(Res.string.mode_label)}: ${snapshot.session.authorityMode}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    routes.forEach { (route, title) ->
                        AssistChip(
                            onClick = { activeRoute = route },
                            label = { Text(text = title) },
                            enabled = route != activeRoute,
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        when (activeRoute) {
            ReferenceSurfaceRoute.GUIDED ->
                GuidedFirstExchangeScreen(
                    viewModel = guidedViewModel,
                    onOpenSolo = { activeRoute = ReferenceSurfaceRoute.SOLO },
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
            ReferenceSurfaceRoute.SOLO ->
                SoloExplorationScreen(modifier = Modifier.fillMaxSize().padding(innerPadding))
            ReferenceSurfaceRoute.ADVANCED ->
                AdvancedControlsScreen(
                    viewModel = advancedViewModel,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
            ReferenceSurfaceRoute.TIMELINE ->
                PlaceholderSurface(
                    title = stringResource(Res.string.timeline_title),
                    summary = stringResource(Res.string.timeline_summary),
                    snapshot = snapshot,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
            ReferenceSurfaceRoute.LAB ->
                LabScreen(modifier = Modifier.fillMaxSize().padding(innerPadding))
            ReferenceSurfaceRoute.HISTORY ->
                PlaceholderSurface(
                    title = stringResource(Res.string.history_title),
                    summary = stringResource(Res.string.history_summary),
                    snapshot = snapshot,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
        }
    }
}

private enum class ReferenceSurfaceRoute {
    GUIDED,
    SOLO,
    ADVANCED,
    TIMELINE,
    LAB,
    HISTORY,
}

@Composable
private fun PlaceholderSurface(
    title: String,
    summary: String,
    snapshot: ReferenceControllerSnapshot,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(text = summary, style = MaterialTheme.typography.bodyLarge)

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Foundational app shell",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Mesh state: ${snapshot.session.meshStateLabel}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Last outcome: ${snapshot.session.lastOutcomeSummary ?: "none yet"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Timeline entries: ${snapshot.timeline.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {}) {
                    Text("Story-specific controls plug into this route next")
                }
            }
        }
    }
}
