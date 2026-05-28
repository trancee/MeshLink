@file:Suppress("FunctionNaming")

package ch.trancee.meshlink.reference.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun LabScreen(
    scenarios: List<LabScenario> = LabScenarioCatalog.scenarios,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(20.dp).testTag("lab-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(text = "Lab", style = MaterialTheme.typography.headlineSmall) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Non-normative",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text =
                            "Everything here is explicitly separated from the supported " +
                                "guided and advanced product-reference path.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        items(items = scenarios, key = { scenario -> scenario.scenarioId }) { scenario ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(text = scenario.title, style = MaterialTheme.typography.titleLarge)
                    Text(text = scenario.summary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
