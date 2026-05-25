package ch.trancee.meshlink.reference.guided

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.design.ReferenceBadge
import ch.trancee.meshlink.reference.design.ReferenceSectionCard
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.referenceTimelineFamilyLabel
import ch.trancee.meshlink.reference.model.referenceTimelineSeverityLabel

@Composable
internal fun GuidedRecentTimelineHeader(): Unit {
    Text(
        text = "Recent timeline",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuidedTimelineEntryCard(entry: TimelineEntry): Unit {
    ReferenceSectionCard(title = entry.title, subtitle = entry.detail) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReferenceBadge(label = referenceTimelineFamilyLabel(entry.family))
            ReferenceBadge(label = referenceTimelineSeverityLabel(entry.severity))
            if (entry.peerSuffix != null) {
                ReferenceBadge(label = "Peer ${entry.peerSuffix}")
            }
        }
    }
}

internal const val RECENT_GUIDED_TIMELINE_ENTRY_COUNT: Int = 4
