package ch.trancee.meshlink.reference

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIView
import androidx.compose.ui.window.ComposeUIViewController
import ch.trancee.meshlink.reference.app.ReferenceApp
import ch.trancee.meshlink.reference.automation.ReferenceAutomationRole
import ch.trancee.meshlink.reference.automation.toReferenceAutomationScenario
import ch.trancee.meshlink.reference.platform.createAutomationPlatformServices
import ch.trancee.meshlink.reference.platform.createLiveAutomationPlatformServices
import ch.trancee.meshlink.reference.platform.createPlatformServices
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIView
import platform.UIKit.UIViewController

/** iOS entry point that wraps the shared Compose app in a UIKit controller. */
@OptIn(ExperimentalForeignApi::class)
public fun createReferenceRootViewController(): UIViewController {
    val platformServices = createPlatformServices()
    return ComposeUIViewController { ReferenceApp(platformServices = platformServices) }
}

/** iOS entry point that wraps the shared Compose app in a UIKit view. */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
public fun createReferenceRootView(): UIView {
    val platformServices = createPlatformServices()
    return ComposeUIView { ReferenceApp(platformServices = platformServices) }
}

/** iOS UI-automation entry point using deterministic scripted platform services. */
@OptIn(ExperimentalForeignApi::class)
public fun createReferenceAutomationRootViewController(
    storageSubdirectory: String,
    blocked: Boolean,
): UIViewController {
    val platformServices =
        createAutomationPlatformServices(
            storageSubdirectory = storageSubdirectory,
            blocked = blocked,
        )
    return ComposeUIViewController { ReferenceApp(platformServices = platformServices) }
}

/** iOS UI-automation entry point using deterministic scripted platform services in a UIKit view. */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
public fun createReferenceAutomationRootView(
    storageSubdirectory: String,
    blocked: Boolean,
): UIView {
    val platformServices =
        createAutomationPlatformServices(
            storageSubdirectory = storageSubdirectory,
            blocked = blocked,
        )
    return ComposeUIView { ReferenceApp(platformServices = platformServices) }
}

/** iOS live-proof automation entry point using the real MeshLink controller. */
@OptIn(ExperimentalForeignApi::class)
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
            role = role.toReferenceAutomationRole(),
            requiredPeerCount = requiredPeerCount,
            targetPeerIndex = targetPeerIndex,
            targetPeerId = targetPeerId,
            scenario = scenario.toReferenceAutomationScenario(),
        )
    return ComposeUIViewController { ReferenceApp(platformServices = platformServices) }
}

/** iOS live-proof automation entry point using the real MeshLink controller in a UIKit view. */
@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
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
            role = role.toReferenceAutomationRole(),
            requiredPeerCount = requiredPeerCount,
            targetPeerIndex = targetPeerIndex,
            targetPeerId = targetPeerId,
            scenario = scenario.toReferenceAutomationScenario(),
        )
    return ComposeUIView { ReferenceApp(platformServices = platformServices) }
}

private fun String.toReferenceAutomationRole(): ReferenceAutomationRole {
    return when {
        equals("sender", ignoreCase = true) -> ReferenceAutomationRole.SENDER
        equals("relay", ignoreCase = true) -> ReferenceAutomationRole.RELAY
        else -> ReferenceAutomationRole.PASSIVE
    }
}
