package ch.trancee.meshlink.reference

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIView
import androidx.compose.ui.window.ComposeUIViewController
import ch.trancee.meshlink.reference.app.ReferenceApp
import ch.trancee.meshlink.reference.automation.AUTOMATION_MODE_LIVE_PROOF
import ch.trancee.meshlink.reference.automation.AUTOMATION_MODE_SCRIPTED_UI
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfig
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfigView
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.platform.createAutomationPlatformServices
import ch.trancee.meshlink.reference.platform.createLiveAutomationPlatformServices
import ch.trancee.meshlink.reference.platform.createPlatformServices
import platform.UIKit.UIView
import platform.UIKit.UIViewController

@Composable
private fun renderReferenceApp(
    platformServices: PlatformServices,
    automationConfig: ReferenceAutomationConfigView? = null,
) {
    ReferenceApp(
        platformName = platformServices.platformName,
        readinessGuidance = platformServices.readinessGuidance,
        readinessBlockers = platformServices.readinessBlockers,
        powerMitigationStatus = platformServices.powerMitigationStatus,
        documentStore = platformServices.documentStore,
        meshLinkController = platformServices.meshLinkController,
        stopPowerMitigation = { platformServices.stopPowerMitigation() },
        currentTimeMillis = { platformServices.currentTimeMillis() },
        emitAutomationLog = { message -> platformServices.emitAutomationLog(message) },
        createSupportedMeshLinkController = { _ -> platformServices.meshLinkController },
        automationConfig = automationConfig,
    )
}

/** iOS entry point that wraps the shared Compose app. */
@OptIn(ExperimentalComposeUiApi::class)
public fun createReferenceRootViewController(): UIViewController {
    val platformServices = createPlatformServices()
    return ComposeUIViewController { renderReferenceApp(platformServices) }
}

/** iOS entry point that renders the shared Compose app into a UIView. */
@OptIn(ExperimentalComposeUiApi::class)
public fun createReferenceRootView(): UIView {
    val platformServices = createPlatformServices()
    return ComposeUIView { renderReferenceApp(platformServices) }
}

public fun createReferenceAutomationRootViewController(
    storageSubdirectory: String,
    appId: String,
    role: String,
): UIViewController {
    val platformServices =
        createAutomationPlatformServices(storageSubdirectory = storageSubdirectory, blocked = false)
    return ComposeUIViewController {
        renderReferenceApp(
            platformServices,
            ReferenceAutomationConfig(
                mode = AUTOMATION_MODE_SCRIPTED_UI,
                role = role,
                appId = appId,
                storageSubdirectory = storageSubdirectory,
            ),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
public fun createReferenceAutomationRootView(
    storageSubdirectory: String,
    appId: String,
    role: String,
): UIView {
    val platformServices =
        createAutomationPlatformServices(storageSubdirectory = storageSubdirectory, blocked = false)
    return ComposeUIView {
        renderReferenceApp(
            platformServices,
            ReferenceAutomationConfig(
                mode = AUTOMATION_MODE_SCRIPTED_UI,
                role = role,
                appId = appId,
                storageSubdirectory = storageSubdirectory,
            ),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
public fun createReferenceLiveAutomationRootViewController(
    storageSubdirectory: String,
    appId: String,
    role: String,
    requiredPeerCount: Int,
    targetPeerIndex: Int,
    targetPeerId: String?,
    scenario: String,
): UIViewController {
    val platformServices =
        createLiveAutomationPlatformServices(
            storageSubdirectory = storageSubdirectory,
            appId = appId,
            role = role,
            requiredPeerCount = requiredPeerCount,
            targetPeerIndex = targetPeerIndex,
            targetPeerId = targetPeerId,
            scenario = scenario,
        )
    return ComposeUIViewController {
        renderReferenceApp(
            platformServices,
            ReferenceAutomationConfig(
                mode = AUTOMATION_MODE_LIVE_PROOF,
                role = role,
                appId = appId,
                storageSubdirectory = storageSubdirectory,
                requiredPeerCount = requiredPeerCount,
                targetPeerIndex = targetPeerIndex,
                targetPeerId = targetPeerId,
                scenario = scenario,
            ),
        )
    }
}

/** iOS live-proof automation root view helper. */
@OptIn(ExperimentalComposeUiApi::class)
public fun createReferenceLiveAutomationRootView(
    storageSubdirectory: String,
    appId: String,
    role: String,
    requiredPeerCount: Int,
    targetPeerIndex: Int,
    targetPeerId: String?,
    scenario: String,
): UIView {
    val platformServices =
        createLiveAutomationPlatformServices(
            storageSubdirectory = storageSubdirectory,
            appId = appId,
            role = role,
            requiredPeerCount = requiredPeerCount,
            targetPeerIndex = targetPeerIndex,
            targetPeerId = targetPeerId,
            scenario = scenario,
        )
    return ComposeUIView {
        renderReferenceApp(
            platformServices,
            ReferenceAutomationConfig(
                mode = AUTOMATION_MODE_LIVE_PROOF,
                role = role,
                appId = appId,
                storageSubdirectory = storageSubdirectory,
                requiredPeerCount = requiredPeerCount,
                targetPeerIndex = targetPeerIndex,
                targetPeerId = targetPeerId,
                scenario = scenario,
            ),
        )
    }
}
