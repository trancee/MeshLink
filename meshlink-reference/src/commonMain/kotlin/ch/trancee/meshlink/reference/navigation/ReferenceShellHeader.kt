package ch.trancee.meshlink.reference.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.design.ReferenceBadge
import ch.trancee.meshlink.reference.resources.Res
import ch.trancee.meshlink.reference.resources.app_title
import ch.trancee.meshlink.reference.resources.mode_label
import ch.trancee.meshlink.reference.resources.platform_label
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ReferenceBottomBar(
    activeSection: ReferencePrimarySection,
    onSelectSection: (ReferencePrimarySection) -> Unit,
): Unit {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            NavigationBar {
                ReferencePrimarySection.entries.forEach { section ->
                    NavigationBarItem(
                        selected = section == activeSection,
                        onClick = { onSelectSection(section) },
                        icon = {
                            Icon(imageVector = section.icon, contentDescription = section.label)
                        },
                        label = { Text(section.label) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReferenceShellHeader(
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
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
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
            if (state.activeSection.supportsSubsurfaceSelection) {
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

internal data class ReferenceShellHeaderState(
    val activeSection: ReferencePrimarySection,
    val activeRoute: ReferenceSurfaceId,
    val workflowTitles: Map<ReferenceSurfaceId, String>,
    val platformName: String,
    val authorityModeLabel: String,
    val meshStateLabel: String,
)
