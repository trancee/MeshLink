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
    automationMode: String? = null,
    automationRole: String? = null,
    automationScenario: String? = null,
    automationTargetPeerId: String? = null,
    autoStartMesh: Boolean = false,
    autoSendHello: Boolean = false,
    emitAutomationLog: (String) -> Unit = {},
    diagnosticMinimalUi: Boolean = false,
) {
    emitAutomationLog(
        "REFERENCE_AUTOMATION app.compose.begin minimal=$diagnosticMinimalUi mode=${automationMode ?: "none"} role=${automationRole ?: "none"} scenario=${automationScenario ?: "none"} targetPeerId=${automationTargetPeerId ?: "none"} autoStartMesh=$autoStartMesh autoSendHello=$autoSendHello"
    )
    emitAutomationLog(
        "REFERENCE_AUTOMATION startup-state=app.compose.begin mode=${automationMode ?: "none"} role=${automationRole ?: "none"} scenario=${automationScenario ?: "none"} autoStartMesh=$autoStartMesh autoSendHello=$autoSendHello"
    )
    ReferenceTheme {
        if (diagnosticMinimalUi) {
            emitAutomationLog("REFERENCE_AUTOMATION app.compose.placeholder")
            Surface(modifier = Modifier.fillMaxSize()) { Unit }
        } else {
            emitAutomationLog("REFERENCE_AUTOMATION app.compose.beforeRemember")
            val guidedViewModel =
                remember(
                    platformName,
                    readinessGuidance,
                    readinessBlockers,
                    powerMitigationStatus,
                    meshLinkController,
                    automationMode,
                    automationRole,
                    automationScenario,
                    automationTargetPeerId,
                    autoStartMesh,
                    autoSendHello,
                ) {
                    GuidedFirstExchangeViewModel(
                        platformName = platformName,
                        readinessGuidance = readinessGuidance,
                        readinessBlockers = readinessBlockers,
                        powerMitigationStatus = powerMitigationStatus,
                        meshLinkController = meshLinkController,
                        automationMode = automationMode,
                        automationRole = automationRole,
                        automationScenario = automationScenario,
                        automationTargetPeerId = automationTargetPeerId,
                        autoStartMesh = autoStartMesh,
                        autoSendHello = autoSendHello,
                        emitAutomationLog = emitAutomationLog,
                    )
                }
            emitAutomationLog("REFERENCE_AUTOMATION startup-state=app.compose.afterRemember")
            emitAutomationLog("REFERENCE_AUTOMATION app.compose.afterRemember")
            Surface(modifier = Modifier.fillMaxSize()) {
                GuidedFirstExchangeScreen(viewModel = guidedViewModel)
            }
            emitAutomationLog("REFERENCE_AUTOMATION startup-state=app.compose.end")
            emitAutomationLog("REFERENCE_AUTOMATION app.compose.end")
        }
    }
}
