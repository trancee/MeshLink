package ch.trancee.meshlink.reference

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import ch.trancee.meshlink.reference.app.ReferenceApp
import ch.trancee.meshlink.reference.platform.createPlatformServices
import platform.UIKit.UIViewController

@OptIn(ExperimentalComposeUiApi::class)
public fun ReferenceViewController(): UIViewController {
    val platformServices = createPlatformServices()
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
        )
    }
}
