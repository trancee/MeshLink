package ch.trancee.meshlink.reference

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import ch.trancee.meshlink.reference.app.ReferenceApp
import ch.trancee.meshlink.reference.platform.createPlatformServices
import platform.UIKit.UIViewController

@OptIn(ExperimentalComposeUiApi::class)
public fun ReferenceViewController(
    appId: String = "demo.meshlink.reference.ios",
    targetPeerId: String? = null,
    storageSubdirectory: String = "default",
    automationMode: String? = null,
    automationRole: String? = null,
    automationScenario: String? = null,
    autoStartMesh: Boolean = false,
    autoSendHello: Boolean = false,
): UIViewController {
    val platformServices =
        createPlatformServices(
            appId = appId,
            targetPeerId = targetPeerId,
            storageSubdirectory = storageSubdirectory,
        )
    return ComposeUIViewController {
        ReferenceApp(
            platformName = platformServices.platformName,
            readinessGuidance = platformServices.readinessGuidance,
            readinessBlockers = platformServices.readinessBlockers,
            powerMitigationStatus = platformServices.powerMitigationStatus,
            documentStore = platformServices.documentStore,
            meshLinkController = platformServices.meshLinkController,
            stopPowerMitigation = { platformServices.stopPowerMitigation() },
            currentTimeMillis = { platformServices.currentTimeMillis() },
            automationMode = automationMode,
            automationRole = automationRole,
            automationScenario = automationScenario,
            automationTargetPeerId = targetPeerId,
            autoStartMesh = autoStartMesh,
            autoSendHello = autoSendHello,
            emitAutomationLog = { message -> platformServices.emitAutomationLog(message) },
        )
    }
}
