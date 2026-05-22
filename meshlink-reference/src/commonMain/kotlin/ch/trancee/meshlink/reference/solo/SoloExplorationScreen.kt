@file:Suppress("FunctionNaming")

package ch.trancee.meshlink.reference.solo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Clearly labeled non-authoritative exploration path for one-device review. */
@Composable
public fun SoloExplorationScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "Solo exploration", style = MaterialTheme.typography.headlineSmall)
        Card(modifier = Modifier.fillMaxWidth().testTag("solo-screen")) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Non-authoritative",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        "Use this surface to inspect workflows, wording, and controls when a second device is unavailable.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text =
                        "It must never be treated as proof of live peer discovery, live delivery, or authoritative diagnostics behavior.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
