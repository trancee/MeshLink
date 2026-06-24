@file:Suppress("FunctionNaming")

package ch.trancee.meshlink.reference.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import ch.trancee.meshlink.reference.design.ReferenceTheme
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeScreen
import ch.trancee.meshlink.reference.guided.GuidedFirstExchangeViewModel
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController

/** Root composable for the MeshLink reference app. */
@Composable
public fun ReferenceApp(
    platformName: String,
    readinessGuidance: List<String>,
    readinessBlockers: List<String>,
    powerMitigationStatus: String?,
    documentStore: Any?,
    meshLinkController: ReferenceMeshLinkController,
    stopPowerMitigation: () -> Unit,
    currentTimeMillis: () -> Long,
) {
    val guidedViewModel =
        remember(
            platformName,
            readinessGuidance,
            readinessBlockers,
            powerMitigationStatus,
            meshLinkController,
        ) {
            GuidedFirstExchangeViewModel(
                platformName = platformName,
                readinessGuidance = readinessGuidance,
                readinessBlockers = readinessBlockers,
                powerMitigationStatus = powerMitigationStatus,
                meshLinkController = meshLinkController,
            )
        }
    ReferenceTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            GuidedFirstExchangeScreen(viewModel = guidedViewModel)
        }
    }
}
