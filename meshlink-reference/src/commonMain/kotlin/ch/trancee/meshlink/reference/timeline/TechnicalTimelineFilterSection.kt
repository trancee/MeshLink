package ch.trancee.meshlink.reference.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.design.ReferenceSectionCard
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.model.referenceTimelineFamilyLabel
import ch.trancee.meshlink.reference.model.referenceTimelineSeverityLabel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimelineFilterSection(
    uiState: TechnicalTimelineUiState,
    availableFamilies: List<TimelineFamily>,
    availablePeerSuffixes: List<String>,
    availableSeverities: List<TimelineSeverity>,
    store: TechnicalTimelineStore,
): Unit {
    ReferenceSectionCard(
        title = "Filter events",
        subtitle =
            "Search the current session and narrow the timeline by family, severity, or peer when you want a smaller operator view.",
    ) {
        OutlinedTextField(
            value = uiState.filters.searchText,
            onValueChange = store::setSearchText,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Search events") },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected =
                    uiState.filters.searchText.isBlank() &&
                        uiState.filters.family == null &&
                        uiState.filters.severity == null &&
                        uiState.filters.peerSuffix == null,
                onClick = store::clearFilters,
                label = { Text("All") },
            )
            availableFamilies.forEach { family ->
                FilterChip(
                    selected = uiState.filters.family == family,
                    onClick = {
                        store.setFamilyFilter(
                            if (uiState.filters.family == family) null else family
                        )
                    },
                    label = { Text(referenceTimelineFamilyLabel(family)) },
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            availableSeverities.forEach { severity ->
                FilterChip(
                    selected = uiState.filters.severity == severity,
                    onClick = {
                        store.setSeverityFilter(
                            if (uiState.filters.severity == severity) null else severity
                        )
                    },
                    label = { Text(referenceTimelineSeverityLabel(severity)) },
                )
            }
        }
        if (availablePeerSuffixes.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                availablePeerSuffixes.forEach { peerSuffix ->
                    FilterChip(
                        selected = uiState.filters.peerSuffix == peerSuffix,
                        onClick = {
                            store.setPeerFilter(
                                if (uiState.filters.peerSuffix == peerSuffix) null else peerSuffix
                            )
                        },
                        label = { Text("Peer $peerSuffix") },
                    )
                }
            }
        }
    }
}
