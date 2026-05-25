package ch.trancee.meshlink.reference.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.design.ReferenceBadge
import ch.trancee.meshlink.reference.design.ReferenceSectionCard
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.referenceTimelineFamilyLabel
import ch.trancee.meshlink.reference.model.referenceTimelineSeverityLabel

@Composable
internal fun TechnicalTimelineHeader(): Unit {
    Text(text = "Technical timeline", style = MaterialTheme.typography.headlineSmall)
}

@Composable
internal fun EmptyTimelineSection(): Unit {
    ReferenceSectionCard(
        title = "No matching events",
        subtitle =
            "Clear the current filters or keep running the session until new diagnostics arrive.",
    ) {
        Text(
            text = "Nothing matches the current search, family, severity, and peer filters.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TimelineEntryCard(entry: TimelineEntry): Unit {
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
        if (entry.payloadPreview != null) {
            Text(
                text = "Preview: ${entry.payloadPreview}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
