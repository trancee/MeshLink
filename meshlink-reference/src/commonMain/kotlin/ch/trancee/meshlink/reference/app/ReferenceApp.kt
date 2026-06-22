@file:Suppress("FunctionNaming")

package ch.trancee.meshlink.reference.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfigView
import ch.trancee.meshlink.reference.design.ReferenceTheme
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.navigation.ReferenceNavHost

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
    emitAutomationLog: (String) -> Unit,
    createSupportedMeshLinkController: (String) -> ReferenceMeshLinkController = {
        meshLinkController
    },
    automationConfig: ReferenceAutomationConfigView? = null,
) {
    ReferenceTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ReferenceNavHost(
                platformName = platformName,
                readinessGuidance = readinessGuidance,
                readinessBlockers = readinessBlockers,
                powerMitigationStatus = powerMitigationStatus,
                documentStore = documentStore,
                meshLinkController = meshLinkController,
                stopPowerMitigation = stopPowerMitigation,
                currentTimeMillis = currentTimeMillis,
                emitAutomationLog = emitAutomationLog,
                createSupportedMeshLinkController = createSupportedMeshLinkController,
                automationConfig = automationConfig,
            )
        }
    }
}
