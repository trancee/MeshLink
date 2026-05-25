package ch.trancee.meshlink.reference.guided

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ch.trancee.meshlink.reference.design.ReferenceSectionCard

@Composable
internal fun GuidedStartupBlockerCard(blockers: List<ReadinessItem>): Unit {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("guided-blocker-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Startup blocked",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            blockers.forEach { blocker ->
                Text(
                    text = "${blocker.title}: ${blocker.detail}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
internal fun GuidedReadinessChecklist(items: List<ReadinessItem>): Unit {
    ReferenceSectionCard(
        title = "Readiness checklist",
        subtitle = "Clear these local prerequisites before you treat the guided flow as live proof.",
    ) {
        items.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = item.title, style = MaterialTheme.typography.labelLarge)
                Text(text = item.detail, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
